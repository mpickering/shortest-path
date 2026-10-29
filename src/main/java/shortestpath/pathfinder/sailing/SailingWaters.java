package shortestpath.pathfinder.sailing;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import static net.runelite.api.Constants.REGION_SIZE;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.IntDeque;
import shortestpath.pathfinder.SplitFlagMap;
import shortestpath.sailing.SeaArea;

/**
 * The navigable sea, as the collision map's layers give it: which tiles are sea, which restricted {@link SeaArea}
 * each is in, and, for each kind of boat, which tiles it can be on and which of them it can get between. All of it is
 * made when the collision map is, so none is worked out here.
 * <ul>
 * <li>{@value #AREA_LAYER}, from the collision map's dumper: per region with any restricted sea, a byte per tile, 0 or
 * the area's number in its {@value #LEGEND}, read from the game's water textures.</li>
 * <li>{@value #LAYER}, from the sailing generator ({@code SailingTimesGenerator}): {@code sea}, 1 per sea tile; and
 * for each kind, {@code <KIND>}: each tile's piece or 0, then each piece's area and the pieces moves from it lead
 * to. The tiles' numbers are {@link TileLayer}s, read where they lie.</li>
 * </ul>
 * The sea is the open water a boat can reach from a mooring: open tiles reachable, by open tile edges, from the water
 * side of a berth. It decides which targets are at sea: a route ends aboard only at a target in it. Water no mooring
 * opens onto, such as a river with no mooring point, is not part of it.
 * <p>
 * A kind's pieces part its sea by what its boat can sail between, by every move the hull clears at its tile's centre
 * at any base speed. A piece is a strongly connected part of those moves within one
 * area (or open water): from any of its tiles a boat can get to any other. Every other move leads from one piece to
 * another. So a boat that can sail from one tile to another follows those links, entering only pieces its parts let it
 * into ({@link #connects}); where no such chain joins them, no search need look. A tile in no piece no move starts or
 * ends on; one moves only leave keeps its piece, but no move arrives there.
 * <p>
 * Tiles are numbered per region and plane, as the collision map's regions are; the sea is all on plane 0.
 */
public final class SailingWaters
{
	/** The collision map's layer of sailing data the generator writes. */
	public static final String LAYER = "sailing";
	/** The collision map's layer of restricted sea areas the dumper writes. */
	public static final String AREA_LAYER = "sea-areas";
	public static final String LEGEND = "legend";
	public static final String SEA = "sea";
	/** The fingerprint of what the sailing layer was made from ({@link SailingTransports#fingerprint}). */
	public static final String INPUTS = "inputs";

	private static final int REGION_SHIFT = Integer.numberOfTrailingZeros(REGION_SIZE);
	private static final int REGION_MASK = REGION_SIZE - 1;
	private static final int TILES = REGION_SIZE * REGION_SIZE;
	private static final int PLANES = 4;

	private static Object cachedMap;
	private static SailingWaters cached;

	/** Each kind's pieces: the piece of each tile, and each piece's area and the pieces moves from it lead to. */
	private static final class Pieces
	{
		final TileLayer tiles;
		// Per piece, from 1: its area (null for open water) and the pieces moves from it lead to
		final SeaArea[] areas;
		final int[][] leadsTo;
		// Per region, a bit per tile a move can end on, made the first time a search asks there: the searches ask
		// for every move
		private final long[][] arrives;

		Pieces(TileLayer tiles, SeaArea[] areas, int[][] leadsTo, int regions)
		{
			this.tiles = tiles;
			this.areas = areas;
			this.leadsTo = leadsTo;
			arrives = new long[regions][];
		}

		boolean arrives(int region, int index)
		{
			long[] bits = arrives[region];
			if (bits == null)
			{
				bits = new long[TILES / Long.SIZE];
				for (int i = 0; i < TILES; i++) if (tiles.get(region, i) > 0) bits[i >>> 6] |= 1L << (i & 63);
				arrives[region] = bits;
			}
			return (bits[index >>> 6] & (1L << (index & 63))) != 0;
		}
	}

