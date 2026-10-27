package shortestpath.pathfinder.sailing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import shortestpath.PrimitiveIntHashMap;
import shortestpath.WorldPointUtil;

/**
 * The sea each boat kind can reach: the tiles its moves end on, sailing from any mooring's berth, from the same
 * searches that time the sailing transports ({@code SailingTimesGenerator}). Narrow channels and shallows a hull
 * can't get through close off what lies beyond them, so a target there is known to be out of a boat's reach before
 * any search looks for it.
 */
public final class SailingReach
{
	public static final String RESOURCE = "/sailing/sea-reach.bin";
	private static volatile SailingReach resource;

	private final String fingerprint;
	// Per kind: a bit per tile, 64 longs per 64 by 64 region, by region key
	private final Map<SailingVessel.Kind, PrimitiveIntHashMap<long[]>> tiles;

	SailingReach(String fingerprint, Map<SailingVessel.Kind, PrimitiveIntHashMap<long[]>> tiles)
	{
		this.fingerprint = fingerprint;
		this.tiles = tiles;
	}

	/** The plugin's data, loaded once. */
	public static SailingReach load()
	{
		SailingReach loaded = resource;
		if (loaded == null)
		{
			synchronized (SailingReach.class)
			{
				if (resource == null) resource = read(SailingTransports.readBytes(RESOURCE));
				loaded = resource;
			}
		}
		return loaded;
	}

	/** What the data was made from; see {@link SailingTransports#fingerprint}. */
	public String fingerprint()
	{
		return fingerprint;
	}

	/** Whether a boat of {@code kind} can end a move on {@code tile}, setting out from some mooring. */
	public boolean reaches(SailingVessel.Kind kind, int tile)
	{
		PrimitiveIntHashMap<long[]> regions = tiles.get(kind);
		long[] bits = regions == null ? null : regions.get(regionKey(tile));
		if (bits == null) return false;
		int index = indexInRegion(tile);
		return (bits[index >>> 6] & (1L << (index & 63))) != 0;
	}

	/** Whether a boat of {@code kind} can end a move within {@code reach} of {@code target}. */
	public boolean reachesNear(SailingVessel.Kind kind, int target, double reach)
	{
		int radius = (int) Math.ceil(reach);
		int x = WorldPointUtil.unpackWorldX(target), y = WorldPointUtil.unpackWorldY(target);
		int z = WorldPointUtil.unpackWorldPlane(target);
		for (int dy = -radius; dy <= radius; dy++)
		{
			for (int dx = -radius; dx <= radius; dx++)
			{
				if (dx * dx + dy * dy <= reach * reach && reaches(kind, WorldPointUtil.packWorldPoint(x + dx, y + dy, z)))
					return true;
			}
		}
		return false;
	}

	/** Marks {@code tile} reached by {@code kind}, for the generator. */
	static void mark(Map<SailingVessel.Kind, PrimitiveIntHashMap<long[]>> tiles, SailingVessel.Kind kind, int tile)
	{
		PrimitiveIntHashMap<long[]> regions = tiles.computeIfAbsent(kind, k -> new PrimitiveIntHashMap<>(1024));
		int key = regionKey(tile);
		long[] bits = regions.get(key);
		if (bits == null)
		{
			bits = new long[64];
			regions.put(key, bits);
		}
		int index = indexInRegion(tile);
		bits[index >>> 6] |= 1L << (index & 63);
	}

	/** The data as written to {@link #RESOURCE}. */
	static byte[] write(String fingerprint, Map<SailingVessel.Kind, PrimitiveIntHashMap<long[]>> tiles,
		int[] regionKeys) throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes)))
		{
			out.writeUTF(fingerprint);
			out.writeInt(tiles.size());
			for (Map.Entry<SailingVessel.Kind, PrimitiveIntHashMap<long[]>> kind : tiles.entrySet())
			{
				out.writeUTF(kind.getKey().name());
				int count = 0;
				for (int key : regionKeys) if (kind.getValue().get(key) != null) count++;
				out.writeInt(count);
				for (int key : regionKeys)
				{
					long[] bits = kind.getValue().get(key);
					if (bits == null) continue;
					out.writeInt(key);
					for (long word : bits) out.writeLong(word);
				}
			}
		}
		return bytes.toByteArray();
	}

	static SailingReach read(byte[] data)
	{
		try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data))))
		{
			String fingerprint = in.readUTF();
			Map<SailingVessel.Kind, PrimitiveIntHashMap<long[]>> tiles = new EnumMap<>(SailingVessel.Kind.class);
			int kinds = in.readInt();
			for (int k = 0; k < kinds; k++)
			{
				SailingVessel.Kind kind = SailingVessel.Kind.valueOf(in.readUTF());
				int count = in.readInt();
				PrimitiveIntHashMap<long[]> regions = new PrimitiveIntHashMap<>(Math.max(16, count * 2));
				for (int i = 0; i < count; i++)
				{
					int key = in.readInt();
					long[] bits = new long[64];
					for (int w = 0; w < 64; w++) bits[w] = in.readLong();
					regions.put(key, bits);
				}
				tiles.put(kind, regions);
			}
			return new SailingReach(fingerprint, tiles);
		}
		catch (IOException error)
		{
			throw new IllegalStateException("cannot read " + RESOURCE, error);
		}
	}

	static int regionKey(int tile)
	{
		return WorldPointUtil.unpackWorldPlane(tile) << 28 | (WorldPointUtil.unpackWorldX(tile) >> 6) << 14
			| (WorldPointUtil.unpackWorldY(tile) >> 6);
	}

	private static int indexInRegion(int tile)
	{
		return (WorldPointUtil.unpackWorldY(tile) & 63) << 6 | (WorldPointUtil.unpackWorldX(tile) & 63);
	}
}
