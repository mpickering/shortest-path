package shortestpath.pathfinder.sailing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import static net.runelite.api.Constants.REGION_SIZE;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.IntDeque;
import shortestpath.pathfinder.SplitFlagMap;

/**
 * The navigable sea: the open water a boat can reach from a mooring. In the collision map the sea is open tiles cut off
 * from the land by a blocked strip along every coast, and each mooring's berth is a blocked dock tile between the land
 * and the water; so the sea is every open tile reachable, by open tile edges, from the water side of a berth — the
 * open tiles a few tiles around it that do not walk back to the mooring's land tile.
 * <p>
 * It decides which targets are at sea: a route ends aboard only at a target in it. A tile on an island is not at sea
 * however close the boat comes, so a route to it docks and walks. Water no mooring opens onto, such as a river with no
 * mooring point, is not part of it.
 * <p>
 * The sea is split into its connected bodies of water: a boat on one never reaches a target or mooring on another, so
 * a route need not search for them.
 * <p>
 * Each hull has its own part of it, the tiles it can end a move on ({@link HullSea}), with the coarse grids its
 * bounds are made on.
 */
public final class SailingWaters
{
	private static final int REGION_SHIFT = Integer.numberOfTrailingZeros(REGION_SIZE);
	private static final int REGION_MASK = REGION_SIZE - 1;
	private static final int PLANES = 4;
	// How far around a berth the water starts, in tiles: the berth and the jetty can stand between them
	private static final int BERTH_REACH = 3;
	// How far the dock side of a berth may be from the mooring's land tile, in walking steps
	private static final int DOCK_SIDE_STEPS = 40;
	private static final int MAX_BODIES = 255;

	private static Object cachedMap;
	private static List<SailingMooring> cachedMoorings;
	private static SailingWaters cached;

	private final SplitFlagMap.RegionExtent extent;
	// The body of water of each tile, numbered from 1, or 0 for none; per region and plane, null for regions with no sea
	private final byte[][] bodies;
	// The bodies of water each mooring's berth opens onto
	private final Map<SailingMooring, int[]> berthBodies = new IdentityHashMap<>();
	private int tileCount;
	private int bodyCount;
	// Whether each tile blocks a boat (BoatHull.blocksBoat), per region and plane: 0 not yet known, 1 no, 2 yes
	private final byte[][] blocking;
	// The sea each hull can end its moves on, by its core (BoatHull.coreOffsets)
	private final Map<String, HullSea> hullSeas = new java.util.HashMap<>();

	private SailingWaters(SplitFlagMap.RegionExtent extent)
	{
		this.extent = extent;
		bodies = new byte[(extent.getWidth() + 1) * (extent.getHeight() + 1) * PLANES][];
		blocking = new byte[bodies.length][];
	}

	/** The sea of {@code map} the moorings open onto, computed once per collision data and mooring set. */
	public static synchronized SailingWaters of(CollisionMap map, List<SailingMooring> moorings)
	{
		if (cached == null || cachedMap != map.dataIdentity() || cachedMoorings != moorings)
		{
			cached = flood(map, moorings);
			cachedMap = map.dataIdentity();
			cachedMoorings = moorings;
		}
		return cached;
	}

	static SailingWaters flood(CollisionMap map, List<SailingMooring> moorings)
	{
		SailingWaters waters = new SailingWaters(map.regionExtent());
		IntDeque queue = new IntDeque(4096);
		for (SailingMooring mooring : moorings)
		{
			int berth = mooring.berthTile();
			int x = WorldPointUtil.unpackWorldX(berth), y = WorldPointUtil.unpackWorldY(berth);
			int plane = WorldPointUtil.unpackWorldPlane(berth);
			Set<Integer> opensOnto = new TreeSet<>();
			// A berth lies between the dock and the water: open tiles on the dock side walk back to the mooring's land
			// tile, the water's never do
			Set<Integer> dockSide = walkableFrom(map, mooring.landTile(), DOCK_SIDE_STEPS);
			for (int dy = -BERTH_REACH; dy <= BERTH_REACH; dy++)
			{
				for (int dx = -BERTH_REACH; dx <= BERTH_REACH; dx++)
				{
					int nx = x + dx, ny = y + dy;
					if (map.isBlocked(nx, ny, plane) || dockSide.contains(WorldPointUtil.packWorldPoint(nx, ny, plane)))
					{
						continue;
					}
					int body = waters.body(nx, ny, plane);
					opensOnto.add(body != 0 ? body : waters.fill(map, nx, ny, plane, queue));
				}
			}
			waters.berthBodies.put(mooring, opensOnto.stream().mapToInt(Integer::intValue).toArray());
		}
		return waters;
	}

	/**
	 * Builds the sea of {@code map} and the part of it each standard boat can end its moves on, so the first route that
	 * sails doesn't: static data, like the routing data, made once per collision map.
	 */
	public static void prepare(CollisionMap map)
	{
		for (SailingVessel.Kind kind : SailingVessel.Kind.values())
		{
			prepareHull(map, SailingVessel.of(kind, SailingMoves.ESTIMATED_SPEED).hull(0, 0));
		}
	}

	/** Builds the sea of {@code map} and the part of it {@code hull} can end its moves on. */
	public static void prepareHull(CollisionMap map, BoatHull hull)
	{
		of(map, SailingMoorings.all()).forHull(map, hull);
	}

	/** The sea a boat with {@code hull} can end its moves on, made once per hull core. */
	synchronized HullSea forHull(CollisionMap map, BoatHull hull)
	{
		int[] core = hull == null ? new int[] {0, 0} : hull.coreOffsets();
		return hullSeas.computeIfAbsent(java.util.Arrays.toString(core), key -> new HullSea(this, map, core));
	}

