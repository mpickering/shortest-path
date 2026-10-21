package shortestpath.pathfinder.astar;

import java.util.Arrays;
import java.util.BitSet;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.WildernessChecker;

/**
 * Static data for searches that may not enter the Wilderness from outside it, built from a
 * {@link RoutingStatic} on first use.
 *
 * <p>A <em>piece</em> is a maximal set of non-Wilderness search tiles of one routing component that
 * walk to each other without entering the Wilderness. Walking out of a piece within its component
 * enters the Wilderness, so a tile or site outside the Wilderness can only walk to its own piece
 * (or leave the component through a site). Tiles and sites inside the Wilderness have no piece and
 * may walk anywhere in their component.
 */
final class WildernessGate
{
	private static final int[] EMPTY = new int[0];
	// Bit b of a walking mask steps by (DX[b], DY[b]), as in AStarForwardSearch.walkBase.
	private static final int[] DX = {0, 1, 1, 1, 0, -1, -1, -1};
	private static final int[] DY = {1, 1, 0, -1, -1, -1, 0, 1};

	private final RoutingStatic stat;
	private final boolean[] siteInWilderness;
	private final BitSet tileInWilderness;
	private final int[] tilePieces;
	private final int pieceCount;
	private final int[] sitePieceOffsets, sitePieceIds;
	private final boolean[] gatedComponents;
	private final int[] pieceComponents;
	private final int[] pieceSiteOffsets, pieceSiteIds;
	private final SparseNetwork network;

	private WildernessGate(RoutingStatic stat, boolean[] siteInWilderness, BitSet tileInWilderness, int[] tilePieces,
		int pieceCount, int[] sitePieceOffsets, int[] sitePieceIds, boolean[] gatedComponents, int[] pieceComponents,
		SparseNetwork network)
	{
		this.gatedComponents = gatedComponents;
		this.pieceComponents = pieceComponents;
		this.pieceSiteOffsets = new int[pieceCount + 1];
		for (int piece : sitePieceIds)
			pieceSiteOffsets[piece + 1]++;
		for (int piece = 0; piece < pieceCount; piece++)
			pieceSiteOffsets[piece + 1] += pieceSiteOffsets[piece];
		this.pieceSiteIds = new int[sitePieceIds.length];
		int[] cursor = Arrays.copyOf(pieceSiteOffsets, pieceCount);
		for (int site = 0; site < siteInWilderness.length; site++)
			for (int i = sitePieceOffsets[site]; i < sitePieceOffsets[site + 1]; i++)
				pieceSiteIds[cursor[sitePieceIds[i]]++] = site;
		this.stat = stat;
		this.siteInWilderness = siteInWilderness;
		this.tileInWilderness = tileInWilderness;
		this.tilePieces = tilePieces;
		this.pieceCount = pieceCount;
		this.sitePieceOffsets = sitePieceOffsets;
		this.sitePieceIds = sitePieceIds;
		this.network = network;
	}

