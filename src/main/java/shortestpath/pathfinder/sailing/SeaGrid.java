package shortestpath.pathfinder.sailing;

import java.util.Arrays;
import shortestpath.PrimitiveIntHashMap;
import shortestpath.WorldPointUtil;

/**
 * The sea as a coarse grid of cells {@value #CELL} wide, for bounds that see coastlines: a cell is water if any of its
 * tiles is sea, and joins the water cells round it. A boat's route over the sea passes through a chain of water cells,
 * so how many cells apart two tiles are bounds how far the boat sails between them.
 * <p>
 * The cells are squares on the tile axes ({@link Frame#AXIS}), or squares turned 45 degrees, on the axes
 * {@code u = x + y} and {@code v = x - y} ({@link Frame#ROTATED}). A route that crosses {@code n} tiles one walking
 * step at a time crosses at least {@code CELL * (cells - 1)} of them: counting diagonal steps once for the axis grid,
 * so {@code n} is at least the Chebyshev steps of its moves; and counting every step for the turned grid, where a
 * step on either tile axis is diagonal, so {@code n} is at least the moves' {@code |dx| + |dy|}. The first is tight
 * for routes along the axes, the second for diagonal ones.
 */
final class SeaGrid
{
	/** The cell size, in tiles. */
	static final int CELL = 8;
	/** A cell distance for a cell the sources' water doesn't reach. */
	static final int UNREACHED = Short.MAX_VALUE;

	enum Frame
	{
		AXIS,
		ROTATED
	}

	private static final int CELL_SHIFT = 3;
	// Keeps the turned grid's x - y axis positive
	private static final int V_OFFSET = 1 << 14;
	private static final int COORD_BITS = 14;
	private static final int COORD_MASK = (1 << COORD_BITS) - 1;

	private final Frame frame;
	private final PrimitiveIntHashMap<Integer> cellsByKey;
	private final int[] keys;
	// Each cell's water neighbours, eight slots per cell, -1 for none
	private final int[] neighbours;

	private SeaGrid(Frame frame, PrimitiveIntHashMap<Integer> cellsByKey, int[] keys)
	{
		this.frame = frame;
		this.cellsByKey = cellsByKey;
		this.keys = keys;
		neighbours = new int[keys.length * 8];
		for (int cell = 0; cell < keys.length; cell++)
		{
			int cu = keyU(keys[cell]), cv = keyV(keys[cell]), plane = keyPlane(keys[cell]);
			int slot = cell * 8;
			for (int du = -1; du <= 1; du++)
			{
				for (int dv = -1; dv <= 1; dv++)
				{
					if (du == 0 && dv == 0) continue;
					Integer neighbour = cellsByKey.get(key(cu + du, cv + dv, plane));
					neighbours[slot++] = neighbour == null ? -1 : neighbour;
				}
			}
		}
	}

	/** The grid over the tiles {@code tiles} visits. */
	static SeaGrid of(Frame frame, java.util.function.Consumer<java.util.function.IntConsumer> tiles)
	{
		PrimitiveIntHashMap<Integer> cellsByKey = new PrimitiveIntHashMap<>(1 << 16);
		int[][] keys = {new int[1 << 12]};
		int[] count = {0};
		tiles.accept(tile ->
		{
			int key = cellKey(frame, tile);
			if (cellsByKey.get(key) != null) return;
			if (count[0] == keys[0].length) keys[0] = Arrays.copyOf(keys[0], count[0] * 2);
			cellsByKey.put(key, count[0]);
			keys[0][count[0]++] = key;
		});
		return new SeaGrid(frame, cellsByKey, Arrays.copyOf(keys[0], count[0]));
	}

	/** Water cells. */
	int cellCount()
	{
		return keys.length;
	}

	/** The water cell holding {@code tile}, or -1 if it holds no sea. */
	int cell(int tile)
	{
		Integer cell = cellsByKey.get(cellKey(frame, tile));
		return cell == null ? -1 : cell;
	}

	/** Every water cell's distance in cells from the cells holding {@code tiles}, or {@link #UNREACHED}. */
	short[] distances(int[] tiles)
	{
		short[] distances = new short[keys.length];
		Arrays.fill(distances, (short) UNREACHED);
		int[] queue = new int[keys.length];
		int head = 0, tail = 0;
		for (int tile : tiles)
		{
			int cell = cell(tile);
			if (cell >= 0 && distances[cell] != 0)
			{
				distances[cell] = 0;
				queue[tail++] = cell;
			}
		}
		while (head < tail)
		{
			int cell = queue[head++];
			short next = (short) Math.min(distances[cell] + 1, UNREACHED - 1);
			for (int slot = cell * 8; slot < cell * 8 + 8; slot++)
			{
				int neighbour = neighbours[slot];
				if (neighbour >= 0 && distances[neighbour] == UNREACHED)
				{
					distances[neighbour] = next;
					queue[tail++] = neighbour;
				}
			}
		}
		return distances;
	}

	/**
	 * The least of {@code distances} over the water cells within {@code radius} cells of the one holding {@code tile},
	 * or {@link #UNREACHED}: for a boat on a tile that is not sea, such as a berth, whose first move lands that near.
	 */
	int nearest(short[] distances, int tile, int radius)
	{
		int key = cellKey(frame, tile);
		int cu = keyU(key), cv = keyV(key), plane = keyPlane(key);
		int best = UNREACHED;
		for (int du = -radius; du <= radius; du++)
		{
			for (int dv = -radius; dv <= radius; dv++)
			{
				Integer cell = cellsByKey.get(key(cu + du, cv + dv, plane));
				if (cell != null) best = Math.min(best, distances[cell]);
			}
		}
		return best;
	}

	private static int cellKey(Frame frame, int tile)
	{
		int x = WorldPointUtil.unpackWorldX(tile), y = WorldPointUtil.unpackWorldY(tile);
		int plane = WorldPointUtil.unpackWorldPlane(tile);
		return frame == Frame.AXIS ? key(x >> CELL_SHIFT, y >> CELL_SHIFT, plane)
			: key((x + y) >> CELL_SHIFT, (x - y + V_OFFSET) >> CELL_SHIFT, plane);
	}

	private static int key(int cu, int cv, int plane)
	{
		return plane << (2 * COORD_BITS) | (cu & COORD_MASK) << COORD_BITS | (cv & COORD_MASK);
	}

	private static int keyU(int key)
	{
		return (key >>> COORD_BITS) & COORD_MASK;
	}

	private static int keyV(int key)
	{
		return key & COORD_MASK;
	}

	private static int keyPlane(int key)
	{
		return key >>> (2 * COORD_BITS);
	}
}
