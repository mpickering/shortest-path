package shortestpath.overlay;

import com.google.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.geom.Area;
import java.util.List;
import javax.inject.Singleton;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import shortestpath.presentation.PathAnnotator;
import shortestpath.scheduler.PathScheduler;
import shortestpath.settings.Settings;

import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.transport.Transport;

/**
 * The debug layers drawn on the world map. What the cursor hovers is kept for the map tooltip
 * overlay, which draws above the map's other widgets.
 */
@Singleton
public class DebugMapOverlay extends Overlay
{
	private static final Color COLOR_AVAILABLE = Color.WHITE;
	private static final Color COLOR_UNAVAILABLE = Color.ORANGE;
	// The world map zoom, in pixels per tile, from which collision walls are drawn.
	private static final float WALL_MIN_ZOOM = 4;
	// Transports within this many tiles of their origin are local hops, drawn as arrows.
	private static final int LOCAL_HOP_TILES = 16;
	// The world map zoom, in pixels per tile, from which local hops get arrows.
	private static final float LOCAL_ARROW_MIN_ZOOM = 3;

	private final Client client;
	private final PathAnnotator annotator;
	private final PathScheduler scheduler;
	private final Settings settings;
	private final WorldMapCanvas canvas;
	private final TeleportMarkers teleportMarkers;
	private final TransportMarkers transportMarkers;

	// The tile and tooltip rows of the debug marker the cursor hovered in the last frame.
	@Getter
	private int hoverPoint = WorldPointUtil.UNDEFINED;
	@Getter
	private List<String> hoverRows = List.of();

	@Inject
	private DebugMapOverlay(Client client, PathAnnotator annotator, PathScheduler scheduler, Settings settings, WorldMapCanvas canvas)
	{
		this.client = client;
		this.annotator = annotator;
		this.scheduler = scheduler;
		this.settings = settings;
		this.canvas = canvas;
		this.teleportMarkers = new TeleportMarkers(annotator);
		this.transportMarkers = new TransportMarkers(annotator);
		setPosition(OverlayPosition.DYNAMIC);
		// Above the path overlay, which is PRIORITY_LOW, so markers on route tiles stay visible.
		setPriority(Overlay.PRIORITY_DEFAULT);
		setLayer(OverlayLayer.MANUAL);
		drawAfterLayer(InterfaceID.Worldmap.MAP_CONTAINER);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		hoverPoint = WorldPointUtil.UNDEFINED;
		hoverRows = List.of();
		if (!settings.display().drawMap() || !(settings.display().drawCollisionMap() || settings.display().drawTransports() || settings.configured().debugLayers().contains(shortestpath.DebugLayer.TARGETS)
			|| settings.configured().debugLayers().contains(shortestpath.DebugLayer.TELEPORTS)))
		{
			return null;
		}

		Rectangle bounds = canvas.bounds();
		if (bounds == null)
		{
			return null;
		}
		Area clip = canvas.clipArea(bounds);
		graphics.setClip(clip);

		if (settings.display().drawCollisionMap())
		{
			renderCollisionMap(graphics, bounds);
		}

		Point cursor = client.getMouseCanvasPosition();
		if (settings.display().drawTransports())
		{
			renderTransports(graphics, clip, cursor);
		}

		if (settings.configured().debugLayers().contains(shortestpath.DebugLayer.TELEPORTS))
		{
			renderTeleports(graphics, clip, cursor);
		}

		if (settings.configured().debugLayers().contains(shortestpath.DebugLayer.TARGETS))
		{
			renderTargets(graphics, cursor);
		}

		return null;
	}

	private void renderTeleports(Graphics2D graphics, Area clip, Point cursor)
	{
		int size = Math.max(4, Math.round(canvas.pixelsPerTile() * 0.7f));
		double hoveredDistance = canvas.hoverRadius();
		TeleportMarkers.Marker hovered = null;
		for (TeleportMarkers.Marker marker : teleportMarkers.markers())
		{
			int x = canvas.x(marker.point);
			int y = canvas.y(marker.point);
			if (!clip.contains(x, y))
			{
				continue;
			}
			graphics.setColor(marker.colour);
			graphics.fillPolygon(new int[]{x, x + size, x, x - size}, new int[]{y - size, y, y + size, y}, 4);
			graphics.setColor(Color.BLACK);
			graphics.drawPolygon(new int[]{x, x + size, x, x - size}, new int[]{y - size, y, y + size, y}, 4);
			double distance = canvas.distance(marker.point, cursor);
			if (distance <= hoveredDistance)
			{
				hovered = marker;
				hoveredDistance = distance;
			}
		}
		if (hovered != null)
		{
			hoverPoint = hovered.point;
			hoverRows = hovered.rows;
		}
	}