	static WildernessGate build(RoutingStatic stat)
	{
		int tileCount = stat.searchTileCount();
		BitSet tileInWilderness = new BitSet(tileCount);
		for (int node = 0; node < tileCount; node++)
			if (WildernessChecker.isInWilderness(stat.searchTile(node)))
				tileInWilderness.set(node);

		// Pieces: union the steps between non-Wilderness tiles of one component, in either
		// direction. Merging more than the exact pieces would only loosen the bounds.
		int[] parent = new int[tileCount];
		for (int node = 0; node < tileCount; node++)
			parent[node] = node;
		for (int node = 0; node < tileCount; node++)
		{
			if (tileInWilderness.get(node)) continue;
			int mask = stat.walkingMask(node);
			for (int bit = 0; bit < 8; bit++)
			{
				if ((mask & (1 << bit)) == 0) continue;
				int next = stat.walkSuccessor(node, bit);
				if (next < 0 || next >= tileCount || tileInWilderness.get(next)
					|| stat.routingComponent(next) != stat.routingComponent(node)) continue;
				union(parent, node, next);
			}
		}
		int[] tilePieces = new int[tileCount];
		int[] pieceOfRoot = new int[tileCount];
		Arrays.fill(pieceOfRoot, -1);
		int pieceCount = 0;
		for (int node = 0; node < tileCount; node++)
		{
			if (tileInWilderness.get(node))
			{
				tilePieces[node] = -1;
				continue;
			}
			int root = find(parent, node);
			if (pieceOfRoot[root] < 0) pieceOfRoot[root] = pieceCount++;
			tilePieces[node] = pieceOfRoot[root];
		}
		parent = null;
		pieceOfRoot = null;

		int siteCount = stat.siteCount();
		boolean[] siteInWilderness = new boolean[siteCount];
		int[] sitePieceOffsets = new int[siteCount + 1];
		int[][] sitePieces = new int[siteCount][];
		for (int site = 0; site < siteCount; site++)
		{
			int tile = stat.siteTile(site);
			siteInWilderness[site] = WildernessChecker.isInWilderness(tile);
			sitePieces[site] = outsidePieces(stat, tileInWilderness, tilePieces, tile);
			sitePieceOffsets[site + 1] = sitePieceOffsets[site] + sitePieces[site].length;
		}
		int[] sitePieceIds = new int[sitePieceOffsets[siteCount]];
		for (int site = 0; site < siteCount; site++)
			System.arraycopy(sitePieces[site], 0, sitePieceIds, sitePieceOffsets[site], sitePieces[site].length);

		// A component needs gating when walking in it can enter the Wilderness: it has a tile or
		// a site inside, or several pieces. Every piece of such a component gets its own tree.
		int componentCount = stat.routingComponentCount();
		boolean[] gatedComponents = new boolean[componentCount];
		int[] pieceComponent = new int[pieceCount];
		int[] firstPiece = new int[componentCount];
		Arrays.fill(firstPiece, -1);
		for (int node = 0; node < tileCount; node++)
		{
			int component = stat.routingComponent(node);
			int piece = tilePieces[node];
			if (piece < 0)
			{
				gatedComponents[component] = true;
				continue;
			}
			pieceComponent[piece] = component;
			if (firstPiece[component] < 0) firstPiece[component] = piece;
			else if (firstPiece[component] != piece) gatedComponents[component] = true;
		}
		for (int site = 0; site < siteCount; site++)
		{
			if (!siteInWilderness[site]) continue;
			for (int component : stat.siteComponents(site))
				gatedComponents[component] = true;
		}
		boolean[] pieceTrees = new boolean[pieceCount];
		for (int piece = 0; piece < pieceCount; piece++)
			pieceTrees[piece] = gatedComponents[pieceComponent[piece]];

		SparseWalkingNetworkBuilder.Result sparse = SparseWalkingNetworkBuilder.buildWildernessGated(
			stat.siteTilesView(), stat.siteComponentOffsetsView(), stat.siteComponentIdsView(), gatedComponents,
			siteInWilderness, sitePieceOffsets, sitePieceIds, pieceTrees);
		SparseNetwork network = new SparseNetwork(sparse.originalCount, sparse.steinerCount, sparse.offsets,
			sparse.destinations, sparse.weights, sparse.gatedEntries);
		return new WildernessGate(stat, siteInWilderness, tileInWilderness, tilePieces, pieceCount,
			sitePieceOffsets, sitePieceIds, gatedComponents, pieceComponent, network);
	}

	SparseNetwork network()
	{
		return network;
	}

	boolean siteInWilderness(int site)
	{
		return siteInWilderness[site];
	}

	/** Whether search tile {@code node} is inside the Wilderness. */
	boolean tileInWilderness(int node)
	{
		return tileInWilderness.get(node);
	}

	/** The piece of search tile {@code node}, or a negative value inside the Wilderness. */
	int tilePiece(int node)
	{
		return tilePieces[node];
	}

	int pieceCount()
	{
		return pieceCount;
	}

	/** The pieces site {@code site} can walk into; empty inside the Wilderness. */
	int[] sitePieces(int site)
	{
		return Arrays.copyOfRange(sitePieceIds, sitePieceOffsets[site], sitePieceOffsets[site + 1]);
	}

