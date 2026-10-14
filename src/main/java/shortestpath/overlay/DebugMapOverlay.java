package shortestpath.overlay;

import com.google.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.util.Arrays;
import net.runelite.api.Client;
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
 * The debug layers drawn on the world map.
 */
public class DebugMapOverlay extends Overlay
{
	private static final Color COLOR_AVAILABLE = Color.WHITE;
	private static final Color COLOR_UNAVAILABLE = Color.ORANGE;

	private final Client client;
	private final ShortestPathPlugin plugin;
	private final WorldMapCanvas canvas;

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
		if (!plugin.drawMap || !(plugin.drawCollisionMap || plugin.drawTransports))
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

		return null;
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
