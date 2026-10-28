package shortestpath.pathfinder.sailing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntUnaryOperator;
import shortestpath.PrimitiveIntHashMap;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.astar.SeaEnds;

/**
 * A route's ends at sea for one query, from the account's boats and moorings: from the boat the player is aboard to
 * each mooring it can dock at and straight to the targets at sea, and from each mooring with a boat of the player's
 * to the targets at sea. Each link's first bound is the straight line at the boat's top speed, plus boarding or
 * stepping off.
 * <p>
 * Legs to the targets are sailed towards them over a {@link SeaField} from the targets, which sees round the land the
 * straight line crosses: it grows only as far as the links the router asks about, tightening their bounds
 * ({@link #refine}) and guiding each leg's search, and it stays still while a leg is sailed, so the search's estimate
 * is consistent and the leg exact. Legs from the start to the moorings share one search by cost from the boat, which
 * goes only as far as the moorings the router reaches.
 * <p>
 * A link the boat can't sail is never offered, so no search runs to find that out: the moorings whose boats reach
 * each tile ({@link SailingReach}) tell a channel its hull can't get through, and the pieces of sea
 * ({@link SailingWaters#connects}) a restricted area its parts don't let it enter.
 */
public final class SailingSeaEnds implements SeaEnds
{
	private static final int START = 0, TARGET = 1, DIRECT = 2;
	// How much further the field grows than the bound it is asked about, in ticks: a step's worth, so a link's bound
	// rises in a few steps rather than many
	private static final int GROWTH_TICKS = 32;
	// How far past a link's bound its leg's search goes each time the router asks, in ticks
	private static final int STEP_TICKS = 24;

	private final CollisionMap map;
	private final SailingWaters waters;
	private final int start;
	private final int[] seaTargets;
	private final SailingQuery.EdgeGate gate;
	// Per link: its kind, its mooring (none for a direct link), the boat and its hull
	private final List<Integer> kinds = new ArrayList<>();
	private final List<SailingMooring> moorings = new ArrayList<>();
	private final List<SailingVessel> vessels = new ArrayList<>();
	private final List<BoatHull> hulls = new ArrayList<>();
	private final List<Integer> headings = new ArrayList<>();
	private final List<Integer> bounds = new ArrayList<>();
	private int[] exact = new int[0];
	private int[][][] legs = new int[0][][];
	private int[] reached = new int[0];
	// Per target or direct link: its leg's search, kept between steps until it arrives or runs dry
	private SailingSolver[] solvers = new SailingSolver[0];
	private final PrimitiveIntHashMap<int[]> targetLinksByLand = new PrimitiveIntHashMap<>(64);
	private int[] startLinks = EMPTY;
	private int directLink = -1;
	private int legsSailed;
	private long sailingNanos;
	// The fields from the targets, by the step costs of the moves they bound, and the farthest a hull reaches
	private final java.util.Map<String, SeaField> fields = new java.util.HashMap<>();
	// Per hull's sea: the coarse cell distances from where it arrives at the targets
	private final java.util.Map<HullSea, short[][]> arrivalCells = new java.util.IdentityHashMap<>();
	private double longestReach;
	// The search by cost from the boat the player is aboard, shared by the start links, made on first ask
	private SailingSolver startSolver;
	private int[] startLinkByExit;

