package shortestpath.pathfinder.astar;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.BankVisitState;

/**
 * Lower bounds, for every tile around the Wilderness, on the cost of reaching a tile whose
 * {@link TeleportCapability} band allows each band's global teleports, without casting a global on
 * the way. {@link WildernessHeuristic} adds them to the cap a search starting in the Wilderness puts
 * on its heuristic, so a state deep in the Wilderness is not priced as if it could teleport where it
 * stands.
 *
 * <p>Two lifetimes. The layout (which tiles have an escape, and their ids) is static, built with the
 * rest of the static data. The escapes themselves follow an account's local transports, so a
 * {@link Table} is built from the local transports the account may take from inside the boxes, in
 * either bank layer, by one multi-source reverse search per band. Tables are cached here by that
 * transport set rather than by account (as many as {@link RoutingStaticBuilder.Options} keeps),
 * since it changes far less often: a session whose account
 * fingerprint changes for any other reason, and accounts of other sessions with the same Wilderness
 * transports, reuse the table.
 *
 * <p>Ids: the search tiles of the boxes, row by row, then the sites in the boxes that are not search
 * tiles (blocked transport origins). Walking into or out of such a site is modelled from all eight
 * neighbours at cost 1, a superset of the forward search's moves, so the escapes can only be low.
 */
final class WildernessEscape
{
	/** The bands with an escape; every tile can cast {@code OVER_30}'s globals. */
	private static final TeleportCapability[] BANDS = {TeleportCapability.OVER_20, TeleportCapability.OVER_0,
		TeleportCapability.ALL};
	private static final char UNREACHED = Character.MAX_VALUE;
	private static final int[] DX = {0, 1, 1, 1, 0, -1, -1, -1};
	private static final int[] DY = {1, 1, 0, -1, -1, -1, 0, 1};
	private static final int PLANES = 4;
	// {minX, maxX (exclusive), minY, maxY (exclusive)}: TeleportCapability's areas, one tile wider, so
	// every tile outside them allows every band.
	private static final int[][] BOXES = {{2943, 3463, 3524, 4209}, {2943, 3463, 9917, 10377}};

	private final RoutingStatic stat;
	/** Per box and plane, the first row; a row is one y of one box on one plane. */
	private final int[][] firstRow;
	/** Per row, its first search node, and its first id (one more entry ends the last row's ids). */
	private final int[] rowNodes, rowIds;
	/** Per row, the rows of its box and plane: {@code [blockStart, blockEnd)}. */
	private final int[] blockStart, blockEnd;
	/** The sites in the boxes that are not search tiles, ascending. */
	private final int[] extraSites;
	private final int searchIdCount;
	/**
	 * Per band, the search tiles allowing it with a neighbour that does not: the only sources whose
	 * walking predecessors can need an escape, so the only ones a table build expands.
	 */
	private final int[][] boundaries;
	/** Per search id, its band, and the walking-mask bits along which a predecessor steps onto it. */
	private final byte[] bands, predecessors;
	private final int tableCapacity;
	private final LinkedHashMap<TransportSet, Table> tables;

	private WildernessEscape(RoutingStatic stat, int[][] firstRow, int[] rowNodes, int[] rowIds, int[] extraSites,
		int[][] boundaries, byte[] bands, byte[] predecessors)
	{
		this.boundaries = boundaries;
		this.tableCapacity = stat.options().wildernessTableCapacity;
		this.tables = new LinkedHashMap<>(Math.min(tableCapacity, 64) * 2, 0.75f, true);
		this.bands = bands;
		this.predecessors = predecessors;
		this.stat = stat;
		this.firstRow = firstRow;
		this.rowNodes = rowNodes;
		this.rowIds = rowIds;
		this.extraSites = extraSites;
		this.searchIdCount = rowIds[rowIds.length - 1];
		this.blockStart = new int[rowNodes.length];
		this.blockEnd = new int[rowNodes.length];
		for (int box = 0; box < BOXES.length; box++)
			for (int plane = 0; plane < PLANES; plane++)
			{
				int first = firstRow[box][plane], end = first + BOXES[box][3] - BOXES[box][2];
				Arrays.fill(blockStart, first, end, first);
				Arrays.fill(blockEnd, first, end, end);
			}
	}

