package shortestpath.pathfinder.sailing;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.function.IntBinaryOperator;
import static net.runelite.api.Constants.REGION_SIZE;

/**
 * A number per tile over the regions of plane 0, 0 for most, as bytes that are read where they lie. Written as the
 * count of regions, then per region its coordinates (two shorts) and one of:
 * <ul>
 * <li>{@code 0}, all its numbered tiles one number: the number (an int) and a bit per tile, set where it is;</li>
 * <li>{@code 1}, a few numbers: how many (a byte), each (an int), then a byte per tile, its number's place among them
 * from 1, or 0;</li>
 * <li>{@code 2}, any: an int per tile.</li>
 * </ul>
 */
final class TileLayer
{
	private static final int TILES = REGION_SIZE * REGION_SIZE;
	private static final int UNIFORM = 0, PALETTE = 1, FULL = 2;
	private static final int MOST_IN_PALETTE = 255;

	private final byte[] data;
	// Where each region's part starts in data (at its format byte), by the caller's region index; -1 for none
	private final int[] offsets;
	/** Where the bytes after the layer start. */
	private final int end;

	/**
	 * The layer at {@code offset} in {@code data}.
	 *
	 * @param regionIndex the caller's index for region (x, y), or -1 for one it doesn't keep
	 */
	TileLayer(byte[] data, int offset, int regionCount, IntBinaryOperator regionIndex)
	{
		this.data = data;
		offsets = new int[regionCount];
		Arrays.fill(offsets, -1);
		int at = offset;
		int count = readInt(at);
		at += Integer.BYTES;
		for (int i = 0; i < count; i++)
		{
			int region = regionIndex.applyAsInt(readShort(at), readShort(at + 2));
			at += 2 * Short.BYTES;
			if (region >= 0) offsets[region] = at;
			int format = data[at];
			if (format == UNIFORM) at += 1 + Integer.BYTES + TILES / Byte.SIZE;
			else if (format == PALETTE) at += 2 + Integer.BYTES * Byte.toUnsignedInt(data[at + 1]) + TILES;
			else at += 1 + Integer.BYTES * TILES;
		}
		end = at;
	}

	/** Where the bytes after the layer start. */
	int end()
	{
		return end;
	}

	/** The number of tile {@code index} of region {@code region}, or 0. */
	int get(int region, int index)
	{
		int at = offsets[region];
		if (at < 0) return 0;
		switch (data[at])
		{
			case UNIFORM:
				return (data[at + 1 + Integer.BYTES + (index >>> 3)] & (1 << (index & 7))) != 0 ? readInt(at + 1) : 0;
			case PALETTE:
			{
				int place = Byte.toUnsignedInt(data[at + 2 + Integer.BYTES * Byte.toUnsignedInt(data[at + 1]) + index]);
				return place == 0 ? 0 : readInt(at + 2 + Integer.BYTES * (place - 1));
			}
			default:
				return readInt(at + 1 + Integer.BYTES * index);
		}
	}

	/** Whether region {@code region} has any numbered tile. */
	boolean has(int region)
	{
		return offsets[region] >= 0;
	}

	/**
	 * Writes {@code regions}, the numbers of each region's tiles by its coordinates ({@code x << 16 | y}), each in the
	 * smallest of the formats it fits.
	 */
	static void write(DataOutputStream out, SortedMap<Integer, int[]> regions) throws IOException
	{
		out.writeInt(regions.size());
		for (Map.Entry<Integer, int[]> region : regions.entrySet())
		{
			out.writeShort(region.getKey() >>> 16);
			out.writeShort(region.getKey() & 0xFFFF);
			int[] numbers = region.getValue();
			TreeSet<Integer> distinct = new TreeSet<>();
			for (int number : numbers) if (number != 0) distinct.add(number);
			if (distinct.size() <= 1)
			{
				out.writeByte(UNIFORM);
				out.writeInt(distinct.isEmpty() ? 0 : distinct.first());
				byte[] bits = new byte[TILES / Byte.SIZE];
				for (int index = 0; index < TILES; index++) if (numbers[index] != 0) bits[index >>> 3] |= 1 << (index & 7);
				out.write(bits);
			}
			else if (distinct.size() <= MOST_IN_PALETTE)
			{
				out.writeByte(PALETTE);
				out.writeByte(distinct.size());
				Integer[] palette = distinct.toArray(new Integer[0]);
				for (int number : palette) out.writeInt(number);
				for (int number : numbers) out.writeByte(number == 0 ? 0 : Arrays.binarySearch(palette, number) + 1);
			}
			else
			{
				out.writeByte(FULL);
				for (int number : numbers) out.writeInt(number);
			}
		}
	}

	private int readInt(int at)
	{
		return (data[at] & 0xFF) << 24 | (data[at + 1] & 0xFF) << 16 | (data[at + 2] & 0xFF) << 8 | (data[at + 3] & 0xFF);
	}

	private int readShort(int at)
	{
		return (data[at] & 0xFF) << 8 | (data[at + 1] & 0xFF);
	}
}