	/**
	 * @param seaTargets the requested targets at sea
	 * @param gate       the walking search's positional gates, applied to every sailing move too
	 */
	public SailingSeaEnds(CollisionMap map, PreparedSailingCapability capability, int start, int[] seaTargets,
		SailingQuery.EdgeGate gate)
	{
		this.map = map;
		this.waters = SailingWaters.of(map, SailingMoorings.all());
		this.seaTargets = seaTargets.clone();
		this.gate = gate;
		SailingFleet.Aboard aboard = capability.fleet().aboard();
		this.start = aboard != null && waters.contains(start) ? start : WorldPointUtil.UNDEFINED;
		List<SailingMooring> landable = new ArrayList<>();
		int[] walkable = SailingMoorings.walkableLandTiles(map);
		Arrays.sort(walkable);
		for (SailingMooring mooring : capability.moorings())
		{
			if (Arrays.binarySearch(walkable, mooring.landTile()) >= 0) landable.add(mooring);
		}
		if (this.start != WorldPointUtil.UNDEFINED)
		{
			SailingVessel vessel = aboard.vessel();
			BoatHull hull = vessel.hull(aboard.pivotX(), aboard.pivotY());
			int body = waters.body(this.start);
			int[] pieces = {waters.piece(this.start)};
			List<Integer> links = new ArrayList<>();
			for (SailingMooring mooring : landable)
			{
				if (!capability.canDock(mooring, vessel) || !opensOnto(mooring, body)) continue;
				if (!reaches(vessel, hull, pieces, mooring)) continue;
				int bound = SailingMooring.DISEMBARK_TICKS
					+ ticks(vessel, distance(this.start, mooring.berthTile()) - SailingMooring.DOCKING_RANGE);
				links.add(add(START, mooring, vessel, hull, aboard.heading(), bound));
			}
			startLinks = links.stream().mapToInt(Integer::intValue).toArray();
			int bound = targetBound(vessel, hull, this.start, body);
			if (bound != NO_LINK && reachesATarget(vessel, hull, this.start, null, pieces))
				directLink = add(DIRECT, null, vessel, hull, aboard.heading(), bound);
		}
		if (this.seaTargets.length > 0)
		{
			for (SailingMooring mooring : landable)
			{
				for (SailingVessel vessel : capability.fleet().vessels())
				{
					if (!capability.canBoard(mooring, vessel)) continue;
					BoatHull hull = vessel.hull(0, 0);
					int sailed = Integer.MAX_VALUE;
					for (int body : bodies(mooring)) sailed = Math.min(sailed, targetBound(vessel, hull, mooring.berthTile(), body));
					if (sailed == Integer.MAX_VALUE || sailed == NO_LINK) continue;
					// Beyond a channel the hull can't get through or water it can't enter: no search would arrive
					if (!reachesATarget(vessel, hull, WorldPointUtil.UNDEFINED, mooring, waters.berthPieces(mooring))) continue;
					// Round the land between, cell by cell over the hull's own water
					int cells = cellTicks(vessel, hull, mooring.berthTile());
					if (cells == NO_LINK) continue;
					sailed = Math.max(sailed, cells);
					int link = add(TARGET, mooring, vessel, hull, -1, SailingMooring.BOARD_TICKS + sailed);
					int[] from = targetLinksByLand.get(mooring.landTile());
					int[] grown = from == null ? new int[1] : Arrays.copyOf(from, from.length + 1);
					grown[grown.length - 1] = link;
					targetLinksByLand.put(mooring.landTile(), grown);
				}
			}
		}
		exact = new int[kinds.size()];
		Arrays.fill(exact, -1);
		legs = new int[kinds.size()][][];
		reached = new int[kinds.size()];
		Arrays.fill(reached, WorldPointUtil.UNDEFINED);
		solvers = new SailingSolver[kinds.size()];
	}

	/** Whether the query has anything at sea: a start aboard, or targets at sea some boat can reach. */
	public boolean active()
	{
		return start != WorldPointUtil.UNDEFINED || targetLinksByLand.size() > 0;
	}

	/** The land tiles target links board from, with the least bound of their links: the target overlay's seeds. */
	public int[][] boardingSeeds()
	{
		List<int[]> seeds = new ArrayList<>();
		for (int link = 0; link < kinds.size(); link++)
		{
			if (kinds.get(link) != TARGET) continue;
			int land = moorings.get(link).landTile();
			boolean merged = false;
			for (int[] seed : seeds)
			{
				if (seed[0] == land)
				{
					seed[1] = Math.min(seed[1], bounds.get(link));
					merged = true;
				}
			}
			if (!merged) seeds.add(new int[] {land, bounds.get(link)});
		}
		int[][] result = new int[2][seeds.size()];
		for (int i = 0; i < seeds.size(); i++)
		{
			result[0][i] = seeds.get(i)[0];
			result[1][i] = seeds.get(i)[1];
		}
		return result;
	}

