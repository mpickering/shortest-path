package shortestpath.pathfinder.astar;

import java.util.Arrays;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.BankVisitState;
import shortestpath.pathfinder.CollisionMap;
import lombok.Getter;
import lombok.experimental.Accessors;
import shortestpath.pathfinder.WildernessChecker;

/**
 * Query-lifetime raw seeds and provenance-reduced exact seed scans.
 *
 * <p>Seeds are bucketed by routing component and bank layer. Over a graph that
 * {@link SiteGraph#avoidsWilderness() avoids the Wilderness}, the seeds outside it are also
 * bucketed by {@link WildernessGate piece}: a tile outside the Wilderness in a component that
 * touches it scans only its piece's bucket, since it cannot walk to the rest of the component
 * without entering the Wilderness. Every other tile scans its component's bucket.
 */
public final class PreparedHeuristic
{
	private static final int[] EMPTY = new int[0];

	@Getter @Accessors(fluent = true) private final TargetOverlay overlay;
	private final ReverseLabels reverse;
	private final int[][] seedTiles;
	private final int[][] seedLabels;
	private final int[][] generatorTiles;
	private final int[][] generatorLabels;
	@Getter @Accessors(fluent = true) private final long preparationNanos;
	/** The Wilderness gate when the graph avoids the Wilderness, else {@code null}. */
	private final WildernessGate gate;
	private final int componentCount;

	private PreparedHeuristic(TargetOverlay overlay, ReverseLabels reverse, int[][] seedTiles, int[][] seedLabels,
		int[][] generatorTiles, int[][] generatorLabels, long preparationNanos)
	{
		this.overlay = overlay;
		this.reverse = reverse;
		this.seedTiles = seedTiles;
		this.seedLabels = seedLabels;
		this.generatorTiles = generatorTiles;
		this.generatorLabels = generatorLabels;
		this.preparationNanos = preparationNanos;
		this.gate = overlay.graph().avoidsWilderness() ? overlay.routingStatic().wildernessGate() : null;
		this.componentCount = overlay.routingStatic().routingComponentCount();
	}

	public static PreparedHeuristic prepare(TargetOverlay overlay, ReverseLabels reverse)
	{
		if (reverse == null || reverse.overlay() != overlay)
			throw new IllegalArgumentException("reverse labels belong to another target overlay");
		long started = System.nanoTime();
		RoutingStatic stat = overlay.routingStatic();
		SiteGraph graph = overlay.graph();
		WildernessGate gate = graph.avoidsWilderness() ? stat.wildernessGate() : null;
		int componentCount = stat.routingComponentCount();
		int bucketCount = (componentCount + (gate == null ? 0 : gate.pieceCount())) * 2;
		IntList[] raw = new IntList[bucketCount];
		GeneratorList[] reduced = new GeneratorList[bucketCount];
		for (int site = 0; site < graph.spatialNodeCount(); site++)
		{
			int[] pieces = gate == null ? null : gate.sitePieces(site);
			for (BankVisitState banked : BankVisitState.values())
				addSite(raw, reduced, reverse, overlay, gate, componentCount, site, stat.siteTile(site),
					stat.siteComponents(site), pieces, banked);
		}
		for (int target = 0; target < overlay.targetCount(); target++)
		{
			if (!overlay.synthetic(target))
				continue;
			int[] pieces = gate == null ? null : gate.outsidePieces(overlay.packedTarget(target));
			for (BankVisitState banked : BankVisitState.values())
				addSite(raw, reduced, reverse, overlay, gate, componentCount, overlay.targetNode(target),
					overlay.packedTarget(target), overlay.componentsView(target), pieces, banked);
		}
		int[][] tiles = new int[bucketCount][], labels = new int[bucketCount][];
		int[][] generatorTiles = new int[bucketCount][], generatorLabels = new int[bucketCount][];
		for (int i = 0; i < bucketCount; i++)
		{
			tiles[i] = raw[i] == null ? EMPTY : raw[i].tiles();
			labels[i] = raw[i] == null ? EMPTY : raw[i].labels();
			generatorTiles[i] = reduced[i] == null ? EMPTY : reduced[i].tiles();
			generatorLabels[i] = reduced[i] == null ? EMPTY : reduced[i].labels();
		}
		return new PreparedHeuristic(overlay, reverse, tiles, labels, generatorTiles, generatorLabels,
			System.nanoTime() - started);
	}