	private final SplitFlagMap.RegionExtent extent;
	// A bit per tile, set for sea, per region and plane; null for regions with none
	private final long[][] sea;
	// The area number of each tile, per region and plane; null for regions with no restricted sea
	private final byte[][] areaNumbers;
	// The areas by number, from the legend
	private final SeaArea[] legend;
	private final Map<SailingVessel.Kind, Pieces> pieces = new java.util.EnumMap<>(SailingVessel.Kind.class);
	private final String inputs;
	private final int regions;
	private int tileCount;
	// Whether each tile blocks a boat (BoatHull.blocksBoat), per region and plane: 0 not yet known, 1 no, 2 yes
	private final byte[][] blocking;
	// The sea each hull can end its moves on: by its kind for a boat the pieces cover, else by its core
	private final Map<String, HullSea> hullSeas = new java.util.HashMap<>();
	// Whether a boat gets from one mooring to another, by the moorings, the boat's kind and parts and its speed
	private final Map<Long, Boolean> sailings = new java.util.concurrent.ConcurrentHashMap<>();
	// Where each boat casts off from each mooring, by the mooring and the boat
	private final Map<List<Object>, int[]> castOffs = new java.util.concurrent.ConcurrentHashMap<>();

	private SailingWaters(SplitFlagMap.RegionExtent extent, Map<String, byte[]> areaLayer, Map<String, byte[]> layer)
	{
		this.extent = extent;
		int regions = (extent.getWidth() + 1) * (extent.getHeight() + 1) * PLANES;
		this.regions = regions;
		areaNumbers = new byte[regions][];
		blocking = new byte[regions][];
		legend = legend(areaLayer.get(LEGEND));
		for (Map.Entry<String, byte[]> entry : areaLayer.entrySet())
		{
			String[] name = entry.getKey().split("_");
			if (name.length != 2 || !name[0].chars().allMatch(Character::isDigit)) continue;
			int region = region(Integer.parseInt(name[0]) << REGION_SHIFT, Integer.parseInt(name[1]) << REGION_SHIFT, 0);
			if (region >= 0) areaNumbers[region] = entry.getValue();
		}
		sea = new long[regions][];
		if (layer.containsKey(SEA))
		{
			TileLayer seaLayer = tileLayer(layer.get(SEA));
			for (int region = 0; region < regions; region++)
			{
				if (!seaLayer.has(region)) continue;
				sea[region] = new long[TILES / Long.SIZE];
				for (int index = 0; index < TILES; index++)
				{
					if (seaLayer.get(region, index) == 0) continue;
					sea[region][index >>> 6] |= 1L << (index & 63);
					tileCount++;
				}
			}
		}
		for (SailingVessel.Kind kind : SailingVessel.Kind.values())
		{
			if (layer.containsKey(kind.name())) pieces.put(kind, readPieces(layer.get(kind.name())));
		}
		byte[] made = layer.get(INPUTS);
		inputs = made == null ? null : new String(made, StandardCharsets.UTF_8).trim();
	}

	/** The sea of {@code map}, from its layers; read once per collision data. */
	public static synchronized SailingWaters of(CollisionMap map)
	{
		if (cached == null || cachedMap != map.dataIdentity())
		{
			cached = fromLayers(map.regionExtent(), map.layers(AREA_LAYER), map.layers(LAYER));
			cachedMap = map.dataIdentity();
		}
		return cached;
	}

	/** The sea the layers describe, for the generator, which makes them. */
	static SailingWaters fromLayers(SplitFlagMap.RegionExtent extent, Map<String, byte[]> areaLayer,
		Map<String, byte[]> layer)
	{
		return new SailingWaters(extent, areaLayer, layer);
	}

	/** Reads the sea of {@code map}, so the first route that sails doesn't: static data, like the routing data. */
	public static void prepare(CollisionMap map)
	{
		of(map);
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
			known = new byte[TILES];
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
		return regions;
	}

	/** Visits every sea tile: for tools that draw or count the sea, such as the routing layers' sailing tiles. */
	public void forEachTile(java.util.function.IntConsumer action)
	{
		for (int region = 0; region < regions; region++)
		{
			long[] bits = sea[region];
			if (bits == null) continue;
			for (int index = 0; index < TILES; index++)
			{
				if ((bits[index >>> 6] & (1L << (index & 63))) != 0) action.accept(tileAt(region, index));
			}
		}
	}