	/** The static layout: which tiles and sites have escapes. */
	static WildernessEscape build(RoutingStatic stat)
	{
		int rowCount = 0;
		int[][] firstRow = new int[BOXES.length][PLANES];
		for (int box = 0; box < BOXES.length; box++)
			for (int plane = 0; plane < PLANES; plane++)
			{
				firstRow[box][plane] = rowCount;
				rowCount += BOXES[box][3] - BOXES[box][2];
			}
		// Each row's search nodes are contiguous: tiles sort by plane, then y, then x.
		int[] rowNodes = new int[rowCount], rowIds = new int[rowCount + 1];
		int ids = 0;
		for (int box = 0; box < BOXES.length; box++)
		{
			int[] b = BOXES[box];
			for (int plane = 0; plane < PLANES; plane++)
				for (int y = b[2]; y < b[3]; y++)
				{
					int row = firstRow[box][plane] + y - b[2];
					int from = lowerBound(stat, WorldPointUtil.packWorldPoint(b[0], y, plane));
					int to = lowerBound(stat, WorldPointUtil.packWorldPoint(b[1], y, plane));
					rowNodes[row] = from;
					rowIds[row] = ids;
					ids += to - from;
				}
		}
		rowIds[rowCount] = ids;
		int[] extra = new int[stat.siteCount()];
		int extraCount = 0;
		for (int site = 0; site < stat.siteCount(); site++)
		{
			int tile = stat.siteTile(site);
			if (inBoxes(tile) && stat.searchIndex(tile) < 0) extra[extraCount++] = site;
		}
		// Boundaries: a tile next to one of another band, found from coordinates alone.
		int[][] boundaries = new int[BANDS.length][];
		int[][] found = new int[BANDS.length][64];
		int[] counts = new int[BANDS.length];
		byte[] bands = new byte[ids], predecessors = new byte[ids];
		for (int row = 0; row < rowCount; row++)
			for (int id = rowIds[row]; id < rowIds[row + 1]; id++)
			{
				int node = rowNodes[row] + id - rowIds[row];
				int tile = stat.searchTile(node);
				int x = WorldPointUtil.unpackWorldX(tile), y = WorldPointUtil.unpackWorldY(tile);
				TeleportCapability here = TeleportCapability.at(x, y);
				bands[id] = (byte) here.ordinal();
				int mask = 0;
				for (int bit = 0; bit < 8; bit++)
				{
					int from = stat.walkSuccessor(node, bit);
					if (from < 0 || from >= stat.searchTileCount()) continue;
					int back = (bit + 4) & 7;
					if ((stat.walkingMask(from) & (1 << back)) != 0 && stat.walkSuccessor(from, back) == node)
						mask |= 1 << bit;
				}
				predecessors[id] = (byte) mask;
				for (int band = 0; band < BANDS.length; band++)
				{
					if (!here.allows(BANDS[band])) continue;
					boolean edge = false;
					for (int direction = 0; direction < 8 && !edge; direction++)
						edge = !TeleportCapability.at(x + DX[direction], y + DY[direction]).allows(BANDS[band]);
					if (!edge) continue;
					if (counts[band] == found[band].length) found[band] = Arrays.copyOf(found[band], counts[band] * 2);
					found[band][counts[band]++] = id;
				}
			}
		for (int band = 0; band < BANDS.length; band++)
			boundaries[band] = Arrays.copyOf(found[band], counts[band]);
		return new WildernessEscape(stat, firstRow, rowNodes, rowIds, Arrays.copyOf(extra, extraCount), boundaries,
			bands, predecessors);
	}

