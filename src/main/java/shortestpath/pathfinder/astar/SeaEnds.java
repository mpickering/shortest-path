package shortestpath.pathfinder.astar;

import java.util.function.BooleanSupplier;

/**
 * Where a route meets the sea at its ends: links from a start at sea to the land tiles a boat can put the player
 * ashore on, from land tiles where a boat can be boarded to a target at sea, and from the start straight to that
 * target. A link's cost is sailing time plus boarding or stepping off, and is found lazily: each link starts with a
 * lower bound ({@link #bound}), and the search asks for the exact cost ({@link #exact}) only once the link is the
 * cheapest thing left to look at, so it sails only the few legs that could end up on the route.
 * <p>
 * The search takes the links as given: whatever makes them (boats, moorings, requirements) lives with the caller.
 */
public interface SeaEnds
{
	/** No ends at sea: the search walks from its start to land targets. */
	SeaEnds NONE = new SeaEnds()
	{
	};

	/** A cost for a link that can't be sailed. */
	int NO_LINK = CostArithmetic.INF;

	/** Whether the route starts at sea, so the start's own tile is not walked from. */
	default boolean startsAtSea()
	{
		return false;
	}

	/** Links from the start at sea to land: each lands on {@link #landTile}. */
	default int[] startLinks()
	{
		return new int[0];
	}

	/** The link from the start at sea straight to a target at sea, or -1. */
	default int directLink()
	{
		return -1;
	}

	/** Links boarded from {@code landTile} to a target at sea; empty for most tiles. */
	default int[] targetLinksFrom(int landTile)
	{
		return EMPTY;
	}

	/** The land tile a start link lands on. */
	default int landTile(int link)
	{
		throw new IllegalArgumentException("no link " + link);
	}

	/** A lower bound on {@code link}'s cost, or {@link #NO_LINK}. */
	default int bound(int link)
	{
		return NO_LINK;
	}

	/**
	 * A tighter lower bound on {@code link}'s cost than {@code bound}, if one can be had cheaply now, else
	 * {@code bound}: the search takes a link whose bound rose back to its queue at the new bound, and sails it only
	 * once its bound stops rising.
	 */
	default int refine(int link, int bound, BooleanSupplier cancelled)
	{
		return bound;
	}

	/** {@code link}'s cost, sailing its leg, or {@link #NO_LINK} if it can't be sailed or was cancelled. */
	default int exact(int link, BooleanSupplier cancelled)
	{
		return NO_LINK;
	}

	/**
	 * The boat's tiles along a link whose {@link #exact} cost is known, with the cost from the link's start at each:
	 * {@code {tiles, costs}}. A start link's last tile is where the boat docks, before the player steps ashore.
	 */
	default int[][] leg(int link)
	{
		throw new IllegalArgumentException("no link " + link);
	}

	/** The target at sea a target or direct link ends at. */
	default int seaTarget(int link)
	{
		throw new IllegalArgumentException("no link " + link);
	}

	/** Exact costs found so far: how many legs the search sailed. */
	default int legsSailed()
	{
		return 0;
	}

	int[] EMPTY = new int[0];
}
