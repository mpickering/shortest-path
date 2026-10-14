package shortestpath.overlay;

import com.google.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import shortestpath.ShortestPathPlugin;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.ExactPathfinder;
import shortestpath.pathfinder.PathStep;
import shortestpath.transport.Transport;

/**
 * The debug layers drawn in the game view.
 */
public class DebugSceneOverlay extends Overlay
{
	private static final Color COLOR_AVAILABLE = Color.WHITE;
	private static final Color COLOR_UNAVAILABLE = Color.ORANGE;
	private static final Color COLOR_CLICK_POINT = new Color(255, 0, 255, 150);

	private final Client client;
	private final ShortestPathPlugin plugin;
	private final TooltipManager tooltipManager;
	private final TeleportMarkers teleportMarkers;
	private final TransportMarkers transportMarkers;

	@Inject
	private DebugSceneOverlay(Client client, ShortestPathPlugin plugin, TooltipManager tooltipManager)
	{
		this.client = client;
		this.plugin = plugin;
		this.tooltipManager = tooltipManager;
		this.teleportMarkers = new TeleportMarkers(plugin);
		this.transportMarkers = new TransportMarkers(plugin);
		setPosition(OverlayPosition.DYNAMIC);
		// Above the path overlay, which is PRIORITY_LOW, so markers on route tiles stay visible.
		setPriority(Overlay.PRIORITY_DEFAULT);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (plugin.drawTransports)
		{
			renderTransports(graphics, client.getMouseCanvasPosition());
		}

		if (plugin.drawCollisionMap)
		{
			renderCollisionMap(graphics);
		}

		if (plugin.drawTeleports)
		{
			renderTeleports(graphics, client.getMouseCanvasPosition());
		}

		if (plugin.drawTargets)
		{
			renderTargets(graphics, client.getMouseCanvasPosition());
		}

		// Click points without the path are floating marks, so they follow the same drawTiles
		// gate and POH skip as the path tiles.
		if (plugin.drawTiles && plugin.drawClickPoints && plugin.getActiveSearch() instanceof ExactPathfinder)
		{
			renderClickPoints(graphics, (ExactPathfinder) plugin.getActiveSearch());
		}

		return null;
	}

	/**
	 * Each origin tile is filled, with an arrow to every destination in view and a small up or
	 * down marker when a transport changes plane. Hovering the tile lists its transports.
	 */
	private void renderTransports(Graphics2D graphics, Point cursor)
	{
		Stroke previousStroke = graphics.getStroke();
		graphics.setStroke(new BasicStroke(1.5f));
		int hovered = WorldPointUtil.UNDEFINED;
		for (TransportMarkers.Origin origin : transportMarkers.origins())
		{
			Point ca = SceneTiles.center(client, origin.point);
			if (ca == null)
			{
				continue;
			}

			for (Polygon poly : SceneTiles.polygons(client, origin.point))
			{
				graphics.setColor(plugin.colourTransports);
				graphics.fill(poly);
				if (hovered == WorldPointUtil.UNDEFINED && cursor != null && poly.contains(cursor.getX(), cursor.getY()))
				{
					hovered = origin.point;
				}
			}

			boolean up = false;
			boolean down = false;
			for (Transport transport : origin.transports)
			{
				int destination = transport.getDestination();
				int planeChange = WorldPointUtil.unpackWorldPlane(destination) - WorldPointUtil.unpackWorldPlane(origin.point);
				up |= planeChange > 0;
				down |= planeChange < 0;
				Point cb = SceneTiles.center(client, destination);
				if (cb != null && (cb.getX() != ca.getX() || cb.getY() != ca.getY()))
				{
					graphics.setColor(transportMarkers.isAvailable(transport) ? COLOR_AVAILABLE : COLOR_UNAVAILABLE);
					graphics.drawLine(ca.getX(), ca.getY(), cb.getX(), cb.getY());
					ArrowHead.draw(graphics, ca.getX(), ca.getY(), cb.getX(), cb.getY(), 8);
				}
			}

			graphics.setColor(origin.anyAvailable ? COLOR_AVAILABLE : COLOR_UNAVAILABLE);
			if (up)
			{
				graphics.fillPolygon(new int[]{ca.getX() - 4, ca.getX() + 4, ca.getX()},
					new int[]{ca.getY() - 2, ca.getY() - 2, ca.getY() - 8}, 3);
			}
			if (down)
			{
				graphics.fillPolygon(new int[]{ca.getX() - 4, ca.getX() + 4, ca.getX()},
					new int[]{ca.getY() + 2, ca.getY() + 2, ca.getY() + 8}, 3);
			}
		}
		graphics.setStroke(previousStroke);

		if (hovered != WorldPointUtil.UNDEFINED)
		{
			tooltipManager.add(new Tooltip(String.join("</br>", transportMarkers.rows(hovered))));
		}
	}