	int idCount()
	{
		return searchIdCount + extraSites.length;
	}

	/** Bytes the static layout keeps, roughly. */
	long layoutBytes()
	{
		long bytes = 16L * rowNodes.length + 4L * extraSites.length + 2L * bands.length;
		for (int[] boundary : boundaries)
			bytes += 4L * boundary.length;
		return bytes;
	}

	/** The account's escapes, built on first use for its Wilderness transports and then cached. */
	Table table(PreparedRoutingAccount account)
	{
		TransportSet transports = TransportSet.of(account);
		synchronized (tables)
		{
			Table table = tables.get(transports);
			if (table != null) return table;
		}
		// Built outside the lock: two searches may build the same table, and one wins.
		Table table = buildTable(transports);
		table.built = true;
		synchronized (tables)
		{
			Table existing = tables.get(transports);
			if (existing != null) return existing;
			tables.put(transports, table);
			Iterator<Map.Entry<TransportSet, Table>> eldest = tables.entrySet().iterator();
			while (tables.size() > tableCapacity)
			{
				eldest.next();
				eldest.remove();
			}
			return table;
		}
	}

	int cachedTableCount()
	{
		synchronized (tables)
		{
			return tables.size();
		}
	}

	/** One account class's escapes. */
	final class Table
	{
		/** Per band, escape by id; {@link #UNREACHED} where no move leads to the band. */
		private final char[][] escapes;
		private final long buildNanos;
		/** Set on the table a {@link #table} call built, until a search reports it. */
		private volatile boolean built;

		private Table(char[][] escapes, long buildNanos)
		{
			this.escapes = escapes;
			this.buildNanos = buildNanos;
		}

		long buildNanos()
		{
			return buildNanos;
		}

		/** Whether this table was built since the last call: true for the one search that built it. */
		boolean takeBuilt()
		{
			if (!built) return false;
			synchronized (this)
			{
				boolean result = built;
				built = false;
				return result;
			}
		}

		long retainedBytes()
		{
			return 2L * BANDS.length * idCount();
		}

		/**
		 * The escape from search tile {@code node} to {@code band}: 0 where the band is castable, and
		 * {@code CostArithmetic.INF} where no walk or local transport leads to it.
		 */
		int escapeFromNode(TeleportCapability band, int node)
		{
			return escape(band, nodeId(node));
		}

		/** The id of search tile {@code node}, or -1 outside the boxes. */
		int nodeId(int node)
		{
			return WildernessEscape.this.nodeId(node);
		}

		/** The id of a site that is not a search tile, or -1 outside the boxes. */
		int siteId(int site)
		{
			return WildernessEscape.this.siteId(site);
		}

		/** The escape from id {@code id} ({@link #nodeId}, {@link #siteId}); 0 for {@code id < 0}. */
		int escape(TeleportCapability band, int id)
		{
			if (band == TeleportCapability.OVER_30 || id < 0) return 0;
			char value = escapes[band.ordinal() - 1][id];
			return value == UNREACHED ? CostArithmetic.INF : value;
		}
	}

	int nodeId(int node)
	{
		int row = row(stat.searchTile(node));
		return row < 0 ? -1 : idInRow(row, node);
	}

	private int idInRow(int row, int node)
	{
		int offset = node - rowNodes[row];
		return offset >= 0 && offset < rowIds[row + 1] - rowIds[row] ? rowIds[row] + offset : -1;
	}

	int siteId(int site)
	{
		int index = Arrays.binarySearch(extraSites, site);
		return index < 0 ? -1 : searchIdCount + index;
	}

	private int row(int tile)
	{
		int x = WorldPointUtil.unpackWorldX(tile), y = WorldPointUtil.unpackWorldY(tile);
		int plane = WorldPointUtil.unpackWorldPlane(tile);
		for (int box = 0; box < BOXES.length; box++)
		{
			int[] b = BOXES[box];
			if (x >= b[0] && x < b[1] && y >= b[2] && y < b[3]) return firstRow[box][plane] + y - b[2];
		}
		return -1;
	}

