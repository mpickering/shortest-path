package shortestpath.pathfinder.sailing;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * One sailed stretch of a route, kept whole so the route can be drawn and followed later: the boat's tile at the start
 * and at the end of every move, the heading each move held, and the cost at each point from the start of the leg.
 * {@link #reached} says what the leg ended for: the berth of the mooring it docks at, or the target its hull covers,
 * which can be a boat length from the boat's own tile.
 */
@Accessors(fluent = true)
public final class SailingLeg
{
	private final int[] points;
	private final int[] headings;
	private final int[] costs;
	@Getter private final int reached;

	SailingLeg(int[] points, int[] headings, int[] costs, int reached)
	{
		if (points.length == 0 || headings.length != points.length || costs.length != points.length)
			throw new IllegalArgumentException("a sailing leg needs one heading and cost per point");
		this.points = points;
		this.headings = headings;
		this.costs = costs;
		this.reached = reached;
	}

	/** Points on the leg. */
	public int size()
	{
		return points.length;
	}

	/** The boat's tile at point {@code index}. */
	public int point(int index)
	{
		return points[index];
	}

	/** The heading of the move that reached point {@code index} (0 south, 8 north), -1 for the first point. */
	public int heading(int index)
	{
		return headings[index];
	}

	/** Ticks from the start of the leg to point {@code index}. */
	public int costAt(int index)
	{
		return costs[index];
	}

	/** Ticks the leg takes. */
	public int cost()
	{
		return costs[costs.length - 1];
	}

	/** The boat's tile where the leg starts. */
	public int entry()
	{
		return points[0];
	}

	/** The boat's tile where the leg ends. */
	public int exit()
	{
		return points[points.length - 1];
	}
}
