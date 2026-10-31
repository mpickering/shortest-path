package shortestpath.pathfinder.sailing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import shortestpath.PrimitiveIntHashMap;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.astar.SeaEnds;

/**
 * One query's targets at sea, and the bounds on sailing to them: the straight line, the hull's coarse water grids
 * ({@link SeaGrid}) and a distance field from the targets ({@link SeaField}), each a lower bound on the ticks a boat
 * needs, and which tiles a boat can arrive at them from. Made per query, as the targets are.
 */
final class SeaTargets
{
	private final CollisionMap map;
	private final SailingWaters waters;
	private final int[] targets;
	// The farthest from a target any boat sailing to them can set out on the move that arrives: the fields' zone
	private double longestReach;
	// The fields from the targets, by the step costs of the moves they bound
	private final Map<String, SeaField> fields = new HashMap<>();
	// Per hull's sea and arrival reach: the coarse cell distances from where it arrives at the targets
	private final Map<List<Object>, short[][]> arrivalCells = new HashMap<>();
	// Per boat (kind, speed, parts): the tiles it can arrive at a target from
	private final Map<List<Object>, int[]> arrivalTilesByBoat = new HashMap<>();

	SeaTargets(CollisionMap map, SailingWaters waters, int[] targets)
	{
		this.map = map;
		this.waters = waters;
		this.targets = targets.clone();
	}

	int[] tiles()
	{
		return targets;
	}

	boolean isEmpty()
	{
		return targets.length == 0;
	}

	/** Notes a boat that may sail to the targets, so the fields reach as far out as it can arrive from. */
	void sailedToBy(BoatHull hull, SailingMoves moves)
	{
		longestReach = Math.max(longestReach, hull.arrivalReach(moves));
	}

	/**
	 * The straight line from the tiles {@code from} to arriving at the nearest target, at the boat's top speed, in
	 * ticks, or {@link SeaEnds#NO_LINK} with no target.
	 */
	int straightLineTicks(SailingVessel vessel, BoatHull hull, int[] from)
	{
		double least = Double.MAX_VALUE;
		double reach = hull.arrivalReach(vessel.moves());
		for (int tile : from)
		{
			for (int target : targets) least = Math.min(least, Tiles.distance(tile, target) - reach);
		}
		return least == Double.MAX_VALUE ? SeaEnds.NO_LINK
			: (int) Math.floor(Math.max(0, least) / vessel.moves().topSpeed());
	}