	/** The id of {@code tile}: its search tile's, or else its site's, or -1. */
	private int id(int tile)
	{
		int node = stat.searchIndex(tile);
		if (node >= 0) return nodeId(node);
		int site = stat.siteIndex(tile);
		return site < 0 ? -1 : siteId(site);
	}

	static boolean inBoxes(int tile)
	{
		int x = WorldPointUtil.unpackWorldX(tile), y = WorldPointUtil.unpackWorldY(tile);
		for (int[] box : BOXES)
			if (x >= box[0] && x < box[1] && y >= box[2] && y < box[3]) return true;
		return false;
	}

	private Table buildTable(TransportSet transports)
	{
		long started = System.nanoTime();
		int idCount = idCount();
		// Per id: its search node (or -1 for an extra site) and its row, for neighbour ids by row
		// arithmetic; and its band.
		int[] nodes = new int[idCount], rows = new int[idCount];
		byte[] bands = Arrays.copyOf(this.bands, idCount);
		for (int row = 0; row + 1 < rowIds.length; row++)
			for (int id = rowIds[row]; id < rowIds[row + 1]; id++)
			{
				nodes[id] = rowNodes[row] + id - rowIds[row];
				rows[id] = row;
			}
		for (int i = 0; i < extraSites.length; i++)
		{
			int id = searchIdCount + i;
			nodes[id] = -1;
			rows[id] = -1;
			bands[id] = (byte) TeleportCapability.at(stat.siteTile(extraSites[i])).ordinal();
		}

		// Other edges, reversed and grouped by their head: transports, and the moves into and out of
		// the extra sites. A move to a tile outside the boxes reaches every band at its cost.
		EdgeList reverse = new EdgeList(), leaving = new EdgeList();
		for (int i = 0; i < transports.size(); i++)
		{
			int from = id(transports.origin(i)), destination = transports.destination(i);
			if (from < 0) continue;
			int to = id(destination);
			if (to >= 0) reverse.add(to, from, transports.cost(i));
			else if (!inBoxes(destination)) leaving.add(from, from, transports.cost(i));
		}
		for (int i = 0; i < extraSites.length; i++)
		{
			int id = searchIdCount + i, tile = stat.siteTile(extraSites[i]);
			int x = WorldPointUtil.unpackWorldX(tile), y = WorldPointUtil.unpackWorldY(tile);
			int plane = WorldPointUtil.unpackWorldPlane(tile);
			for (int direction = 0; direction < 8; direction++)
			{
				int neighbour = WorldPointUtil.packWorldPoint(x + DX[direction], y + DY[direction], plane);
				int other = id(neighbour);
				if (other >= 0)
				{
					reverse.add(id, other, 1);
					reverse.add(other, id, 1);
				}
				else if (!inBoxes(neighbour) && (stat.searchIndex(neighbour) >= 0 || stat.siteIndex(neighbour) >= 0))
					leaving.add(id, id, 1);
			}
		}
		int[][] csr = reverse.csr(idCount);

		char[][] escapes = new char[BANDS.length][];
		int[] distance = new int[idCount];
		BucketQueue queue = new BucketQueue();
		for (int band = 0; band < BANDS.length; band++)
		{
			int allowed = BANDS[band].ordinal();
			Arrays.fill(distance, Integer.MAX_VALUE);
			queue.clear();
			// Sources (tiles already in the band) are at 0 and expanded directly rather than queued.
			// They are most of the boxes, but only those at a boundary have walking predecessors
			// outside the band; the other edges are expanded from every source.
			for (int id = 0; id < idCount; id++)
				if (bands[id] >= allowed) distance[id] = 0;
			for (int id : boundaries[band])
				expand(id, 0, nodes, rows, csr, distance, queue);
			for (int id = 0; id < idCount; id++)
				if (bands[id] >= allowed && csr[0][id] != csr[0][id + 1]) expand(id, 0, null, null, csr, distance, queue);
			for (int i = 0; i < leaving.size; i++)
			{
				int id = leaving.to[i];
				if (leaving.cost[i] < distance[id])
				{
					distance[id] = leaving.cost[i];
					queue.add(leaving.cost[i], id);
				}
			}
			for (int cost = 0; cost <= queue.maxCost; cost++)
			{
				// Edges may cost 0, so the bucket can grow while it is drained.
				for (int i = 0; i < queue.size(cost); i++)
				{
					int id = queue.get(cost, i);
					if (distance[id] == cost) expand(id, cost, nodes, rows, csr, distance, queue);
				}
			}
			char[] table = new char[idCount];
			for (int id = 0; id < idCount; id++)
				table[id] = distance[id] == Integer.MAX_VALUE ? UNREACHED
					: (char) Math.min(distance[id], UNREACHED - 1);
			escapes[band] = table;
		}
		return new Table(escapes, System.nanoTime() - started);
	}

