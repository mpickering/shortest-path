package shortestpath.pathfinder.astar;

import java.util.Arrays;
import shortestpath.WorldPointUtil;
import lombok.AllArgsConstructor;
import shortestpath.pathfinder.WildernessChecker;

/**
 * Lower bounds on the walking cost from a target's attachment sites to the target, from a bounded
 * breadth-first search backwards over the static walking edges, within the target's routing
 * components.
 *
 * <p>Why a walk inside the components is enough: sites are every transport endpoint, crossing
 * endpoint and bank, and routing components are only left by crossing a cut, whose endpoints are
 * sites. A route's last stretch after its last site is therefore a pure walk inside the target's
 * components, and costs at least the distance found here. A site never reached gets the distance
 * of the search's frontier when it stopped (no closer tile was left unvisited), and every bound
 * is at least the Chebyshev distance the overlay used before.
 *
 * <p>Avoiding the Wilderness, the search follows only steps that do not enter it from outside.
 * Then a finished search proves that a site it never reached cannot walk to the target without
 * entering the Wilderness, and {@link #bound} returns {@link CostArithmetic#INF} for it. (Without
 * the gate every tile of a routing component reaches every other, so that never happens.)
 */
@AllArgsConstructor
final class TargetWalkDistances
{
	/** Tiles the search may visit per target; larger components get frontier bounds beyond it. */
	static final int TILE_BUDGET = 1 << 16;

	// Bit b of a walking mask steps by (DX[b], DY[b]), as in AStarForwardSearch.walkBase.
	private static final int[] DX = {0, 1, 1, 1, 0, -1, -1, -1};
	private static final int[] DY = {1, 1, 0, -1, -1, -1, 0, 1};

	private final RoutingStatic stat;
	private final int[] components;
	private final NodeDistances distances;
	private final int frontier;
	/** Whether a site the search never reached cannot walk to the target (gated, finished search). */
	private final boolean unreachedIsUnreachable;



	/** Null when the target is not a static search tile (its attachments keep Chebyshev costs). */
	static TargetWalkDistances search(RoutingStatic stat, int target, int[] components)
	{
		return search(stat, target, components, false);
	}

	/** @param avoidWilderness follow only steps that do not enter the Wilderness from outside it */
	static TargetWalkDistances search(RoutingStatic stat, int target, int[] components, boolean avoidWilderness)
	{
		int start = stat.searchIndex(target);
		if (start < 0) return null;
		NodeDistances distances = new NodeDistances(TILE_BUDGET);
		int[] queue = new int[TILE_BUDGET];
		int head = 0, tail = 0;
		distances.put(start, 0);
		queue[tail++] = start;
		int frontier = 0;
		boolean exhausted = true;
		while (head < tail)
		{
			int node = queue[head++];
			int distance = distances.get(node);
			for (int direction = 0; direction < 8; direction++)
			{
				// The neighbour in this direction is a predecessor if it walks back onto this tile,
				// by the same index arithmetic the forward search uses.
				int from = successor(stat, node, direction), bit = (direction + 4) & 7;
				if (from < 0 || from >= stat.searchTileCount()) continue;
				if ((stat.walkingMask(from) & (1 << bit)) == 0 || successor(stat, from, bit) != node) continue;
				if (distances.get(from) >= 0 || !inComponents(stat.routingComponent(from), components)) continue;
				if (avoidWilderness && entersWilderness(stat.searchTile(from), stat.searchTile(node))) continue;
				if (tail == TILE_BUDGET)
				{
					exhausted = false;
					break;
				}
				distances.put(from, distance + 1);
				queue[tail++] = from;
			}
			if (!exhausted)
			{
				// Every unvisited tile is at least as far as the tile being expanded.
				frontier = distance;
				break;
			}
			frontier = distance + 1;
		}
		return new TargetWalkDistances(stat, components, distances, frontier, avoidWilderness && exhausted);
	}

	private static boolean entersWilderness(int from, int to)
	{
		return !WildernessChecker.isInWilderness(from) && WildernessChecker.isInWilderness(to);
	}

	/**
	 * A lower bound on walking from {@code site} to the target, never below {@code chebyshev}.
	 * A site off the search tiles (a blocked transport origin) first steps to a neighbour.
	 */
	int bound(int site, int chebyshev)
	{
		int node = stat.searchIndex(site);
		if (node >= 0)
		{
			int distance = distance(node);
			return distance == CostArithmetic.INF ? distance : Math.max(chebyshev, distance);
		}
		int x = WorldPointUtil.unpackWorldX(site), y = WorldPointUtil.unpackWorldY(site);
		int plane = WorldPointUtil.unpackWorldPlane(site);
		int best = Integer.MAX_VALUE;
		boolean anyNeighbour = false;
		for (int bit = 0; bit < 8; bit++)
		{
			int neighbourTile = WorldPointUtil.packWorldPoint(x + DX[bit], y + DY[bit], plane);
			int neighbour = stat.searchIndex(neighbourTile);
			if (neighbour < 0) continue;
			anyNeighbour = true;
			if (unreachedIsUnreachable && entersWilderness(site, neighbourTile)) continue;
			int distance = distance(neighbour);
			if (distance != CostArithmetic.INF) best = Math.min(best, distance);
		}
		if (best != Integer.MAX_VALUE) return Math.max(chebyshev, 1 + best);
		return anyNeighbour && unreachedIsUnreachable ? CostArithmetic.INF : chebyshev;
	}

	private int distance(int node)
	{
		int known = distances.get(node);
		return known >= 0 ? known : unreachedIsUnreachable ? CostArithmetic.INF : frontier;
	}

	private static int successor(RoutingStatic stat, int node, int bit)
	{
		int south = stat.southNode(node), north = stat.northNode(node);
		switch (bit)
		{
			case 0: return north;
			case 1: return north < 0 ? -1 : north + 1;
			case 2: return node + 1;
			case 3: return south < 0 ? -1 : south + 1;
			case 4: return south;
			case 5: return south < 0 ? -1 : south - 1;
			case 6: return node - 1;
			default: return north < 0 ? -1 : north - 1;
		}
	}

	private static boolean inComponents(int component, int[] components)
	{
		for (int value : components)
			if (value == component) return true;
		return false;
	}

	/** Open-addressing node -> distance map sized for the tile budget. */
	private static final class NodeDistances
	{
		private final int[] keys, values;
		private final int mask;

		NodeDistances(int capacity)
		{
			int size = Integer.highestOneBit(capacity * 2 - 1) << 1;
			keys = new int[size];
			values = new int[size];
			Arrays.fill(keys, -1);
			mask = size - 1;
		}

		void put(int key, int value)
		{
			int slot = mix(key) & mask;
			while (keys[slot] != -1 && keys[slot] != key) slot = (slot + 1) & mask;
			keys[slot] = key;
			values[slot] = value;
		}

		int get(int key)
		{
			int slot = mix(key) & mask;
			while (keys[slot] != -1)
			{
				if (keys[slot] == key) return values[slot];
				slot = (slot + 1) & mask;
			}
			return -1;
		}

		private static int mix(int key)
		{
			int h = key * 0x9E3779B9;
			return h ^ (h >>> 16);
		}
	}
}
