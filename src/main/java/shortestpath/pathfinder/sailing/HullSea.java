package shortestpath.pathfinder.sailing;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;

/**
 * The sea one hull can end its moves on: the sea tiles none of whose hull core tiles (those the hull covers facing any
 * heading, {@link BoatHull#coreOffsets}) blocks a boat. Every move the hull check allows ends on one, so a search may
 * leave the rest out; and its coarse grids (see {@link SeaGrid}) see the channels too narrow for the hull as land, so
 * bounds made on them see that a big boat goes round.
 */
final class HullSea
{
	private final SailingWaters waters;
	// One byte per tile, 1 where the hull can end a move, per region and plane; null for regions with none
	private final byte[][] navigable;
	private final SeaGrid axis, rotated;
	// Each mooring's docking range for this hull as distances on both grids, made on first ask
	private final Map<SailingMooring, short[][]> dockingFields = new IdentityHashMap<>();
	private int tileCount;

	HullSea(SailingWaters waters, CollisionMap map, int[] core)
	{
		this.waters = waters;
		navigable = new byte[waters.regionCount()][];
		waters.forEachTile(tile ->
		{
			int x = WorldPointUtil.unpackWorldX(tile), y = WorldPointUtil.unpackWorldY(tile);
			int z = WorldPointUtil.unpackWorldPlane(tile);
			for (int i = 0; i < core.length; i += 2)
			{
				if (waters.blocksBoat(map, x + core[i], y + core[i + 1], z)) return;
			}
			int region = waters.regionOf(tile);
			if (navigable[region] == null) navigable[region] = new byte[4096];
			navigable[region][SailingWaters.indexInRegion(tile)] = 1;
			tileCount++;
		});
		axis = SeaGrid.of(SeaGrid.Frame.AXIS, this::forEachTile);
		rotated = SeaGrid.of(SeaGrid.Frame.ROTATED, this::forEachTile);
	}

	/** Whether the hull can end a move on {@code tile}. */
	boolean navigable(int tile)
	{
		int region = waters.regionOf(tile);
		return region >= 0 && navigable[region] != null && navigable[region][SailingWaters.indexInRegion(tile)] != 0;
	}

	/** Tiles the hull can end a move on. */
	int tileCount()
	{
		return tileCount;
	}

	SeaGrid axisGrid()
	{
		return axis;
	}

	SeaGrid rotatedGrid()
	{
		return rotated;
	}

	/** The distances on both grids from where this hull can dock at {@code mooring}; made once per mooring. */
	synchronized short[][] dockingDistances(SailingMooring mooring)
	{
		short[][] distances = dockingFields.get(mooring);
		if (distances == null)
		{
			int berth = mooring.berthTile();
			int range = (int) Math.ceil(SailingMooring.DOCKING_RANGE);
			List<Integer> zone = new ArrayList<>();
			for (int dy = -range; dy <= range; dy++)
			{
				for (int dx = -range; dx <= range; dx++)
				{
					int tile = WorldPointUtil.packWorldPoint(WorldPointUtil.unpackWorldX(berth) + dx,
						WorldPointUtil.unpackWorldY(berth) + dy, WorldPointUtil.unpackWorldPlane(berth));
					if (mooring.inDockingRange(tile) && navigable(tile)) zone.add(tile);
				}
			}
			distances = distancesFrom(zone);
			dockingFields.put(mooring, distances);
		}
		return distances;
	}

	/**
	 * The distances on both grids from the tiles this hull can end a move on within {@code reach} of {@code target}:
	 * where it can arrive at the target.
	 */
	short[][] arrivalDistances(int target, double reach)
	{
		int radius = (int) Math.ceil(reach);
		List<Integer> zone = new ArrayList<>();
		for (int dy = -radius; dy <= radius; dy++)
		{
			for (int dx = -radius; dx <= radius; dx++)
			{
				int tile = WorldPointUtil.packWorldPoint(WorldPointUtil.unpackWorldX(target) + dx,
					WorldPointUtil.unpackWorldY(target) + dy, WorldPointUtil.unpackWorldPlane(target));
				if (dx * dx + dy * dy <= reach * reach && navigable(tile)) zone.add(tile);
			}
		}
		return distancesFrom(zone);
	}

	private short[][] distancesFrom(List<Integer> zone)
	{
		int[] tiles = zone.stream().mapToInt(Integer::intValue).toArray();
		return new short[][] {axis.distances(tiles), rotated.distances(tiles)};
	}

	private void forEachTile(java.util.function.IntConsumer action)
	{
		waters.forEachTile(tile ->
		{
			if (navigable(tile)) action.accept(tile);
		});
	}
}
