package shortestpath.pathfinder.sailing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import static net.runelite.api.Constants.REGION_SIZE;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.IntDeque;
import shortestpath.pathfinder.SplitFlagMap;
import shortestpath.sailing.SeaArea;

/**
 * Makes the collision map's sailing layer ({@link SailingWaters}): the sea, flooded from the moorings' berths, and for
 * each kind of boat its pieces, from every move its hull clears.
 * <p>
 * The sea is every open tile reachable, by open tile edges, from the water side of a berth: the open tiles a few tiles
 * around it that do not walk back to the mooring's land tile.
 * <p>
 * A kind's pieces come from every move a boat of its kind could make: holding any heading for the fewest ticks that
 * lands on a whole tile, clear for the kind's hull at its tile's centre. A piece is a strongly connected part of those
 * moves within one area: from any tile of it, a boat can get to any other. Every other move leads from one piece to
 * another. The moves are those at every base speed up to the fastest (every whole number of 128ths of a tile per
 * tick), so every route a boat of the kind sails, at whatever speed, follows those links. A tile moves only leave,
 * never end on, keeps its piece negated: a boat can set out from it but never arrive.
 */
final class SailingLayerBuilder
{
	private static final int REGION_SHIFT = Integer.numberOfTrailingZeros(REGION_SIZE);
	private static final int REGION_MASK = REGION_SIZE - 1;
	private static final int TILES = REGION_SIZE * REGION_SIZE;
	private static final int HEADINGS = 16;
	// How far around a berth the water starts, in tiles: the berth and the jetty can stand between them
	private static final int BERTH_REACH = 3;
	// How far the dock side of a berth may be from the mooring's land tile, in walking steps
	private static final int DOCK_SIDE_STEPS = 40;
	// Entries' times, so the same layers make the same zip
	private static final long ENTRY_TIME = 315532800000L;

	// The sea's tiles, all on plane 0, and each one's index among them by region
	private final int[] tiles;
	private final Map<Integer, int[]> indexByRegion = new HashMap<>();
	// Each sea tile's area number in the dumper's layer, 0 for open water
	private final byte[] areaNumbers;

	private SailingLayerBuilder(SailingWaters areas, int[] tiles)
	{
		this.tiles = tiles;
		areaNumbers = new byte[tiles.length];
		for (int i = 0; i < tiles.length; i++)
		{
			int tile = tiles[i];
			int key = regionKey(tile);
			indexByRegion.computeIfAbsent(key, k ->
			{
				int[] page = new int[TILES];
				Arrays.fill(page, -1);
				return page;
			})[SailingWaters.indexInRegion(tile)] = i;
			SeaArea area = areas.area(tile);
			areaNumbers[i] = (byte) (area == null ? 0 : area.ordinal() + 1);
		}
	}

	/**
	 * The sailing layer for the collision map {@code flags}, by entry name within it ({@code sea}, {@code RAFT}, ...),
	 * with {@code inputs} as its fingerprint.
	 */
	static SortedMap<String, byte[]> build(SplitFlagMap flags, List<SailingMooring> moorings, String inputs,
		int threads) throws Exception
	{
		CollisionMap map = new CollisionMap(flags);
		SailingWaters areas = SailingWaters.fromLayers(map.regionExtent(), flags.layers(SailingWaters.AREA_LAYER),
			Map.of());
		int[] sea = flood(map, moorings);
		System.err.printf("sea: %d tiles%n", sea.length);
		SailingLayerBuilder builder = new SailingLayerBuilder(areas, sea);
		SortedMap<String, byte[]> layer = new TreeMap<>();
		builder.writeSea(layer);
		int[][] moves = moves();
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try
		{
			for (SailingVessel.Kind kind : SailingVessel.Kind.values())
			{
				long started = System.nanoTime();
				builder.writePieces(kind, moves, pool, threads, layer, flags);
				System.err.printf("%s pieces at %.1f s%n", kind, (System.nanoTime() - started) / 1e9);
			}
		}
		finally
		{
			pool.shutdownNow();
		}
		layer.put(SailingWaters.INPUTS, (inputs + "\n").getBytes(StandardCharsets.UTF_8));
		return layer;
	}