	/** Relaxes the predecessors of {@code id}, reached at {@code cost}. */
	private void expand(int id, int cost, int[] nodes, int[] rows, int[][] csr, int[] distance, BucketQueue queue)
	{
		int node = nodes == null ? -1 : nodes[id];
		if (node >= 0)
		{
			int row = rows[id];
			for (int bits = predecessors[id] & 0xFF; bits != 0; bits &= bits - 1)
			{
				int bit = Integer.numberOfTrailingZeros(bits);
				int from = stat.walkSuccessor(node, bit);
				// Bits 7, 0 and 1 step north, 3, 4 and 5 south, within the box and plane.
				int fromRow = bit == 7 || bit <= 1 ? row + 1 : bit >= 3 && bit <= 5 ? row - 1 : row;
				int fromId = fromRow < blockStart[row] || fromRow >= blockEnd[row] ? -1 : idInRow(fromRow, from);
				if (fromId >= 0 && cost + 1 < distance[fromId])
				{
					distance[fromId] = cost + 1;
					queue.add(cost + 1, fromId);
				}
			}
		}
		for (int e = csr[0][id]; e < csr[0][id + 1]; e++)
		{
			int fromId = csr[1][e], candidate = cost + csr[2][e];
			if (candidate < distance[fromId])
			{
				distance[fromId] = candidate;
				queue.add(candidate, fromId);
			}
		}
	}

	private static int lowerBound(RoutingStatic stat, int tile)
	{
		int low = 0, high = stat.searchTileCount();
		while (low < high)
		{
			int middle = (low + high) >>> 1;
			if (Integer.compareUnsigned(stat.searchTile(middle), tile) < 0) low = middle + 1;
			else high = middle;
		}
		return low;
	}

	/**
	 * The local transports an account may take from inside the boxes, in either bank layer, as
	 * {@code origin << 32 | destination} ascending with the least cost of each: the key of a table.
	 */
	static final class TransportSet
	{
		private final long[] keys;
		private final int[] costs;
		private final int hash;

		private TransportSet(long[] keys, int[] costs)
		{
			this.keys = keys;
			this.costs = costs;
			this.hash = 31 * Arrays.hashCode(keys) + Arrays.hashCode(costs);
		}