	/** Time spent sailing legs, in nanoseconds. */
	public long sailingNanos()
	{
		return sailingNanos;
	}

	@Override
	public boolean startsAtSea()
	{
		return start != WorldPointUtil.UNDEFINED;
	}

	@Override
	public int[] startLinks()
	{
		return startLinks;
	}

	@Override
	public int directLink()
	{
		return directLink;
	}

	@Override
	public int[] targetLinksFrom(int landTile)
	{
		int[] links = targetLinksByLand.get(landTile);
		return links == null ? EMPTY : links;
	}

	@Override
	public int landTile(int link)
	{
		return moorings.get(link).landTile();
	}

	@Override
	public int bound(int link)
	{
		return bounds.get(link);
	}

	@Override
	public int refine(int link, int bound, BooleanSupplier cancelled)
	{
		if (exact[link] >= 0) return exact[link];
		long started = System.nanoTime();
		try
		{
			int kind = kinds.get(link);
			if (kind == START)
			{
				// Sail on from the boat until docking there, or until the search has passed this bound
				advanceStart(bound - SailingMooring.DISEMBARK_TICKS + STEP_TICKS, cancelled);
				if (exact[link] >= 0) return exact[link];
				int frontier = startSolver.frontier();
				return frontier == SailingSolver.EXHAUSTED ? NO_LINK
					: Math.max(bound, SailingMooring.DISEMBARK_TICKS + frontier);
			}
			int offset = kind == TARGET ? SailingMooring.BOARD_TICKS : 0;
			if (solvers[link] == null)
			{
				// First the field: cheap, and it sees round the land the straight line crosses
				SeaField field = field(vessels.get(link).moves());
				field.growTo(bound - offset + GROWTH_TICKS);
				int from = kind == TARGET ? moorings.get(link).berthTile() : start;
				int ticks = fieldTicks(field, from, longestMove(vessels.get(link).moves()));
				if (ticks == Integer.MAX_VALUE) return exact[link] = NO_LINK;
				if (offset + ticks > bound) return offset + ticks;
				solvers[link] = targetSolver(link, field);
			}
			// Then the leg's own search, a step at a time: its frontier bounds the leg, so a leg that never arrives
			// costs only as much search as the routes cheaper than it leave room for
			SailingSolver solver = solvers[link];
			SailingLeg[] leg = new SailingLeg[1];
			solver.advance(bound - offset + STEP_TICKS, cancelled, new SailingSolver.Listener()
			{
				@Override
				public boolean target(int target, int node)
				{
					leg[0] = solver.leg(node, target);
					return false;
				}
			});
			if (leg[0] != null)
			{
				record(link, leg[0]);
				releaseSolver(link);
				return exact[link];
			}
			if (cancelled.getAsBoolean()) return bound;
			int frontier = solver.frontier();
			if (frontier == SailingSolver.EXHAUSTED)
			{
				releaseSolver(link);
				return exact[link] = NO_LINK;
			}
			return Math.max(bound, offset + frontier);
		}
		finally
		{
			sailingNanos += System.nanoTime() - started;
		}
	}

	@Override
	public int exact(int link, BooleanSupplier cancelled)
	{
		// The search reaches here once refine has stopped raising the bound, which it does only once the leg is found
		int bound = bounds.get(link);
		while (exact[link] < 0 && !cancelled.getAsBoolean())
		{
			int raised = refine(link, bound, cancelled);
			if (raised == NO_LINK) return NO_LINK;
			bound = Math.max(bound + STEP_TICKS, raised);
		}
		return exact[link] < 0 ? NO_LINK : exact[link];
	}

	@Override
	public int[][] leg(int link)
	{
		if (legs[link] == null) throw new IllegalStateException("link " + link + " not sailed");
		return legs[link];
	}

