package shortestpath.pathfinder.sailing;

import java.util.Arrays;
import java.util.function.IntUnaryOperator;

import static net.runelite.api.Constants.REGION_SIZE;
import shortestpath.PrimitiveIntHashMap;
import shortestpath.PrimitiveIntList;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.BankVisitState;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.NodeGraph;
import shortestpath.pathfinder.SplitFlagMap;

/**
 * One sailing search's moves, hull, targets and when it counts as arriving (see {@link SailingSolver}).
 * <p>
 * The path arrives on the move whose hull, as it sails, passes over a target tile: the targets are expanded, once,
 * into the tiles each move can set out from to do so ({@link #arrivingMoves}). The boat passes over the target during
 * the move, so it may arrive though no move ends with the target under its hull: at 2.5 tiles a tick every move sails
 * 5 tiles, and a small inlet can lie between where they end.
 * <p>
 * It also remembers the cheapest cost each tile has been queued at, so that a tile is only queued again when a route
 * reaches it for less — or when a costlier move would arrive on a heading none has tried: a dearer one would only be
 * dequeued once the tile is done.
 */
final class SailingSearch
{
	/** An estimate meaning no end of the search can be reached from the tile: the move there isn't queued. */
	static final int NO_ROUTE = Integer.MAX_VALUE;

	private static final int REGION_SHIFT = Integer.numberOfTrailingZeros(REGION_SIZE);
	private static final int REGION_MASK = REGION_SIZE - 1;
	private static final int PLANES = 4;
	private static final int HEADINGS = 16;

	final SailingMoves moves;
	final BoatHull hull;
	// A lower bound on the ticks left from each boat tile; may answer NO_ROUTE
	private final IntUnaryOperator estimate;
	private final int[] targets;
	// The heading the boat is facing where it sets out, or -1 when that isn't known: a source has no move to face, so
	// without it a target under the hull counts as arrived facing any heading — even one the boat, at rest, isn't
	// pointing
	private final int initialHeading;
	// The targets expanded by the moves: per tile a move can set out from to pass over a target, which moves do, as
	// one bitmask per target
	private final PrimitiveIntHashMap<int[]> arrivingMoves;
	private final SplitFlagMap.RegionExtent extent;
	// The cheapest cost each tile has been queued at, one array per region and plane, made as the search reaches them
	private final int[][] queuedCosts;
	// Which moves have already been queued into each tile at a cost that doesn't beat the tile's cheapest, as a bitmask.
	// A tile's cheapest route may face a way its hull doesn't cover a target in, so a costlier move that would arrive is
	// still queued — once per move
	private final int[][] queuedArrivals;
	// The cost each tile was settled at, cheapest first; a tile reached for less later is settled again, which lets the
	// search keep an estimate that is admissible but not consistent
	private final int[][] settledCosts;

	SailingSearch(SailingMoves moves, BoatHull hull, int[] targets, int initialHeading, IntUnaryOperator estimate)
	{
		if (hull == null) throw new NullPointerException("hull");
		this.moves = moves;
		this.hull = hull;
		this.targets = targets;
		this.initialHeading = initialHeading;
		this.estimate = estimate;
		arrivingMoves = targets.length == 0 ? null : arrivingMoves(hull, moves, targets);
		extent = SplitFlagMap.getRegionExtents();
		int regions = (extent.getWidth() + 1) * (extent.getHeight() + 1) * PLANES;
		queuedCosts = new int[regions][];
		queuedArrivals = new int[regions][];
		settledCosts = new int[regions][];
	}

	/**
	 * The heading the boat faces on {@code node}: the way the move that reached it sailed, or on a source, which no
	 * move reached, the heading the boat was facing there. -1 means unknown, which lets {@link #arrivedAt} try every
	 * heading.
	 */
	int arrivalHeading(NodeGraph graph, int node)
	{
		// The move is stored on the node rather than recovered from its displacement, which different headings can
		// share at low speeds
		int move = graph.moveIndex(node);
		return move < 0 ? initialHeading : moves.heading(move);
	}

