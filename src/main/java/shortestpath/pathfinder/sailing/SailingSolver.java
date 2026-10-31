package shortestpath.pathfinder.sailing;

import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntUnaryOperator;
import shortestpath.PrimitiveIntHashMap;
import shortestpath.PrimitiveIntList;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.BankVisitState;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.IntMinHeap;
import shortestpath.pathfinder.NodeGraph;

/**
 * The sailing search as a plain solver: a search over the boat's 16 headings (see {@link SailingMoves}) that keeps the
 * whole hull clear along every move ({@link BoatHull}) and arrives once the hull covers a target ({@link SailingSearch}).
 * It sets out from the tiles it is given ({@link #castOff}) and can be advanced a cost band at a time, reporting each
 * end it settles — a target, or a mooring it can dock at — to a {@link Listener}, so a caller can interleave it with
 * another search and stop it whenever it likes.
 * <p>
 * Every state is a boat tile; the boat's heading on it is the move that reached it. A tile is settled once, except
 * that a costlier move arriving on a heading whose hull covers a target is still taken.
 * <p>
 * One solver is one search: it holds the search's node graph until {@link #release}.
 */
public final class SailingSolver
{
	/** {@link #frontier} once nothing is left to expand. */
	public static final int EXHAUSTED = Integer.MAX_VALUE;
	/** What a query's estimate answers for a boat tile from which nothing it looks for can be reached. */
	public static final int NO_ROUTE = SailingSearch.NO_ROUTE;

	/** What a search reports as it reaches its ends; each method returns whether to keep going. */
	public interface Listener
	{
		/** The boat arrived at {@code target} on {@code node}. */
		default boolean target(int target, int node)
		{
			return true;
		}

		/**
		 * The boat came within docking range of exit {@code exit} (its index in the query's exits) on {@code node}:
		 * once per exit for a search by cost alone, at every tile in range for a directed one.
		 */
		default boolean exit(int exit, int node)
		{
			return true;
		}
	}

	/** Why {@link #advance} returned. */
	public enum Stop
	{
		/** The frontier passed the limit. */
		LIMIT,
		/** Nothing is left to expand. */
		EXHAUSTED,
		/** The listener asked to stop. */
		LISTENER,
		CANCELLED
	}

	private final CollisionMap map;
	private final SailingQuery query;
	private final SailingSearch sailing;
	private final NodeGraph graph = new NodeGraph(1 << 14);
	private final IntMinHeap pending = new IntMinHeap(graph, 256);
	private final PrimitiveIntList neighbors = new PrimitiveIntList(16);
	// Exits by every boat tile within docking range of them
	private final PrimitiveIntHashMap<int[]> exitsByTile;
	private final boolean[] exitReported;
	private final boolean[] targetReported;
	// The square of how far from a target the boat's tile can be while arriving at it, with a tile to spare
	private final double arrivalReachSquared;
	private boolean released;

	public SailingSolver(CollisionMap map, SailingQuery query)
	{
		this.map = map;
		this.query = query;
		// A move can end past the target it passed over: as far as the hull reaches, and the move's length, beyond it
		double arrivalReach = query.hull().arrivalReach(query.moves()) + 1;
		this.arrivalReachSquared = arrivalReach * arrivalReach;
		this.sailing = new SailingSearch(query.moves(), query.hull(), query.targets(), query.initialHeading(),
			estimate(query));
		List<SailingMooring> exits = query.exits();
		exitReported = new boolean[exits.size()];
		targetReported = new boolean[query.targets().length];
		exitsByTile = new PrimitiveIntHashMap<>(Math.max(16, exits.size() * 128));
		for (int i = 0; i < exits.size(); i++)
		{
			for (int tile : exits.get(i).dockingTiles())
			{
				int[] here = exitsByTile.get(tile);
				int[] grown = here == null ? new int[1] : Arrays.copyOf(here, here.length + 1);
				grown[grown.length - 1] = i;
				exitsByTile.put(tile, grown);
			}
		}
	}

	/**
	 * The query's estimate; without one, for a search that heads for its targets, the straight line to the nearest
	 * at the boat's top speed, less how far from a target the hull covers it; else none.
	 */
	private static IntUnaryOperator estimate(SailingQuery query)
	{
		if (query.estimate() != null) return query.estimate();
		if (!query.directed() || query.targets().length == 0) return tile -> 0;
		int[] targets = query.targets();
		double reach = query.hull().reach() + Math.sqrt(2) / 2;
		double speed = query.moves().topSpeed();
		return tile ->
		{
			double tilesLeft = Double.MAX_VALUE;
			for (int target : targets) tilesLeft = Math.min(tilesLeft, Tiles.distance(tile, target) - reach);
			return (int) (Math.max(0, tilesLeft) / speed);
		};
	}

