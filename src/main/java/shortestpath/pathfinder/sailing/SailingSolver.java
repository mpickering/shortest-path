package shortestpath.pathfinder.sailing;

import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
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
 * It can be advanced a cost band at a time, reporting each end it settles — a target, a goal tile, or a mooring it can
 * dock at — to a {@link Listener}, so a caller can interleave it with another search and stop it whenever it likes.
 * <p>
 * Every state is a boat tile; the boat's heading on it is the move that reached it. As in the standalone search, a
 * tile is settled once, except that a costlier move arriving on a heading whose hull covers a target is still taken.
 * <p>
 * One solver is one search: it holds the search's node graph until {@link #release}.
 */
public final class SailingSolver
{
	/** {@link #frontier} once nothing is left to expand. */
	public static final int EXHAUSTED = Integer.MAX_VALUE;
	/** What a query's estimate answers for a boat tile from which nothing it looks for can be reached. */
	public static final int NO_ROUTE = SailingSearch.NO_ROUTE;

	/** What a search reports as it settles states; each method returns whether to keep going. */
	public interface Listener
	{
		/** A boat tile was settled at {@code cost}. */
		default boolean settled(int node, int tile, int cost)
		{
			return true;
		}

		/** The boat arrived at {@code target}, or sailed onto goal tile {@code target}, on {@code node}. */
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

	// Cancellation is checked every this many settled states, so its clock reads stay off the hot path
	private static final int CANCEL_CHECK_INTERVAL = 256;

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
	private final int[] goals;
	private int startNode = NodeGraph.NO_NODE;
	private int startEstimate;
	// The square of how far from a target the boat's tile can be while arriving at it, with a tile to spare
	private final double arrivalReachSquared;
	private boolean startSettled;
	private int statesSettled;
	private int statesQueued;
	private int polls;
	private boolean released;

	public SailingSolver(CollisionMap map, SailingQuery query)
	{
		this.map = map;
		this.query = query;
		this.goals = query.goals();
		double arrivalReach = (query.hull() == null ? Math.sqrt(2) : query.hull().reach() + Math.sqrt(2) / 2) + 1;
		this.arrivalReachSquared = arrivalReach * arrivalReach;
		Arrays.sort(goals);
		this.sailing = new SailingSearch(query.moves(), query.hull(), query.targets(), query.goals(),
			query.initialHeading(), query.objective(), query.directed(), query.estimate());
		List<SailingMooring> exits = query.exits();
		exitReported = new boolean[exits.size()];
		targetReported = new boolean[query.targets().length];
		exitsByTile = new PrimitiveIntHashMap<>(Math.max(16, exits.size() * 128));
		int range = (int) Math.ceil(SailingMooring.DOCKING_RANGE);
		for (int i = 0; i < exits.size(); i++)
		{
			int berth = exits.get(i).berthTile();
			int bx = WorldPointUtil.unpackWorldX(berth), by = WorldPointUtil.unpackWorldY(berth);
			int plane = WorldPointUtil.unpackWorldPlane(berth);
			for (int dy = -range; dy <= range; dy++)
			{
				for (int dx = -range; dx <= range; dx++)
				{
					int tile = WorldPointUtil.packWorldPoint(bx + dx, by + dy, plane);
					if (!exits.get(i).inDockingRange(tile)) continue;
					int[] here = exitsByTile.get(tile);
					int[] grown = here == null ? new int[1] : Arrays.copyOf(here, here.length + 1);
					grown[grown.length - 1] = i;
					exitsByTile.put(tile, grown);
				}
			}
		}
		if (query.start() == WorldPointUtil.UNDEFINED)
		{
			// A search fed by addSource alone
			startSettled = true;
			return;
		}
		startNode = graph.createStart(query.start());
		startEstimate = query.estimate() == null ? 0 : query.estimate().applyAsInt(query.start());
		if (startEstimate == NO_ROUTE)
		{
			// Nothing it looks for can be reached from the start: the search is over before it begins
			startSettled = true;
		}
	}

	/**
	 * Sets out from {@code tile} at {@code cost} as well, facing the query's initial heading: another boat state the
	 * search grows from, such as a berth boarded later. Its first move gets the berthed-tile escape. A source can be
	 * added at any time; one that reaches settled tiles for less settles them again. Returns its node, the root of
	 * every leg that grows from it, or {@link NodeGraph#NO_NODE} if nothing the search looks for can be reached from it.
	 */
	public int addSource(int tile, int cost)
	{
		if (released) return NodeGraph.NO_NODE;
		int estimate = query.estimate() == null ? 0 : query.estimate().applyAsInt(tile);
		if (estimate == NO_ROUTE) return NodeGraph.NO_NODE;
		int node = graph.createWeightedTile(tile, NodeGraph.NO_NODE, cost, estimate, BankVisitState.CARRIED, -1);
		pending.add(node);
		int x = WorldPointUtil.unpackWorldX(tile), y = WorldPointUtil.unpackWorldY(tile), z = WorldPointUtil.unpackWorldPlane(tile);
		sailing.setQueuedCost(x, y, z, BankVisitState.CARRIED, cost);
		return node;
	}

	/** The source (or the start) the leg to {@code node} grows from. */
	public int root(int node)
	{
		int at = node;
		while (graph.previous(at) != NodeGraph.NO_NODE) at = graph.previous(at);
		return at;
	}

