package shortestpath.pathfinder.astar;

import java.util.Arrays;
import java.util.Comparator;
import shortestpath.WorldPointUtil;

/**
 * Builds the sparse same-component walking network used by {@link ReverseLabels#computeSparse}.
 *
 * <p>Within each routing component, walking distance between two sites on the same plane is at
 * least their Chebyshev distance. Instead of connecting every pair of sites, the construction
 * works in rotated Manhattan coordinates {@code a = x + y}, {@code b = x - y}, where Manhattan
 * distance is exactly twice Chebyshev distance, and adds Steiner vertices along balanced
 * coordinate splits so that the shortest path between any two sites equals that distance.
 * Every edge weight is therefore doubled walking distance; {@link ReverseLabels#computeSparse}
 * halves it once at the end.
 */
public final class SparseWalkingNetworkBuilder
{
	private SparseWalkingNetworkBuilder()
	{
	}

	/** Section G in flat, undirected CSR form (adjacencyCount == 2 * undirectedEdgeCount). */
	public static final class Result
	{
		public final int originalCount;
		public final int vertexCount;
		public final int steinerCount;
		public final int undirectedEdgeCount;
		public final int[] offsets;
		public final int[] destinations;
		public final int[] weights;
		/**
		 * For a Wilderness-gated network, whether adjacency entry {@code i} is a spoke from a Steiner
		 * vertex into a site outside the Wilderness in a component that has sites on both sides.
		 * Relaxing it in the reverse search would let that site walk into the tree, and from there
		 * into the Wilderness, so the gated reverse search skips it. {@code null} when ungated.
		 */
		public final boolean[] gatedEntries;

		private Result(int originalCount, int vertexCount, int steinerCount, int undirectedEdgeCount, int[] offsets,
			int[] destinations, int[] weights, boolean[] gatedEntries)
		{
			this.originalCount = originalCount;
			this.vertexCount = vertexCount;
			this.steinerCount = steinerCount;
			this.undirectedEdgeCount = undirectedEdgeCount;
			this.offsets = offsets;
			this.destinations = destinations;
			this.weights = weights;
			this.gatedEntries = gatedEntries;
		}

		public int adjacencyCount()
		{
			return destinations.length;
		}
	}

	/**
	 * Builds the network from the static sites and their routing-component attachments:
	 *
	 * <ul>
	 *   <li>{@code siteTiles[s]}: packed tile of site {@code s}, ascending.</li>
	 *   <li>{@code siteComponentOffsets}, {@code siteComponentIds}: each site's ascending
	 *       routing-component attachments. A site attached to several components contributes
	 *       one point to each component, all sharing the same site vertex.</li>
	 * </ul>
	 *
	 * <p>Vertices {@code 0..siteTiles.length-1} are the sites, followed by Steiner vertices. A
	 * site with no component attachment is an isolated vertex.
	 *
	 * @param siteTiles              packed tiles, ascending unsigned packed order, one per site
	 * @param siteComponentOffsets   length {@code siteTiles.length + 1}, CSR offsets into {@code siteComponentIds}
	 * @param siteComponentIds       routing-component ids per site, ascending within each site's range
	 * @return the flattened CSR sparse walking network (section G)
	 */
	public static Result build(int[] siteTiles, int[] siteComponentOffsets, int[] siteComponentIds)
	{
		return build(siteTiles, siteComponentOffsets, siteComponentIds, null);
	}

	/**
	 * As {@link #build(int[], int[], int[])}, but each {@link ExactWalks#isExact exact} component's
	 * sites are joined by its exact walking edges instead of a tree.
	 */
	static Result build(int[] siteTiles, int[] siteComponentOffsets, int[] siteComponentIds, ExactWalks exactWalks)
	{
		int originalCount = siteTiles.length;
		int maxComponentId = -1;
		for (int cid : siteComponentIds)
		{
			if (cid > maxComponentId)
			{
				maxComponentId = cid;
			}
		}
		EdgeList edges = new EdgeList();
		boolean[] trees = treeGroups(maxComponentId + 1, exactWalks, null);
		int vertexCount = buildGroups(originalCount, siteTiles, siteComponentOffsets, siteComponentIds,
			maxComponentId + 1, trees, null, null, edges);
		addExactEdges(trees, exactWalks, edges);
		int steinerCount = vertexCount - originalCount;
		return undirectedAdjacency(originalCount, vertexCount, steinerCount, edges, false);
	}