	/**
	 * Every target expanded into the tiles a move can set out from with the hull passing over it, mapped to which moves
	 * do so, one bitmask per target.
	 */
	static PrimitiveIntHashMap<int[]> arrivingMoves(BoatHull hull, SailingMoves moves, int[] targets)
	{
		PrimitiveIntHashMap<int[]> starts = new PrimitiveIntHashMap<>(256);
		for (int t = 0; t < targets.length; t++)
		{
			int tx = WorldPointUtil.unpackWorldX(targets[t]), ty = WorldPointUtil.unpackWorldY(targets[t]);
			int tz = WorldPointUtil.unpackWorldPlane(targets[t]);
			for (int move = 0; move < moves.size(); move++)
			{
				int[] swept = hull.sweptOffsets(moves.heading(move), moves.dx(move), moves.dy(move));
				for (int i = 0; i < swept.length; i += 2)
				{
					int start = WorldPointUtil.packWorldPoint(tx - swept[i], ty - swept[i + 1], tz);
					int[] masks = starts.get(start);
					if (masks == null)
					{
						masks = new int[targets.length];
						starts.put(start, masks);
					}
					masks[t] |= 1 << move;
				}
			}
		}
		return starts;
	}

	/**
	 * The target the boat passes over sailing {@code move} from the tile at {@code from}, or
	 * {@link WorldPointUtil#UNDEFINED}.
	 */
	int arrivedByMove(int from, int move)
	{
		int[] masks = arrivingMoves == null ? null : arrivingMoves.get(from);
		if (masks == null) return WorldPointUtil.UNDEFINED;
		for (int t = 0; t < masks.length; t++)
		{
			if ((masks[t] & (1 << move)) != 0) return targets[t];
		}
		return WorldPointUtil.UNDEFINED;
	}

	/**
	 * Whether the boat arrives at {@code target} on {@code node}: by the move that reached it passing over the target,
	 * or on a source, with the target under its hull already ({@link #arrivedAt(BoatHull, int, int, int)}).
	 */
	boolean arrivedAt(NodeGraph graph, int node, int target)
	{
		int move = graph.moveIndex(node), previous = graph.previous(node);
		if (move < 0 || previous == NodeGraph.NO_NODE)
			return arrivedAt(hull, target, graph.packedPosition(node), arrivalHeading(graph, node));
		int[] masks = arrivingMoves.get(graph.packedPosition(previous));
		if (masks == null) return false;
		for (int t = 0; t < masks.length; t++)
		{
			if (targets[t] == target && (masks[t] & (1 << move)) != 0) return true;
		}
		return false;
	}

	/** The target the boat arrives at on {@code node} (see {@link #arrivedAt(NodeGraph, int, int)}), or UNDEFINED. */
	int arrivedTarget(NodeGraph graph, int node)
	{
		for (int target : targets)
		{
			if (arrivedAt(graph, node, target)) return target;
		}
		return WorldPointUtil.UNDEFINED;
	}

	/**
	 * Whether a boat with {@code hull} on {@code packedPosition} facing {@code heading} (-1 any) has arrived at
	 * {@code target}: its hull covers it.
	 */
	static boolean arrivedAt(BoatHull hull, int target, int packedPosition, int heading)
	{
		if (WorldPointUtil.unpackWorldPlane(target) != WorldPointUtil.unpackWorldPlane(packedPosition)) return false;
		int dx = WorldPointUtil.unpackWorldX(target) - WorldPointUtil.unpackWorldX(packedPosition);
		int dy = WorldPointUtil.unpackWorldY(target) - WorldPointUtil.unpackWorldY(packedPosition);
		for (int facing = heading < 0 ? 0 : heading; facing < (heading < 0 ? HEADINGS : heading + 1); facing++)
		{
			if (hull.covers(facing, dx, dy)) return true;
		}
		return false;
	}

	/** The estimate for {@code tile}: a lower bound on the ticks left, or {@link #NO_ROUTE}. */
	int estimate(int tile)
	{
		return estimate.applyAsInt(tile);
	}

	/**
	 * The cheapest cost tile (x, y, z) has been queued at so far, or {@link Integer#MAX_VALUE} if it hasn't been; 0 if
	 * it's off the map, so it never is.
	 */
	int queuedCost(int x, int y, int z)
	{
		int region = regionIndex(x, y, z);
		if (region < 0) return 0;
		int[] costs = queuedCosts[region];
		return costs == null ? Integer.MAX_VALUE : costs[tileIndex(x, y)];
	}

	/** The cost tile (x, y, z) was last settled at, or {@link Integer#MAX_VALUE} if it hasn't been; 0 off the map. */
	int settledCost(int x, int y, int z)
	{
		int region = regionIndex(x, y, z);
		if (region < 0) return 0;
		int[] costs = settledCosts[region];
		return costs == null ? Integer.MAX_VALUE : costs[tileIndex(x, y)];
	}

	/** Notes that tile (x, y, z) is settled at {@code cost}. */
	void setSettledCost(int x, int y, int z, int cost)
	{
		costsFor(settledCosts, regionIndex(x, y, z))[tileIndex(x, y)] = cost;
	}