	public ReverseLabels reverseLabels()
	{
		return reverse;
	}
	public int seedCount(int component, BankVisitState banked)
	{
		return seedTiles[key(component, banked)].length;
	}
	public int seedTile(int component, BankVisitState banked, int index)
	{
		return seedTiles[key(component, banked)][index];
	}
	public int seedLabel(int component, BankVisitState banked, int index)
	{
		return seedLabels[key(component, banked)][index];
	}
	public int generatorCount(int component, BankVisitState banked)
	{
		return generatorTiles[key(component, banked)].length;
	}
	public int generatorTile(int component, BankVisitState banked, int index)
	{
		return generatorTiles[key(component, banked)][index];
	}
	public int generatorLabel(int component, BankVisitState banked, int index)
	{
		return generatorLabels[key(component, banked)][index];
	}
	public int rawSeedCount()
	{
		return total(seedTiles);
	}
	public int generatorCount()
	{
		return total(generatorTiles);
	}
	public double generatorRatio()
	{
		int raw = rawSeedCount();
		return raw == 0 ? 0 : (double) generatorCount() / raw;
	}


	public int estimate(int packed, BankVisitState banked)
	{
		return estimate(packed, banked, overlay.routingStatic().attachments(packed, overlay.collision()));
	}

	public int estimate(TargetOverlay candidate, int packed, BankVisitState banked)
	{
		candidate.requireCompatible(overlay.graph());
		if (candidate != overlay)
			throw new IllegalArgumentException("heuristic belongs to another target query");
		return estimate(packed, banked);
	}

	/** Evaluate against the collision snapshot used to create the target overlay. */
	public int estimate(CollisionMap collision, int packed, BankVisitState banked)
	{
		if (collision == null)
			throw new NullPointerException("collision");
		return estimate(packed, banked, overlay.routingStatic().attachments(packed, collision));
	}

	public int estimate(int packed, BankVisitState banked, int[] components)
	{
		return estimateTable(packed, banked, components, generatorTiles, generatorLabels, true);
	}

	public int estimateBaseNode(int packed, BankVisitState banked, int component)
	{
		return estimateBaseNode(packed, banked, component, overlay.routingStatic().searchIndex(packed));
	}

	/** As {@link #estimateBaseNode(int, BankVisitState, int)} for search tile {@code node}. */
	public int estimateBaseNode(int packed, BankVisitState banked, int component, int node)
	{
		int best = targetLabel(packed, banked);
		int site = overlay.routingStatic().siteIndex(packed);
		if (site >= 0) best = Math.min(best, reverse.label(site, banked));
		int key = gate == null || gate.tileInWilderness(node) || !gate.gatedComponent(component)
			? componentKey(component, banked) : pieceKey(componentCount, gate.tilePiece(node), banked);
		return Math.min(best, estimateBucket(packed, key, generatorTiles, generatorLabels, true));
	}

	/** Raw seed-scan oracle retained for reduced-generator differential checks. */
	int estimateRaw(int packed, BankVisitState banked, int[] components)
	{
		return estimateTable(packed, banked, components, seedTiles, seedLabels, false);
	}

	int estimateGenerators(int packed, BankVisitState banked, int[] components)
	{
		return estimateTable(packed, banked, components, generatorTiles, generatorLabels, false);
	}