	/**
	 * The network for searches that may not enter the Wilderness from outside it.
	 *
	 * <p>A <em>piece</em> is a maximal set of non-Wilderness tiles of one routing component that
	 * walk to each other without entering the Wilderness; a site outside the Wilderness belongs to
	 * the pieces it can walk into. Such a site may only walk to sites of its own pieces, while a
	 * site inside the Wilderness may walk to any site of its component (leaving the Wilderness is
	 * allowed, and treating every site as reachable keeps the distances lower bounds).
	 *
	 * <p>So in each {@code gatedComponents} component, the full tree marks the spokes into its
	 * non-Wilderness sites as {@link Result#gatedEntries gated}: a gated reverse search never
	 * relaxes them, so those sites never walk out through the full tree. Each {@code pieceTrees}
	 * piece then gets a tree of its own over its sites, appended after every component tree.
	 * Other components are built exactly as by {@link #build(int[], int[], int[])}.
	 *
	 * @param gatedComponents  per routing component, whether to gate its full tree
	 * @param siteInWilderness per site, whether its tile is inside the Wilderness
	 * @param sitePieceOffsets length {@code siteTiles.length + 1}, CSR offsets into {@code sitePieceIds}
	 * @param sitePieceIds     each non-Wilderness site's pieces, ascending
	 * @param pieceTrees       per piece, whether to build its tree
	 */
	public static Result buildWildernessGated(int[] siteTiles, int[] siteComponentOffsets, int[] siteComponentIds,
		boolean[] gatedComponents, boolean[] siteInWilderness, int[] sitePieceOffsets, int[] sitePieceIds,
		boolean[] pieceTrees)
	{
		return buildWildernessGated(siteTiles, siteComponentOffsets, siteComponentIds, gatedComponents,
			siteInWilderness, sitePieceOffsets, sitePieceIds, pieceTrees, null);
	}

	/** As above; exact components that are not gated keep their exact edges instead of a tree. */
	static Result buildWildernessGated(int[] siteTiles, int[] siteComponentOffsets, int[] siteComponentIds,
		boolean[] gatedComponents, boolean[] siteInWilderness, int[] sitePieceOffsets, int[] sitePieceIds,
		boolean[] pieceTrees, ExactWalks exactWalks)
	{
		EdgeList edges = new EdgeList();
		boolean[] trees = treeGroups(gatedComponents.length, exactWalks, gatedComponents);
		int next = buildGroups(siteTiles.length, siteTiles, siteComponentOffsets, siteComponentIds,
			gatedComponents.length, trees, gatedComponents, siteInWilderness, edges);
		addExactEdges(trees, exactWalks, edges);
		next = buildGroups(next, siteTiles, sitePieceOffsets, sitePieceIds, pieceTrees.length, pieceTrees, null,
			null, edges);
		return undirectedAdjacency(siteTiles.length, next, next - siteTiles.length, edges, true);
	}

	/**
	 * Which components get a tree: every one without exact walks, and every gated one (its walks
	 * may cross the Wilderness). {@code null}, meaning all of them, without exact walks.
	 */
	private static boolean[] treeGroups(int groupCount, ExactWalks exactWalks, boolean[] gated)
	{
		if (exactWalks == null) return null;
		boolean[] trees = new boolean[groupCount];
		for (int g = 0; g < groupCount; g++)
			trees[g] = !exactWalks.isExact(g) || gated != null && gated[g];
		return trees;
	}

	/** The exact edges of every component without a tree, doubled like every other weight. */
	private static void addExactEdges(boolean[] trees, ExactWalks exactWalks, EdgeList edges)
	{
		if (trees == null) return;
		for (int g = 0; g < trees.length; g++)
		{
			if (trees[g]) continue;
			int[] triples = exactWalks.edges(g);
			for (int i = 0; i < triples.length; i += 3)
				edges.add(triples[i], triples[i + 1], CostArithmetic.twice(triples[i + 2]), false);
		}
	}

	/**
	 * Builds one tree per group (routing component or piece) that owns at least one site, in
	 * ascending group order, numbering Steiner vertices from {@code next}.
	 *
	 * @param build            which groups to build, or {@code null} for all of them
	 * @param gated            which groups gate the spokes into their non-Wilderness sites, or
	 *                         {@code null} for none
	 * @param siteInWilderness per site, whether it is inside the Wilderness; needed with {@code gated}
	 */
	private static int buildGroups(int next, int[] siteTiles, int[] siteGroupOffsets, int[] siteGroupIds,
		int groupCount, boolean[] build, boolean[] gated, boolean[] siteInWilderness, EdgeList edges)
	{
		// Bucket sites by group id; only groups that own at least one site are processed,
		// in ascending id order.
		int[] bucketCounts = new int[groupCount];
		for (int gid : siteGroupIds)
		{
			bucketCounts[gid]++;
		}
		int[] bucketOffsets = new int[groupCount + 1];
		for (int g = 0; g < groupCount; g++)
		{
			bucketOffsets[g + 1] = bucketOffsets[g] + bucketCounts[g];
		}
		int[] bucketSites = new int[siteGroupIds.length];
		int[] cursor = bucketOffsets.clone();
		for (int site = 0; site < siteTiles.length; site++)
		{
			for (int p = siteGroupOffsets[site]; p < siteGroupOffsets[site + 1]; p++)
			{
				int gid = siteGroupIds[p];
				bucketSites[cursor[gid]++] = site;
			}
		}
		for (int gid = 0; gid < groupCount; gid++)
		{
			int start = bucketOffsets[gid];
			int end = bucketOffsets[gid + 1];
			if (end == start || build != null && !build[gid])
			{
				continue;
			}
			boolean gateGroup = gated != null && gated[gid];
			Point[] points = new Point[end - start];
			for (int i = start; i < end; i++)
			{
				int siteIndex = bucketSites[i];
				int tile = siteTiles[siteIndex];
				int x = WorldPointUtil.unpackWorldX(tile);
				int y = WorldPointUtil.unpackWorldY(tile);
				points[i - start] = new Point(siteIndex, x + y, x - y, gateGroup && !siteInWilderness[siteIndex]);
			}
			Arrays.sort(points, BY_A_B_ORIGINAL);
			next = buildComponent(next, points, edges);
		}
		return next;
	}

