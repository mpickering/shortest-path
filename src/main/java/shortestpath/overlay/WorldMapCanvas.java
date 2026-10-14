package shortestpath.overlay;

import com.google.inject.Inject;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.JagexColors;
import shortestpath.ShortestPathPlugin;
import shortestpath.WorldPointUtil;

/**
 * Where world tiles land on the open world map, shared by the map overlays: tile squares, hover
 * tests, the visible extent, and the tooltip box drawn over the map.
 */
public class WorldMapCanvas
{
	private static final int TOOLTIP_OFFSET_HEIGHT = 25;
	private static final int TOOLTIP_OFFSET_WIDTH = 15;
	private static final int TOOLTIP_PADDING_HEIGHT = 1;
	private static final int TOOLTIP_PADDING_WIDTH = 2;
	private static final int TOOLTIP_TEXT_OFFSET_HEIGHT = -2;

	private final Client client;
	private final ShortestPathPlugin plugin;

	@Inject
	WorldMapCanvas(Client client, ShortestPathPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
	}

	/** The map's bounds on the canvas, or null when the world map is closed. */
	public Rectangle bounds()
	{
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		return map == null ? null : map.getBounds();
	}

	/** The map's bounds less the overview and surface selector drawn over it. */
	public Area clipArea(Rectangle bounds)
	{
		final Widget overview = client.getWidget(InterfaceID.Worldmap.OVERVIEW_CONTAINER);
		final Widget surfaceSelector = client.getWidget(InterfaceID.Worldmap.MAPLIST_BOX_GRAPHIC0);

		Area clipArea = new Area(bounds);

		if (overview != null && !overview.isHidden())
		{
			clipArea.subtract(new Area(overview.getBounds()));
		}

		if (surfaceSelector != null && !surfaceSelector.isHidden())
		{
			clipArea.subtract(new Area(surfaceSelector.getBounds()));
		}

		return clipArea;
	}

	/** The canvas x of a tile's centre, or {@link Integer#MIN_VALUE} when the map is closed. */
	public int x(int point)
	{
		return plugin.mapWorldPointToGraphicsPointX(point);
	}

	/** The canvas y of a tile's centre, or {@link Integer#MIN_VALUE} when the map is closed. */
	public int y(int point)
	{
		return plugin.mapWorldPointToGraphicsPointY(point);
	}

	public float pixelsPerTile()
	{
		return client.getWorldMap().getWorldMapZoom();
	}

	/** The square a tile covers on the map, or null when the map is closed. */
	public Rectangle tileRect(int point)
	{
		int offsetPoint = WorldPointUtil.dxdy(point, 1, -1);
		int startX = x(point);
		int startY = y(point);
		int endX = x(offsetPoint);
		int endY = y(offsetPoint);
		if (startX == Integer.MIN_VALUE || startY == Integer.MIN_VALUE
			|| endX == Integer.MIN_VALUE || endY == Integer.MIN_VALUE)
		{
			return null;
		}
		int width = endX - startX;
		int height = endY - startY;
		return new Rectangle(startX - width / 2, startY - height / 2, width, height);
	}

	/** Whether the cursor is over the tile's square, edges included. */
	public boolean isHovered(int point, Point cursor)
	{
		Rectangle rect = tileRect(point);
		return rect != null && cursor != null
			&& cursor.getX() >= rect.x && cursor.getX() <= rect.x + rect.width
			&& cursor.getY() >= rect.y && cursor.getY() <= rect.y + rect.height;
	}

	/**
	 * How far, in pixels, the cursor may be from a tile's centre and still hover it: the tile's
	 * own half-width, but never so small that a zoomed-out marker cannot be hovered.
	 */
	public double hoverRadius()
	{
		return Math.max(6, pixelsPerTile() / 2);
	}

	/** The pixel distance from the cursor to a tile's centre; infinite when the map is closed. */
	public double distance(int point, Point cursor)
	{
		int x = x(point);
		int y = y(point);
		if (cursor == null || x == Integer.MIN_VALUE || y == Integer.MIN_VALUE)
		{
			return Double.POSITIVE_INFINITY;
		}
		return Math.hypot(cursor.getX() - x, cursor.getY() - y);
	}

	/**
	 * The world tiles the map shows: {minX, minY, maxX, maxY}, inclusive, or null when the map is
	 * closed.
	 */
	public int[] visibleTiles(Rectangle bounds)
	{
		int topLeft = plugin.calculateMapPoint(bounds.x, bounds.y);
		int bottomRight = plugin.calculateMapPoint(bounds.x + bounds.width, bounds.y + bounds.height);
		if (topLeft == WorldPointUtil.UNDEFINED || bottomRight == WorldPointUtil.UNDEFINED)
		{
			return null;
		}
		return new int[]{
			WorldPointUtil.unpackWorldX(topLeft), WorldPointUtil.unpackWorldY(bottomRight),
			WorldPointUtil.unpackWorldX(bottomRight), WorldPointUtil.unpackWorldY(topLeft)};
	}

	/** Draws a game-style tooltip next to (x, y), kept inside the map's bounds. */
	public void drawTooltip(Graphics2D graphics, Rectangle bounds, int x, int y, List<String> rows)
	{
		graphics.setFont(FontManager.getRunescapeFont());
		FontMetrics fm = graphics.getFontMetrics();
		int tooltipHeight = fm.getHeight();
		int tooltipWidth = 0;
		for (String row : rows)
		{
			tooltipWidth = Math.max(tooltipWidth, fm.stringWidth(row));
		}
		int clippedHeight = tooltipHeight * rows.size() + TOOLTIP_PADDING_HEIGHT * 2;
		int clippedWidth = tooltipWidth + TOOLTIP_PADDING_WIDTH * 2;

		int rightBoundary = bounds.width + bounds.x;
		int bottomBoundary = bounds.height + bounds.y;

		int drawPointX = x + TOOLTIP_OFFSET_WIDTH;
		int drawPointY = y;
		if (drawPointX + clippedWidth > rightBoundary)
		{
			drawPointX = rightBoundary - clippedWidth;
		}
		if (drawPointY + clippedHeight > bottomBoundary)
		{
			drawPointY = y - clippedHeight;
		}
		drawPointY += TOOLTIP_OFFSET_HEIGHT;

		int tooltipRectX = drawPointX - TOOLTIP_PADDING_WIDTH;
		int tooltipRectY = drawPointY - TOOLTIP_PADDING_HEIGHT;

		graphics.setColor(JagexColors.TOOLTIP_BACKGROUND);
		graphics.fillRect(tooltipRectX, tooltipRectY, clippedWidth, clippedHeight);

		graphics.setColor(JagexColors.TOOLTIP_BORDER);
		graphics.drawRect(tooltipRectX, tooltipRectY, clippedWidth, clippedHeight);

		graphics.setColor(JagexColors.TOOLTIP_TEXT);
		for (int i = 0; i < rows.size(); i++)
		{
			graphics.drawString(rows.get(i), drawPointX, drawPointY + TOOLTIP_TEXT_OFFSET_HEIGHT + (i + 1) * tooltipHeight);
		}
	}
}