	/**
	 * Whether walking in the component can enter the Wilderness (it has a tile or site inside, or
	 * several pieces). Every other component is a single piece wholly outside the Wilderness.
	 */
	boolean gatedComponent(int component)
	{
		return gatedComponents[component];
	}

	/** Whether {@code piece} belongs to a gated component, and so has a tree of its own. */
	boolean gatedPiece(int piece)
	{
		return gatedComponents[pieceComponents[piece]];
	}

	/** The sites of a piece. */
	int[] pieceSites(int piece)
	{
		return Arrays.copyOfRange(pieceSiteIds, pieceSiteOffsets[piece], pieceSiteOffsets[piece + 1]);
	}

	/**
	 * Whether a walk may go from one site or tile to another without entering the Wilderness from
	 * outside it, in the relaxed sense of the gated sparse network (collision inside a component is
	 * ignored). Never from outside to inside. Otherwise, the two must share a component, and from
	 * outside the Wilderness, that component must never touch the Wilderness or the two must share
	 * one of its pieces.
	 */
	boolean walkAllowed(boolean fromInside, int[] fromComponents, int[] fromPieces, boolean toInside,
		int[] toComponents, int[] toPieces)
	{
		if (!fromInside && toInside) return false;
		for (int component : fromComponents)
		{
			if (!contains(toComponents, component)) continue;
			if (fromInside || !gatedComponents[component]) return true;
		}
		for (int piece : fromPieces)
			if (gatedPiece(piece) && contains(toPieces, piece))
				return true;
		return false;
	}

	/** {@link #walkAllowed} between two sites. */
	boolean walkAllowed(int from, int to)
	{
		return walkAllowed(siteInWilderness[from], stat.siteComponents(from), sitePieces(from), siteInWilderness[to],
			stat.siteComponents(to), sitePieces(to));
	}

	private static boolean contains(int[] values, int target)
	{
		for (int value : values)
			if (value == target)
				return true;
		return false;
	}

	boolean siteInPiece(int site, int piece)
	{
		for (int i = sitePieceOffsets[site]; i < sitePieceOffsets[site + 1]; i++)
			if (sitePieceIds[i] == piece)
				return true;
		return false;
	}

	/**
	 * The pieces a tile outside the Wilderness can walk into: a search tile's own piece, or for any
	 * other tile (a blocked transport endpoint or target) the pieces of its neighbouring search
	 * tiles outside the Wilderness. Empty for a tile inside the Wilderness.
	 */
	int[] outsidePieces(int packed)
	{
		return outsidePieces(stat, tileInWilderness, tilePieces, packed);
	}

	private static int[] outsidePieces(RoutingStatic stat, BitSet tileInWilderness, int[] tilePieces, int packed)
	{
		int node = stat.searchIndex(packed);
		if (node >= 0) return tileInWilderness.get(node) ? EMPTY : new int[] {tilePieces[node]};
		if (WildernessChecker.isInWilderness(packed)) return EMPTY;
		int x = WorldPointUtil.unpackWorldX(packed), y = WorldPointUtil.unpackWorldY(packed);
		int plane = WorldPointUtil.unpackWorldPlane(packed);
		int[] result = new int[8];
		int count = 0;
		for (int bit = 0; bit < 8; bit++)
		{
			int neighbour = stat.searchIndex(WorldPointUtil.packWorldPoint(x + DX[bit], y + DY[bit], plane));
			if (neighbour < 0 || tileInWilderness.get(neighbour)) continue;
			int piece = tilePieces[neighbour];
			boolean seen = false;
			for (int i = 0; i < count; i++)
				seen |= result[i] == piece;
			if (!seen) result[count++] = piece;
		}
		result = Arrays.copyOf(result, count);
		Arrays.sort(result);
		return result;
	}

	private static int find(int[] parent, int node)
	{
		while (parent[node] != node)
		{
			parent[node] = parent[parent[node]];
			node = parent[node];
		}
		return node;
	}

	private static void union(int[] parent, int left, int right)
	{
		int a = find(parent, left), b = find(parent, right);
		if (a != b) parent[Math.max(a, b)] = Math.min(a, b);
	}
}