	private enum Axis
	{
		A, B
	}

	private static final class Point
	{
		final int original;
		final int a;
		final int b;
		/** Whether the spokes from Steiner vertices into this point are gated. */
		final boolean gated;

		Point(int original, int a, int b, boolean gated)
		{
			this.original = original;
			this.a = a;
			this.b = b;
			this.gated = gated;
		}
	}

	private static final Comparator<Point> BY_A_B_ORIGINAL = (x, y) ->
	{
		if (x.a != y.a)
		{
			return Integer.compare(x.a, y.a);
		}
		if (x.b != y.b)
		{
			return Integer.compare(x.b, y.b);
		}
		return Integer.compare(x.original, y.original);
	};

	private static int coordOf(Axis axis, Point p)
	{
		return axis == Axis.A ? p.a : p.b;
	}

	private static int otherCoordOf(Axis axis, Point p)
	{
		return axis == Axis.A ? p.b : p.a;
	}

	private static Comparator<Point> axisComparator(Axis axis)
	{
		return (x, y) ->
		{
			int cx = coordOf(axis, x);
			int cy = coordOf(axis, y);
			if (cx != cy)
			{
				return Integer.compare(cx, cy);
			}
			return BY_A_B_ORIGINAL.compare(x, y);
		};
	}

	/**
	 * Recursively splits one component's points. {@code pts} arrives in inherited order (the
	 * global {@code a,b,original} sort at the top level, or a slice of the parent split's
	 * axis-sorted list below it); that order, not any re-sort here, decides which Steiner vertex
	 * id each point receives in {@link #addProjectionLevel}, keeping numbering deterministic.
	 */
	private static int buildComponent(int next, Point[] pts, EdgeList edges)
	{
		if (pts.length <= 1)
		{
			return next;
		}
		int a0 = pts[0].a;
		int b0 = pts[0].b;
		boolean allSameA = true;
		boolean allSameB = true;
		for (Point p : pts)
		{
			if (p.a != a0)
			{
				allSameA = false;
			}
			if (p.b != b0)
			{
				allSameB = false;
			}
		}
		if (allSameA && allSameB)
		{
			// Coincident points are chained with zero-weight edges; pts is already sorted by `original`
			// here, since a/b are constant and the inherited order derives from a sort
			// keyed on (axis-or-a-b, a, b, original).
			for (int i = 0; i + 1 < pts.length; i++)
			{
				// Coincident points share x and y, so they are on the same side of the Wilderness line.
				edges.add(pts[i].original, pts[i + 1].original, 0, false);
			}
			return next;
		}
		Axis axis = allSameA ? Axis.B : Axis.A;
		return splitOn(axis, next, pts, edges);
	}

	/** Splits {@code pts} at the most balanced gap along {@code axis} and recurses on both halves. */
	private static int splitOn(Axis axis, int next, Point[] pts, EdgeList edges)
	{
		Point[] sorted = pts.clone();
		Arrays.sort(sorted, axisComparator(axis));
		int split = balancedGapSplit(axis, sorted);
		Point[] left = Arrays.copyOfRange(sorted, 0, split);
		Point[] right = Arrays.copyOfRange(sorted, split, sorted.length);
		int splitCoordinate = Math.floorDiv(
			coordOf(axis, sorted[split - 1]) + coordOf(axis, sorted[split]), 2);
		int afterProjection = addProjectionLevel(axis, splitCoordinate, next, pts, edges);
		int afterLeft = buildComponent(afterProjection, left, edges);
		return buildComponent(afterLeft, right, edges);
	}