	/** Whether {@code packedTile} is navigable sea. */
	public boolean contains(int packedTile)
	{
		int region = regionOf(packedTile);
		if (region < 0 || sea[region] == null) return false;
		int index = indexInRegion(packedTile);
		return (sea[region][index >>> 6] & (1L << (index & 63))) != 0;
	}

	/** The restricted area {@code packedTile} lies in, or {@code null} for open water or land. */
	public SeaArea area(int packedTile)
	{
		int region = regionOf(packedTile);
		if (region < 0 || areaNumbers[region] == null) return null;
		int number = areaNumbers[region][indexInRegion(packedTile)];
		return number > 0 && number < legend.length ? legend[number] : null;
	}

	/** Whether {@code vessel}'s parts let it end a move on {@code packedTile}. */
	public boolean allows(SailingVessel vessel, int packedTile)
	{
		return vessel.canEnter(area(packedTile));
	}

	/** Sea tiles. */
	public int tileCount()
	{
		return tileCount;
	}

	/** The base speeds the generator sails, in these units: 128ths of a tile per tick, as the game stores them. */
	public static final int SPEED_UNITS = 128;

	/**
	 * Whether the pieces describe {@code vessel} with {@code hull}: a boat of a standard kind, of that kind's bounds, at
	 * its tile's centre, at a base speed the generator sailed (a whole number of 128ths of a tile per tick up to the
	 * fastest). For any other boat {@link #connects} can't tell, and says it can.
	 */
	public boolean describes(SailingVessel vessel, BoatHull hull)
	{
		return piecesFor(vessel, hull) != null;
	}

	// The pieces that describe the boat, or null for none
	private Pieces piecesFor(SailingVessel vessel, BoatHull hull)
	{
		if (hull == null || hull.pivotX() != 0 || hull.pivotY() != 0) return null;
		return piecesAtCentre(vessel);
	}

	// The pieces that describe the boat at its tile's centre, or null for none
	private Pieces piecesAtCentre(SailingVessel vessel)
	{
		if (!vessel.standard()) return null;
		double units = vessel.speed() * SPEED_UNITS;
		if (units != Math.rint(units) || units < 1 || units > SailingMoves.MAX_SPEED * SPEED_UNITS) return null;
		return pieces.get(vessel.kind());
	}

	// Whether a move can end on the tile, by the pieces
	private boolean arrives(Pieces described, int packedTile)
	{
		int region = regionOf(packedTile);
		return region >= 0 && described.arrives(region, indexInRegion(packedTile));
	}

	// The tile's piece, negated for a tile moves only leave
	private int label(Pieces described, int packedTile)
	{
		int region = regionOf(packedTile);
		return described == null || region < 0 ? 0 : described.tiles.get(region, indexInRegion(packedTile));
	}

	/**
	 * The sea a boat with {@code hull} can end its moves on, with coarse grids of it for bounds: for a boat the pieces
	 * describe, the tiles moves end on in its pieces; for any other, the sea none of whose hull core tiles blocks it.
	 */
	synchronized HullSea forHull(CollisionMap map, SailingVessel vessel, BoatHull hull)
	{
		Pieces described = piecesFor(vessel, hull);
		if (described != null)
			return hullSeas.computeIfAbsent(described.toString(), key -> new HullSea(this, tile -> arrives(described, tile)));
		int[] core = hull == null ? new int[] {0, 0} : hull.coreOffsets();
		return hullSeas.computeIfAbsent(java.util.Arrays.toString(core), key -> HullSea.withCore(this, map, core));
	}