		static TransportSet of(PreparedRoutingAccount account)
		{
			int capacity = 0;
			for (BankVisitState banked : BankVisitState.values())
				capacity += account.localView(banked).count;
			long[] keys = new long[capacity];
			int count = 0;
			for (BankVisitState banked : BankVisitState.values())
			{
				PreparedRoutingAccount.View view = account.localView(banked);
				for (int i = 0; i < view.count; i++)
				{
					if (view.costs[i] == CostArithmetic.INF || !inBoxes(view.origins[i])) continue;
					keys[count++] = (long) view.origins[i] << 32 | (view.destinations[i] & 0xFFFF_FFFFL);
				}
			}
			long[] sorted = Arrays.copyOf(keys, count);
			Arrays.sort(sorted);
			int unique = 0;
			for (int i = 0; i < count; i++)
				if (unique == 0 || sorted[unique - 1] != sorted[i]) sorted[unique++] = sorted[i];
			long[] result = Arrays.copyOf(sorted, unique);
			int[] costs = new int[unique];
			Arrays.fill(costs, Integer.MAX_VALUE);
			for (BankVisitState banked : BankVisitState.values())
			{
				PreparedRoutingAccount.View view = account.localView(banked);
				for (int i = 0; i < view.count; i++)
				{
					if (view.costs[i] == CostArithmetic.INF || !inBoxes(view.origins[i])) continue;
					int index = Arrays.binarySearch(result,
						(long) view.origins[i] << 32 | (view.destinations[i] & 0xFFFF_FFFFL));
					costs[index] = Math.min(costs[index], view.costs[i]);
				}
			}
			return new TransportSet(result, costs);
		}

		int size()
		{
			return keys.length;
		}

		int origin(int index)
		{
			return (int) (keys[index] >>> 32);
		}

		int destination(int index)
		{
			return (int) keys[index];
		}

		int cost(int index)
		{
			return costs[index];
		}

		@Override
		public boolean equals(Object other)
		{
			return other instanceof TransportSet && Arrays.equals(keys, ((TransportSet) other).keys)
				&& Arrays.equals(costs, ((TransportSet) other).costs);
		}

		@Override
		public int hashCode()
		{
			return hash;
		}
	}

	/** Edges {@code from -> to}, collected for a CSR grouped by {@code to}. */
	private static final class EdgeList
	{
		int[] to = new int[64], from = new int[64], cost = new int[64];
		int size;

		void add(int to, int from, int cost)
		{
			if (size == this.to.length)
			{
				this.to = Arrays.copyOf(this.to, size * 2);
				this.from = Arrays.copyOf(this.from, size * 2);
				this.cost = Arrays.copyOf(this.cost, size * 2);
			}
			this.to[size] = to;
			this.from[size] = from;
			this.cost[size++] = cost;
		}

		/** {offsets by {@code to}, from, cost}. */
		int[][] csr(int idCount)
		{
			int[] offsets = new int[idCount + 1];
			for (int i = 0; i < size; i++)
				offsets[to[i] + 1]++;
			for (int i = 0; i < idCount; i++)
				offsets[i + 1] += offsets[i];
			int[] cursor = Arrays.copyOf(offsets, idCount);
			int[] froms = new int[size], costs = new int[size];
			for (int i = 0; i < size; i++)
			{
				int position = cursor[to[i]]++;
				froms[position] = from[i];
				costs[position] = cost[i];
			}
			return new int[][] {offsets, froms, costs};
		}
	}

	/** Ids by cost, one growable bucket per cost value. */
	private static final class BucketQueue
	{
		private int[][] buckets = new int[256][];
		private int[] sizes = new int[256];
		int maxCost = -1;

		void clear()
		{
			Arrays.fill(sizes, 0);
			maxCost = -1;
		}

		void add(int cost, int id)
		{
			if (cost >= buckets.length)
			{
				int length = Math.max(cost + 1, buckets.length * 2);
				buckets = Arrays.copyOf(buckets, length);
				sizes = Arrays.copyOf(sizes, length);
			}
			int[] bucket = buckets[cost];
			if (bucket == null) bucket = buckets[cost] = new int[64];
			else if (sizes[cost] == bucket.length) bucket = buckets[cost] = Arrays.copyOf(bucket, bucket.length * 2);
			bucket[sizes[cost]++] = id;
			maxCost = Math.max(maxCost, cost);
		}

		int size(int cost)
		{
			return cost < sizes.length ? sizes[cost] : 0;
		}

		int get(int cost, int index)
		{
			return buckets[cost][index];
		}
	}
}
