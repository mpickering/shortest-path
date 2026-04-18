package shortestpath.analysis.visualizer;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.PathStep;

public final class SyntheticDemoData {
    private SyntheticDemoData() {
    }

    public static DemoContext create() {
        TileRegion region = new TileRegion(0, 0, 32, 24, 0);
        int start = WorldPointUtil.packWorldPoint(3, 3, 0);
        int goal = WorldPointUtil.packWorldPoint(27, 18, 0);
        int bank = WorldPointUtil.packWorldPoint(7, 15, 0);
        int teleportEntry = WorldPointUtil.packWorldPoint(5, 19, 0);
        int teleportExit = WorldPointUtil.packWorldPoint(24, 5, 0);

        Set<Integer> blocked = new HashSet<>();
        for (int x = 10; x < 14; x++) {
            for (int y = 4; y < 18; y++) {
                blocked.add(WorldPointUtil.packWorldPoint(x, y, 0));
            }
        }
        for (int x = 18; x < 28; x++) {
            blocked.add(WorldPointUtil.packWorldPoint(x, 11, 0));
        }
        blocked.remove(WorldPointUtil.packWorldPoint(12, 9, 0));
        blocked.remove(WorldPointUtil.packWorldPoint(22, 11, 0));

        Set<Integer> visited = new HashSet<>();
        for (int x = 2; x < 12; x++) {
            for (int y = 2; y < 8; y++) {
                visited.add(WorldPointUtil.packWorldPoint(x, y, 0));
            }
        }
        for (int x = 20; x < 27; x++) {
            for (int y = 3; y < 10; y++) {
                visited.add(WorldPointUtil.packWorldPoint(x, y, 0));
            }
        }

        List<PathStep> path = List.of(
            new PathStep(start, false),
            new PathStep(WorldPointUtil.packWorldPoint(4, 3, 0), false),
            new PathStep(WorldPointUtil.packWorldPoint(5, 3, 0), false),
            new PathStep(teleportEntry, false),
            new PathStep(teleportExit, false),
            new PathStep(WorldPointUtil.packWorldPoint(25, 6, 0), false),
            new PathStep(WorldPointUtil.packWorldPoint(26, 7, 0), false),
            new PathStep(WorldPointUtil.packWorldPoint(27, 8, 0), false),
            new PathStep(WorldPointUtil.packWorldPoint(27, 9, 0), false),
            new PathStep(WorldPointUtil.packWorldPoint(27, 10, 0), false),
            new PathStep(goal, false)
        );

        TileStateQuery query = new SyntheticTileStateQuery(
            blocked,
            Set.of(start),
            Set.of(goal),
            Set.of(bank),
            Set.of(teleportEntry),
            Set.of(teleportExit));
        SearchOverlay overlay = new SyntheticOverlay(visited, Collections.emptySet(), path);
        return new DemoContext(region, query, overlay, start, goal);
    }

    public static final class DemoContext {
        public final TileRegion region;
        public final TileStateQuery query;
        public final SearchOverlay overlay;
        public final int start;
        public final int goal;

        private DemoContext(TileRegion region, TileStateQuery query, SearchOverlay overlay, int start, int goal) {
            this.region = region;
            this.query = query;
            this.overlay = overlay;
            this.start = start;
            this.goal = goal;
        }
    }

    private static class SyntheticTileStateQuery implements TileStateQuery {
        private final Set<Integer> blocked;
        private final Set<Integer> starts;
        private final Set<Integer> goals;
        private final Set<Integer> banks;
        private final Set<Integer> teleportEntries;
        private final Set<Integer> teleportExits;

        private SyntheticTileStateQuery(Set<Integer> blocked, Set<Integer> starts, Set<Integer> goals, Set<Integer> banks,
            Set<Integer> teleportEntries, Set<Integer> teleportExits) {
            this.blocked = blocked;
            this.starts = starts;
            this.goals = goals;
            this.banks = banks;
            this.teleportEntries = teleportEntries;
            this.teleportExits = teleportExits;
        }

        @Override
        public TileState getTileState(int x, int y, int plane) {
            int packed = WorldPointUtil.packWorldPoint(x, y, plane);
            return blocked.contains(packed) ? TileState.BLOCKED : TileState.WALKABLE;
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
            return teleportEntries.contains(packedPoint);
        }

        @Override
        public boolean isTeleportExit(int packedPoint) {
            return teleportExits.contains(packedPoint);
        }

        @Override
        public boolean isBank(int packedPoint) {
            return banks.contains(packedPoint);
        }
    }

    private static class SyntheticOverlay implements SearchOverlay {
        private final Set<Integer> visited;
        private final Set<Integer> visitedWithBank;
        private final Set<Integer> path;

        private SyntheticOverlay(Set<Integer> visited, Set<Integer> visitedWithBank, List<PathStep> pathSteps) {
            this.visited = visited;
            this.visitedWithBank = visitedWithBank;
            Set<Integer> pathTiles = new HashSet<>();
            for (PathStep step : pathSteps) {
                pathTiles.add(step.getPackedPosition());
            }
            this.path = pathTiles;
        }

        @Override
        public boolean isVisited(int packedPoint) {
            return visited.contains(packedPoint);
        }

        @Override
        public boolean isVisitedWithBank(int packedPoint) {
            return visitedWithBank.contains(packedPoint);
        }

        @Override
        public boolean isOnPath(int packedPoint) {
            return path.contains(packedPoint);
        }
    }
}
