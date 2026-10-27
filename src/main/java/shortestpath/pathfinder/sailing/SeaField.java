package shortestpath.pathfinder.sailing;

import java.util.Arrays;
import static net.runelite.api.Constants.REGION_SIZE;
import shortestpath.WorldPointUtil;

/**
 * A lower bound on the ticks a boat needs to reach a zone at sea from any sea tile, exact round coastlines: a shortest
 * path search outwards from the zone over the sea tiles, grown only as far as it is asked about.
 * <p>
 * The boat's centre crosses a chain of sea tiles; a move of (dx, dy) tiles crosses {@code max - min} of them in a
 * straight line and {@code min} diagonally, where {@code max} and {@code min} are the larger and smaller of
 * {@code |dx|} and {@code |dy|}. A straight step costs {@code straight} units and a diagonal one {@code diagonal},
 * {@link #UNITS_PER_TICK} to the tick, chosen from the boat's moves so that no move's steps cost more than its ticks.
 * So a tile's distance here, in ticks, is never more than the boat needs.
 * <p>
 * A tile the search hasn't reached yet is at least as far as the search has got. Lookups never grow it: the caller
 * grows it ({@link #growTo}) as far as it needs exact answers.
 */
final class SeaField
{
	/** Distance units per tick. */
	static final int UNITS_PER_TICK = 12;

	private static final int REGION_SHIFT = Integer.numberOfTrailingZeros(REGION_SIZE);
	private static final int REGION_MASK = REGION_SIZE - 1;
	private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
	private static final int[] DY = {0, 0, 1, -1, 1, -1, 1, -1};
	private static final int UNREACHED = Integer.MAX_VALUE;

	private final SailingWaters waters;
	private final int straight, diagonal;
	// Each tile's distance, per region and plane, made as the search reaches it
	private final int[][] distances;
	// Dial's buckets: tiles queued at each distance, modulo the bucket count
	private final int[][] buckets;
	private final int[] bucketSizes;
	private int queued;
	// The distance the search is settling: every tile not reached yet is at least this far
	private int level;

	/**
	 * @param zone     the sea tiles the boat may end on
	 * @param straight units per straight step
	 * @param diagonal units per diagonal step
	 */
	SeaField(SailingWaters waters, int[] zone, int straight, int diagonal)
	{
		this.waters = waters;
		this.straight = straight;
		this.diagonal = diagonal;
		distances = new int[waters.regionCount()][];
		int bucketCount = Math.max(straight, diagonal) + 1;
		buckets = new int[bucketCount][];
		bucketSizes = new int[bucketCount];
		for (int i = 0; i < bucketCount; i++) buckets[i] = new int[64];
		for (int tile : zone)
		{
			if (waters.contains(tile) && relax(tile, 0)) queued++;
		}
	}

	/** The step costs for {@code moves}, in {@link #UNITS_PER_TICK}ths of a tick: {straight, diagonal}. */
	static int[] stepCosts(SailingMoves moves)
	{
		double chebyshevSpeed = 0;
		for (int move = 0; move < moves.size(); move++)
		{
			chebyshevSpeed = Math.max(chebyshevSpeed,
				(double) Math.max(Math.abs(moves.dx(move)), Math.abs(moves.dy(move))) / moves.ticks(move));
		}
		int straight = (int) Math.floor(UNITS_PER_TICK / chebyshevSpeed);
		int diagonal = Integer.MAX_VALUE;
		for (int move = 0; move < moves.size(); move++)
		{
			int high = Math.max(Math.abs(moves.dx(move)), Math.abs(moves.dy(move)));
			int low = Math.min(Math.abs(moves.dx(move)), Math.abs(moves.dy(move)));
			if (low == 0) continue;
			diagonal = Math.min(diagonal, (UNITS_PER_TICK * moves.ticks(move) - straight * (high - low)) / low);
		}
		if (diagonal == Integer.MAX_VALUE) diagonal = straight;
		return new int[] {Math.max(1, straight), Math.max(1, diagonal)};
	}

	/**
	 * A lower bound on the ticks from sea tile {@code tile} to the zone: exact once the search has reached it, else as
	 * far as the search has got; {@link Integer#MAX_VALUE} once the search has run dry without reaching it.
	 */
	int ticks(int tile)
	{
		int distance = distance(tile);
		// Below the level a distance is settled; at or above it, only queued, and every unsettled tile is at least the
		// level away
		if (distance < level) return distance / UNITS_PER_TICK;
		return queued == 0 ? (distance == UNREACHED ? Integer.MAX_VALUE : distance / UNITS_PER_TICK)
			: level / UNITS_PER_TICK;
	}

	/** Ticks the search has settled every tile within: {@link #ticks} answers at least this for any tile beyond. */
	int reachedTicks()
	{
		return level / UNITS_PER_TICK;
	}

	/** Whether {@link #ticks} for {@code tile} is final: its distance is settled, or the search has run dry. */
	boolean settled(int tile)
	{
		return queued == 0 || distance(tile) < level;
	}

	/** Grows the search until every tile up to {@code ticks} away is reached, or it runs dry. */
	void growTo(int ticks)
	{
		long target = Math.min(Integer.MAX_VALUE - 1L, (long) ticks * UNITS_PER_TICK + UNITS_PER_TICK);
		while (queued > 0 && level < target)
		{
			settleLevel();
		}
	}

	// Settles every tile queued at the current level, then moves on to the next
	private void settleLevel()
	{
		int bucket = level % buckets.length;
		int[] tiles = buckets[bucket];
		for (int i = 0; i < bucketSizes[bucket]; i++)
		{
			int tile = tiles[i];
			queued--;
			if (distance(tile) != level) continue;
			int x = WorldPointUtil.unpackWorldX(tile), y = WorldPointUtil.unpackWorldY(tile);
			int z = WorldPointUtil.unpackWorldPlane(tile);
			for (int d = 0; d < DX.length; d++)
			{
				int next = WorldPointUtil.packWorldPoint(x + DX[d], y + DY[d], z);
				if (waters.contains(next) && relax(next, level + (d < 4 ? straight : diagonal))) queued++;
			}
			// relax may have grown this bucket's array
			tiles = buckets[bucket];
		}
		bucketSizes[bucket] = 0;
		level++;
	}

	private boolean relax(int tile, int distance)
	{
		int region = waters.regionOf(tile);
		if (region < 0) return false;
		int[] page = distances[region];
		if (page == null)
		{
			page = new int[REGION_SIZE * REGION_SIZE];
			Arrays.fill(page, UNREACHED);
			distances[region] = page;
		}
		int index = SailingWaters.indexInRegion(tile);
		if (distance >= page[index]) return false;
		page[index] = distance;
		int bucket = distance % buckets.length;
		if (bucketSizes[bucket] == buckets[bucket].length)
			buckets[bucket] = Arrays.copyOf(buckets[bucket], buckets[bucket].length * 2);
		buckets[bucket][bucketSizes[bucket]++] = tile;
		return true;
	}

	private int distance(int tile)
	{
		int region = waters.regionOf(tile);
		if (region < 0 || distances[region] == null) return UNREACHED;
		return distances[region][SailingWaters.indexInRegion(tile)];
	}
}