	/**
	 * Where {@code vessel} with {@code hull} can end its moves: the sea its hull can be on ({@link #forHull}), in water
	 * its parts let it enter.
	 */
	public IntPredicate landing(CollisionMap map, SailingVessel vessel, BoatHull hull)
	{
		Pieces described = piecesFor(vessel, hull);
		if (described != null)
		{
			if (vessel.areas().containsAll(SeaArea.ALL)) return tile -> arrives(described, tile);
			return tile -> arrives(described, tile) && allows(vessel, tile);
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
			BoatHull hull = vessel.hull(0, 0);
			List<Integer> tiles = new ArrayList<>();
			for (int tile : dockingTiles(mooring))
			{
				int x = WorldPointUtil.unpackWorldX(tile), y = WorldPointUtil.unpackWorldY(tile);
				int plane = WorldPointUtil.unpackWorldPlane(tile);
				if (!contains(tile) || !allows(vessel, tile)) continue;
				if (hull == null ? blocksBoat(map, x, y, plane) : !hull.fits(map, x, y, plane)) continue;
				tiles.add(tile);
			}
			return tiles.stream().mapToInt(Integer::intValue).toArray();
		}).clone();
	}

	/** The tiles within docking range of {@code mooring}'s berth: where a boat can be to dock there. */
	public int[] dockingTiles(SailingMooring mooring)
	{
		List<Integer> tiles = new ArrayList<>();
		int berth = mooring.berthTile(), range = (int) Math.ceil(SailingMooring.DOCKING_RANGE);
		int bx = WorldPointUtil.unpackWorldX(berth), by = WorldPointUtil.unpackWorldY(berth);
		int plane = WorldPointUtil.unpackWorldPlane(berth);
		for (int dy = -range; dy <= range; dy++)
		{
			for (int dx = -range; dx <= range; dx++)
			{
				int tile = WorldPointUtil.packWorldPoint(bx + dx, by + dy, plane);
				if (mooring.inDockingRange(tile)) tiles.add(tile);
			}
		}
		return tiles.stream().mapToInt(Integer::intValue).toArray();
	}

	/** The tiles within {@code reach} tiles of {@code packedTile}: where a boat can be to arrive there. */
	public static int[] tilesNear(int packedTile, double reach)
	{
		int radius = (int) Math.ceil(reach);
		int x = WorldPointUtil.unpackWorldX(packedTile), y = WorldPointUtil.unpackWorldY(packedTile);
		int plane = WorldPointUtil.unpackWorldPlane(packedTile);
		List<Integer> tiles = new ArrayList<>();
		for (int dy = -radius; dy <= radius; dy++)
		{
			for (int dx = -radius; dx <= radius; dx++)
			{
				if (dx * dx + dy * dy <= reach * reach) tiles.add(WorldPointUtil.packWorldPoint(x + dx, y + dy, plane));
			}
		}
		return tiles.stream().mapToInt(Integer::intValue).toArray();
	}

	/**
	 * Whether {@code vessel} with {@code hull}, on one of the tiles {@code from}, might get to one of {@code to}: one
	 * of them already, or along the links between pieces of its kind's sea, through pieces its parts let it enter, to a
	 * tile of {@code to} a move can end on. Never false for a boat
	 * that can, so a search need not look when it is; true whenever the pieces can't tell, for a boat they don't
	 * describe.
	 */
	public boolean connects(SailingVessel vessel, BoatHull hull, int[] from, int[] to)
	{
		return connects(hull == null || hull.pivotX() != 0 || hull.pivotY() != 0 ? null : piecesAtCentre(vessel),
			vessel, from, to);
	}

	/**
	 * Whether {@code vessel} might get from where it casts off at {@code from} to where it docks at {@code to}
	 * ({@link #connects}); remembered per pair of moorings and boat, as every refresh asks it of every leg.
	 */
	public boolean sails(CollisionMap map, SailingVessel vessel, SailingMooring from, SailingMooring to)
	{
		Pieces described = piecesAtCentre(vessel);
		if (described == null) return connects(null, vessel, castOff(map, from, vessel), dockingTiles(to));
		long areas = 0;
		for (SeaArea area : vessel.areas()) areas |= 1L << area.ordinal();
		long key = ((long) from.id() << 48) | ((long) to.id() << 32) | ((long) vessel.kind().ordinal() << 24)
			| (areas << 16) | (long) (vessel.speed() * SPEED_UNITS);
		return sailings.computeIfAbsent(key, k -> connects(described, vessel, castOff(map, from, vessel), dockingTiles(to)));
	}

	private boolean connects(Pieces kindPieces, SailingVessel vessel, int[] from, int[] to)
	{
		if (from == null || to == null) return true;
		for (int a : from) for (int b : to) if (a == b) return true;
		if (kindPieces == null) return true;
		boolean[] wanted = new boolean[kindPieces.areas.length];
		boolean any = false;
		for (int tile : to)
		{
			int piece = label(kindPieces, tile);
			if (piece <= 0) continue;
			if (vessel.canEnter(kindPieces.areas[piece]))
			{
				wanted[piece] = true;
				any = true;
			}
		}
		if (!any) return false;
		boolean[] seen = new boolean[kindPieces.areas.length];
		IntDeque queue = new IntDeque(64);
		for (int tile : from)
		{
			// No move leaves a tile in no piece: from there the boat only arrives where it is, checked above
			int piece = Math.abs(label(kindPieces, tile));
			if (piece == 0) continue;
			if (wanted[piece]) return true;
			if (!seen[piece])
			{
				seen[piece] = true;
				queue.addLast(piece);
			}
		}
		while (!queue.isEmpty())
		{
			int piece = queue.pollFirst();
			for (int next : kindPieces.leadsTo[piece])
			{
				if (seen[next] || !vessel.canEnter(kindPieces.areas[next])) continue;
				if (wanted[next]) return true;
				seen[next] = true;
				queue.addLast(next);
			}
		}
		return false;
	}

	/** The name of a region's entry in a layer: its region coordinates, as the collision map names its own. */
	public static String regionName(int regionX, int regionY)
	{
		return regionX + "_" + regionY;
	}

	/** The packed tile at {@code index} in plane-0 region ({@code regionX}, {@code regionY}). */
	static int tileIn(int regionX, int regionY, int index)
	{
		return WorldPointUtil.packWorldPoint((regionX << REGION_SHIFT) + (index & REGION_MASK),
			(regionY << REGION_SHIFT) + (index >> REGION_SHIFT), 0);
	}

	private int tileAt(int region, int index)
	{
		int plane = region % PLANES, at = region / PLANES;
		int baseX = (at % (extent.getWidth() + 1) + extent.getMinX()) << REGION_SHIFT;
		int baseY = (at / (extent.getWidth() + 1) + extent.getMinY()) << REGION_SHIFT;
		return WorldPointUtil.packWorldPoint(baseX + (index & REGION_MASK), baseY + (index >> REGION_SHIFT), plane);
	}

	private static SeaArea[] legend(byte[] text)
	{
		List<SeaArea> byNumber = new ArrayList<>();
		if (text == null) return new SeaArea[0];
		for (String line : new String(text, StandardCharsets.UTF_8).split("\n"))
		{
			if (line.isBlank()) continue;
			String[] fields = line.split("\t");
			int number = Integer.parseInt(fields[0].trim());
			while (byNumber.size() <= number) byNumber.add(null);
			byNumber.set(number, SeaArea.valueOf(fields[1].trim()));
		}
		return byNumber.toArray(new SeaArea[0]);
	}

	private TileLayer tileLayer(byte[] data)
	{
		return new TileLayer(data, 0, regions, (x, y) -> region(x << REGION_SHIFT, y << REGION_SHIFT, 0));
	}

	// A kind's pieces: the piece of each tile (a TileLayer), then the count of pieces and per piece its area's ordinal
	// plus one (0 for open water), how many pieces moves from it lead to and which
	private Pieces readPieces(byte[] data)
	{
		TileLayer tiles = tileLayer(data);
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, tiles.end(), data.length - tiles.end())))
		{
			int count = in.readInt();
			SeaArea[] areas = new SeaArea[count + 1];
			int[][] leadsTo = new int[count + 1][];
			leadsTo[0] = new int[0];
			for (int piece = 1; piece <= count; piece++)
			{
				int area = in.readUnsignedByte();
				areas[piece] = area == 0 ? null : SeaArea.values()[area - 1];
				leadsTo[piece] = new int[in.readInt()];
				for (int i = 0; i < leadsTo[piece].length; i++) leadsTo[piece][i] = in.readInt();
			}
			return new Pieces(tiles, areas, leadsTo, regions);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
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