	private int estimateTable(int packed, BankVisitState banked, int[] components, int[][] tiles, int[][] labels,
		boolean fallbackToRaw)
	{
		RoutingStatic stat = overlay.routingStatic();
		int best = targetLabel(packed, banked);
		int site = stat.siteIndex(packed);
		if (site >= 0)
			best = Math.min(best, reverse.label(site, banked));
		boolean inside = gate == null || WildernessChecker.isInWilderness(packed);
		for (int component : components)
		{
			if (inside || !gate.gatedComponent(component))
				best = Math.min(best, estimateBucket(packed, componentKey(component, banked), tiles, labels,
					fallbackToRaw));
		}
		if (!inside)
		{
			for (int piece : gate.outsidePieces(packed))
			{
				if (gate.gatedPiece(piece))
					best = Math.min(best, estimateBucket(packed, pieceKey(componentCount, piece, banked), tiles,
						labels, fallbackToRaw));
			}
		}
		return best;
	}

	private int estimateBucket(int packed, int key, int[][] tiles, int[][] labels, boolean fallbackToRaw)
	{
		int[] bucketTiles = tiles[key];
		int[] bucketLabels = labels[key];
		if (fallbackToRaw && bucketTiles.length == 0)
		{
			bucketTiles = seedTiles[key];
			bucketLabels = seedLabels[key];
		}
		int best = CostArithmetic.INF;
		for (int i = 0; i < bucketTiles.length; i++)
			best = Math.min(best, CostArithmetic.add(bucketLabels[i],
				WorldPointUtil.distanceBetween(packed, bucketTiles[i])));
		return best;
	}

	/**
	 * Adds a node's seed to the bucket of each of its components and, with a gate, of each of its
	 * gated pieces.
	 */
	private static void addSite(IntList[] raw, GeneratorList[] reduced, ReverseLabels reverse,
		TargetOverlay overlay, WildernessGate gate, int componentCount, int node, int packed, int[] components,
		int[] pieces, BankVisitState banked)
	{
		int state = SiteGraph.stateId(node, banked);
		int distance = reverse.label(node, banked);
		if (distance == CostArithmetic.INF)
			return;
		for (int component : components)
			addSeed(raw, reduced, reverse, overlay, gate, componentKey(component, banked), node, packed, state,
				distance, component, -1);
		if (gate == null)
			return;
		for (int piece : pieces)
		{
			if (gate.gatedPiece(piece))
				addSeed(raw, reduced, reverse, overlay, gate, pieceKey(componentCount, piece, banked), node, packed,
					state, distance, -1, piece);
		}
	}

	/** Adds one seed to bucket {@code key}, which is either {@code component}'s or {@code piece}'s. */
	private static void addSeed(IntList[] raw, GeneratorList[] reduced, ReverseLabels reverse, TargetOverlay overlay,
		WildernessGate gate, int key, int node, int packed, int state, int distance, int component, int piece)
	{
		add(raw, key, packed, distance);
		if (!reverse.hasProvenance())
			return;
		int origin = reverse.generatorOrigin(state);
		int generatorState = validOrigin(reverse, overlay, gate, packed, component, piece, state, origin)
			? origin : state;
		int generatorNode = generatorState / 2;
		int generatorTile = overlay.nodeTile(generatorNode);
		int generatorLabel = reverse.label(generatorNode,
			(generatorState & 1) != 0 ? BankVisitState.BANKED : BankVisitState.CARRIED);
		if (reduced[key] == null)
			reduced[key] = new GeneratorList();
		reduced[key].add(generatorState, generatorTile, generatorLabel);
	}