	/**
	 * {@code zip} with its sailing layer replaced by {@code layer}: the entries in name order, with fixed times, so the
	 * same contents make the same bytes.
	 */
	static byte[] withLayer(byte[] zip, SortedMap<String, byte[]> layer) throws IOException
	{
		SortedMap<String, byte[]> entries = new TreeMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip)))
		{
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null)
			{
				if (entry.isDirectory() || entry.getName().startsWith(SailingWaters.LAYER + "/")) continue;
				entries.put(entry.getName(), in.readAllBytes());
			}
		}
		for (Map.Entry<String, byte[]> entry : layer.entrySet())
			entries.put(SailingWaters.LAYER + "/" + entry.getKey(), entry.getValue());
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream out = new ZipOutputStream(bytes))
		{
			for (Map.Entry<String, byte[]> entry : entries.entrySet())
			{
				ZipEntry written = new ZipEntry(entry.getKey());
				written.setTime(ENTRY_TIME);
				out.putNextEntry(written);
				out.write(entry.getValue());
				out.closeEntry();
			}
		}
		return bytes.toByteArray();
	}

	// The sea: every open tile reachable from the water side of a berth, sorted
	private static int[] flood(CollisionMap map, List<SailingMooring> moorings)
	{
		Set<Integer> sea = new HashSet<>();
		IntDeque queue = new IntDeque(4096);
		for (SailingMooring mooring : moorings)
		{
			int berth = mooring.berthTile();
			int x = WorldPointUtil.unpackWorldX(berth), y = WorldPointUtil.unpackWorldY(berth);
			int plane = WorldPointUtil.unpackWorldPlane(berth);
			// A berth lies between the dock and the water: open tiles on the dock side walk back to the mooring's land
			// tile, the water's never do
			Set<Integer> dockSide = walkableFrom(map, mooring.landTile(), DOCK_SIDE_STEPS);
			for (int dy = -BERTH_REACH; dy <= BERTH_REACH; dy++)
			{
				for (int dx = -BERTH_REACH; dx <= BERTH_REACH; dx++)
				{
					int nx = x + dx, ny = y + dy;
					int tile = WorldPointUtil.packWorldPoint(nx, ny, plane);
					if (map.isBlocked(nx, ny, plane) || dockSide.contains(tile) || !sea.add(tile)) continue;
					queue.addLast(tile);
					while (!queue.isEmpty())
					{
						int next = queue.pollFirst();
						int tx = WorldPointUtil.unpackWorldX(next), ty = WorldPointUtil.unpackWorldY(next);
						int tz = WorldPointUtil.unpackWorldPlane(next);
						if (map.w(tx, ty, tz)) add(sea, queue, tx - 1, ty, tz);
						if (map.e(tx, ty, tz)) add(sea, queue, tx + 1, ty, tz);
						if (map.s(tx, ty, tz)) add(sea, queue, tx, ty - 1, tz);
						if (map.n(tx, ty, tz)) add(sea, queue, tx, ty + 1, tz);
					}
				}
			}
		}
		int[] tiles = sea.stream().mapToInt(Integer::intValue).toArray();
		Arrays.sort(tiles);
		for (int tile : tiles)
		{
			if (WorldPointUtil.unpackWorldPlane(tile) != 0) throw new IllegalStateException("sea off plane 0 at " + tile);
		}
		return tiles;
	}

	private static void add(Set<Integer> sea, IntDeque queue, int x, int y, int z)
	{
		int tile = WorldPointUtil.packWorldPoint(x, y, z);
		if (sea.add(tile)) queue.addLast(tile);
	}

	// The tiles at most steps open-edge steps from from
	private static Set<Integer> walkableFrom(CollisionMap map, int from, int steps)
	{
		Set<Integer> seen = new HashSet<>();
		List<Integer> frontier = new ArrayList<>();
		frontier.add(from);
		seen.add(from);
		for (int step = 0; step < steps && !frontier.isEmpty(); step++)
		{
			List<Integer> next = new ArrayList<>();
			for (int tile : frontier)
			{
				int x = WorldPointUtil.unpackWorldX(tile), y = WorldPointUtil.unpackWorldY(tile);
				int plane = WorldPointUtil.unpackWorldPlane(tile);
				if (map.w(x, y, plane) && seen.add(WorldPointUtil.packWorldPoint(x - 1, y, plane)))
					next.add(WorldPointUtil.packWorldPoint(x - 1, y, plane));
				if (map.e(x, y, plane) && seen.add(WorldPointUtil.packWorldPoint(x + 1, y, plane)))
					next.add(WorldPointUtil.packWorldPoint(x + 1, y, plane));
				if (map.s(x, y, plane) && seen.add(WorldPointUtil.packWorldPoint(x, y - 1, plane)))
					next.add(WorldPointUtil.packWorldPoint(x, y - 1, plane));
				if (map.n(x, y, plane) && seen.add(WorldPointUtil.packWorldPoint(x, y + 1, plane)))
					next.add(WorldPointUtil.packWorldPoint(x, y + 1, plane));
			}
			frontier = next;
		}
		return seen;
	}

	// Every move a boat makes at some base speed: {heading, dx, dy}, shortest first
	private static int[][] moves()
	{
		Set<List<Integer>> moves = new LinkedHashSet<>();
		for (int units = 1; units <= SailingMoves.MAX_SPEED * SailingWaters.SPEED_UNITS; units++)
		{
			if (Boolean.getBoolean("probe.onlySpeed2") && units != 256) continue;
			SailingMoves table = SailingMoves.forSpeed(units / (double) SailingWaters.SPEED_UNITS);
			for (int move = 0; move < table.size(); move++)
				moves.add(List.of(table.heading(move), table.dx(move), table.dy(move)));
		}
		List<int[]> sorted = new ArrayList<>();
		for (List<Integer> move : moves) sorted.add(new int[] {move.get(0), move.get(1), move.get(2)});
		sorted.sort((a, b) -> Integer.compare(a[1] * a[1] + a[2] * a[2], b[1] * b[1] + b[2] * b[2]));
		System.err.printf("%d moves%n", sorted.size());
		return sorted.toArray(new int[0][]);
	}

	private void writeSea(SortedMap<String, byte[]> layer) throws IOException
	{
		SortedMap<Integer, int[]> numbers = new TreeMap<>();
		for (int tile : tiles) numbers.computeIfAbsent(regionKey(tile), k -> new int[TILES])[SailingWaters.indexInRegion(tile)] = 1;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes))
		{
			TileLayer.write(out, numbers);
		}
		layer.put(SailingWaters.SEA, bytes.toByteArray());
	}

	/**
	 * A kind's pieces, written as one entry: the strongly connected parts of its moves within each area, numbered from
	 * 1, and which pieces moves lead to. Ends of moves that go both ways are joined at once, which is most of the sea;
	 * the moves that go only one way, near coasts, are kept, and the parts they make strongly connected are found
	 * among the joined ends (Tarjan's algorithm).
	 */
	private void writePieces(SailingVessel.Kind kind, int[][] moves, ExecutorService pool, int threads,
		SortedMap<String, byte[]> layer, SplitFlagMap flags) throws Exception
	{
		SailingVessel vessel = SailingVessel.of(kind, SailingMoves.ESTIMATED_SPEED);
		int n = tiles.length;
		// Each thread takes its share of the moves: it joins the ends of those that go both ways within an area, and
		// keeps those that go one way, or from one area to another, as {from, to} tile pairs
		List<Future<Object[]>> parts = new ArrayList<>();
		for (int t = 0; t < threads; t++)
		{
			int share = t;
			parts.add(pool.submit(() ->
			{
				CollisionMap own = new CollisionMap(flags);
				// At its tile's centre, as the model has it
				BoatHull hull = vessel.hull(0, 0);
				int[] parent = identity(n);
				BitSet entered = new BitSet(n), left = new BitSet(n);
				LongList oneWay = new LongList();
				for (int m = share; m < moves.length; m += threads)
				{
					int[] move = moves[m];
					int[] back = {(move[0] + HEADINGS / 2) % HEADINGS, -move[1], -move[2]};
					for (int i = 0; i < n; i++)
					{
						int j = indexOf(tiles[i], move[1], move[2]);
						if (j < 0) continue;
						boolean within = areaNumbers[i] == areaNumbers[j];
						// Already joined both ways, and both ends known for what they are: nothing to learn
						if (within && entered.get(j) && left.get(i) && find(parent, i) == find(parent, j)) continue;
						if (!hull.canMove(own, x(i), y(i), 0, move[0], move[1], move[2])) continue;
						left.set(i);
						entered.set(j);
						if (within && hull.canMove(own, x(j), y(j), 0, back[0], back[1], back[2]))
						{
							left.set(j);
							entered.set(i);
							union(parent, i, j);
						}
						else oneWay.add(((long) i << 32) | j);
					}
				}
				return new Object[] {parent, entered, left, oneWay};
			}));
		}
		int[] parent = identity(n);
		BitSet entered = new BitSet(n), left = new BitSet(n);
		List<LongList> oneWays = new ArrayList<>();
		for (Future<Object[]> part : parts)
		{
			Object[] result = part.get();
			int[] own = (int[]) result[0];
			for (int i = 0; i < n; i++)
			{
				int root = find(own, i);
				if (root != i) union(parent, i, root);
			}
			entered.or((BitSet) result[1]);
			left.or((BitSet) result[2]);
			oneWays.add((LongList) result[3]);
		}
		// The joined ends, as nodes, and the one-way moves between them
		int[] node = new int[n];
		Arrays.fill(node, -1);
		List<Integer> nodeRoot = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			if (!entered.get(i) && !left.get(i)) continue;
			int root = find(parent, i);
			if (node[root] < 0)
			{
				node[root] = nodeRoot.size();
				nodeRoot.add(root);
			}
			node[i] = node[root];
		}
		int nodes = nodeRoot.size();
		Set<Long> within = new HashSet<>(), across = new HashSet<>();
		for (LongList list : oneWays)
		{
			for (int e = 0; e < list.size(); e++)
			{
				int i = (int) (list.get(e) >>> 32), j = (int) list.get(e);
				if (node[i] == node[j]) continue;
				long edge = ((long) node[i] << 32) | node[j];
				if (areaNumbers[i] == areaNumbers[j]) within.add(edge);
				else across.add(edge);
			}
		}
		int[] component = stronglyConnected(nodes, within);
		int pieceCount = 0;
		for (int c : component) pieceCount = Math.max(pieceCount, c + 1);
		// Number the pieces from 1 in tile order
		int[] number = new int[pieceCount];
		int numbered = 0;
		int[] piece = new int[n];
		byte[] pieceArea = new byte[pieceCount + 1];
		for (int i = 0; i < n; i++)
		{
			if (node[i] < 0) continue;
			int c = component[node[i]];
			if (number[c] == 0)
			{
				number[c] = ++numbered;
				pieceArea[numbered] = areaNumbers[i];
			}
			piece[i] = entered.get(i) ? number[c] : -number[c];
		}
		List<Set<Integer>> leadsTo = new ArrayList<>();
		for (int p = 0; p <= numbered; p++) leadsTo.add(new TreeSet<>());
		for (Set<Long> edges : List.of(within, across))
		{
			for (long edge : edges)
			{
				int a = number[component[(int) (edge >>> 32)]], b = number[component[(int) edge]];
				if (a != b) leadsTo.get(a).add(b);
			}
		}
		SortedMap<Integer, int[]> labels = new TreeMap<>();
		for (int i = 0; i < n; i++)
		{
			if (piece[i] == 0) continue;
			labels.computeIfAbsent(regionKey(tiles[i]), k -> new int[TILES])[SailingWaters.indexInRegion(tiles[i])] = piece[i];
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		int links = 0;
		try (DataOutputStream out = new DataOutputStream(bytes))
		{
			TileLayer.write(out, labels);
			out.writeInt(numbered);
			for (int p = 1; p <= numbered; p++)
			{
				out.writeByte(pieceArea[p]);
				out.writeInt(leadsTo.get(p).size());
				for (int next : leadsTo.get(p)) out.writeInt(next);
				links += leadsTo.get(p).size();
			}
		}
		layer.put(kind.name(), bytes.toByteArray());
		System.err.printf("%s: %d pieces over %d tiles (%d only left), %d one-way moves between %d joined parts, %d links%n",
			kind, numbered, entered.cardinality() + left.cardinality() - intersection(entered, left),
			left.cardinality() - intersection(entered, left),
			within.size() + across.size(), nodes, links);
	}

	private static int intersection(BitSet a, BitSet b)
	{
		BitSet both = (BitSet) a.clone();
		both.and(b);
		return both.cardinality();
	}

	// Each node's strongly connected component, numbered from 0, over the directed edges {from << 32 | to}
	private static int[] stronglyConnected(int nodes, Set<Long> edges)
	{
		int[] count = new int[nodes + 1];
		for (long edge : edges) count[(int) (edge >>> 32) + 1]++;
		for (int v = 0; v < nodes; v++) count[v + 1] += count[v];
		int[] targets = new int[edges.size()];
		int[] fill = Arrays.copyOf(count, nodes);
		for (long edge : edges) targets[fill[(int) (edge >>> 32)]++] = (int) edge;
		int[] index = new int[nodes], low = new int[nodes], component = new int[nodes], next = new int[nodes];
		Arrays.fill(index, -1);
		boolean[] onStack = new boolean[nodes];
		int[] stack = new int[nodes], call = new int[nodes];
		int depth = 0, top = 0, counter = 0, components = 0;
		for (int start = 0; start < nodes; start++)
		{
			if (index[start] >= 0) continue;
			call[depth++] = start;
			index[start] = low[start] = counter++;
			next[start] = count[start];
			stack[top++] = start;
			onStack[start] = true;
			while (depth > 0)
			{
				int v = call[depth - 1];
				if (next[v] < count[v + 1])
				{
					int w = targets[next[v]++];
					if (index[w] < 0)
					{
						index[w] = low[w] = counter++;
						next[w] = count[w];
						stack[top++] = w;
						onStack[w] = true;
						call[depth++] = w;
					}
					else if (onStack[w]) low[v] = Math.min(low[v], index[w]);
					continue;
				}
				depth--;
				if (depth > 0) low[call[depth - 1]] = Math.min(low[call[depth - 1]], low[v]);
				if (low[v] == index[v])
				{
					int w;
					do
					{
						w = stack[--top];
						onStack[w] = false;
						component[w] = components;
					}
					while (w != v);
					components++;
				}
			}
		}
		return component;
	}

	// A growable list of longs
	private static final class LongList
	{
		private long[] values = new long[1024];
		private int size;

		void add(long value)
		{
			if (size == values.length) values = Arrays.copyOf(values, size * 2);
			values[size++] = value;
		}

		long get(int index)
		{
			return values[index];
		}

		int size()
		{
			return size;
		}
	}

	private int x(int i)
	{
		return WorldPointUtil.unpackWorldX(tiles[i]);
	}

	private int y(int i)
	{
		return WorldPointUtil.unpackWorldY(tiles[i]);
	}

	// The index of the sea tile (dx, dy) from tile, or -1 if that isn't sea
	private int indexOf(int tile, int dx, int dy)
	{
		int x = WorldPointUtil.unpackWorldX(tile) + dx, y = WorldPointUtil.unpackWorldY(tile) + dy;
		int[] page = indexByRegion.get(((x >> REGION_SHIFT) << 16) | (y >> REGION_SHIFT));
		return page == null ? -1 : page[((y & REGION_MASK) << REGION_SHIFT) | (x & REGION_MASK)];
	}

	private static int regionKey(int tile)
	{
		return ((WorldPointUtil.unpackWorldX(tile) >> REGION_SHIFT) << 16) | (WorldPointUtil.unpackWorldY(tile) >> REGION_SHIFT);
	}

	private static int[] identity(int n)
	{
		int[] parent = new int[n];
		for (int i = 0; i < n; i++) parent[i] = i;
		return parent;
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
		if (ra == rb) return;
		if (ra < rb) parent[rb] = ra;
		else parent[ra] = rb;
	}
}
