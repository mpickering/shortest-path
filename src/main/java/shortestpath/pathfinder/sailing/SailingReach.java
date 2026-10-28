package shortestpath.pathfinder.sailing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import shortestpath.PrimitiveIntHashMap;
import shortestpath.WorldPointUtil;

/**
 * The sea each boat kind can reach, and from where: the tiles its moves end on sailing from each mooring's berth,
 * from the same searches that time the sailing transports ({@code SailingTimesGenerator}), with every restricted area
 * its kind can be fitted for open, each tile marked with the moorings whose boats reach it. A boat that can sail from
 * one tile to another can do so from every mooring that reaches the first, so the second's moorings include the
 * first's: where they don't, the second is out of reach, known without a search. Channels a hull can't get through
 * part the sea this way.
 * <p>
 * Water a hull fits that no mooring's boat reaches is in pockets, each searched from one of its tiles as far as the
 * moorings' water. A boat in a pocket reaches only the pocket's tiles and, beyond the moorings' tiles the pocket's
 * search came to, what those reach. Pockets whose searches meet are taken as one, which only ever says a boat might
 * get somewhere it can't.
 */
public final class SailingReach
{
	public static final String RESOURCE = "/sailing/sea-reach.bin";
	private static final int FORMAT = 5;
	private static volatile SailingReach resource;

	/** One region's tiles a kind reaches: each tile's mark, most regions having one for all. */
	private static final class Region
	{
		// The mark of every marked tile, or -1 when they differ and marks has them; 0 is no mark
		final int uniform;
		final char[] marks;
		// With a uniform mark: a bit per tile that has it
		final long[] marked;

		Region(int uniform, char[] marks, long[] marked)
		{
			this.uniform = uniform;
			this.marks = marks;
			this.marked = marked;
		}

		int mark(int index)
		{
			if (marks != null) return marks[index];
			return (marked[index >>> 6] & (1L << (index & 63))) != 0 ? uniform : 0;
		}
	}

	/** One kind's sea: per region the tiles' marks, what each mark means, and the moorings. */
	private static final class KindReach
	{
		final PrimitiveIntHashMap<Region> regions;
		// Per mark (from 1): the moorings reaching its tiles, or for a pocket's mark, null
		final List<BitSet> moorings;
		// Per mark: for a pocket's, the marks of the moorings' tiles its search came to; otherwise null
		final List<int[]> touched;
		// Each mooring's number in the marks' sets, by name
		final Map<String, Integer> mooringNumbers;

		KindReach(PrimitiveIntHashMap<Region> regions, List<BitSet> moorings, List<int[]> touched,
			Map<String, Integer> mooringNumbers)
		{
			this.regions = regions;
			this.moorings = moorings;
			this.touched = touched;
			this.mooringNumbers = mooringNumbers;
		}

		int mark(int tile)
		{
			Region region = regions.get(regionKey(tile));
			return region == null ? 0 : region.mark(indexInRegion(tile));
		}

		// Whether a boat on a tile of mark from might sail to a tile of mark to
		boolean mayReach(int from, int to)
		{
			if (from == 0) return true;
			if (to == 0) return false;
			BitSet fromMoorings = moorings.get(from);
			if (fromMoorings == null)
			{
				// A pocket: its own tiles, or beyond the moorings' tiles it came to
				if (from == to) return true;
				for (int edge : touched.get(from)) if (mayReach(edge, to)) return true;
				return false;
			}
			BitSet toMoorings = moorings.get(to);
			if (toMoorings == null) return false;
			BitSet missing = (BitSet) fromMoorings.clone();
			missing.andNot(toMoorings);
			return missing.isEmpty();
		}
	}

	private final String fingerprint;
	private final Map<SailingVessel.Kind, KindReach> kinds;

	private SailingReach(String fingerprint, Map<SailingVessel.Kind, KindReach> kinds)
	{
		this.fingerprint = fingerprint;
		this.kinds = kinds;
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
		KindReach reach = kinds.get(kind);
		if (reach == null) return false;
		int mark = reach.mark(tile);
		return mark != 0 && reach.moorings.get(mark) != null;
	}

