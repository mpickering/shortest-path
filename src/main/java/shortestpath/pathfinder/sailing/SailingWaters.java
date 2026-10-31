package shortestpath.pathfinder.sailing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import static net.runelite.api.Constants.REGION_SIZE;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.IntDeque;
import shortestpath.pathfinder.SplitFlagMap;
import shortestpath.sailing.BoatSpeed;
import shortestpath.sailing.SeaArea;

/**
 * The navigable sea, as the collision map's sailing layer gives it: which tiles are sea, which restricted
 * {@link SeaArea} each is in, and for each kind of boat, which body of water each tile is in. All of it is made when the
 * collision map is (shortest-path-tooling's {@code sailingData} task, from the dumper's {@value #DUMPER_LAYER} layer),
 * so none is worked
 * out here. The layer {@value #LAYER} has two entries:
 * <ul>
 * <li>{@value #TILES}: a {@link TileLayer}, read where it lies, with one number per tile packing its area (the low
 * {@value #AREA_BITS} bits: 0 not sea, {@link #areaCode}) and, for each kind, its body of water ({@value #BODY_BITS}
 * bits from {@link #bodyShift}: 0 for none);</li>
 * <li>{@value #INDEX}: text, the fingerprint of what the layer was made from, then a line per kind and body: the kind,
 * the body, its area, and the bodies of other areas moves join it to.</li>
 * </ul>
 * The sea is the water a boat can reach from a mooring: tiles of sea water (from the floor textures) reachable, by open
 * tile edges between them, from the water round a berth. It decides which targets are at sea: a route ends aboard only
 * at a target in it.
 * <p>
 * A kind's bodies of water part its sea by where its boat can get to: every move the hull clears at its tile's centre,
 * at any base speed, joins its two ends into one body, within one area, and a move from one area to another links their
 * bodies. Only bodies a boat can get to from some berth, and of a fair size, are kept: a coastal pocket or a pool no
 * mooring reaches is no body, so a boat is never there. So a boat that can sail from one tile to another goes through
 * linked bodies, entering only those its parts let it into ({@link #connects}); where no chain joins them, no search
 * need look.
 * <p>
 * Tiles are numbered per region and plane, as the collision map's regions are; the sea is all on plane 0.
 */
public final class SailingWaters
{
	/** The collision map's layer of sailing data the generator writes. */
	public static final String LAYER = "sailing";
	/** The collision map's layer of sea water the dumper writes, from the floor textures: the generator's input. */
	public static final String DUMPER_LAYER = "sea";
	/** The dumper layer's entry naming its numbers: a line per number, {@code OPEN} or a {@link SeaArea}. */
	public static final String LEGEND = "legend";
	public static final String TILES = "tiles";
	public static final String INDEX = "index";
	/** The index's line naming what the layer was made from ({@link SailingTransports#fingerprint}). */
	public static final String INPUTS = "inputs";
	/** The name of open water, in the dumper's legend and the index. */
	public static final String OPEN = "OPEN";
	/** Bits of a tile's number for its area, and for each kind's body; the most bodies a kind can have. */
	public static final int AREA_BITS = 3, BODY_BITS = 4, MAX_BODIES = (1 << BODY_BITS) - 1;

	private static final int REGION_SHIFT = Integer.numberOfTrailingZeros(REGION_SIZE);
	private static final int REGION_MASK = REGION_SIZE - 1;
	private static final int TILES_PER_REGION = REGION_SIZE * REGION_SIZE;
	private static final int PLANES = 4;
	private static final SeaArea[] AREAS = SeaArea.values();

	private static Object cachedMap;
	private static SailingWaters cached;

	/** A kind's bodies of water: each body's area and the bodies moves join it to, by body from 1. */
	private static final class Bodies
	{
		final int shift;
		final SeaArea[] areas;
		final int[][] links;

		Bodies(int shift, SeaArea[] areas, int[][] links)
		{
			this.shift = shift;
			this.areas = areas;
			this.links = links;
		}
	}

