package shortestpath.overlay;

import com.google.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.geom.Area;
import java.util.Arrays;
import java.util.List;
import javax.inject.Singleton;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import shortestpath.PrimitiveIntHashMap;
import shortestpath.ShortestPathPlugin;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.TransportAvailability;
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

	private final Client client;
	private final ShortestPathPlugin plugin;
	private final WorldMapCanvas canvas;

	// The tile and tooltip rows of the debug marker the cursor hovered in the last frame.
	@Getter
	private int hoverPoint = WorldPointUtil.UNDEFINED;
	@Getter
	private List<String> hoverRows = List.of();

	@Inject
	private DebugMapOverlay(Client client, ShortestPathPlugin plugin, WorldMapCanvas canvas)
	{
		this.client = client;
		this.plugin = plugin;
		this.canvas = canvas;
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
		if (!plugin.drawMap || !(plugin.drawCollisionMap || plugin.drawTransports || plugin.drawTargets))
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

		if (plugin.drawCollisionMap)
		{
			renderCollisionMap(graphics, bounds);
		}

		if (plugin.drawTransports)
		{
			renderTransports(graphics, clip);
		}

		if (plugin.drawTargets)
		{
			renderTargets(graphics, client.getMouseCanvasPosition());
		}

		return null;
	}

	private void renderTargets(Graphics2D graphics, Point cursor)
	{
		List<TargetMarkers.Marker> markers = TargetMarkers.collect(plugin.getActiveSearch(), plugin.getFilteredTargets());
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

	private void renderCollisionMap(Graphics2D graphics, Rectangle bounds)
	{
		int[] visible = canvas.visibleTiles(bounds);
		if (visible == null)
		{
			return;
		}
		graphics.setColor(plugin.colourCollisionMap);
		final CollisionMap map = plugin.getMap();
		final int z = client.getTopLevelWorldView().getPlane();
		for (int x = visible[0]; x <= visible[2]; x++)
		{
			for (int y = visible[1]; y <= visible[3]; y++)
			{
				if (map.isBlocked(x, y, z))
				{
					Rectangle rect = canvas.tileRect(WorldPointUtil.packWorldPoint(x, y, z));
					if (rect != null)
					{
						graphics.fill(rect);
					}
				}
			}
		}
	}

	private void renderTransports(Graphics2D graphics, Area clip)
	{
		PrimitiveIntHashMap<Transport[]> allTransports = plugin.getAllDisplayTransports();
		PrimitiveIntHashMap<Transport[]> availableTransports = plugin.getTransports();

		for (int a : allTransports.keys())
		{
			if (a == Transport.UNDEFINED_ORIGIN)
			{
				continue; // skip teleports
			}

			int mapAX = canvas.x(a);
			int mapAY = canvas.y(a);
			if (!clip.contains(mapAX, mapAY))
			{
				continue;
			}

			Transport[] availableAtOrigin = availableTransports.getOrDefault(a, TransportAvailability.EMPTY_TRANSPORTS);

			for (Transport b : allTransports.getOrDefault(a, TransportAvailability.EMPTY_TRANSPORTS))
			{
				if (b == null || (b.getType() != null && b.getType().isTeleport()))
				{
					continue; // skip teleports
				}

				int mapBX = canvas.x(b.getDestination());
				int mapBY = canvas.y(b.getDestination());
				if (!clip.contains(mapBX, mapBY))
				{
					continue;
				}

				boolean isAvailable = Arrays.asList(availableAtOrigin).contains(b);
				graphics.setColor(isAvailable ? COLOR_AVAILABLE : COLOR_UNAVAILABLE);
				graphics.drawLine(mapAX, mapAY, mapBX, mapBY);
			}
		}
	}
}