	/** Whether a boat of {@code kind} setting out from some mooring can end a move within {@code reach} of {@code target}. */
	public boolean reachesNear(SailingVessel.Kind kind, int target, double reach)
	{
		for (int tile : near(target, reach)) if (reaches(kind, tile)) return true;
		return false;
	}

	/**
	 * Whether a boat of {@code kind} on {@code from} might end a move within {@code reach} of one of {@code targets}:
	 * never false when it can. Unknown for a tile its kind can't be on, so true.
	 */
	public boolean mayReach(SailingVessel.Kind kind, int from, int[] targets, double reach)
	{
		KindReach kindReach = kinds.get(kind);
		if (kindReach == null) return true;
		int mark = kindReach.mark(from);
		if (mark == 0) return true;
		for (int target : targets)
		{
			for (int tile : near(target, reach)) if (kindReach.mayReach(mark, kindReach.mark(tile))) return true;
		}
		return false;
	}

	/**
	 * Whether a boat of {@code kind} leaving {@code mooring}'s berth might end a move within {@code reach} of one of
	 * {@code targets}: whether its boats reach there. Unknown for a mooring not timed for the kind, so true.
	 */
	public boolean mayReachFrom(SailingVessel.Kind kind, SailingMooring mooring, int[] targets, double reach)
	{
		KindReach kindReach = kinds.get(kind);
		Integer number = kindReach == null ? null : kindReach.mooringNumbers.get(mooring.name());
		if (number == null) return true;
		for (int target : targets)
		{
			for (int tile : near(target, reach))
			{
				BitSet moorings = kindReach.moorings.get(kindReach.mark(tile));
				if (moorings != null && moorings.get(number)) return true;
			}
		}
		return false;
	}

	// The tiles within reach of target
	private static int[] near(int target, double reach)
	{
		int radius = (int) Math.ceil(reach);
		int x = WorldPointUtil.unpackWorldX(target), y = WorldPointUtil.unpackWorldY(target);
		int z = WorldPointUtil.unpackWorldPlane(target);
		int[] tiles = new int[(2 * radius + 1) * (2 * radius + 1)];
		int count = 0;
		for (int dy = -radius; dy <= radius; dy++)
		{
			for (int dx = -radius; dx <= radius; dx++)
			{
				if (dx * dx + dy * dy <= reach * reach) tiles[count++] = WorldPointUtil.packWorldPoint(x + dx, y + dy, z);
			}
		}
		return Arrays.copyOf(tiles, count);
	}

	/**
	 * Builds the data, for the generator: first each mooring's search, then each pocket's, in turn, a pocket's search
	 * stopping at the moorings' tiles ({@link #reachedFromMooring}).
	 */
	static final class Builder
	{
		private final String fingerprint;
		private final Map<SailingVessel.Kind, KindBuilder> kinds = new EnumMap<>(SailingVessel.Kind.class);

		Builder(String fingerprint)
		{
			this.fingerprint = fingerprint;
		}

		/** Adds the tiles {@code mooring}'s boat of {@code kind} reaches from its berth. */
		synchronized void addMooring(SailingVessel.Kind kind, SailingMooring mooring, int[] tiles)
		{
			kinds.computeIfAbsent(kind, k -> new KindBuilder()).addMooring(mooring, tiles);
		}

		/**
		 * Adds a pocket's tiles, and the moorings' tiles its search came to and stopped at. Call after every mooring's.
		 */
		synchronized void addPocket(SailingVessel.Kind kind, int[] tiles, int[] edges)
		{
			kinds.computeIfAbsent(kind, k -> new KindBuilder()).addPocket(tiles, edges);
		}

		/** Whether a mooring's boat of {@code kind} reaches {@code tile}. */
		synchronized boolean reachedFromMooring(SailingVessel.Kind kind, int tile)
		{
			KindBuilder builder = kinds.get(kind);
			return builder != null && builder.mooringsOf(tile) != null;
		}

