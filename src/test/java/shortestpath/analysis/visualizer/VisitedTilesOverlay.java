package shortestpath.analysis.visualizer;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import shortestpath.pathfinder.PathStep;
import shortestpath.pathfinder.VisitedTiles;

public class VisitedTilesOverlay implements SearchOverlay {
    private final VisitedTiles visitedTiles;
    private final TileRegion region;
    private final Set<Integer> pathTiles;

    public VisitedTilesOverlay(VisitedTiles visitedTiles, TileRegion region, List<PathStep> pathSteps) {
        this.visitedTiles = visitedTiles;
        this.region = region;
        Set<Integer> collected = new HashSet<>();
        if (pathSteps != null) {
            for (PathStep step : pathSteps) {
                collected.add(step.getPackedPosition());
            }
        }
        this.pathTiles = Collections.unmodifiableSet(collected);
    }

    @Override
    public boolean isVisited(int packedPoint) {
        return visitedTiles != null && visitedTiles.get(packedPoint, false);
    }

    @Override
    public boolean isVisitedWithBank(int packedPoint) {
        return visitedTiles != null && visitedTiles.get(packedPoint, true);
    }

    @Override
    public boolean isOnPath(int packedPoint) {
        return pathTiles.contains(packedPoint);
    }

    public int countVisitedTiles(boolean bankVisited) {
        if (visitedTiles == null) {
            return 0;
        }
        int count = 0;
        for (int x = region.getMinX(); x < region.getMaxXExclusive(); x++) {
            for (int y = region.getMinY(); y < region.getMaxYExclusive(); y++) {
                int packed = shortestpath.WorldPointUtil.packWorldPoint(x, y, region.getPlane());
                if (visitedTiles.get(packed, bankVisited)) {
                    count++;
                }
            }
        }
        return count;
    }
}