	/**
	 * Sets out from {@code tile} at {@code cost}, facing the query's initial heading: a boat state the search grows
	 * from. A source can be added at any time; one that reaches settled tiles for less settles them again. Returns its
	 * node, the root of every leg that grows from it, or {@link NodeGraph#NO_NODE} if nothing the search looks for can
	 * be reached from it.
	 */
	public int addSource(int tile, int cost)
	{
		if (released) return NodeGraph.NO_NODE;
		int estimate = sailing.estimate(tile);
		if (estimate == NO_ROUTE) return NodeGraph.NO_NODE;
		int node = graph.createWeightedTile(tile, NodeGraph.NO_NODE, cost, estimate, BankVisitState.CARRIED, -1);
		pending.add(node);
		int x = WorldPointUtil.unpackWorldX(tile), y = WorldPointUtil.unpackWorldY(tile), z = WorldPointUtil.unpackWorldPlane(tile);
		sailing.setQueuedCost(x, y, z, cost);
		return node;
	}

	/**
	 * Sets out from every tile of {@code tiles} at {@code cost}: where a boat boarded at a mooring casts off
	 * ({@link SailingWaters#castOff}).
	 */
	public void castOff(int[] tiles, int cost)
	{
		for (int tile : tiles) addSource(tile, cost);
	}

	/**
	 * The lowest cost (plus estimate, for a directed search) of any state not yet settled, or {@link #EXHAUSTED}. For
	 * a search with exits, no end it has not reported yet costs less.
	 */
	public int frontier()
	{
		if (released) return EXHAUSTED;
		int head = pending.peek();
		return head == NodeGraph.NO_NODE ? EXHAUSTED : graph.compareCost(head);
	}

	/**
	 * Settles states while {@link #frontier} is at most {@code limit} — at least one, if any is left — reporting each
	 * target and exit the first time the search reaches it.
	 */
	public Stop advance(int limit, BooleanSupplier cancelled, Listener listener)
	{
		boolean first = true;
		while (true)
		{
			int frontier = frontier();
			if (frontier == EXHAUSTED) return Stop.EXHAUSTED;
			if (!first && frontier > limit) return Stop.LIMIT;
			// Asked at every state, so a cutoff stops the search at once: a caller's check must be cheap, as the
			// router's is (SearchDeadline reads the clock only every so often)
			if (cancelled.getAsBoolean()) return Stop.CANCELLED;
			int node = pending.poll();
			// A tile is settled by its cheapest dequeue; dearer duplicates are dropped, unless the duplicate is a move
			// that arrives, its hull passing over a target, queued for it because the settled route didn't. A tile
			// reached for less after it settled is settled again: the estimate may be admissible without being
			// consistent
			int packed = graph.packedPosition(node), cost = graph.cost(node);
			int px = WorldPointUtil.unpackWorldX(packed), py = WorldPointUtil.unpackWorldY(packed);
			int pz = WorldPointUtil.unpackWorldPlane(packed);
			if ((cost > sailing.queuedCost(px, py, pz) || cost >= sailing.settledCost(px, py, pz))
				&& sailing.arrivedTarget(graph, node) == WorldPointUtil.UNDEFINED)
			{
				continue;
			}
			// The estimate may have risen since the node was queued (a bound that learns as the search goes): queue it
			// again at its new priority rather than settle it early
			int previous = graph.previous(node);
			if (query.estimate() != null && previous != NodeGraph.NO_NODE)
			{
				int estimate = query.estimate().applyAsInt(packed);
				if (estimate == NO_ROUTE) continue;
				if (estimate > graph.differentialCost(node))
				{
					pending.add(graph.createWeightedTile(packed, previous, cost - graph.cost(previous), estimate,
						BankVisitState.CARRIED, graph.moveIndex(node)));
					continue;
				}
			}
			if (cost < sailing.settledCost(px, py, pz)) sailing.setSettledCost(px, py, pz, cost);
			first = false;
			int tile = graph.packedPosition(node);
			if (!reportEnds(node, tile, listener)) return Stop.LISTENER;
			expand(node, tile);
		}
	}