	public SailingQuery query()
	{
		return query;
	}

	/**
	 * The lowest cost (plus estimate, for a directed search) of any state not yet settled, or {@link #EXHAUSTED}. For
	 * a search with exits, no end it has not reported yet costs less.
	 */
	public int frontier()
	{
		if (released) return EXHAUSTED;
		if (!startSettled) return startEstimate;
		int head = pending.peek();
		return head == NodeGraph.NO_NODE ? EXHAUSTED : graph.compareCost(head);
	}

	/** States settled so far. */
	public int statesSettled()
	{
		return statesSettled;
	}

	/** Moves queued so far: every move past the checks, whether or not its tile is settled by it. */
	public int statesQueued()
	{
		return statesQueued;
	}

	/**
	 * Settles states while {@link #frontier} is at most {@code limit} — at least one, if any is left — reporting each
	 * target, goal tile and exit the first time the search reaches it.
	 */
	public Stop advance(int limit, BooleanSupplier cancelled, Listener listener)
	{
		boolean first = true;
		while (true)
		{
			int frontier = frontier();
			if (frontier == EXHAUSTED) return Stop.EXHAUSTED;
			if (!first && frontier > limit) return Stop.LIMIT;
			if (polls++ % CANCEL_CHECK_INTERVAL == 0 && cancelled.getAsBoolean()) return Stop.CANCELLED;
			int node;
			if (!startSettled)
			{
				startSettled = true;
				node = startNode;
			}
			else
			{
				node = pending.poll();
				// A tile is settled by its cheapest dequeue; dearer duplicates are dropped, unless the duplicate is a
				// move that arrives on its own heading, queued for it because the settled heading didn't cover a
				// target. A tile reached for less after it settled is settled again: the estimate may be admissible
				// without being consistent
				int packed = graph.packedPosition(node), cost = graph.cost(node);
				int px = WorldPointUtil.unpackWorldX(packed), py = WorldPointUtil.unpackWorldY(packed);
				int pz = WorldPointUtil.unpackWorldPlane(packed);
				if ((cost > sailing.queuedCost(px, py, pz, BankVisitState.CARRIED) || cost >= sailing.settledCost(px, py, pz))
					&& sailing.arrivedTarget(packed, sailing.arrivalHeading(graph, node)) == WorldPointUtil.UNDEFINED)
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
			}
			first = false;
			statesSettled++;
			int tile = graph.packedPosition(node);
			if (!reportEnds(node, tile, listener)) return Stop.LISTENER;
			if (!listener.settled(node, tile, graph.cost(node))) return Stop.LISTENER;
			expand(node, tile);
		}
	}

	private boolean reportEnds(int node, int tile, Listener listener)
	{
		if (goals.length != 0 && Arrays.binarySearch(goals, tile) >= 0 && !listener.target(tile, node)) return false;
		int[] targets = query.targets();
		if (targets.length != 0)
		{
			int heading = sailing.arrivalHeading(graph, node);
			for (int i = 0; i < targets.length; i++)
			{
				if (targetReported[i] || !arrivedAt(targets[i], tile, heading)) continue;
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

	// Whether the boat on tile facing heading has arrived at target, by the search's arrival rule
	private boolean arrivedAt(int target, int tile, int heading)
	{
		// Beyond the hull's reach nothing is covered: skip the hull test
		int dx = WorldPointUtil.unpackWorldX(target) - WorldPointUtil.unpackWorldX(tile);
		int dy = WorldPointUtil.unpackWorldY(target) - WorldPointUtil.unpackWorldY(tile);
		if ((double) dx * dx + (double) dy * dy > arrivalReachSquared
			|| WorldPointUtil.unpackWorldPlane(target) != WorldPointUtil.unpackWorldPlane(tile))
		{
			return false;
		}
		return SailingSearch.arrivedAt(sailing.hull, target, tile, heading);
	}

	private void expand(int node, int nodePacked)
	{
		final int x = WorldPointUtil.unpackWorldX(nodePacked);
		final int y = WorldPointUtil.unpackWorldY(nodePacked);
		final int z = WorldPointUtil.unpackWorldPlane(nodePacked);
		neighbors.clear();
		sailing.addNeighbors(map, neighbors, node, x, y, z, BankVisitState.CARRIED, graph);
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
			statesQueued++;
			// Only now, past the gates, is the tile noted as queued at this cost, so a move they turn down doesn't
			// stop another route from queuing the tile
			sailing.setQueuedCost(WorldPointUtil.unpackWorldX(neighborPacked), WorldPointUtil.unpackWorldY(neighborPacked),
				z, BankVisitState.CARRIED, graph.cost(neighbor));
		}
	}

	/** The leg from the start, or the source it grows from, to {@code node}, ending for {@code reached}; costs from its start. */
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

	/** The heading the boat faces on {@code node}, -1 if unknown; see {@link SailingSearch#arrivalHeading}. */
	public int heading(int node)
	{
		return sailing.arrivalHeading(graph, node);
	}

	/** The target the boat on {@code tile}, facing {@code heading}, has arrived at; see {@link SailingSearch#arrivedTarget}. */
	public int arrivedTarget(int tile, int heading)
	{
		return sailing.arrivedTarget(tile, heading);
	}

	/** The node graph, for callers that walk a path themselves while the search runs. */
	public NodeGraph graph()
	{
		return graph;
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