	private void renderTargets(Graphics2D graphics, Point cursor)
	{
		List<TargetMarkers.Marker> markers = TargetMarkers.collect(scheduler.getActiveSearch(), annotator.getFilteredTargets());
		float pixelsPerTile = canvas.pixelsPerTile();
		Stroke previousStroke = graphics.getStroke();

		// Lines from each blocked target to its goals, under the markers.
		graphics.setStroke(new BasicStroke(1));
		graphics.setColor(withAlpha(TargetMarkers.COLOUR_GOAL, 110));
		for (TargetMarkers.Marker marker : markers)
		{
			for (int goal : marker.goals)
			{
				drawLine(graphics, marker.point, goal);
			}
		}

		TargetMarkers.Marker hovered = null;
		double hoveredDistance = canvas.hoverRadius();
		int goalSize = Math.max(3, Math.round(pixelsPerTile * 0.6f));
		int targetRadius = Math.max(5, Math.round(pixelsPerTile * 0.75f));
		graphics.setStroke(new BasicStroke(2));
		for (TargetMarkers.Marker marker : markers)
		{
			int x = canvas.x(marker.point);
			int y = canvas.y(marker.point);
			if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE)
			{
				continue;
			}
			graphics.setColor(marker.colour);
			if (marker.target)
			{
				graphics.drawOval(x - targetRadius, y - targetRadius, targetRadius * 2, targetRadius * 2);
			}
			else
			{
				graphics.fillRect(x - goalSize / 2, y - goalSize / 2, goalSize, goalSize);
			}
			double distance = canvas.distance(marker.point, cursor);
			if (distance <= hoveredDistance)
			{
				hovered = marker;
				hoveredDistance = distance;
			}
		}
		graphics.setStroke(previousStroke);