	private boolean reportEnds(int node, int tile, Listener listener)
	{
		int[] targets = query.targets();
		if (targets.length != 0)
		{
			for (int i = 0; i < targets.length; i++)
			{
				if (targetReported[i] || !arrivedAt(node, targets[i], tile)) continue;
				targetReported[i] = true;
				if (!listener.target(targets[i], node)) return false;
			}
		}
		int[] exits = exitsByTile.get(tile);
		if (exits != null)
		{
			for (int exit : exits)
			{
				// A directed search can settle a dearer tile in docking range before a cheaper one, so it reports
				// every one and the caller keeps the cheapest; by cost alone, the first is the cheapest
				if (exitReported[exit] && !query.directed()) continue;
				exitReported[exit] = true;
				if (!listener.exit(exit, node)) return false;
			}
		}
		return true;
	}

	// Whether the boat has arrived at target on node, on tile, by the search's arrival rule
	private boolean arrivedAt(int node, int target, int tile)
	{
		// Beyond the hull's reach and a move's length nothing is passed over: skip the test
		int dx = WorldPointUtil.unpackWorldX(target) - WorldPointUtil.unpackWorldX(tile);
		int dy = WorldPointUtil.unpackWorldY(target) - WorldPointUtil.unpackWorldY(tile);
		if ((double) dx * dx + (double) dy * dy > arrivalReachSquared
			|| WorldPointUtil.unpackWorldPlane(target) != WorldPointUtil.unpackWorldPlane(tile))
		{
			return false;
		}
		return sailing.arrivedAt(graph, node, target);
	}

	private void expand(int node, int nodePacked)
	{
		final int x = WorldPointUtil.unpackWorldX(nodePacked);
		final int y = WorldPointUtil.unpackWorldY(nodePacked);
		final int z = WorldPointUtil.unpackWorldPlane(nodePacked);
		neighbors.clear();
		sailing.addNeighbors(map, neighbors, node, x, y, z, graph);
		SailingQuery.EdgeGate gate = query.gate();
		for (int i = 0; i < neighbors.size(); i++)
		{
			int neighbor = neighbors.get(i);
			int neighborPacked = graph.packedPosition(neighbor);
			if (query.landing() != null && !query.landing().test(neighborPacked))
			{
				continue;
			}
			// A move crosses its whole span at once, so its midpoint is checked too, so a leg can't clip a gated area
			int midPacked = WorldPointUtil.packWorldPoint(
				(x + WorldPointUtil.unpackWorldX(neighborPacked)) / 2,
				(y + WorldPointUtil.unpackWorldY(neighborPacked)) / 2, z);
			if (!gate.allowed(nodePacked, neighborPacked) || !gate.allowed(nodePacked, midPacked)
				|| !gate.allowed(midPacked, neighborPacked))
			{
				continue;
			}
			pending.add(neighbor);
			// Only now, past the gates, is the tile noted as queued at this cost, so a move they turn down doesn't
			// stop another route from queuing the tile
			sailing.setQueuedCost(WorldPointUtil.unpackWorldX(neighborPacked), WorldPointUtil.unpackWorldY(neighborPacked),
				z, graph.cost(neighbor));
		}
	}

	/** The leg from the source it grows from to {@code node}, ending for {@code reached}; costs from its start. */
	public SailingLeg leg(int node, int reached)
	{
		int count = 0;
		for (int at = node; at != NodeGraph.NO_NODE; at = graph.previous(at)) count++;
		int[] points = new int[count], headings = new int[count], costs = new int[count];
		int i = count;
		for (int at = node; at != NodeGraph.NO_NODE; at = graph.previous(at))
		{
			i--;
			points[i] = graph.packedPosition(at);
			int move = graph.moveIndex(at);
			headings[i] = move < 0 ? -1 : query.moves().heading(move);
			costs[i] = graph.cost(at);
		}
		// Costs from the leg's own start, which a source can set out from at a cost
		int base = costs[0];
		for (int j = 0; j < count; j++) costs[j] -= base;
		return new SailingLeg(points, headings, costs, reached);
	}

	/** The boat's tile on {@code node}. */
	public int tile(int node)
	{
		return graph.packedPosition(node);
	}

	/** The cost of reaching {@code node}. */
	public int cost(int node)
	{
		return graph.cost(node);
	}

	/**
	 * Whether a boat with {@code hull} on {@code tile} facing {@code heading} (-1 any) has arrived at {@code target}:
	 * its hull covers it.
	 */
	public static boolean arrivedAt(BoatHull hull, int target, int tile, int heading)
	{
		return SailingSearch.arrivedAt(hull, target, tile, heading);
	}

	/** Lets go of the node graph and per-tile costs; the solver reports nothing after. */
	public void release()
	{
		if (released) return;
		released = true;
		pending.clear();
		graph.release();
		sailing.release();
	}
}
