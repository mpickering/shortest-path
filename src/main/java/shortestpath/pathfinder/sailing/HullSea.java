package shortestpath.pathfinder.sailing;

import java.util.Arrays;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;

/**
 * The sea one hull can end its moves on, with coarse grids of it (see {@link SeaGrid}) that see the channels too
 * narrow for the hull as land, so bounds made on them see that a big boat goes round. For a boat the sea's bodies
 * of water describe, it is the tiles in its kind's bodies; for any other, the sea tiles none of
 * whose hull core tiles (those the hull covers facing any heading, {@link BoatHull#coreOffsets}) blocks a boat. Every
 * move the hull check allows ends on one, so a search may leave the rest out.
 */
final class HullSea
{
	private final SailingWaters waters;
	private final IntPredicate navigable;
	private final SeaGrid axis, rotated;

	HullSea(SailingWaters waters, IntPredicate navigable)
	{
		this.waters = waters;
		this.navigable = navigable;
		axis = SeaGrid.of(SeaGrid.Frame.AXIS, this::forEachTile);
		rotated = SeaGrid.of(SeaGrid.Frame.ROTATED, this::forEachTile);
	}

	/** The sea none of whose tiles at {@code core} offsets blocks a boat, worked out once per tile. */
	static HullSea withCore(SailingWaters waters, CollisionMap map, int[] core)
	{
		// One byte per tile, 1 where the hull can end a move, per region and plane; null for regions with none
		byte[][] clear = new byte[waters.regionCount()][];
		waters.forEachTile(tile ->
		{
			int x = WorldPointUtil.unpackWorldX(tile), y = WorldPointUtil.unpackWorldY(tile);
			int z = WorldPointUtil.unpackWorldPlane(tile);
			for (int i = 0; i < core.length; i += 2)
			{
				if (waters.blocksBoat(map, x + core[i], y + core[i + 1], z)) return;
			}
			int region = waters.regionOf(tile);
			if (clear[region] == null) clear[region] = new byte[4096];
			clear[region][SailingWaters.indexInRegion(tile)] = 1;
		});
		return new HullSea(waters, tile ->
		{
			int region = waters.regionOf(tile);
			return region >= 0 && clear[region] != null && clear[region][SailingWaters.indexInRegion(tile)] != 0;
		});
	}

	/** Whether the hull can end a move on {@code tile}. */
	boolean navigable(int tile)
	{
		return navigable.test(tile);
	}

	SeaGrid axisGrid()
	{
		return axis;
	}

	SeaGrid rotatedGrid()
	{
		return rotated;
	}

	/**
	 * The distances on both grids from the tiles this hull can end a move on within {@code reach} of {@code target}:
	 * where it can arrive at the target.
	 */
	short[][] arrivalDistances(int target, double reach)
	{
		return distancesFrom(Tiles.within(target, reach));
	}

	// The distances on both grids from those of tiles this hull can end a move on
	private short[][] distancesFrom(int[] tiles)
	{
		int[] zone = Arrays.stream(tiles).filter(this::navigable).toArray();
		return new short[][] {axis.distances(zone), rotated.distances(zone)};
	}

	private void forEachTile(IntConsumer action)
	{
		waters.forEachTile(tile ->
		{
			if (navigable(tile)) action.accept(tile);
		});
	}
}