		/** Whether some search by a boat of {@code kind} has reached {@code tile}. */
		synchronized boolean reached(SailingVessel.Kind kind, int tile)
		{
			KindBuilder builder = kinds.get(kind);
			return builder != null && (builder.mooringsOf(tile) != null || builder.pocketOf(tile) != 0);
		}

		synchronized SailingReach build()
		{
			Map<SailingVessel.Kind, KindReach> built = new EnumMap<>(SailingVessel.Kind.class);
			kinds.forEach((kind, builder) -> built.put(kind, builder.build()));
			return new SailingReach(fingerprint, built);
		}
	}

	// One kind's searches as they are added
	private static final class KindBuilder
	{
		final List<SailingMooring> moorings = new ArrayList<>();
		// Per region, per tile: the moorings reaching it
		final PrimitiveIntHashMap<BitSet[]> mooringTiles = new PrimitiveIntHashMap<>(1024);
		// Per region, per tile: the pocket (from 1) whose search reached it first, 0 for none
		final PrimitiveIntHashMap<int[]> pocketTiles = new PrimitiveIntHashMap<>(1024);
		final List<Integer> keys = new ArrayList<>();
		// Per pocket (from 1; 0 unused): the moorings' tiles its search came to, and the pockets joined
		final List<int[]> pocketEdges = new ArrayList<>(List.of(new int[0]));
		int[] pocketParent = new int[16];

		void addMooring(SailingMooring mooring, int[] tiles)
		{
			int number = moorings.size();
			moorings.add(mooring);
			for (int tile : tiles)
			{
				BitSet[] region = mooringTiles.get(regionKey(tile));
				if (region == null)
				{
					region = new BitSet[4096];
					mooringTiles.put(regionKey(tile), region);
					if (pocketTiles.get(regionKey(tile)) == null) keys.add(regionKey(tile));
				}
				int index = indexInRegion(tile);
				if (region[index] == null) region[index] = new BitSet();
				region[index].set(number);
			}
		}

		void addPocket(int[] tiles, int[] edges)
		{
			int pocket = pocketEdges.size();
			pocketEdges.add(edges);
			if (pocket == pocketParent.length) pocketParent = Arrays.copyOf(pocketParent, pocketParent.length * 2);
			pocketParent[pocket] = pocket;
			for (int tile : tiles)
			{
				int[] region = pocketTiles.get(regionKey(tile));
				if (region == null)
				{
					region = new int[4096];
					pocketTiles.put(regionKey(tile), region);
					if (mooringTiles.get(regionKey(tile)) == null) keys.add(regionKey(tile));
				}
				int index = indexInRegion(tile);
				if (region[index] == 0) region[index] = pocket;
				else union(pocketParent, region[index], pocket);
			}
		}

		BitSet mooringsOf(int tile)
		{
			BitSet[] region = mooringTiles.get(regionKey(tile));
			return region == null ? null : region[indexInRegion(tile)];
		}

		int pocketOf(int tile)
		{
			int[] region = pocketTiles.get(regionKey(tile));
			return region == null ? 0 : region[indexInRegion(tile)];
		}