	/**
	 * Notes that tile (x, y, z) is queued at {@code cost}; only lowers the noted cost, so it stays the cheapest the
	 * tile has been queued at (see {@link #queuedCost}).
	 */
	void setQueuedCost(int x, int y, int z, int cost)
	{
		int[] costs = costsFor(queuedCosts, regionIndex(x, y, z));
		int index = tileIndex(x, y);
		costs[index] = Math.min(costs[index], cost);
	}

	/**
	 * Lets a move reaching tile (x, y, z) at a cost that doesn't beat its cheapest queue anyway when the boat arrives
	 * on it, passing over a target — the tile's cheapest route may not. Marks the move queued, so each move is let
	 * through once.
	 *
	 * @return whether the move should be queued despite its cost
	 */
	boolean queueArrival(boolean arrives, int move, int x, int y, int z)
	{
		if (!arrives) return false;
		int region = regionIndex(x, y, z);
		if (region < 0) return false;
		int[] arrivals = queuedArrivals[region];
		if (arrivals == null)
		{
			arrivals = new int[REGION_SIZE * REGION_SIZE];
			queuedArrivals[region] = arrivals;
		}
		int index = tileIndex(x, y);
		int bit = 1 << move;
		if ((arrivals[index] & bit) != 0) return false;
		arrivals[index] |= bit;
		return true;
	}

	/** Lets go of the costs noted per tile, and the hull's caches, once the search is done. */
	void release()
	{
		Arrays.fill(queuedCosts, null);
		Arrays.fill(queuedArrivals, null);
		Arrays.fill(settledCosts, null);
		hull.release();
	}

	/**
	 * Adds the moves from {@code node} on tile (x, y, z) to {@code out}, each costing its ticks. Sailing moves are
	 * different lengths, so they are queued by cost. A tile is only queued again if a route reaches it for less, since
	 * a dearer one would only be dequeued once the tile is done — or if a dearer move would arrive on a heading none
	 * has tried, since the cheapest route's facing may not cover a target. The cost check comes first, as it costs less
	 * than the hull's. The whole hull must fit along the move.
	 */
	void addNeighbors(CollisionMap map, PrimitiveIntList out, int node, int x, int y, int z, NodeGraph graph)
	{
		int costSoFar = graph.cost(node);
		int from = WorldPointUtil.packWorldPoint(x, y, z);
		for (int i = 0; i < moves.size(); i++)
		{
			int dx = moves.dx(i), dy = moves.dy(i);
			int cost = moves.ticks(i);
			// Whether the hull passes over a target on this move: the search has then arrived, wherever it ends
			boolean arrives = arrivingMoves != null && arrivedByMove(from, i) != WorldPointUtil.UNDEFINED;
			// A tile queued for no more already has its route; settled tiles are among them
			if ((costSoFar + cost >= queuedCost(x + dx, y + dy, z) && !queueArrival(arrives, i, x + dx, y + dy, z))
				|| !hull.canMove(map, x, y, z, moves.heading(i), dx, dy))
			{
				continue;
			}
			int to = WorldPointUtil.packWorldPoint(x + dx, y + dy, z);
			// Nothing is left to sail once the boat has arrived, wherever the move ends
			int estimate = arrives ? 0 : estimate(to);
			if (estimate == NO_ROUTE) continue;
			out.add(graph.createWeightedTile(to, node, cost, estimate, BankVisitState.CARRIED, i));
		}
	}

	private static int[] costsFor(int[][] byRegion, int region)
	{
		if (byRegion[region] == null)
		{
			byRegion[region] = new int[REGION_SIZE * REGION_SIZE];
			Arrays.fill(byRegion[region], Integer.MAX_VALUE);
		}
		return byRegion[region];
	}

	// Index of the region and plane holding tile (x, y, z), or -1 if it's off the map
	private int regionIndex(int x, int y, int z)
	{
		int regionX = (x >> REGION_SHIFT) - extent.getMinX();
		int regionY = (y >> REGION_SHIFT) - extent.getMinY();
		if (regionX < 0 || regionY < 0 || regionX > extent.getWidth() || regionY > extent.getHeight() || z < 0 || z >= PLANES)
		{
			return -1;
		}
		return (regionX + regionY * (extent.getWidth() + 1)) * PLANES + z;
	}

	private static int tileIndex(int x, int y)
	{
		return ((y & REGION_MASK) << REGION_SHIFT) | (x & REGION_MASK);
	}
}