	/**
	 * Blocked tiles are filled. Edges that cannot be crossed between two open tiles (walls, fences)
	 * are drawn as solid lines, and passable edges that carry a wall, such as open doors, as dashed
	 * lines. Edges against a blocked tile are left to its fill.
	 */
	private void renderCollisionMap(Graphics2D graphics)
	{
		CollisionMap map = plugin.getMap();
		Color fill = plugin.colourCollisionMap;
		Color wall = withAlpha(fill, 255);
		Stroke previousStroke = graphics.getStroke();
		Stroke wallStroke = new BasicStroke(3);
		Stroke doorStroke = new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{4, 4}, 0);
		for (Tile[] row : client.getTopLevelWorldView().getScene().getTiles()[client.getTopLevelWorldView().getPlane()])
		{
			for (Tile tile : row)
			{
				if (tile == null)
				{
					continue;
				}

				Polygon poly = Perspective.getCanvasTilePoly(client, tile.getLocalLocation());

				if (poly == null || poly.npoints != 4)
				{
					continue;
				}

				int location = WorldPointUtil.fromLocalInstance(client, tile.getLocalLocation());
				int x = WorldPointUtil.unpackWorldX(location);
				int y = WorldPointUtil.unpackWorldY(location);
				int z = WorldPointUtil.unpackWorldPlane(location);

				boolean blocked = map.isBlocked(x, y, z);
				if (blocked)
				{
					graphics.setColor(fill);
					graphics.fill(poly);
				}

				// The polygon's corners run south-west, south-east, north-east, north-west. Each tile
				// draws its north and east edges, so every edge is drawn once.
				graphics.setColor(wall);
				drawEdge(graphics, poly, 2, 3, blocked || map.isBlocked(x, y + 1, z), map.n(x, y, z),
					map.wallN(x, y, z), wallStroke, doorStroke);
				drawEdge(graphics, poly, 1, 2, blocked || map.isBlocked(x + 1, y, z), map.e(x, y, z),
					map.wallE(x, y, z), wallStroke, doorStroke);
			}
		}
		graphics.setStroke(previousStroke);
	}

	private static void drawEdge(Graphics2D graphics, Polygon poly, int from, int to, boolean againstBlocked,
		boolean passable, boolean hasWall, Stroke wallStroke, Stroke doorStroke)
	{
		if (againstBlocked || passable && !hasWall)
		{
			return;
		}
		graphics.setStroke(passable ? doorStroke : wallStroke);
		graphics.drawLine(poly.xpoints[from], poly.ypoints[from], poly.xpoints[to], poly.ypoints[to]);
	}

	private void renderClickPoints(Graphics2D graphics, ExactPathfinder exact)
	{
		if (!exact.isDone() || exact.isShowingProvisionalPath())
		{
			return;
		}
		List<PathStep> path = exact.getPath();
		for (int index : exact.getClickPoints())
		{
			int point = path.get(index).getPackedPosition();
			if (!ShortestPathPlugin.isInsidePoh(WorldPointUtil.unpackWorldX(point),
				WorldPointUtil.unpackWorldY(point)))
			{
				fillTile(graphics, point, COLOR_CLICK_POINT);
			}
		}
	}

	private void renderTeleports(Graphics2D graphics, Point cursor)
	{
		Stroke previousStroke = graphics.getStroke();
		graphics.setStroke(new BasicStroke(2));
		List<String> hoverRows = null;
		for (TeleportMarkers.Marker marker : teleportMarkers.markers())
		{
			for (Polygon poly : SceneTiles.polygons(client, marker.point))
			{
				graphics.setColor(withAlpha(marker.colour, 50));
				graphics.fill(poly);
				graphics.setColor(marker.colour);
				graphics.draw(poly);
				if (hoverRows == null && cursor != null && poly.contains(cursor.getX(), cursor.getY()))
				{
					hoverRows = marker.rows;
				}
			}
		}
		graphics.setStroke(previousStroke);
		if (hoverRows != null)
		{
			tooltipManager.add(new Tooltip(String.join("</br>", hoverRows)));
		}
	}

	private void renderTargets(Graphics2D graphics, Point cursor)
	{
		List<TargetMarkers.Marker> markers = TargetMarkers.collect(plugin.getActiveSearch(), plugin.getFilteredTargets());
		Stroke previousStroke = graphics.getStroke();
		graphics.setStroke(new BasicStroke(2));
		List<String> hoverRows = null;
		for (TargetMarkers.Marker marker : markers)
		{
			for (Polygon poly : SceneTiles.polygons(client, marker.point))
			{
				if (!marker.target)
				{
					graphics.setColor(withAlpha(marker.colour, 60));
					graphics.fill(poly);
				}
				graphics.setColor(marker.colour);
				graphics.draw(poly);
				// Targets come first, so a target under the cursor wins over a goal on the same tile.
				if (hoverRows == null && cursor != null && poly.contains(cursor.getX(), cursor.getY()))
				{
					hoverRows = marker.rows;
				}
			}
		}
		graphics.setStroke(previousStroke);
		if (hoverRows != null)
		{
			tooltipManager.add(new Tooltip(String.join("</br>", hoverRows)));
		}
	}

	private static Color withAlpha(Color colour, int alpha)
	{
		return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), alpha);
	}

	private void fillTile(Graphics2D graphics, int point, Color color)
	{
		graphics.setColor(color);
		for (Polygon poly : SceneTiles.polygons(client, point))
		{
			graphics.fill(poly);
		}
	}
}