		KindReach build()
		{
			// The moorings numbered by name, so the data doesn't depend on the order the searches finished in
			List<SailingMooring> byName = new ArrayList<>(moorings);
			byName.sort(java.util.Comparator.comparing(SailingMooring::name));
			int[] renumber = new int[moorings.size()];
			Map<String, Integer> mooringNumbers = new HashMap<>();
			for (int i = 0; i < byName.size(); i++)
			{
				renumber[moorings.indexOf(byName.get(i))] = i;
				mooringNumbers.put(byName.get(i).name(), i);
			}
			// A mark per distinct set of moorings, then one per pocket
			List<BitSet> markMoorings = new ArrayList<>();
			List<int[]> markTouched = new ArrayList<>();
			markMoorings.add(null);
			markTouched.add(null);
			Map<BitSet, Integer> marksBySet = new LinkedHashMap<>();
			PrimitiveIntHashMap<char[]> marks = new PrimitiveIntHashMap<>(Math.max(16, keys.size() * 2));
			keys.sort(Integer::compare);
			for (int key : keys)
			{
				BitSet[] region = mooringTiles.get(key);
				if (region == null) continue;
				char[] regionMarks = new char[4096];
				for (int index = 0; index < regionMarks.length; index++)
				{
					if (region[index] == null) continue;
					BitSet set = new BitSet();
					for (int m = region[index].nextSetBit(0); m >= 0; m = region[index].nextSetBit(m + 1)) set.set(renumber[m]);
					Integer mark = marksBySet.get(set);
					if (mark == null)
					{
						mark = markMoorings.size();
						markMoorings.add(set);
						markTouched.add(null);
						marksBySet.put(set, mark);
					}
					regionMarks[index] = (char) (int) mark;
				}
				marks.put(key, regionMarks);
			}
			// A mark per group of joined pockets, with the moorings' marks any of them came to
			int[] pocketMark = new int[pocketEdges.size()];
			Map<Integer, TreeSet<Integer>> groupEdges = new LinkedHashMap<>();
			for (int pocket = 1; pocket < pocketEdges.size(); pocket++)
			{
				int root = find(pocketParent, pocket);
				if (pocketMark[root] == 0)
				{
					pocketMark[root] = markMoorings.size();
					markMoorings.add(null);
					markTouched.add(null);
					groupEdges.put(root, new TreeSet<>());
				}
				for (int tile : pocketEdges.get(pocket))
				{
					char[] regionMarks = marks.get(regionKey(tile));
					if (regionMarks != null && regionMarks[indexInRegion(tile)] != 0)
						groupEdges.get(root).add((int) regionMarks[indexInRegion(tile)]);
				}
			}
			if (markMoorings.size() > Character.MAX_VALUE) throw new IllegalStateException("too many marks");
			groupEdges.forEach((root, edges) ->
				markTouched.set(pocketMark[root], edges.stream().mapToInt(Integer::intValue).toArray()));
			PrimitiveIntHashMap<Region> regions = new PrimitiveIntHashMap<>(Math.max(16, keys.size() * 2));
			for (int key : keys)
			{
				char[] regionMarks = marks.get(key);
				if (regionMarks == null) regionMarks = new char[4096];
				int[] pockets = pocketTiles.get(key);
				if (pockets != null)
				{
					for (int index = 0; index < regionMarks.length; index++)
					{
						if (regionMarks[index] == 0 && pockets[index] != 0)
							regionMarks[index] = (char) pocketMark[find(pocketParent, pockets[index])];
					}
				}
				regions.put(key, compact(regionMarks));
			}
			return new KindReach(regions, markMoorings, markTouched, mooringNumbers);
		}
	}

	private static int find(int[] parent, int i)
	{
		while (parent[i] != i)
		{
			parent[i] = parent[parent[i]];
			i = parent[i];
		}
		return i;
	}

	private static void union(int[] parent, int a, int b)
	{
		int ra = find(parent, a), rb = find(parent, b);
		if (ra != rb) parent[Math.max(ra, rb)] = Math.min(ra, rb);
	}

	// A region's marks, kept as one mark and a bit per tile when every marked tile has the same one
	private static Region compact(char[] marks)
	{
		int uniform = 0;
		for (char mark : marks)
		{
			if (mark == 0) continue;
			if (uniform == 0) uniform = mark;
			else if (uniform != mark) return new Region(-1, marks, null);
		}
		long[] marked = new long[64];
		for (int index = 0; index < marks.length; index++) if (marks[index] != 0) marked[index >>> 6] |= 1L << (index & 63);
		return new Region(uniform, null, marked);
	}

