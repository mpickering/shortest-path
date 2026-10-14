package shortestpath.overlay;

import com.google.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import shortestpath.ShortestPathPlugin;
import shortestpath.TileStyle;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.PathStep;

public class PathMapOverlay extends Overlay
{
	private final Client client;
	private final ShortestPathPlugin plugin;
	private final WorldMapCanvas canvas;

	@Inject
	private PathMapOverlay(Client client, ShortestPathPlugin plugin, WorldMapCanvas canvas)
	{
		this.client = client;
		this.plugin = plugin;
		this.canvas = canvas;
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_LOW);
		setLayer(OverlayLayer.MANUAL);
		drawAfterLayer(InterfaceID.Worldmap.MAP_CONTAINER);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.drawMap)
		{
			return null;
		}

		Rectangle worldMapRectangle = canvas.bounds();
		if (worldMapRectangle == null)
		{
			return null;
		}
		graphics.setClip(canvas.clipArea(worldMapRectangle));

		if (plugin.getActiveSearch() != null)
		{
			Color colour = plugin.getPathColor();
			List<PathStep> path = plugin.getActiveSearch().getPath();
			Point cursorPos = client.getMouseCanvasPosition();
			if (plugin.isSailing())
			{
				// On a boat the path sails the boat's headings: a line along each heading it holds, with a small square
				// at the end of each move
				graphics.setColor(plugin.isPathUnreachable() ? plugin.getPathColor() : ShortestPathPlugin.COLOUR_SAILING_PATH);
				for (int i = 0; i < path.size(); i++)
				{
					int point = path.get(i).getPackedPosition();
					if (i > 0)
					{
						drawSolidLineOnMap(graphics, path.get(i - 1).getPackedPosition(), point);
					}
					drawSmallSquareOnMap(graphics, point);
				}
			}
			else if (TileStyle.ARROW_LINE.equals(plugin.pathStyle))
			{
				graphics.setColor(colour);
				drawArrowPath(graphics, path);
			}
			else
			{
				for (int i = 0; i < path.size(); i++)
				{
					graphics.setColor(colour);
					int point = path.get(i).getPackedPosition();
					int lastPoint = (i > 0) ? path.get(i - 1).getPackedPosition() : point;
					if (WorldPointUtil.distanceBetween(point, lastPoint) > 1)
					{
						drawOnMap(graphics, lastPoint, point, true, cursorPos);
					}
					drawOnMap(graphics, point, true, cursorPos);
				}
			}
			for (int target : plugin.getActiveSearch().getTargets().points())
			{
				if (!path.isEmpty() && target != path.get(path.size() - 1).getPackedPosition())
				{
					graphics.setColor(plugin.colourPathCalculating);
					drawOnMap(graphics, target, true, cursorPos);
				}
			}
		}

		return null;
	}

	/**
	 * Draws the path as a directed polyline: consecutive same-direction walking steps are compressed
	 * into one straight segment, each segment ends in a small arrowhead showing travel direction, and
	 * teleport/transport jumps are dashed. (Arrowhead technique adapted from Quest Helper's
	 * DirectionArrow, see {@link ArrowHead}.)
	 */
	private void drawArrowPath(Graphics2D graphics, List<PathStep> path)
	{
		final Stroke walkStroke = new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
		final Stroke jumpStroke = new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{9}, 0);

		int i = 0;
		while (i < path.size() - 1)
		{
			int from = path.get(i).getPackedPosition();
			int next = path.get(i + 1).getPackedPosition();

			if (WorldPointUtil.distanceBetween(from, next) > 1)
			{
				// Teleport/transport jump: dashed segment.
				drawMapSegment(graphics, from, next, jumpStroke);
				i++;
				continue;
			}

			// Extend the straight walking run while the direction stays the same.
			int dx = WorldPointUtil.unpackWorldX(next) - WorldPointUtil.unpackWorldX(from);
			int dy = WorldPointUtil.unpackWorldY(next) - WorldPointUtil.unpackWorldY(from);
			int j = i + 1;
			while (j < path.size() - 1)
			{
				int a = path.get(j).getPackedPosition();
				int b = path.get(j + 1).getPackedPosition();
				if (WorldPointUtil.distanceBetween(a, b) > 1
					|| WorldPointUtil.unpackWorldX(b) - WorldPointUtil.unpackWorldX(a) != dx
					|| WorldPointUtil.unpackWorldY(b) - WorldPointUtil.unpackWorldY(a) != dy)
				{
					break;
				}
				j++;
			}
			drawMapSegment(graphics, from, path.get(j).getPackedPosition(), walkStroke);
			i = j;
		}
	}

	private void drawMapSegment(Graphics2D graphics, int from, int to, Stroke stroke)
	{
		int x1 = plugin.mapWorldPointToGraphicsPointX(from);
		int y1 = plugin.mapWorldPointToGraphicsPointY(from);
		int x2 = plugin.mapWorldPointToGraphicsPointX(to);
		int y2 = plugin.mapWorldPointToGraphicsPointY(to);
		if (x1 == Integer.MIN_VALUE || y1 == Integer.MIN_VALUE
			|| x2 == Integer.MIN_VALUE || y2 == Integer.MIN_VALUE)
		{
			return;
		}
		graphics.setStroke(stroke);
		graphics.drawLine(x1, y1, x2, y2);
		// Skip the head on segments too short to fit it (e.g. zoomed far out).
		if (Math.hypot(x2 - x1, y2 - y1) >= 10)
		{
			ArrowHead.draw(graphics, x1, y1, x2, y2, 7);
		}
	}

	private void drawSmallSquareOnMap(Graphics2D graphics, int point)
	{
		int x = plugin.mapWorldPointToGraphicsPointX(point);
		int y = plugin.mapWorldPointToGraphicsPointY(point);
		int nextX = plugin.mapWorldPointToGraphicsPointX(WorldPointUtil.dxdy(point, 1, -1));
		if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || nextX == Integer.MIN_VALUE)
		{
			return;
		}
		int size = Math.max(2, (nextX - x) / 2);
		graphics.fillRect(x - size / 2, y - size / 2, size, size);
	}

	private void drawSolidLineOnMap(Graphics2D graphics, int from, int to)
	{
		int x1 = plugin.mapWorldPointToGraphicsPointX(from);
		int y1 = plugin.mapWorldPointToGraphicsPointY(from);
		int x2 = plugin.mapWorldPointToGraphicsPointX(to);
		int y2 = plugin.mapWorldPointToGraphicsPointY(to);
		if (x1 == Integer.MIN_VALUE || y1 == Integer.MIN_VALUE || x2 == Integer.MIN_VALUE || y2 == Integer.MIN_VALUE)
		{
			return;
		}
		graphics.setStroke(new BasicStroke(2));
		graphics.drawLine(x1, y1, x2, y2);
	}

	private void drawOnMap(Graphics2D graphics, int point, boolean checkHover, Point cursorPos)
	{
		drawOnMap(graphics, point, WorldPointUtil.dxdy(point, 1, -1), checkHover, cursorPos);
	}

	private void drawOnMap(Graphics2D graphics, int point, int offsetPoint, boolean checkHover, Point cursorPos)
	{
		int startX = plugin.mapWorldPointToGraphicsPointX(point);
		int startY = plugin.mapWorldPointToGraphicsPointY(point);
		int endX = plugin.mapWorldPointToGraphicsPointX(offsetPoint);
		int endY = plugin.mapWorldPointToGraphicsPointY(offsetPoint);

		if (startX == Integer.MIN_VALUE || startY == Integer.MIN_VALUE ||
			endX == Integer.MIN_VALUE || endY == Integer.MIN_VALUE)
		{
			return;
		}

		int x = startX;
		int y = startY;
		final int width = endX - x;
		final int height = endY - y;
		x -= width / 2;
		y -= height / 2;

		if (WorldPointUtil.distanceBetween(point, offsetPoint) > 1)
		{
			graphics.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{9}, 0));
			graphics.drawLine(startX, startY, endX, endY);
		}
		else
		{
			if (checkHover && cursorPos != null &&
				cursorPos.getX() >= x && cursorPos.getX() <= (endX - width / 2) &&
				cursorPos.getY() >= y && cursorPos.getY() <= (endY - width / 2))
			{
				graphics.setColor(graphics.getColor().darker());
			}
			graphics.fillRect(x, y, width, height);
		}
	}
}