	@Override
	public int seaTarget(int link)
	{
		return reached[link];
	}

	@Override
	public int legsSailed()
	{
		return legsSailed;
	}

	private int add(int kind, SailingMooring mooring, SailingVessel vessel, BoatHull hull, int heading, int bound)
	{
		if (kind != START) longestReach = Math.max(longestReach, arrivalReach(hull));
		kinds.add(kind);
		moorings.add(mooring);
		vessels.add(vessel);
		hulls.add(hull);
		headings.add(heading);
		bounds.add(bound);
		return kinds.size() - 1;
	}

	// The search by cost from the boat, on until its frontier passes limit, timing every mooring it docks at
	private void advanceStart(int limit, BooleanSupplier cancelled)
	{
		if (startSolver == null)
		{
			List<SailingMooring> exits = new ArrayList<>();
			startLinkByExit = new int[startLinks.length];
			for (int i = 0; i < startLinks.length; i++)
			{
				exits.add(moorings.get(startLinks[i]));
				startLinkByExit[i] = startLinks[i];
			}
			int first = startLinks[0];
			SailingVessel vessel = vessels.get(first);
			BoatHull hull = hulls.get(first);
			SailingQuery query = new SailingQuery(start, headings.get(first), vessel.moves(), hull,
				SailingObjective.TICKS, new int[0], new int[0], exits, gate).landingOn(waters.landing(map, vessel, hull));
			startSolver = new SailingSolver(map, query);
		}
		startSolver.advance(limit, cancelled, new SailingSolver.Listener()
		{
			@Override
			public boolean exit(int exit, int node)
			{
				int docked = startLinkByExit[exit];
				if (exact[docked] < 0) record(docked, startSolver.leg(node, moorings.get(docked).berthTile()));
				return true;
			}
		});
	}

	// Keeps a link's leg and its cost
	private void record(int link, SailingLeg leg)
	{
		legsSailed++;
		int kind = kinds.get(link);
		int offset = kind == TARGET ? SailingMooring.BOARD_TICKS : 0;
		int[] tiles = new int[leg.size()], costs = new int[leg.size()];
		for (int i = 0; i < leg.size(); i++)
		{
			tiles[i] = leg.point(i);
			costs[i] = offset + leg.costAt(i);
		}
		legs[link] = new int[][] {tiles, costs};
		reached[link] = leg.reached();
		exact[link] = kind == START ? leg.cost() + SailingMooring.DISEMBARK_TICKS : offset + leg.cost();
	}

	/**
	 * The search for a target or direct link's leg, towards the targets over their field. It estimates each tile at
	 * the field's ticks, but at most what the field had reached when the search began: the field grows on for other
	 * links, and the cap keeps this search's estimate the same throughout, so consistent.
	 */
	private SailingSolver targetSolver(int link, SeaField field)
	{
		int kind = kinds.get(link);
		SailingVessel vessel = vessels.get(link);
		BoatHull hull = hulls.get(link);
		int from = kind == TARGET ? moorings.get(link).berthTile() : start;
		int cap = field.reachedTicks();
		IntUnaryOperator estimate = tile ->
		{
			int ticks = field.ticks(tile);
			return ticks == Integer.MAX_VALUE ? SailingSolver.NO_ROUTE : Math.min(ticks, cap);
		};
		SailingQuery query = new SailingQuery(from, headings.get(link), vessel.moves(), hull, SailingObjective.TICKS,
			seaTargets, new int[0], List.of(), gate, estimate).landingOn(waters.landing(map, vessel, hull));
		return new SailingSolver(map, query);
	}

	private void releaseSolver(int link)
	{
		if (solvers[link] != null) solvers[link].release();
		solvers[link] = null;
	}

