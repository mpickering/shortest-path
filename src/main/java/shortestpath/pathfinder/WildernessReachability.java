package shortestpath.pathfinder;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import shortestpath.PrimitiveIntHashMap;
import shortestpath.WorldPointUtil;
import shortestpath.transport.Transport;

/**
 * Finds targets that can only be reached through the Wilderness, for which Avoid wilderness must
 * not apply: the route has no choice, so avoiding it only makes the target unreachable (the
 * Abyss, the Death altar, the King Black Dragon lair).
 * <p>
 * A target needs the Wilderness when no route from the start reaches it without stepping from
 * outside the Wilderness into it. That is answered by flooding backwards from the target's goal
 * tiles over tiles outside the Wilderness -- walking steps, and the account's usable transports
 * with both ends outside it -- until the flood reaches the start or the landing tile of a usable
 * teleport. If the flood closes first, every tile it visited needs the Wilderness too (any of them
 * reaches the target without it), so the answer is a property of that whole area and is kept for
 * later searches. The config builds a new instance on every refresh, since transports depend on
 * the account.
 */
final class WildernessReachability
{
	// Floods that grow past this many tiles stop and keep avoiding the Wilderness: an area that
	// large almost always holds a teleport landing, so this only bounds the worst case.
	private static final int FLOOD_LIMIT = 250_000;
	private static final int[] DX = {0, 1, 1, 1, 0, -1, -1, -1};
	private static final int[] DY = {1, 1, 0, -1, -1, -1, 0, 1};

	private final TransportAvailability transports;
	private final Set<Integer> needsWilderness = ConcurrentHashMap.newKeySet();
	// destination -> origins of usable transports with both ends outside the Wilderness
	private volatile PrimitiveIntHashMap<int[]> reverseTransports;
	private volatile Set<Integer> teleportLandings;

	WildernessReachability(TransportAvailability transports)
	{
		this.transports = transports;
	}

	/**
	 * Whether every route from start to the goals must enter the Wilderness. Goals inside the
	 * Wilderness are left to the caller's own check. The collision map is the caller's thread's.
	 */
	boolean onlyThroughWilderness(CollisionMap map, int start, Set<Integer> goals)
	{
		final Set<Integer> seeds = new HashSet<>();
		for (int goal : goals)
		{
			if (WildernessChecker.isInWilderness(goal))
			{
				return false;
			}
			if (needsWilderness.contains(goal))
			{
				continue;
			}
			seeds.add(goal);
		}
		if (seeds.isEmpty())
		{
			return !goals.isEmpty();
		}
		if (WildernessChecker.isInWilderness(start))
		{
			return false;
		}
		prepare();
		final Set<Integer> seen = new HashSet<>(seeds);
		final ArrayDeque<Integer> queue = new ArrayDeque<>(seeds);
		while (!queue.isEmpty())
		{
			final int tile = queue.poll();
			if (tile == start || teleportLandings.contains(tile))
			{
				return false;
			}
			final int x = WorldPointUtil.unpackWorldX(tile);
			final int y = WorldPointUtil.unpackWorldY(tile);
			final int z = WorldPointUtil.unpackWorldPlane(tile);
			for (int i = 0; i < DX.length; i++)
			{
				final int from = WorldPointUtil.packWorldPoint(x + DX[i], y + DY[i], z);
				if (!seen.contains(from) && walksTo(map, from, tile) && !WildernessChecker.isInWilderness(from))
				{
					seen.add(from);
					queue.add(from);
				}
			}
			final int[] origins = reverseTransports.get(tile);
			if (origins != null)
			{
				for (int from : origins)
				{
					if (seen.add(from))
					{
						queue.add(from);
					}
				}
			}
			if (seen.size() > FLOOD_LIMIT)
			{
				return false;
			}
		}
		needsWilderness.addAll(seen);
		return true;
	}

	private static boolean walksTo(CollisionMap map, int from, int to)
	{
		for (int next : map.ordinaryWalkingNeighbors(from))
		{
			if (next == to)
			{
				return true;
			}
		}
		return false;
	}

	private synchronized void prepare()
	{
		if (reverseTransports != null)
		{
			return;
		}
		final Set<Integer> landings = new HashSet<>();
		for (Transport teleport : transports.getUsableTeleports())
		{
			if (!WildernessChecker.isInWilderness(teleport.getDestination()))
			{
				landings.add(teleport.getDestination());
			}
		}
		final PrimitiveIntHashMap<int[]> reverse = new PrimitiveIntHashMap<>(1024);
		final PrimitiveIntHashMap<Transport[]> byOrigin = transports.getTransportsPacked();
		for (int origin : byOrigin.keys())
		{
			if (WildernessChecker.isInWilderness(origin))
			{
				continue;
			}
			for (Transport transport : byOrigin.get(origin))
			{
				final int destination = transport.getDestination();
				if (destination == WorldPointUtil.UNDEFINED || WildernessChecker.isInWilderness(destination))
				{
					continue;
				}
				final int[] known = reverse.get(destination);
				final int[] grown = known == null ? new int[1] : java.util.Arrays.copyOf(known, known.length + 1);
				grown[grown.length - 1] = origin;
				reverse.put(destination, grown);
			}
		}
		teleportLandings = landings;
		reverseTransports = reverse;
	}
}