	private final SplitFlagMap.RegionExtent extent;
	private final TileLayer tiles;
	private final Map<SailingVessel.Kind, Bodies> bodies = new EnumMap<>(SailingVessel.Kind.class);
	private final String inputs;
	private final int regions;
	// Whether each tile blocks a boat (BoatHull.blocksBoat), per region and plane: 0 not yet known, 1 no, 2 yes
	private final byte[][] blocking;
	// The sea each hull can end its moves on, for a boat the bodies don't describe, by its core
	private final Map<String, HullSea> hullSeas = new HashMap<>();
	// Whether a boat gets from one mooring to another, by the moorings and the boat's kind and parts
	private final Map<Long, Boolean> sailings = new ConcurrentHashMap<>();
	// Where each boat casts off from each mooring, by the mooring and the boat
	private final Map<List<Object>, int[]> castOffs = new ConcurrentHashMap<>();

	private SailingWaters(SplitFlagMap.RegionExtent extent, Map<String, byte[]> layer)
	{
		this.extent = extent;
		regions = (extent.getWidth() + 1) * (extent.getHeight() + 1) * PLANES;
		blocking = new byte[regions][];
		byte[] data = layer.get(TILES);
		tiles = data == null ? null
			: new TileLayer(data, 0, regions, (x, y) -> region(x << REGION_SHIFT, y << REGION_SHIFT, 0));
		String found = null;
		byte[] index = layer.get(INDEX);
		Map<SailingVessel.Kind, List<String[]>> rows = new EnumMap<>(SailingVessel.Kind.class);
		for (String line : index == null ? new String[0] : new String(index, StandardCharsets.UTF_8).split("\n"))
		{
			String[] fields = line.split("\t", -1);
			if (fields[0].equals(INPUTS)) found = fields[1].trim();
			else if (fields.length == 4) rows.computeIfAbsent(SailingVessel.Kind.valueOf(fields[0]), k -> new ArrayList<>()).add(fields);
		}
		inputs = found;
		rows.forEach((kind, lines) ->
		{
			SeaArea[] areas = new SeaArea[lines.size() + 1];
			int[][] links = new int[lines.size() + 1][];
			links[0] = new int[0];
			for (String[] fields : lines)
			{
				int body = Integer.parseInt(fields[1]);
				areas[body] = fields[2].equals(OPEN) ? null : SeaArea.valueOf(fields[2]);
				links[body] = fields[3].isEmpty() ? new int[0]
					: Arrays.stream(fields[3].split(",")).mapToInt(Integer::parseInt).toArray();
			}
			bodies.put(kind, new Bodies(bodyShift(kind), areas, links));
		});
	}

	/** The sea of {@code map}, from its layer; read once per collision data. */
	public static synchronized SailingWaters of(CollisionMap map)
	{
		if (cached == null || cachedMap != map.dataIdentity())
		{
			cached = new SailingWaters(map.regionExtent(), map.layers(LAYER));
			cachedMap = map.dataIdentity();
		}
		return cached;
	}

	/** Reads the sea of {@code map}, so the first route that sails doesn't: static data, like the routing data. */
	public static void prepare(CollisionMap map)
	{
		of(map);
	}

	/** A tile's area code: 1 for open water, then each restricted area in order. 0 is not sea. */
	public static int areaCode(SeaArea area)
	{
		return area == null ? 1 : area.ordinal() + 2;
	}

	/** Where {@code kind}'s body number lies in a tile's number. */
	public static int bodyShift(SailingVessel.Kind kind)
	{
		return AREA_BITS + kind.ordinal() * BODY_BITS;
	}

	/** The fingerprint of what the sailing layer was made from, or {@code null} for a map without one. */
	public String inputs()
	{
		return inputs;
	}