		if (hovered != null)
		{
			hoverPoint = hovered.point;
			hoverRows = hovered.rows;
		}
	}

	private void drawLine(Graphics2D graphics, int from, int to)
	{
		int x1 = canvas.x(from);
		int y1 = canvas.y(from);
		int x2 = canvas.x(to);
		int y2 = canvas.y(to);
		if (x1 != Integer.MIN_VALUE && y1 != Integer.MIN_VALUE && x2 != Integer.MIN_VALUE && y2 != Integer.MIN_VALUE)
		{
			graphics.drawLine(x1, y1, x2, y2);
		}
	}

	private static Color withAlpha(Color colour, int alpha)
	{
		return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), alpha);
	}

	/**
	 * Blocked tiles are filled. Once zoomed in far enough to see single tiles, edges that cannot be
	 * crossed between two open tiles (walls, fences) are drawn too, as in the game view.
	 */
	private void renderCollisionMap(Graphics2D graphics, Rectangle bounds)
	{
		int[] visible = canvas.visibleTiles(bounds);
		if (visible == null)
		{
			return;
		}
		Color fill = settings.display().colourCollisionMap();
		boolean walls = canvas.pixelsPerTile() >= WALL_MIN_ZOOM;
		Stroke previousStroke = graphics.getStroke();
		graphics.setStroke(new BasicStroke(2));
		final CollisionMap map = annotator.getMap();
		final int z = client.getTopLevelWorldView().getPlane();
		for (int x = visible[0]; x <= visible[2]; x++)
		{
			for (int y = visible[1]; y <= visible[3]; y++)
			{
				boolean blocked = map.isBlocked(x, y, z);
				if (!blocked && !walls)
				{
					continue;
				}
				Rectangle rect = canvas.tileRect(WorldPointUtil.packWorldPoint(x, y, z));
				if (rect == null)
				{
					continue;
				}
				if (blocked)
				{
					graphics.setColor(fill);
					graphics.fill(rect);
					continue;
				}
				graphics.setColor(withAlpha(fill, 255));
				// North is up on the map. Each tile draws its north and east edges.
				if (!map.n(x, y, z) && !map.isBlocked(x, y + 1, z))
				{
					graphics.drawLine(rect.x, rect.y, rect.x + rect.width, rect.y);
				}
				if (!map.e(x, y, z) && !map.isBlocked(x + 1, y, z))
				{
					graphics.drawLine(rect.x + rect.width, rect.y, rect.x + rect.width, rect.y + rect.height);
				}
			}
		}
		graphics.setStroke(previousStroke);
	}

	/**
	 * Every origin is a dot, white when one of its transports is usable and orange otherwise. Only
	 * short local hops get arrows, and only once zoomed in: lines across the map for every ship,
	 * fairy ring and tree route bury everything else. Hovering a dot draws every transport leaving
	 * or arriving there, however far, and lists them.
	 */
	private void renderTransports(Graphics2D graphics, Area clip, Point cursor)
	{
		List<TransportMarkers.Origin> origins = transportMarkers.origins();
		float pixelsPerTile = canvas.pixelsPerTile();
		boolean arrows = pixelsPerTile >= LOCAL_ARROW_MIN_ZOOM;
		int dot = Math.max(3, Math.round(pixelsPerTile * 0.5f));
		Stroke previousStroke = graphics.getStroke();
		graphics.setStroke(new BasicStroke(1));

		int hovered = WorldPointUtil.UNDEFINED;
		double hoveredDistance = canvas.hoverRadius();
		for (TransportMarkers.Origin origin : origins)
		{
			int x = canvas.x(origin.point);
			int y = canvas.y(origin.point);
			if (!clip.contains(x, y))
			{
				continue;
			}
			if (arrows)
			{
				for (Transport transport : origin.transports)
				{
					if (TransportMarkers.isLocal(transport, LOCAL_HOP_TILES)
						&& !sameTile2D(transport.getOrigin(), transport.getDestination()))
					{
						graphics.setColor(withAlpha(colour(transport), 160));
						drawArrow(graphics, transport.getOrigin(), transport.getDestination(), 5);
					}
				}
			}
			graphics.setColor(origin.anyAvailable ? COLOR_AVAILABLE : COLOR_UNAVAILABLE);
			graphics.fillOval(x - dot / 2, y - dot / 2, dot, dot);
			double distance = canvas.distance(origin.point, cursor);
			if (distance <= hoveredDistance)
			{
				hovered = origin.point;
				hoveredDistance = distance;
			}
		}

		if (hovered != WorldPointUtil.UNDEFINED)
		{
			graphics.setStroke(new BasicStroke(2));
			for (Transport transport : transportMarkers.from(hovered))
			{
				graphics.setColor(colour(transport));
				drawArrow(graphics, transport.getOrigin(), transport.getDestination(), 8);
			}
			for (Transport transport : transportMarkers.to(hovered))
			{
				graphics.setColor(colour(transport));
				drawArrow(graphics, transport.getOrigin(), transport.getDestination(), 8);
			}
			hoverPoint = hovered;
			hoverRows = transportMarkers.rows(hovered);
		}
		graphics.setStroke(previousStroke);
	}

	private Color colour(Transport transport)
	{
		return transportMarkers.isAvailable(transport) ? COLOR_AVAILABLE : COLOR_UNAVAILABLE;
	}

	private static boolean sameTile2D(int a, int b)
	{
		return WorldPointUtil.unpackWorldX(a) == WorldPointUtil.unpackWorldX(b)
			&& WorldPointUtil.unpackWorldY(a) == WorldPointUtil.unpackWorldY(b);
	}

	private void drawArrow(Graphics2D graphics, int from, int to, int headSize)
	{
		int x1 = canvas.x(from);
		int y1 = canvas.y(from);
		int x2 = canvas.x(to);
		int y2 = canvas.y(to);
		if (x1 == Integer.MIN_VALUE || y1 == Integer.MIN_VALUE || x2 == Integer.MIN_VALUE || y2 == Integer.MIN_VALUE)
		{
			return;
		}
		graphics.drawLine(x1, y1, x2, y2);
		// Skip the head on segments too short to fit it.
		if (Math.hypot(x2 - x1, y2 - y1) >= headSize * 1.5)
		{
			ArrowHead.draw(graphics, x1, y1, x2, y2, headSize);
		}
	}
}