	/** The data as written to {@link #RESOURCE}. */
	byte[] write() throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes)))
		{
			out.writeInt(FORMAT);
			out.writeUTF(fingerprint);
			out.writeInt(kinds.size());
			for (Map.Entry<SailingVessel.Kind, KindReach> kind : kinds.entrySet())
			{
				KindReach reach = kind.getValue();
				out.writeUTF(kind.getKey().name());
				String[] names = reach.mooringNumbers.keySet().toArray(new String[0]);
				Arrays.sort(names, java.util.Comparator.comparing(reach.mooringNumbers::get));
				out.writeInt(names.length);
				for (String name : names) out.writeUTF(name);
				out.writeInt(reach.moorings.size() - 1);
				for (int mark = 1; mark < reach.moorings.size(); mark++)
				{
					BitSet moorings = reach.moorings.get(mark);
					out.writeBoolean(moorings != null);
					if (moorings != null)
					{
						long[] words = moorings.toLongArray();
						out.writeInt(words.length);
						for (long word : words) out.writeLong(word);
					}
					else
					{
						int[] touched = reach.touched.get(mark);
						out.writeInt(touched.length);
						for (int edge : touched) out.writeChar(edge);
					}
				}
				int[] keys = reach.regions.keys();
				Arrays.sort(keys);
				out.writeInt(keys.length);
				for (int key : keys)
				{
					Region region = reach.regions.get(key);
					out.writeInt(key);
					out.writeBoolean(region.marks != null);
					if (region.marks != null) for (char mark : region.marks) out.writeChar(mark);
					else
					{
						out.writeChar(region.uniform);
						for (long word : region.marked) out.writeLong(word);
					}
				}
			}
		}
		return bytes.toByteArray();
	}

	static SailingReach read(byte[] data)
	{
		try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data))))
		{
			int format = in.readInt();
			if (format != FORMAT) throw new IOException("format " + format + ", not " + FORMAT);
			String fingerprint = in.readUTF();
			Map<SailingVessel.Kind, KindReach> kinds = new EnumMap<>(SailingVessel.Kind.class);
			int kindCount = in.readInt();
			for (int k = 0; k < kindCount; k++)
			{
				SailingVessel.Kind kind = SailingVessel.Kind.valueOf(in.readUTF());
				Map<String, Integer> mooringNumbers = new HashMap<>();
				int moorings = in.readInt();
				for (int i = 0; i < moorings; i++) mooringNumbers.put(in.readUTF(), i);
				List<BitSet> markMoorings = new ArrayList<>();
				List<int[]> markTouched = new ArrayList<>();
				markMoorings.add(null);
				markTouched.add(null);
				int markCount = in.readInt();
				for (int mark = 0; mark < markCount; mark++)
				{
					if (in.readBoolean())
					{
						long[] words = new long[in.readInt()];
						for (int w = 0; w < words.length; w++) words[w] = in.readLong();
						markMoorings.add(BitSet.valueOf(words));
						markTouched.add(null);
					}
					else
					{
						int[] touched = new int[in.readInt()];
						for (int t = 0; t < touched.length; t++) touched[t] = in.readChar();
						markMoorings.add(null);
						markTouched.add(touched);
					}
				}
				int regionCount = in.readInt();
				PrimitiveIntHashMap<Region> regions = new PrimitiveIntHashMap<>(Math.max(16, regionCount * 2));
				for (int i = 0; i < regionCount; i++)
				{
					int key = in.readInt();
					if (in.readBoolean())
					{
						char[] marks = new char[4096];
						for (int t = 0; t < marks.length; t++) marks[t] = in.readChar();
						regions.put(key, new Region(-1, marks, null));
					}
					else
					{
						int uniform = in.readChar();
						long[] marked = new long[64];
						for (int w = 0; w < marked.length; w++) marked[w] = in.readLong();
						regions.put(key, new Region(uniform, null, marked));
					}
				}
				kinds.put(kind, new KindReach(regions, markMoorings, markTouched, mooringNumbers));
			}
			return new SailingReach(fingerprint, kinds);
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