	/** Whether tile (x, y, z) blocks a boat ({@link BoatHull#blocksBoat}), remembered per tile. */
	boolean blocksBoat(CollisionMap map, int x, int y, int z)
	{
		int region = region(x, y, z);
		if (region < 0) return true;
		byte[] known = blocking[region];
		if (known == null)
		{
			known = new byte[REGION_SIZE * REGION_SIZE];
			blocking[region] = known;
		}
		int index = ((y & REGION_MASK) << REGION_SHIFT) | (x & REGION_MASK);
		if (known[index] == 0) known[index] = (byte) (BoatHull.blocksBoat(map, x, y, z) ? 2 : 1);
		return known[index] == 2;
	}

	/** The index of the region and plane holding {@code tile}, or -1 off the map. */
	int regionOf(int tile)
	{
		return region(WorldPointUtil.unpackWorldX(tile), WorldPointUtil.unpackWorldY(tile), WorldPointUtil.unpackWorldPlane(tile));
	}

	/** The index of {@code tile} within its region. */
	static int indexInRegion(int tile)
	{
		return ((WorldPointUtil.unpackWorldY(tile) & REGION_MASK) << REGION_SHIFT) | (WorldPointUtil.unpackWorldX(tile) & REGION_MASK);
	}

	/** Regions and planes. */
	int regionCount()
	{
		return bodies.length;
	}

	/** Visits every sea tile: for tools that draw or count the sea, such as the routing layers' sailing tiles. */
	public void forEachTile(java.util.function.IntConsumer action)
	{
		for (int region = 0; region < bodies.length; region++)
		{
			byte[] regionBodies = bodies[region];
			if (regionBodies == null) continue;
			int plane = region % PLANES, index = region / PLANES;
			int baseX = (index % (extent.getWidth() + 1) + extent.getMinX()) << REGION_SHIFT;
			int baseY = (index / (extent.getWidth() + 1) + extent.getMinY()) << REGION_SHIFT;
			for (int tile = 0; tile < regionBodies.length; tile++)
			{
				if (regionBodies[tile] != 0)
					action.accept(WorldPointUtil.packWorldPoint(baseX + (tile & REGION_MASK), baseY + (tile >> REGION_SHIFT), plane));
			}
		}
	}

	/** Whether {@code packedTile} is navigable sea. */
	public boolean contains(int packedTile)
	{
		return body(packedTile) != 0;
	}

	/** The body of water {@code packedTile} belongs to, numbered from 1, or 0 if it is not sea. */
	public int body(int packedTile)
	{
		return body(WorldPointUtil.unpackWorldX(packedTile), WorldPointUtil.unpackWorldY(packedTile),
			WorldPointUtil.unpackWorldPlane(packedTile));
	}

	/**
	 * The bodies of water {@code mooring}'s berth opens onto, or none for a mooring this sea was not made from (one of
	 * them is then taken to open onto any).
	 */
	public int[] berthBodies(SailingMooring mooring)
	{
		int[] opens = berthBodies.get(mooring);
		return opens == null ? null : opens.clone();
	}

	/** Sea tiles. */
	public int tileCount()
	{
		return tileCount;
	}

	/** Bodies of water. */
	public int bodyCount()
	{
		return bodyCount;
	}

	// Floods a new body of water from open tile (x, y, plane) and returns its number
	private int fill(CollisionMap map, int x, int y, int plane, IntDeque queue)
	{
		if (bodyCount == MAX_BODIES) throw new IllegalStateException("more than " + MAX_BODIES + " bodies of water");
		int body = ++bodyCount;
		add(x, y, plane, body, queue);
		while (!queue.isEmpty())
		{
			int tile = queue.pollFirst();
			int tx = WorldPointUtil.unpackWorldX(tile), ty = WorldPointUtil.unpackWorldY(tile);
			int tz = WorldPointUtil.unpackWorldPlane(tile);
			if (map.w(tx, ty, tz)) add(tx - 1, ty, tz, body, queue);
			if (map.e(tx, ty, tz)) add(tx + 1, ty, tz, body, queue);
			if (map.s(tx, ty, tz)) add(tx, ty - 1, tz, body, queue);
			if (map.n(tx, ty, tz)) add(tx, ty + 1, tz, body, queue);
		}
		return body;
	}

	// The tiles at most {@code steps} open-edge steps from {@code from}
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

	private int body(int x, int y, int plane)
	{
		int region = region(x, y, plane);
		if (region < 0 || bodies[region] == null) return 0;
		return Byte.toUnsignedInt(bodies[region][((y & REGION_MASK) << REGION_SHIFT) | (x & REGION_MASK)]);
	}

	private void add(int x, int y, int plane, int body, IntDeque queue)
	{
		int region = region(x, y, plane);
		if (region < 0) return;
		byte[] regionBodies = bodies[region];
		if (regionBodies == null)
		{
			regionBodies = new byte[REGION_SIZE * REGION_SIZE];
			bodies[region] = regionBodies;
		}
		int index = ((y & REGION_MASK) << REGION_SHIFT) | (x & REGION_MASK);
		if (regionBodies[index] != 0) return;
		regionBodies[index] = (byte) body;
		tileCount++;
		queue.addLast(WorldPointUtil.packWorldPoint(x, y, plane));
	}

	private int region(int x, int y, int plane)
	{
		int regionX = (x >> REGION_SHIFT) - extent.getMinX();
		int regionY = (y >> REGION_SHIFT) - extent.getMinY();
		if (regionX < 0 || regionY < 0 || regionX > extent.getWidth() || regionY > extent.getHeight()
			|| plane < 0 || plane >= PLANES)
		{
			return -1;
		}
		return (regionX + regionY * (extent.getWidth() + 1)) * PLANES + plane;
	}
}