	/**
	 * Whether {@code origin} may stand in for the seed in its bucket: it must be in the same layer,
	 * reach the seed along a straight Chebyshev geodesic, and be in the bucket itself, attached to
	 * {@code component} or (for a piece bucket) in {@code piece}, so every tile that scans the
	 * bucket can use it.
	 */
	private static boolean validOrigin(ReverseLabels reverse, TargetOverlay overlay, WildernessGate gate,
		int packed, int component, int piece, int state, int origin)
	{
		if (origin < 0 || (origin & 1) != (state & 1))
			return false;
		int originNode = origin / 2;
		if (originNode >= overlay.graph().spatialNodeCount() && overlay.syntheticTarget(originNode) < 0)
			return false;
		int originTile = overlay.nodeTile(originNode);
		int direct = WorldPointUtil.distanceBetween(originTile, packed);
		if (direct == CostArithmetic.INF)
			return false;
		if (reverse.generatorWeightDoubled(state) == CostArithmetic.INF
			|| reverse.doubledLabel(state) != CostArithmetic.add(reverse.generatorWeightDoubled(state), CostArithmetic.twice(direct)))
			return false;
		if (piece >= 0)
		{
			int target = overlay.syntheticTarget(originNode);
			return contains(target >= 0 ? gate.outsidePieces(originTile) : gate.sitePieces(originNode), piece);
		}
		return contains(overlay.nodeComponents(originNode), component);
	}

	private int targetLabel(int packed, BankVisitState banked)
	{
		int target = overlay.targetIndex(packed);
		return target < 0 ? CostArithmetic.INF : reverse.targetLabel(target, banked);
	}

	private static int key(int component, BankVisitState banked)
	{
		return componentKey(component, banked);
	}

	private static int componentKey(int component, BankVisitState banked)
	{
		return component * 2 + (banked == BankVisitState.BANKED ? 1 : 0);
	}

	/** Piece buckets follow every component bucket. */
	private static int pieceKey(int componentCount, int piece, BankVisitState banked)
	{
		return componentKey(componentCount + piece, banked);
	}

	private static void add(IntList[] raw, int key, int packed, int label)
	{
		if (raw[key] == null)
			raw[key] = new IntList();
		raw[key].add(packed, label);
	}

	private static boolean contains(int[] values, int target)
	{
		for (int value : values)
			if (value == target)
				return true;
		return false;
	}

	private static int total(int[][] values)
	{
		int total = 0;
		for (int[] value : values)
			total += value.length;
		return total;
	}

	private static final class IntList
	{
		private int[] tiles = new int[8], labels = new int[8];
		private int size;

		void add(int tile, int label)
		{
			if (size == tiles.length)
			{
				tiles = Arrays.copyOf(tiles, size * 2);
				labels = Arrays.copyOf(labels, size * 2);
			}
			tiles[size] = tile;
			labels[size++] = label;
		}
		int[] tiles()
		{
			return Arrays.copyOf(tiles, size);
		}
		int[] labels()
		{
			return Arrays.copyOf(labels, size);
		}
	}

	private static final class GeneratorList
	{
		private int[] ids = new int[8], tiles = new int[8], labels = new int[8];
		private int size;

		void add(int id, int tile, int label)
		{
			for (int i = 0; i < size; i++)
				if (ids[i] == id)
					return;
			if (size == ids.length)
			{
				ids = Arrays.copyOf(ids, size * 2);
				tiles = Arrays.copyOf(tiles, size * 2);
				labels = Arrays.copyOf(labels, size * 2);
			}
			ids[size] = id;
			tiles[size] = tile;
			labels[size++] = label;
		}
		int[] tiles()
		{
			sort();
			return Arrays.copyOf(tiles, size);
		}
		int[] labels()
		{
			sort();
			return Arrays.copyOf(labels, size);
		}
		private void sort()
		{
			for (int i = 1; i < size; i++)
			{
				int j = i;
				while (j > 0 && ids[j] < ids[j - 1])
				{
					int id = ids[j];
					ids[j] = ids[j - 1];
					ids[j - 1] = id;
					int tile = tiles[j];
					tiles[j] = tiles[j - 1];
					tiles[j - 1] = tile;
					int label = labels[j];
					labels[j] = labels[j - 1];
					labels[j - 1] = label;
					j--;
				}
			}
		}
	}
}