	// The field from where a boat arrives at the targets, for boats with moves, made on first ask
	private SeaField field(SailingMoves moves)
	{
		int[] costs = SeaField.stepCosts(moves);
		return fields.computeIfAbsent(costs[0] + ":" + costs[1], key ->
		{
			int radius = (int) Math.ceil(longestReach);
			List<Integer> zone = new ArrayList<>();
			for (int target : seaTargets)
			{
				int x = WorldPointUtil.unpackWorldX(target), y = WorldPointUtil.unpackWorldY(target);
				for (int dy = -radius; dy <= radius; dy++)
				{
					for (int dx = -radius; dx <= radius; dx++)
					{
						if (dx * dx + dy * dy <= longestReach * longestReach)
							zone.add(WorldPointUtil.packWorldPoint(x + dx, y + dy, WorldPointUtil.unpackWorldPlane(target)));
					}
				}
			}
			return new SeaField(waters, zone.stream().mapToInt(Integer::intValue).toArray(), costs[0], costs[1]);
		});
	}

	// The field's ticks from tile; from a tile off the sea (a berth), the least over where a first move can land
	private int fieldTicks(SeaField field, int tile, int firstMoveTiles)
	{
		if (waters.contains(tile)) return field.ticks(tile);
		int best = Integer.MAX_VALUE;
		int x = WorldPointUtil.unpackWorldX(tile), y = WorldPointUtil.unpackWorldY(tile);
		int z = WorldPointUtil.unpackWorldPlane(tile);
		for (int dy = -firstMoveTiles; dy <= firstMoveTiles; dy++)
		{
			for (int dx = -firstMoveTiles; dx <= firstMoveTiles; dx++)
			{
				int next = WorldPointUtil.packWorldPoint(x + dx, y + dy, z);
				if (waters.contains(next)) best = Math.min(best, field.ticks(next));
			}
		}
		return best;
	}

	// The most tiles any move goes along an axis
	private static int longestMove(SailingMoves moves)
	{
		int longest = 0;
		for (int move = 0; move < moves.size(); move++)
			longest = Math.max(longest, Math.max(Math.abs(moves.dx(move)), Math.abs(moves.dy(move))));
		return longest;
	}

	/**
	 * A bound on the ticks from {@code from} (a berth: its first move lands within a cell or two) to arriving at a
	 * target, from the hull's coarse water grids (see {@link SeaGrid}), or {@link #NO_LINK} if they join no target.
	 */
	private int cellTicks(SailingVessel vessel, BoatHull hull, int from)
	{
		HullSea sea = waters.forHull(map, hull);
		short[][] distances = arrivalCells.computeIfAbsent(sea, key ->
		{
			short[][] least = null;
			for (int target : seaTargets)
			{
				short[][] cells = key.arrivalDistances(target, arrivalReach(hull));
				if (least == null) least = cells;
				else
				{
					for (int frame = 0; frame < 2; frame++)
						for (int i = 0; i < least[frame].length; i++)
							least[frame][i] = (short) Math.min(least[frame][i], cells[frame][i]);
				}
			}
			return least;
		});
		SailingMoves moves = vessel.moves();
		double chebyshevSpeed = 0, manhattanSpeed = 0;
		int longestChebyshev = 0, longestManhattan = 0;
		for (int move = 0; move < moves.size(); move++)
		{
			int dx = Math.abs(moves.dx(move)), dy = Math.abs(moves.dy(move)), ticks = moves.ticks(move);
			chebyshevSpeed = Math.max(chebyshevSpeed, (double) Math.max(dx, dy) / ticks);
			manhattanSpeed = Math.max(manhattanSpeed, (double) (dx + dy) / ticks);
			longestChebyshev = Math.max(longestChebyshev, Math.max(dx, dy));
			longestManhattan = Math.max(longestManhattan, dx + dy);
		}
		SeaGrid axis = sea.axisGrid(), rotated = sea.rotatedGrid();
		int axisCells = axis.cell(from) >= 0 ? distances[0][axis.cell(from)]
			: axis.nearest(distances[0], from, longestChebyshev / SeaGrid.CELL + 1);
		int rotatedCells = rotated.cell(from) >= 0 ? distances[1][rotated.cell(from)]
			: rotated.nearest(distances[1], from, longestManhattan / SeaGrid.CELL + 1);
		if (axisCells == SeaGrid.UNREACHED || rotatedCells == SeaGrid.UNREACHED) return NO_LINK;
		double ticks = Math.max(SeaGrid.CELL * (axisCells - 1) / chebyshevSpeed,
			SeaGrid.CELL * (rotatedCells - 1) / manhattanSpeed);
		return (int) Math.max(0, Math.floor(ticks));
	}

