package shortestpath.analysis;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Constants;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.OrdinalDirection;
import shortestpath.pathfinder.SplitFlagMap;

public final class ComponentAnalysisSupport {
    private static final OrdinalDirection[] DIRECTIONS = OrdinalDirection.values();
    private static final Set<Integer> ANALYSIS_BLOCKED_TILES = buildAnalysisBlockedTiles();

    private ComponentAnalysisSupport() {
    }

    public static ComponentComputation computeComponents(CollisionMap collisionMap, SplitFlagMap splitFlagMap) {
        SplitFlagMap.RegionExtent extents = SplitFlagMap.getRegionExtents();
        byte[] regionPlanes = splitFlagMap.getRegionMapPlaneCounts();
        int widthInclusive = extents.getWidth() + 1;
        long walkableTileCount = 0L;
        int componentId = 0;
        Map<Integer, Integer> packedPointToComponent = new LinkedHashMap<>();

        for (int regionY = extents.getMinY(); regionY <= extents.getMaxY(); regionY++) {
            for (int regionX = extents.getMinX(); regionX <= extents.getMaxX(); regionX++) {
                int planeCount = regionPlanes[(regionX - extents.getMinX()) + (regionY - extents.getMinY()) * widthInclusive] & 0xFF;
                if (planeCount == 0) {
                    continue;
                }

                int startX = regionX * Constants.REGION_SIZE;
                int startY = regionY * Constants.REGION_SIZE;
                for (int plane = 0; plane < planeCount; plane++) {
                    for (int x = startX; x < startX + Constants.REGION_SIZE; x++) {
                        for (int y = startY; y < startY + Constants.REGION_SIZE; y++) {
                            int packed = WorldPointUtil.packWorldPoint(x, y, plane);
                            if (packedPointToComponent.containsKey(packed) || isAnalysisBlocked(collisionMap, x, y, plane)) {
                                continue;
                            }

                            walkableTileCount += floodFillComponent(collisionMap, packed, componentId, packedPointToComponent);
                            componentId++;
                        }
                    }
                }
            }
        }

        return new ComponentComputation(packedPointToComponent, walkableTileCount);
    }

    public static boolean isAnalysisBlocked(CollisionMap map, int x, int y, int plane) {
        return map.isBlocked(x, y, plane) || ANALYSIS_BLOCKED_TILES.contains(WorldPointUtil.packWorldPoint(x, y, plane));
    }

    public static Set<Integer> getAnalysisBlockedTiles() {
        return ANALYSIS_BLOCKED_TILES;
    }

    public static boolean canWalk(CollisionMap map, int x, int y, int plane, OrdinalDirection direction) {
        int targetX = x + dx(direction);
        int targetY = y + dy(direction);
        if (isAnalysisBlocked(map, x, y, plane) || isAnalysisBlocked(map, targetX, targetY, plane)) {
            return false;
        }
        switch (direction) {
            case WEST:
                return map.w(x, y, plane);
            case EAST:
                return map.e(x, y, plane);
            case SOUTH:
                return map.s(x, y, plane);
            case NORTH:
                return map.n(x, y, plane);
            case SOUTH_WEST:
                return map.s(x, y, plane) && map.w(x, y - 1, plane) && map.w(x, y, plane) && map.s(x - 1, y, plane);
            case SOUTH_EAST:
                return map.s(x, y, plane) && map.e(x, y - 1, plane) && map.e(x, y, plane) && map.s(x + 1, y, plane);
            case NORTH_WEST:
                return map.n(x, y, plane) && map.w(x, y + 1, plane) && map.w(x, y, plane) && map.n(x - 1, y, plane);
            case NORTH_EAST:
                return map.n(x, y, plane) && map.e(x, y + 1, plane) && map.e(x, y, plane) && map.n(x + 1, y, plane);
            default:
                return false;
        }
    }

    public static int dx(OrdinalDirection direction) {
        switch (direction) {
            case WEST:
            case NORTH_WEST:
            case SOUTH_WEST:
                return -1;
            case EAST:
            case NORTH_EAST:
            case SOUTH_EAST:
                return 1;
            default:
                return 0;
        }
    }

    public static int dy(OrdinalDirection direction) {
        switch (direction) {
            case SOUTH:
            case SOUTH_WEST:
            case SOUTH_EAST:
                return -1;
            case NORTH:
            case NORTH_WEST:
            case NORTH_EAST:
                return 1;
            default:
                return 0;
        }
    }

    private static int floodFillComponent(
        CollisionMap collisionMap,
        int startPacked,
        int componentId,
        Map<Integer, Integer> packedPointToComponent
    ) {
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(startPacked);
        packedPointToComponent.put(startPacked, componentId);
        int size = 0;

        while (!queue.isEmpty()) {
            int packed = queue.removeFirst();
            size++;

            int x = WorldPointUtil.unpackWorldX(packed);
            int y = WorldPointUtil.unpackWorldY(packed);
            int plane = WorldPointUtil.unpackWorldPlane(packed);
            for (OrdinalDirection direction : DIRECTIONS) {
                if (!canWalk(collisionMap, x, y, plane, direction)) {
                    continue;
                }

                int neighbor = WorldPointUtil.packWorldPoint(x + dx(direction), y + dy(direction), plane);
                if (packedPointToComponent.containsKey(neighbor)) {
                    continue;
                }
                packedPointToComponent.put(neighbor, componentId);
                queue.addLast(neighbor);
            }
        }

        return size;
    }

    private static Set<Integer> buildAnalysisBlockedTiles() {
        Set<Integer> blockedTiles = new HashSet<>();
        addBlockedLine(blockedTiles, 3268, 3230, 0, 3268, 3226, 0);
        addBlockedLine(blockedTiles, 3275, 3330, 0, 3287, 3330, 0);
        addBlockedLine(blockedTiles, 3069, 3242, 0, 3069, 3526, 0);
        addBlockedLine(blockedTiles, 2854, 3441, 0, 2857, 3441, 0);
        addBlockedLine(blockedTiles, 2652, 3595, 0, 2655, 3595, 0);
        return Set.copyOf(blockedTiles);
    }

    private static void addBlockedLine(Set<Integer> blockedTiles, int startX, int startY, int startPlane, int endX, int endY, int endPlane) {
        if (startPlane != endPlane) {
            throw new IllegalArgumentException("Analysis blocked lines must be on a single plane");
        }
        if (startX != endX && startY != endY) {
            throw new IllegalArgumentException("Analysis blocked lines must be horizontal or vertical");
        }

        int minX = Math.min(startX, endX);
        int maxX = Math.max(startX, endX);
        int minY = Math.min(startY, endY);
        int maxY = Math.max(startY, endY);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                blockedTiles.add(WorldPointUtil.packWorldPoint(x, y, startPlane));
            }
        }
    }

    public static final class ComponentComputation {
        private final Map<Integer, Integer> packedPointToComponent;
        private final long walkableTileCount;

        private ComponentComputation(Map<Integer, Integer> packedPointToComponent, long walkableTileCount) {
            this.packedPointToComponent = packedPointToComponent;
            this.walkableTileCount = walkableTileCount;
        }

        public Map<Integer, Integer> getPackedPointToComponent() {
            return packedPointToComponent;
        }

        public long getWalkableTileCount() {
            return walkableTileCount;
        }
    }
}
