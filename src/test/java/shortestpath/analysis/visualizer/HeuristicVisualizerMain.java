package shortestpath.analysis.visualizer;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarbitID;
import org.mockito.Mockito;
import shortestpath.TestShortestPathConfig;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.Pathfinder;
import shortestpath.pathfinder.PathfinderConfig;
import shortestpath.pathfinder.PathfinderResult;
import shortestpath.pathfinder.SplitFlagMap;
import shortestpath.pathfinder.TestPathfinderConfig;
import shortestpath.pathfinder.VisitedTiles;

public class HeuristicVisualizerMain {
    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        Palette palette = new Palette();
        PngTileRenderer renderer = new PngTileRenderer(palette);

        if ("synthetic".equals(parsed.demo)) {
            SyntheticDemoData.DemoContext demo = SyntheticDemoData.create();
            renderer.renderToFile(parsed.output, demo.region, demo.query, new ZeroHeuristicField(), demo.overlay,
                parsed.renderMode, parsed.scalingMode, parsed.clipMin, parsed.clipMax);
            System.out.println("Wrote " + parsed.output.toAbsolutePath());
            return;
        }

        RepoContext repo = RepoContext.create(parsed);
        renderer.renderToFile(parsed.output, repo.region, repo.query, new ZeroHeuristicField(), repo.overlay,
            parsed.renderMode, parsed.scalingMode, parsed.clipMin, parsed.clipMax);
        System.out.println("Wrote " + parsed.output.toAbsolutePath());
        if (repo.overlay instanceof VisitedTilesOverlay) {
            VisitedTilesOverlay visited = (VisitedTilesOverlay) repo.overlay;
            System.out.println("Visited tiles (unbanked): " + visited.countVisitedTiles(false));
            System.out.println("Visited tiles (banked): " + visited.countVisitedTiles(true));
        }
    }

    private static class RepoContext {
        private final TileRegion region;
        private final CollisionMapTileStateQuery query;
        private final SearchOverlay overlay;

        private RepoContext(TileRegion region, CollisionMapTileStateQuery query, SearchOverlay overlay) {
            this.region = region;
            this.query = query;
            this.overlay = overlay;
        }

        static RepoContext create(Arguments parsed) throws Exception {
            Client client = Mockito.mock(Client.class);
            Mockito.when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
            Mockito.when(client.getClientThread()).thenReturn(Thread.currentThread());
            Mockito.when(client.getBoostedSkillLevel(Mockito.any(Skill.class))).thenReturn(99);
            Mockito.when(client.getTotalLevel()).thenReturn(2277);
            Mockito.when(client.getVarbitValue(VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE)).thenReturn(1);
            Mockito.when(client.getVarbitValue(VarbitID.FAIRY2_QUEENCURE_QUEST)).thenReturn(100);

            TestShortestPathConfig config = new TestShortestPathConfig();
            config.setCalculationCutoffValue(50);
            config.setIncludeBankPathValue(true);
            PathfinderConfig pathfinderConfig = new TestPathfinderConfig(client, config);
            pathfinderConfig.refresh();

            SplitFlagMap splitFlagMap = SplitFlagMap.fromResources();
            CollisionMap collisionMap = new CollisionMap(splitFlagMap);
            Set<Integer> starts = parsed.start != null ? Set.of(parsed.start) : Collections.emptySet();
            Set<Integer> goals = parsed.goal != null ? Set.of(parsed.goal) : Collections.emptySet();
            Set<Integer> banks = pathfinderConfig.hasDestination("bank")
                ? new HashSet<>(pathfinderConfig.getDestinations("bank"))
                : Collections.emptySet();
            CollisionMapTileStateQuery query = new CollisionMapTileStateQuery(
                collisionMap,
                splitFlagMap.getRegionMapPlaneCounts(),
                starts,
                goals,
                banks,
                TransportMarkerIndex.fromResources());

            SearchOverlay overlay = SearchOverlay.NONE;
            if (parsed.showVisited || parsed.showPath) {
                if (parsed.start == null || parsed.goal == null) {
                    throw new IllegalArgumentException("--start and --goal are required when visited/path overlay is enabled");
                }
                Pathfinder pathfinder = new Pathfinder(pathfinderConfig, parsed.start, Set.of(parsed.goal));
                pathfinder.run();
                PathfinderResult result = pathfinder.getResult();
                List<shortestpath.pathfinder.PathStep> pathSteps = parsed.showPath && result != null ? result.getPathSteps() : List.of();
                VisitedTiles visitedTiles = parsed.showVisited ? pathfinder.getVisitedSnapshot() : null;
                overlay = new VisitedTilesOverlay(visitedTiles, parsed.region, pathSteps);
            }
            return new RepoContext(parsed.region, query, overlay);
        }
    }

    static class Arguments {
        private Path output = Paths.get("build/heuristic-visualizer/output.png");
        private TileRegion region;
        private RenderMode renderMode = RenderMode.BASE_MAP;
        private ScalingMode scalingMode = ScalingMode.LINEAR;
        private Double clipMin;
        private Double clipMax;
        private boolean showVisited;
        private boolean showPath;
        private String demo = "repo";
        private Integer start;
        private Integer goal;

        static Arguments parse(String[] args) {
            Arguments parsed = new Arguments();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--output":
                        parsed.output = Paths.get(args[++i]);
                        break;
                    case "--bounds":
                        parsed.region = parseRegion(args[++i]);
                        break;
                    case "--mode":
                        parsed.renderMode = RenderMode.valueOf(args[++i].trim().toUpperCase(Locale.ROOT));
                        break;
                    case "--scaling":
                        parsed.scalingMode = ScalingMode.valueOf(args[++i].trim().toUpperCase(Locale.ROOT));
                        break;
                    case "--clip-min":
                        parsed.clipMin = Double.parseDouble(args[++i]);
                        break;
                    case "--clip-max":
                        parsed.clipMax = Double.parseDouble(args[++i]);
                        break;
                    case "--demo":
                        parsed.demo = args[++i].trim().toLowerCase(Locale.ROOT);
                        break;
                    case "--show-visited":
                        parsed.showVisited = true;
                        if (parsed.renderMode == RenderMode.BASE_MAP) {
                            parsed.renderMode = RenderMode.VISITED_OVERLAY;
                        }
                        break;
                    case "--show-path":
                        parsed.showPath = true;
                        if (parsed.renderMode == RenderMode.BASE_MAP) {
                            parsed.renderMode = RenderMode.VISITED_OVERLAY;
                        }
                        break;
                    case "--start":
                        parsed.start = parsePoint(args[++i]);
                        break;
                    case "--goal":
                        parsed.goal = parsePoint(args[++i]);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            if (parsed.region == null) {
                parsed.region = "synthetic".equals(parsed.demo)
                    ? SyntheticDemoData.create().region
                    : defaultRepoRegion();
            }
            return parsed;
        }

        private static TileRegion defaultRepoRegion() {
            SplitFlagMap.fromResources();
            SplitFlagMap.RegionExtent extents = SplitFlagMap.getRegionExtents();
            return new TileRegion(
                extents.getMinX() * net.runelite.api.Constants.REGION_SIZE,
                extents.getMinY() * net.runelite.api.Constants.REGION_SIZE,
                (extents.getMaxX() + 1) * net.runelite.api.Constants.REGION_SIZE,
                (extents.getMaxY() + 1) * net.runelite.api.Constants.REGION_SIZE,
                0);
        }

        private static TileRegion parseRegion(String value) {
            String[] parts = value.split(",");
            if (parts.length != 5) {
                throw new IllegalArgumentException("Bounds must be minX,minY,maxXExclusive,maxYExclusive,plane");
            }
            return new TileRegion(
                Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()),
                Integer.parseInt(parts[3].trim()),
                Integer.parseInt(parts[4].trim()));
        }

        private static int parsePoint(String value) {
            String[] parts = value.split(",");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Point must be x,y,plane");
            }
            return WorldPointUtil.packWorldPoint(
                Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()));
        }
    }
}