	/**
	 * Whether a boat setting out from {@code from}, or from {@code mooring}'s berth, from {@code pieces} can arrive at
	 * some target: one its kind's boats can sail to from there, by pieces of sea its parts let it enter. Never false
	 * for a boat that can.
	 */
	private boolean reachesATarget(SailingVessel vessel, BoatHull hull, int from, SailingMooring mooring, int[] pieces)
	{
		if (vessel.kind() != null && hull != null)
		{
			SailingReach reach = SailingReach.load();
			boolean may = mooring == null ? reach.mayReach(vessel.kind(), from, seaTargets, arrivalReach(hull))
				: reach.mayReachFrom(vessel.kind(), mooring, seaTargets, arrivalReach(hull));
			if (!may) return false;
		}
		java.util.Set<Integer> near = new java.util.TreeSet<>();
		for (int target : seaTargets) for (int piece : waters.piecesNear(target, arrivalReach(hull))) near.add(piece);
		return !near.isEmpty() && waters.connects(vessel, pieces, near.stream().mapToInt(Integer::intValue).toArray());
	}

	/** Whether a boat setting out from the start, from {@code pieces}, can dock at {@code mooring}; see above. */
	private boolean reaches(SailingVessel vessel, BoatHull hull, int[] pieces, SailingMooring mooring)
	{
		if (vessel.kind() != null && hull != null && !SailingReach.load().mayReach(vessel.kind(), start,
			new int[] {mooring.berthTile()}, SailingMooring.DOCKING_RANGE)) return false;
		return waters.connects(vessel, pieces, waters.berthPieces(mooring));
	}

	// The straight line to the nearest target on body, at top speed, or NO_LINK with none there
	private int targetBound(SailingVessel vessel, BoatHull hull, int from, int body)
	{
		double least = Double.MAX_VALUE;
		for (int target : seaTargets)
		{
			if (body != 0 && waters.body(target) != body) continue;
			least = Math.min(least, distance(from, target) - arrivalReach(hull));
		}
		return least == Double.MAX_VALUE ? NO_LINK : ticks(vessel, least);
	}

	private boolean opensOnto(SailingMooring mooring, int body)
	{
		for (int opens : bodies(mooring)) if (opens == 0 || opens == body) return true;
		return false;
	}

	// The bodies a berth opens onto; 0 for any when the sea was not made with it
	private int[] bodies(SailingMooring mooring)
	{
		int[] opens = waters.berthBodies(mooring);
		return opens == null ? new int[] {0} : opens;
	}

	private static int ticks(SailingVessel vessel, double tiles)
	{
		return (int) Math.floor(Math.max(0, tiles) / topSpeed(vessel.moves()));
	}

	// The farthest any move goes per tick, in a straight line
	private static double topSpeed(SailingMoves moves)
	{
		double top = 0;
		for (int move = 0; move < moves.size(); move++)
			top = Math.max(top, Math.hypot(moves.dx(move), moves.dy(move)) / moves.ticks(move));
		return top;
	}

	// How far from a target the boat's tile can be while its hull covers it
	private static double arrivalReach(BoatHull hull)
	{
		return hull == null ? Math.sqrt(2) : hull.reach() + Math.sqrt(2) / 2;
	}

	private static double distance(int a, int b)
	{
		return Math.hypot(WorldPointUtil.unpackWorldX(a) - WorldPointUtil.unpackWorldX(b),
			WorldPointUtil.unpackWorldY(a) - WorldPointUtil.unpackWorldY(b));
	}
}
