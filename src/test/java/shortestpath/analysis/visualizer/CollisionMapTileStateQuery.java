package shortestpath.analysis.visualizer;

import java.util.Collections;
import java.util.Set;
import net.runelite.api.Constants;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.SplitFlagMap;

public class CollisionMapTileStateQuery implements TileStateQuery {
    private final CollisionMap collisionMap;
    private final SplitFlagMap.RegionExtent extents;
    private final byte[] regionPlaneCounts;
    private final int widthInclusive;
    private final Set<Integer> starts;
    private final Set<Integer> goals;
    private final Set<Integer> banks;
    private final TransportMarkerIndex transportMarkerIndex;

    public CollisionMapTileStateQuery(
        CollisionMap collisionMap,
        byte[] regionPlaneCounts,
        Set<Integer> starts,
        Set<Integer> goals,
        Set<Integer> banks,
        TransportMarkerIndex transportMarkerIndex
    ) {
        this.collisionMap = collisionMap;
        this.extents = SplitFlagMap.getRegionExtents();
        this.regionPlaneCounts = regionPlaneCounts;
        this.widthInclusive = extents.getWidth() + 1;
        this.starts = starts != null ? starts : Collections.emptySet();
        this.goals = goals != null ? goals : Collections.emptySet();
        this.banks = banks != null ? banks : Collections.emptySet();
        this.transportMarkerIndex = transportMarkerIndex != null
            ? transportMarkerIndex
            : new TransportMarkerIndex(Collections.emptySet(), Collections.emptySet());
    }

    @Override
    public TileState getTileState(int x, int y, int plane) {
        if (!hasPlaneData(x, y, plane)) {
            return TileState.MISSING;
        }
        return collisionMap.isBlocked(x, y, plane) ? TileState.BLOCKED : TileState.WALKABLE;
    }

    @Override
    public boolean isStart(int packedPoint) {
        return starts.contains(packedPoint);
    }

    @Override
    public boolean isGoal(int packedPoint) {
        return goals.contains(packedPoint);
    }

    @Override
    public boolean isTeleportEntry(int packedPoint) {
        return transportMarkerIndex.isTeleportEntry(packedPoint);
    }

    @Override
    public boolean isTeleportExit(int packedPoint) {
        return transportMarkerIndex.isTeleportExit(packedPoint);
    }

    @Override
    public boolean isBank(int packedPoint) {
        return banks.contains(packedPoint);
    }

    private boolean hasPlaneData(int x, int y, int plane) {
        int regionX = x / Constants.REGION_SIZE;
        int regionY = y / Constants.REGION_SIZE;
        if (regionX < extents.getMinX() || regionX > extents.getMaxX()
            || regionY < extents.getMinY() || regionY > extents.getMaxY()) {
            return false;
        }
        int index = (regionX - extents.getMinX()) + (regionY - extents.getMinY()) * widthInclusive;
        int planeCount = regionPlaneCounts[index] & 0xFF;
        return plane < planeCount;
    }

    public static int packedPoint(int x, int y, int plane) {
        return WorldPointUtil.packWorldPoint(x, y, plane);
    }
}
