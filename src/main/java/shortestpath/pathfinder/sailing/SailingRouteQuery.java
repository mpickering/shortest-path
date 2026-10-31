package shortestpath.pathfinder.sailing;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import lombok.Getter;
import lombok.experimental.Accessors;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.PathStep;
import shortestpath.pathfinder.astar.CostArithmetic;
import shortestpath.pathfinder.astar.SeaEnds;

/**
 * What sailing adds to one route query: the ends at sea the search links to ({@link SailingSeaEnds}), which targets
 * are walked to rather than sailed to, the heuristic's targets for those sailed to, and, once a route is found, its
 * sailing legs filled in ({@link SailingRoutes}). With a boat aboard or moored where a route can board it, a start at
 * sea and targets at sea are sailed from and to; targets at sea are then reached aboard, never snapped to the shore.
 */
public final class SailingRouteQuery
{
	/** The heuristic's accepted layers for a seed: reached with or without a bank visit. */
	private static final int ANY_BANK_STATE = 3;

	/** The heuristic's targets: tiles, the bank states each accepts, and the cost each is seeded with. */
	@Getter
	@Accessors(fluent = true)
	public static final class HeuristicTargets
	{
		private final int[] tiles, layers, seedCosts;

		HeuristicTargets(int[] tiles, int[] layers, int[] seedCosts)
		{
			this.tiles = tiles;
			this.layers = layers;
			this.seedCosts = seedCosts;
		}
	}

	private final CollisionMap map;
	private final PreparedSailingCapability capability;
	private final int start;
	// The ends at sea, or null when the query has none
	private final SailingSeaEnds ends;
	private final Set<Integer> landTargets;
	// Time spent making the ends at sea, in nanoseconds
	private final long setupNanos;

	private SailingRouteQuery(CollisionMap map, PreparedSailingCapability capability, int start, SailingSeaEnds ends,
		Set<Integer> landTargets, long setupNanos)
	{
		this.map = map;
		this.capability = capability;
		this.start = start;
		this.ends = ends;
		this.landTargets = landTargets;
		this.setupNanos = setupNanos;
	}

	/**
	 * The sailing for a query from {@code start} to {@code targets} with {@code capability}, every sailing move
	 * checked against {@code gate}, the walking search's positional gates.
	 */
	public static SailingRouteQuery prepare(CollisionMap map, PreparedSailingCapability capability, int start,
		Set<Integer> targets, SailingQuery.EdgeGate gate)
	{
		if (!capability.active()) return new SailingRouteQuery(map, capability, start, null, targets, 0);
		long started = System.nanoTime();
		SailingWaters waters = SailingWaters.of(map);
		int[] seaTargets = targets.stream().filter(waters::contains).mapToInt(Integer::intValue).toArray();
		SailingSeaEnds ends = new SailingSeaEnds(map, capability, start, seaTargets, gate);
		if (!ends.active()) return new SailingRouteQuery(map, capability, start, null, targets, System.nanoTime() - started);
		Set<Integer> landTargets = targets;
		if (seaTargets.length > 0 && ends.atSea())
		{
			landTargets = new HashSet<>(targets);
			for (int target : seaTargets) landTargets.remove(target);
		}
		return new SailingRouteQuery(map, capability, start, ends, landTargets, System.nanoTime() - started);
	}

	/** Whether the search has ends at sea to link to. */
	public boolean atSea()
	{
		return ends != null;
	}

	/** The ends at sea for the search, {@link SeaEnds#NONE} when there are none. */
	public SeaEnds ends()
	{
		return ends == null ? SeaEnds.NONE : ends;
	}

	/** The targets walked to: all of them but those reached aboard. */
	public Set<Integer> landTargets()
	{
		return landTargets;
	}

	/**
	 * The heuristic's targets: the land goals {@code tiles} with their {@code layers}, and each mooring a target at
	 * sea is boarded at, seeded with a bound on boarding and sailing from it. Aboard, with targets at sea only reached
	 * straight from the boat, no walk leads anywhere: the start, never reached.
	 */
	public HeuristicTargets heuristicTargets(int[] tiles, int[] layers)
	{
		int[][] seeds = ends == null ? new int[2][0] : ends.boardingSeeds();
		if (tiles.length + seeds[0].length == 0)
			return new HeuristicTargets(new int[] {start}, new int[] {ANY_BANK_STATE}, new int[] {CostArithmetic.INF});
		int[] allTiles = Arrays.copyOf(tiles, tiles.length + seeds[0].length);
		int[] allLayers = Arrays.copyOf(layers, allTiles.length);
		int[] seedCosts = new int[allTiles.length];
		for (int i = 0; i < seeds[0].length; i++)
		{
			allTiles[tiles.length + i] = seeds[0][i];
			allLayers[tiles.length + i] = ANY_BANK_STATE;
			seedCosts[tiles.length + i] = seeds[1][i];
		}
		return new HeuristicTargets(allTiles, allLayers, seedCosts);
	}

	/** Legs the search sailed. */
	public int legsSailed()
	{
		return ends == null ? 0 : ends.legsSailed();
	}

	/** Time spent making the ends at sea and sailing their legs, in nanoseconds. */
	public long seaNanos()
	{
		return setupNanos + (ends == null ? 0 : ends.sailingNanos());
	}

	/**
	 * {@code path} with each sailing leg between moorings filled in ({@link SailingRoutes#expand}): the leg the search
	 * sailed for the player's boat if it did, else sailed again for that boat.
	 */
	public List<PathStep> withLegs(List<PathStep> path, BooleanSupplier cancelled)
	{
		if (!SailingRoutes.hasLegs(path)) return path;
		SailingFleet fleet = capability.fleet();
		return SailingRoutes.expand(map, path, ends == null ? transport -> null : ends::sailedLeg,
			transport ->
			{
				SailingFleet.Boat boat = SailingTransports.boat(transport, fleet);
				return boat == null ? null : boat.vessel();
			}, cancelled);
	}
}