	/**
	 * A bound on the ticks from the tiles {@code from} to arriving at a target, from the hull's coarse water grids,
	 * which see round the land between, or {@link SeaEnds#NO_LINK} if they join no target.
	 */
	int cellTicks(SailingVessel vessel, BoatHull hull, int[] from)
	{
		HullSea sea = waters.forHull(map, vessel, hull);
		double reach = hull.arrivalReach(vessel.moves());
		short[][] distances = arrivalCells.computeIfAbsent(List.of(sea, reach), key ->
		{
			short[][] least = null;
			for (int target : targets)
			{
				short[][] cells = sea.arrivalDistances(target, reach);
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
		SeaGrid axis = sea.axisGrid(), rotated = sea.rotatedGrid();
		int axisCells = SeaGrid.UNREACHED, rotatedCells = SeaGrid.UNREACHED;
		for (int tile : from)
		{
			axisCells = Math.min(axisCells, axis.cell(tile) >= 0 ? distances[0][axis.cell(tile)]
				: axis.nearest(distances[0], tile, moves.longestChebyshev() / SeaGrid.CELL + 1));
			rotatedCells = Math.min(rotatedCells, rotated.cell(tile) >= 0 ? distances[1][rotated.cell(tile)]
				: rotated.nearest(distances[1], tile, moves.longestManhattan() / SeaGrid.CELL + 1));
		}
		if (axisCells == SeaGrid.UNREACHED || rotatedCells == SeaGrid.UNREACHED) return SeaEnds.NO_LINK;
		double ticks = Math.max(SeaGrid.CELL * (axisCells - 1) / moves.chebyshevSpeed(),
			SeaGrid.CELL * (rotatedCells - 1) / moves.manhattanSpeed());
		return (int) Math.max(0, Math.floor(ticks));
	}

	/**
	 * Whether a boat setting out from the tiles {@code from} might arrive at some target: through bodies of water of its kind
	 * its parts let it enter ({@link SailingWaters#connects}). Never false for a boat that can.
	 */
	boolean reachable(SailingVessel vessel, BoatHull hull, int[] from)
	{
		return waters.connects(vessel, hull, from, arrivalTiles(vessel, hull));
	}

	/** The field from where a boat with {@code moves} arrives at the targets, made on first ask. */
	SeaField field(SailingMoves moves)
	{
		int[] costs = SeaField.stepCosts(moves);
		return fields.computeIfAbsent(costs[0] + ":" + costs[1], key ->
		{
			TreeSet<Integer> zone = new TreeSet<>();
			for (int target : targets) for (int tile : Tiles.within(target, longestReach)) zone.add(tile);
			return new SeaField(waters, zone.stream().mapToInt(Integer::intValue).toArray(), costs[0], costs[1]);
		});
	}

	/** The least of {@code field}'s ticks from the tiles {@code from}. */
	static int fieldTicks(SeaField field, int[] from)
	{
		int best = Integer.MAX_VALUE;
		for (int tile : from) best = Math.min(best, field.ticks(tile));
		return best;
	}

	/**
	 * The tiles {@code vessel} can arrive at a target from: where a move it can sail sets out to pass its hull over one
	 * (the targets expanded, {@link SailingSearch#arrivingMoves}), and where it already covers one, as it does setting
	 * out. Exact, so a boat that can't get its hull over a target, such as a sloop in shallows only a raft fits, is
	 * never offered a link there.
	 */
	private int[] arrivalTiles(SailingVessel vessel, BoatHull hull)
	{
		// The same for every boat of a kind, speed and fit-out at its tile's centre; links ask once per mooring
		List<Object> key = hull.pivotX() != 0 || hull.pivotY() != 0 || !vessel.standard() ? null
			: List.of(vessel.kind(), vessel.speed(), vessel.areas());
		int[] known = key == null ? null : arrivalTilesByBoat.get(key);
		if (known != null) return known;
		int[] tiles = arrivalTilesOf(vessel, hull);
		if (key != null) arrivalTilesByBoat.put(key, tiles);
		return tiles;
	}

	private int[] arrivalTilesOf(SailingVessel vessel, BoatHull hull)
	{
		TreeSet<Integer> tiles = new TreeSet<>();
		SailingMoves moves = vessel.moves();
		IntPredicate landing = waters.landing(map, vessel, hull);
		PrimitiveIntHashMap<int[]> arriving = SailingSearch.arrivingMoves(hull, moves, targets);
		for (int start : arriving.keys())
		{
			if (!waters.contains(start) || !waters.allows(vessel, start)) continue;
			int x = WorldPointUtil.unpackWorldX(start), y = WorldPointUtil.unpackWorldY(start);
			int z = WorldPointUtil.unpackWorldPlane(start);
			int mask = 0;
			for (int bits : arriving.get(start)) mask |= bits;
			for (int move = 0; move < moves.size(); move++)
			{
				if ((mask & (1 << move)) == 0) continue;
				int end = WorldPointUtil.packWorldPoint(x + moves.dx(move), y + moves.dy(move), z);
				if (landing.test(end) && waters.allows(vessel, end)
					&& hull.canMove(map, x, y, z, moves.heading(move), moves.dx(move), moves.dy(move)))
				{
					tiles.add(start);
					break;
				}
			}
			// Covering a target where it sets out: on the way already, by any move
			if (!tiles.contains(start))
			{
				for (int target : targets)
				{
					if (SailingSearch.arrivedAt(hull, target, start, -1) && hull.fits(map, x, y, z)) tiles.add(start);
				}
			}
		}
		return tiles.stream().mapToInt(Integer::intValue).toArray();
	}
}
