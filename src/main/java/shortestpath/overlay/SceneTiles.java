package shortestpath.overlay;

import java.awt.Polygon;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import shortestpath.PrimitiveIntList;
import shortestpath.WorldPointUtil;

/**
 * Where world tiles land in the game view, mapped into the viewed instance.
 */
final class SceneTiles
{
	private SceneTiles()
	{
	}

	/** The canvas centre of a tile on the viewed plane, or null when it is not in view. */
	static Point center(Client client, int point)
	{
		if (point == WorldPointUtil.UNDEFINED || client == null)
		{
			return null;
		}

		// Map the canonical point into the viewed instance before checking the
		// plane — inside an instance (e.g. a POH) the stored template plane can
		// differ from the instance plane without being on a different floor.
		PrimitiveIntList points = WorldPointUtil.toLocalInstance(client, point);
		for (int i = 0; i < points.size(); i++)
		{
			int local = points.get(i);
			if (local == WorldPointUtil.UNDEFINED
				|| WorldPointUtil.unpackWorldPlane(local) != client.getTopLevelWorldView().getPlane())
			{
				continue;
			}

			LocalPoint lp = WorldPointUtil.toLocalPoint(client, local);
			if (lp == null)
			{
				continue;
			}

			Polygon poly = Perspective.getCanvasTilePoly(client, lp);
			if (poly == null)
			{
				continue;
			}

			int cx = poly.getBounds().x + poly.getBounds().width / 2;
			int cy = poly.getBounds().y + poly.getBounds().height / 2;
			return new Point(cx, cy);
		}

		return null;
	}

	/** The canvas outline of each instance copy of a tile in view. */
	static List<Polygon> polygons(Client client, int point)
	{
		List<Polygon> polygons = new ArrayList<>(1);
		if (client == null)
		{
			return polygons;
		}

		PrimitiveIntList points = WorldPointUtil.toLocalInstance(client, point);
		for (int i = 0; i < points.size(); i++)
		{
			int local = points.get(i);
			if (local == WorldPointUtil.UNDEFINED)
			{
				continue;
			}

			LocalPoint lp = WorldPointUtil.toLocalPoint(client, local);
			if (lp == null)
			{
				continue;
			}

			Polygon poly = Perspective.getCanvasTilePoly(client, lp);
			if (poly != null)
			{
				polygons.add(poly);
			}
		}
		return polygons;
	}
}
