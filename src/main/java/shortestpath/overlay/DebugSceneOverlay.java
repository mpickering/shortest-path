package shortestpath.overlay;

import com.google.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.util.Arrays;
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
import shortestpath.PrimitiveIntHashMap;
import shortestpath.ShortestPathPlugin;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.ExactPathfinder;
import shortestpath.pathfinder.PathStep;
import shortestpath.pathfinder.TransportAvailability;
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

	@Inject
	private DebugSceneOverlay(Client client, ShortestPathPlugin plugin, TooltipManager tooltipManager)
	{
		this.client = client;
		this.plugin = plugin;
		this.tooltipManager = tooltipManager;
		this.teleportMarkers = new TeleportMarkers(plugin);
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
			renderTransports(graphics);
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

	private void renderTransports(Graphics2D graphics)
	{
		PrimitiveIntHashMap<Transport[]> allTransports = plugin.getAllDisplayTransports();
		PrimitiveIntHashMap<Transport[]> availableTransports = plugin.getTransports();

		for (int a : allTransports.keys())
		{
			if (a == Transport.UNDEFINED_ORIGIN)
			{
				continue; // skip teleports
			}

			Point ca = SceneTiles.center(client, a);

			if (ca == null)
			{
				continue;
			}

			boolean drawStart = false;
			StringBuilder s = new StringBuilder();
			Transport[] availableAtOrigin = availableTransports.getOrDefault(a, TransportAvailability.EMPTY_TRANSPORTS);

			for (Transport b : allTransports.getOrDefault(a, TransportAvailability.EMPTY_TRANSPORTS))
			{
				if (b == null || (b.getType() != null && b.getType().isTeleport()))
				{
					continue; // skip teleports
				}

				boolean isAvailable = Arrays.asList(availableAtOrigin).contains(b);
				graphics.setColor(isAvailable ? COLOR_AVAILABLE : COLOR_UNAVAILABLE);

				int destination = b.getDestination();
				if (destination == Transport.UNDEFINED_DESTINATION)
				{
					continue;
				}
				Point cb = SceneTiles.center(client, destination);
				if (cb != null)
				{
					graphics.drawLine(ca.getX(), ca.getY(), cb.getX(), cb.getY());
					drawStart = true;
				}
				if (WorldPointUtil.unpackWorldPlane(destination) > WorldPointUtil.unpackWorldPlane(a))
				{
					s.append("+");
				}
				else if (WorldPointUtil.unpackWorldPlane(destination) < WorldPointUtil.unpackWorldPlane(a))
				{
					s.append("-");
				}
				else
				{
					s.append("=");
				}
			}

			if (drawStart)
			{
				fillTile(graphics, a, plugin.colourTransports);
			}

			graphics.setColor(Color.WHITE);
			graphics.drawString(s.toString(), ca.getX(), ca.getY());
		}
	}

	private void renderCollisionMap(Graphics2D graphics)
	{
		CollisionMap map = plugin.getMap();
		for (Tile[] row : client.getTopLevelWorldView().getScene().getTiles()[client.getTopLevelWorldView().getPlane()])
		{
			for (Tile tile : row)
			{
				if (tile == null)
				{
					continue;
				}

				Polygon tilePolygon = Perspective.getCanvasTilePoly(client, tile.getLocalLocation());

				if (tilePolygon == null)
				{
					continue;
				}

				int location = WorldPointUtil.fromLocalInstance(client, tile.getLocalLocation());
				int x = WorldPointUtil.unpackWorldX(location);
				int y = WorldPointUtil.unpackWorldY(location);
				int z = WorldPointUtil.unpackWorldPlane(location);

				String s = (!map.n(x, y, z) ? "n" : "") +
					(!map.s(x, y, z) ? "s" : "") +
					(!map.e(x, y, z) ? "e" : "") +
					(!map.w(x, y, z) ? "w" : "");

				if (map.isBlocked(x, y, z))
				{
					graphics.setColor(plugin.colourCollisionMap);
					graphics.fill(tilePolygon);
				}
				if (!s.isEmpty() && !s.equals("nsew"))
				{
					graphics.setColor(Color.WHITE);
					int stringX = (int) (tilePolygon.getBounds().getCenterX()
						- graphics.getFontMetrics().getStringBounds(s, graphics).getWidth() / 2);
					int stringY = (int) tilePolygon.getBounds().getCenterY();
					graphics.drawString(s, stringX, stringY);
				}
			}
		}
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