	/** Whether tile (x, y, z) blocks a boat ({@link BoatHull#blocksBoat}), remembered per tile. */
	boolean blocksBoat(CollisionMap map, int x, int y, int z)
	{
		int region = region(x, y, z);
		if (region < 0) return true;
		byte[] known = blocking[region];
		if (known == null)
		{
			known = new byte[TILES_PER_REGION];
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

	/** The index of {@code tile} within its region, as the layer numbers them. */
	public static int indexInRegion(int tile)
	{
		return ((WorldPointUtil.unpackWorldY(tile) & REGION_MASK) << REGION_SHIFT) | (WorldPointUtil.unpackWorldX(tile) & REGION_MASK);
	}

	/** Regions and planes. */
	int regionCount()
	{
		return regions;
	}

	// The tile's number in the layer, 0 off the sea
	private int number(int packedTile)
	{
		int region = regionOf(packedTile);
		return tiles == null || region < 0 ? 0 : tiles.get(region, indexInRegion(packedTile));
	}

	/** Visits every sea tile: for tools that draw or count the sea, such as the routing layers' sailing tiles. */
	public void forEachTile(IntConsumer action)
	{
		if (tiles == null) return;
		for (int region = 0; region < regions; region++)
		{
			if (!tiles.has(region)) continue;
			for (int index = 0; index < TILES_PER_REGION; index++)
			{
				if (tiles.get(region, index) != 0) action.accept(tileAt(region, index));
			}
		}
	}

	/** Whether {@code packedTile} is navigable sea. */
	public boolean contains(int packedTile)
	{
		return number(packedTile) != 0;
	}

	/** The restricted area {@code packedTile} lies in, or {@code null} for open water or land. */
	public SeaArea area(int packedTile)
	{
		int code = number(packedTile) & ((1 << AREA_BITS) - 1);
		return code < 2 ? null : AREAS[code - 2];
	}

	/** Whether {@code vessel}'s parts let it end a move on {@code packedTile}. */
	public boolean allows(SailingVessel vessel, int packedTile)
	{
		return vessel.canEnter(area(packedTile));
	}

	// The bodies that describe the boat, or null for none
	private Bodies bodiesFor(SailingVessel vessel, BoatHull hull)
	{
		return hull.pivotX() != 0 || hull.pivotY() != 0 ? null : bodiesAtCentre(vessel);
	}

	// The bodies that describe the boat at its tile's centre, or null for none: a boat of a standard kind, of that kind's
	// bounds, at a base speed the generator sailed
	private Bodies bodiesAtCentre(SailingVessel vessel)
	{
		if (!vessel.standard()) return null;
		if (vessel.speed() < 1 || vessel.speed() > SailingMoves.MAX_SPEED * BoatSpeed.UNITS) return null;
		return bodies.get(vessel.kind());
	}

	// The tile's body of water for the kind, or 0
	private int body(Bodies described, int packedTile)
	{
		return (number(packedTile) >>> described.shift) & MAX_BODIES;
	}

	/**
	 * The sea a boat with {@code hull} can end its moves on, with coarse grids of it for bounds: for a boat the bodies
	 * describe, the tiles in its bodies; for any other, the sea none of whose hull core tiles blocks it.
	 */
	synchronized HullSea forHull(CollisionMap map, SailingVessel vessel, BoatHull hull)
	{
		Bodies described = bodiesFor(vessel, hull);
		if (described != null)
			return hullSeas.computeIfAbsent(vessel.kind().name(), key -> new HullSea(this, tile -> body(described, tile) != 0));
		int[] core = hull.coreOffsets();
		return hullSeas.computeIfAbsent(Arrays.toString(core), key -> HullSea.withCore(this, map, core));
	}

	/**
	 * Where {@code vessel} with {@code hull} can end its moves: the sea its hull can be on ({@link #forHull}), in water
	 * its parts let it enter.
	 */
	public IntPredicate landing(CollisionMap map, SailingVessel vessel, BoatHull hull)
	{
		Bodies described = bodiesFor(vessel, hull);
		if (described != null)
		{
			if (vessel.areas().containsAll(SeaArea.ALL)) return tile -> body(described, tile) != 0;
			return tile -> body(described, tile) != 0 && allows(vessel, tile);
		}
		HullSea hullSea = forHull(map, vessel, hull);
		if (vessel.areas().containsAll(SeaArea.ALL)) return hullSea::navigable;
		return tile -> hullSea.navigable(tile) && allows(vessel, tile);
	}

	/**
	 * Where {@code vessel} casts off from {@code mooring}: every tile within docking range of its berth the boat can be
	 * on — sea its hull fits facing some heading, in water its parts let it enter. Boarding puts the boat on any of
	 * them, as docking takes it from any tile in range; with none, the boat can't set out from there. Made once per
	 * mooring and boat.
	 */
	public int[] castOff(CollisionMap map, SailingMooring mooring, SailingVessel vessel)
	{
		return castOffs.computeIfAbsent(List.of(mooring, vessel.boundsX(), vessel.boundsY(), vessel.boundsWidth(),
			vessel.boundsHeight(), vessel.areas()), key ->
		{
			BoatHull hull = vessel.hull();
			return Arrays.stream(mooring.dockingTiles())
				.filter(tile -> contains(tile) && allows(vessel, tile) && hull.fits(map, WorldPointUtil.unpackWorldX(tile),
					WorldPointUtil.unpackWorldY(tile), WorldPointUtil.unpackWorldPlane(tile)))
				.toArray();
		}).clone();
	}

	/**
	 * Whether {@code vessel} with {@code hull}, on one of the tiles {@code from}, might get to one of {@code to}: one of
	 * them already, or through linked bodies of water of its kind, each in an area its parts let it enter, to one of
	 * {@code to}. Never false for a boat that can, but for one in water too small or too cut off to be a body, which is
	 * taken to be unreachable; true whenever the bodies can't tell, for a boat they don't describe.
	 */
	public boolean connects(SailingVessel vessel, BoatHull hull, int[] from, int[] to)
	{
		return connects(bodiesFor(vessel, hull), vessel, from, to);
	}

	/**
	 * Whether {@code vessel} might get from where it casts off at {@code from} to where it docks at {@code to}
	 * ({@link #connects}); remembered per pair of moorings and boat, as every refresh asks it of every leg.
	 */
	public boolean sails(CollisionMap map, SailingVessel vessel, SailingMooring from, SailingMooring to)
	{
		Bodies described = bodiesAtCentre(vessel);
		if (described == null) return connects(null, vessel, castOff(map, from, vessel), to.dockingTiles());
		long areas = 0;
		for (SeaArea area : vessel.areas()) areas |= 1L << area.ordinal();
		long key = ((long) from.id() << 48) | ((long) to.id() << 32) | ((long) vessel.kind().ordinal() << 24)
			| (areas << 16);
		return sailings.computeIfAbsent(key, k -> connects(described, vessel, castOff(map, from, vessel), to.dockingTiles()));
	}

	private boolean connects(Bodies kindBodies, SailingVessel vessel, int[] from, int[] to)
	{
		if (from == null || to == null) return true;
		for (int a : from) for (int b : to) if (a == b) return true;
		if (kindBodies == null) return true;
		boolean[] wanted = new boolean[kindBodies.areas.length];
		boolean any = false;
		for (int tile : to)
		{
			int body = body(kindBodies, tile);
			if (body > 0 && vessel.canEnter(kindBodies.areas[body]))
			{
				wanted[body] = true;
				any = true;
			}
		}
		if (!any) return false;
		boolean[] seen = new boolean[kindBodies.areas.length];
		IntDeque queue = new IntDeque(16);
		for (int tile : from)
		{
			// A tile in no body is one no boat of the kind is ever on
			int body = body(kindBodies, tile);
			if (body == 0 || seen[body]) continue;
			if (wanted[body]) return true;
			seen[body] = true;
			queue.addLast(body);
		}
		while (!queue.isEmpty())
		{
			for (int next : kindBodies.links[queue.pollFirst()])
			{
				if (seen[next] || !vessel.canEnter(kindBodies.areas[next])) continue;
				if (wanted[next]) return true;
				seen[next] = true;
				queue.addLast(next);
			}
		}
		return false;
	}

	private int tileAt(int region, int index)
	{
		int plane = region % PLANES, at = region / PLANES;
		int baseX = (at % (extent.getWidth() + 1) + extent.getMinX()) << REGION_SHIFT;
		int baseY = (at / (extent.getWidth() + 1) + extent.getMinY()) << REGION_SHIFT;
		return WorldPointUtil.packWorldPoint(baseX + (index & REGION_MASK), baseY + (index >> REGION_SHIFT), plane);
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