	/**
	 * Returns the split index {@code i}: {@code sorted[0..i)} and {@code sorted[i..)} lie on
	 * either side of the chosen gap; the caller
	 * derives the Steiner splitter coordinate from the pair straddling that index.
	 */
	private static int balancedGapSplit(Axis axis, Point[] sorted)
	{
		int n = sorted.length;
		int preferred = n / 2;
		int bestIndex = -1;
		int bestDiff = Integer.MAX_VALUE;
		for (int i = 1; i < n; i++)
		{
			if (coordOf(axis, sorted[i - 1]) < coordOf(axis, sorted[i]))
			{
				int diff = Math.abs(i - preferred);
				if (diff < bestDiff)
				{
					bestDiff = diff;
					bestIndex = i;
				}
			}
		}
		if (bestIndex < 0)
		{
			throw new IllegalStateException("sparse walking split without a strict coordinate gap");
		}
		return bestIndex;
	}

	/**
	 * Allocates one fresh
	 * Steiner vertex per point in {@code pts} (in {@code pts}'s own order), a spoke edge
	 * from each point to its projection vertex, then chains the projections in ascending
	 * order of the other axis coordinate (ties by original site index).
	 */
	private static int addProjectionLevel(Axis axis, int splitCoordinate, int firstProjection, Point[] pts,
		EdgeList edges)
	{
		int count = pts.length;
		int[] steinerIds = new int[count];
		for (int i = 0; i < count; i++)
		{
			steinerIds[i] = firstProjection + i;
			edges.add(pts[i].original, steinerIds[i], Math.abs(coordOf(axis, pts[i]) - splitCoordinate), pts[i].gated);
		}
		Integer[] order = new Integer[count];
		for (int i = 0; i < count; i++)
		{
			order[i] = i;
		}
		Arrays.sort(order, (x, y) ->
		{
			int ox = otherCoordOf(axis, pts[x]);
			int oy = otherCoordOf(axis, pts[y]);
			if (ox != oy)
			{
				return Integer.compare(ox, oy);
			}
			return Integer.compare(pts[x].original, pts[y].original);
		});
		for (int i = 0; i + 1 < count; i++)
		{
			int leftIndex = order[i];
			int rightIndex = order[i + 1];
			int weight = Math.abs(otherCoordOf(axis, pts[leftIndex]) - otherCoordOf(axis, pts[rightIndex]));
			edges.add(steinerIds[leftIndex], steinerIds[rightIndex], weight, false);
		}
		return firstProjection + count;
	}

	/** Growable primitive (from, to, weight) triples; final counts are small primitive arrays. */
	private static final class EdgeList
	{
		private int[] from = new int[16];
		private int[] to = new int[16];
		private int[] weight = new int[16];
		/** Whether the direction {@code to -> from} is gated (a spoke into a gated site). */
		private boolean[] gatedReverse = new boolean[16];
		private int size;

		void add(int a, int b, int w, boolean gated)
		{
			if (size == from.length)
			{
				int capacity = size * 2;
				from = Arrays.copyOf(from, capacity);
				to = Arrays.copyOf(to, capacity);
				weight = Arrays.copyOf(weight, capacity);
				gatedReverse = Arrays.copyOf(gatedReverse, capacity);
			}
			from[size] = a;
			to[size] = b;
			weight[size] = w;
			gatedReverse[size] = gated;
			size++;
		}
	}

	/**
	 * Materialises both directions of every edge as CSR adjacency. The order of entries within
	 * one vertex's range does not affect any distance.
	 */
	private static Result undirectedAdjacency(int originalCount, int vertexCount, int steinerCount, EdgeList edges,
		boolean wildernessGated)
	{
		int[] counts = new int[vertexCount];
		for (int i = 0; i < edges.size; i++)
		{
			counts[edges.from[i]]++;
			counts[edges.to[i]]++;
		}
		int[] offsets = new int[vertexCount + 1];
		for (int v = 0; v < vertexCount; v++)
		{
			offsets[v + 1] = offsets[v] + counts[v];
		}
		int[] destinations = new int[offsets[vertexCount]];
		int[] weights = new int[offsets[vertexCount]];
		boolean[] gatedEntries = wildernessGated ? new boolean[offsets[vertexCount]] : null;
		int[] cursor = offsets.clone();
		for (int i = 0; i < edges.size; i++)
		{
			int a = edges.from[i];
			int b = edges.to[i];
			int w = edges.weight[i];
			destinations[cursor[a]] = b;
			weights[cursor[a]] = w;
			cursor[a]++;
			destinations[cursor[b]] = a;
			weights[cursor[b]] = w;
			if (gatedEntries != null)
			{
				gatedEntries[cursor[b]] = edges.gatedReverse[i];
			}
			cursor[b]++;
		}
		return new Result(originalCount, vertexCount, steinerCount, edges.size, offsets, destinations, weights,
			gatedEntries);
	}
}
