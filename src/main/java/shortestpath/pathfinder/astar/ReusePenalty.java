package shortestpath.pathfinder.astar;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.PathStep;
import shortestpath.transport.Transport;

/**
 * The second cost component of an alternative-route search. A path costs {@code (duration, reused)},
 * where {@code reused} counts its journeys (see {@link #isJourney}) that land where an already
 * chosen route's journeys land. The search orders paths by {@code duration + perReuse * reused}
 * and drops any whose duration cannot stay within {@code durationLimit}.
 *
 * <p>Costs only rise, so the prepared heuristic stays admissible for the score and is a lower
 * bound on the remaining duration.
 */
public final class ReusePenalty
{
	/** A transport moving at most this far is a door, a stair or a step: not a choice of route. */
	private static final int MAX_LOCAL_TILES = 8;

	private final int[] chosenLandings;
	private final int perReuse;
	private final int durationLimit;

	ReusePenalty(Set<Integer> chosenLandings, int perReuse, int durationLimit)
	{
		this.chosenLandings = chosenLandings.stream().mapToInt(Integer::intValue).sorted().toArray();
		this.perReuse = CostArithmetic.validate(perReuse);
		this.durationLimit = CostArithmetic.validate(durationLimit);
	}

	/**
	 * Whether a user would see this transport as a choice of route: a teleport, or a jump far
	 * enough to matter. Walking-distance transports are never counted, so a route is not sent on a
	 * detour to avoid a door.
	 */
	public static boolean isJourney(Transport transport)
	{
		int origin = transport.getOrigin();
		if (origin == Transport.UNDEFINED_ORIGIN || origin == Transport.LOCATION_PERMUTATION) return true;
		int destination = transport.getDestination();
		return Math.max(Math.abs(WorldPointUtil.unpackWorldX(origin) - WorldPointUtil.unpackWorldX(destination)),
			Math.abs(WorldPointUtil.unpackWorldY(origin) - WorldPointUtil.unpackWorldY(destination)))
			> MAX_LOCAL_TILES;
	}

	/**
	 * Where each journey along {@code route} lands. A journey is identified by its landing tile, so
	 * a teleport's spell and tablet, or two fairy rings to one ring, count as the same choice.
	 */
	public static Set<Integer> journeyLandings(AStarRoute route)
	{
		Set<Integer> landings = new HashSet<>();
		for (PathStep step : route.steps())
		{
			Transport transport = step.getTransport();
			if (transport != null && isJourney(transport)) landings.add(transport.getDestination());
		}
		return landings;
	}

	/** How many chosen journeys {@code transport} reuses: 0 or 1. Walking edges pass {@code null}. */
	int reuses(Transport transport)
	{
		return transport != null && isJourney(transport)
			&& Arrays.binarySearch(chosenLandings, transport.getDestination()) >= 0 ? 1 : 0;
	}

	int perReuse()
	{
		return perReuse;
	}

	/** Whether a path with this duration so far, and {@code heuristic} left, overruns the limit. */
	boolean overruns(int duration, int heuristic)
	{
		return CostArithmetic.add(duration, heuristic) > durationLimit;
	}
}
