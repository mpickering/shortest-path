package shortestpath.analysis.visualizer;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import shortestpath.pathfinder.PathfinderHeuristic;
import shortestpath.pathfinder.PathfinderResult;
import shortestpath.pathfinder.SplitFlagMap;
import shortestpath.pathfinder.TestPathfinderConfig;
import shortestpath.pathfinder.TransportAvailability;
import shortestpath.pathfinder.TransportSnapshotLoader;
import shortestpath.pathfinder.TransportUsageMask;
import shortestpath.pathfinder.VisitedTiles;
import shortestpath.transport.Transport;

public class HeuristicVisualizerMain {
    private static final int KANDARIN_PROBE = WorldPointUtil.packWorldPoint(2567, 3134, 0);

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
        renderer.setComponentLabelIndex(repo.componentLabelIndex);
        if (parsed.renderAllTransportMasks && repo.heuristicField instanceof AbstractGraphHeuristicField) {
            printTransportMaskProbes((AbstractGraphHeuristicField) repo.heuristicField);
        }
        renderer.renderToFile(parsed.output, repo.region, repo.query, repo.heuristicField, repo.overlay,
            parsed.renderMode, parsed.scalingMode, parsed.clipMin, parsed.clipMax, parsed.transportMask);
        System.out.println("Wrote " + parsed.output.toAbsolutePath());
        if (parsed.transportMask != null) {
            System.out.println("Render transport mask: " + formatTransportMask(parsed.transportMask));
        }
        if (repo.componentLabelIndex != null) {
            System.out.println("Largest components:");
            for (ComponentLabelIndex.ComponentSize component : repo.componentLabelIndex.largestComponents(5)) {
                if (repo.heuristicField instanceof ComponentTeleportHeuristicField) {
                    ComponentTeleportHeuristicField componentHeuristic = (ComponentTeleportHeuristicField) repo.heuristicField;
                    System.out.println(" - component " + component.getComponentId() + ": " + component.getSize() + " tiles"
                        + ", entries=" + componentHeuristic.countEntriesInComponent(component.getComponentId())
                        + ", exits=" + componentHeuristic.countExitsInComponent(component.getComponentId()));
                } else {
                    System.out.println(" - component " + component.getComponentId() + ": " + component.getSize() + " tiles");
                }
            }
        }
        if (parsed.start != null) {
            HeuristicSample startSample = repo.heuristicField.sample(parsed.start,
                repo.query.getTileState(
                    WorldPointUtil.unpackWorldX(parsed.start),
                    WorldPointUtil.unpackWorldY(parsed.start),
                    WorldPointUtil.unpackWorldPlane(parsed.start)),
                parsed.transportMask != null ? parsed.transportMask : TransportUsageMask.ALL_AVAILABLE);
            String value = startSample.isDefined() ? Double.toString(startSample.getValue()) : "undefined";
            System.out.println("Heuristic at start " + formatPackedPoint(parsed.start) + ": " + value);
        }
        if (repo.overlay instanceof VisitedTilesOverlay) {
            VisitedTilesOverlay visited = (VisitedTilesOverlay) repo.overlay;
            System.out.println("Visited tiles (unbanked): " + visited.countVisitedTiles(false));
            System.out.println("Visited tiles (banked): " + visited.countVisitedTiles(true));
        }
        if (repo.heuristicField instanceof AbstractGraphHeuristicField) {
            printAbstractGraphDiagnostics((AbstractGraphHeuristicField) repo.heuristicField);
        }
        if (parsed.renderAllTransportMasks && repo.heuristicField instanceof AbstractGraphHeuristicField) {
            renderAllTransportMaskCharts(renderer, parsed, repo);
        }
    }

    private static class RepoContext {
        private final TileRegion region;
        private final CollisionMapTileStateQuery query;
        private final SearchOverlay overlay;
        private final ComponentLabelIndex componentLabelIndex;
        private final HeuristicField heuristicField;

        private RepoContext(TileRegion region, CollisionMapTileStateQuery query, SearchOverlay overlay,
            ComponentLabelIndex componentLabelIndex, HeuristicField heuristicField) {
            this.region = region;
            this.query = query;
            this.overlay = overlay;
            this.componentLabelIndex = componentLabelIndex;
            this.heuristicField = heuristicField;
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
            PathfinderConfig pathfinderConfig = parsed.transportSnapshot == null
                ? new TestPathfinderConfig(client, config)
                : new SnapshotPathfinderConfig(client, config, TransportSnapshotLoader.load(parsed.transportSnapshot));
            pathfinderConfig.refresh();

            SplitFlagMap splitFlagMap = SplitFlagMap.fromResources();
            CollisionMap collisionMap = new CollisionMap(splitFlagMap);
            ComponentLabelIndex componentLabelIndex = (parsed.renderMode == RenderMode.COMPONENTS
                || "component-teleport".equals(parsed.heuristicName)
                || "abstract-graph".equals(parsed.heuristicName))
                ? ComponentLabelIndex.build(collisionMap, splitFlagMap)
                : null;
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
            HeuristicField heuristicField = createHeuristicField(parsed, componentLabelIndex, collisionMap);
            if (parsed.showVisited || parsed.showPath) {
                if (parsed.start == null || parsed.goal == null) {
                    throw new IllegalArgumentException("--start and --goal are required when visited/path overlay is enabled");
                }
                PathfinderHeuristic searchHeuristic = heuristicField instanceof PathfinderHeuristic
                    ? (PathfinderHeuristic) heuristicField
                    : PathfinderHeuristic.ZERO;
                Pathfinder pathfinder = Pathfinder.withHeuristic(pathfinderConfig, parsed.start, Set.of(parsed.goal), searchHeuristic);
                pathfinder.run();
                PathfinderResult result = pathfinder.getResult();
                List<shortestpath.pathfinder.PathStep> pathSteps = parsed.showPath && result != null ? result.getPathSteps() : List.of();
                VisitedTiles visitedTiles = parsed.showVisited ? pathfinder.getVisitedSnapshot() : null;
                overlay = new VisitedTilesOverlay(visitedTiles, parsed.region, pathSteps);
                if (result != null) {
                    System.out.println("Search stats:");
                    System.out.println(" - reached: " + result.isReached());
                    System.out.println(" - nodes checked: " + result.getNodesChecked());
                    System.out.println(" - transports checked: " + result.getTransportsChecked());
                    System.out.println(" - elapsed nanos: " + result.getElapsedNanos());
                    System.out.println(" - termination: " + result.getTerminationReason());
                    printPathTransports(result.getPathSteps(), pathfinderConfig);
                }
            }
            return new RepoContext(parsed.region, query, overlay, componentLabelIndex, heuristicField);
        }

        private static HeuristicField createHeuristicField(
            Arguments parsed,
            ComponentLabelIndex componentLabelIndex,
            CollisionMap collisionMap
        ) {
            if ("component-teleport".equals(parsed.heuristicName)) {
                if (parsed.goal == null) {
                    throw new IllegalArgumentException("--goal is required for heuristic=component-teleport");
                }
                return new ComponentTeleportHeuristicField(componentLabelIndex, parsed.goal);
            }
            if ("abstract-graph".equals(parsed.heuristicName)) {
                if (parsed.goal == null) {
                    throw new IllegalArgumentException("--goal is required for heuristic=abstract-graph");
                }
                return new AbstractGraphHeuristicField(collisionMap, componentLabelIndex, parsed.goal);
            }
            return new ZeroHeuristicField();
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
        private String heuristicName = "zero";
        private Integer start;
        private Integer goal;
        private Integer transportMask;
        private boolean renderAllTransportMasks;
        private Path transportSnapshot;

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
                    case "--heuristic":
                        parsed.heuristicName = args[++i].trim().toLowerCase(Locale.ROOT);
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
                    case "--transport-mask":
                        parsed.transportMask = parseTransportMask(args[++i]);
                        break;
                    case "--transport-snapshot":
                        parsed.transportSnapshot = Paths.get(args[++i]);
                        break;
                    case "--render-all-transport-masks":
                        parsed.renderAllTransportMasks = true;
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

        private static int parseTransportMask(String value) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            switch (normalized) {
                case "all":
                case "all-available":
                    return TransportUsageMask.ALL_AVAILABLE;
                case "no-fairy-ring":
                    return TransportUsageMask.ALL_AVAILABLE & ~TransportUsageMask.FAIRY_RING;
                case "no-spirit-tree":
                    return TransportUsageMask.ALL_AVAILABLE & ~TransportUsageMask.SPIRIT_TREE;
                case "no-fairy-ring-or-spirit-tree":
                case "none":
                    return 0;
                default:
                    throw new IllegalArgumentException("Unknown transport mask: " + value);
            }
        }
    }

    private static String formatPackedPoint(int packedPoint) {
        return WorldPointUtil.unpackWorldX(packedPoint)
            + "," + WorldPointUtil.unpackWorldY(packedPoint)
            + "," + WorldPointUtil.unpackWorldPlane(packedPoint);
    }

    private static String formatTransportMask(int transportMask) {
        if (transportMask == TransportUsageMask.ALL_AVAILABLE) {
            return "ALL_AVAILABLE";
        }
        if (transportMask == (TransportUsageMask.ALL_AVAILABLE & ~TransportUsageMask.FAIRY_RING)) {
            return "NO_FAIRY_RING";
        }
        if (transportMask == (TransportUsageMask.ALL_AVAILABLE & ~TransportUsageMask.SPIRIT_TREE)) {
            return "NO_SPIRIT_TREE";
        }
        if (transportMask == 0) {
            return "NO_FAIRY_RING_OR_SPIRIT_TREE";
        }
        return Integer.toString(transportMask);
    }

    private static void renderAllTransportMaskCharts(PngTileRenderer renderer, Arguments parsed, RepoContext repo) throws IOException {
        Path output = parsed.output;
        String fileName = output.getFileName().toString();
        int extensionIndex = fileName.lastIndexOf('.');
        String baseName = extensionIndex >= 0 ? fileName.substring(0, extensionIndex) : fileName;
        String extension = extensionIndex >= 0 ? fileName.substring(extensionIndex) : ".png";
        Path parent = output.getParent() != null ? output.getParent() : Paths.get(".");

        renderTransportMaskChart(renderer, parent.resolve(baseName + "-all-available" + extension), parsed, repo, TransportUsageMask.ALL_AVAILABLE);
        renderTransportMaskChart(renderer, parent.resolve(baseName + "-no-fairy-ring" + extension), parsed, repo,
            TransportUsageMask.ALL_AVAILABLE & ~TransportUsageMask.FAIRY_RING);
        renderTransportMaskChart(renderer, parent.resolve(baseName + "-no-spirit-tree" + extension), parsed, repo,
            TransportUsageMask.ALL_AVAILABLE & ~TransportUsageMask.SPIRIT_TREE);
        renderTransportMaskChart(renderer, parent.resolve(baseName + "-no-hub-reuse" + extension), parsed, repo, 0);
        renderTransportMaskDifference(renderer, parent.resolve(baseName + "-diff-no-fairy-ring" + extension), parsed, repo,
            TransportUsageMask.ALL_AVAILABLE & ~TransportUsageMask.FAIRY_RING);
        renderTransportMaskDifference(renderer, parent.resolve(baseName + "-diff-no-spirit-tree" + extension), parsed, repo,
            TransportUsageMask.ALL_AVAILABLE & ~TransportUsageMask.SPIRIT_TREE);
        renderTransportMaskDifference(renderer, parent.resolve(baseName + "-diff-no-hub-reuse" + extension), parsed, repo, 0);
    }

    private static void renderTransportMaskChart(
        PngTileRenderer renderer,
        Path output,
        Arguments parsed,
        RepoContext repo,
        int transportMask
    ) throws IOException {
        renderer.renderToFile(output, repo.region, repo.query, repo.heuristicField, repo.overlay,
            RenderMode.HEURISTIC_VALUE, parsed.scalingMode, parsed.clipMin, parsed.clipMax, transportMask);
        System.out.println("Wrote " + output.toAbsolutePath() + " [" + formatTransportMask(transportMask) + "]");
    }

    private static void renderTransportMaskDifference(
        PngTileRenderer renderer,
        Path output,
        Arguments parsed,
        RepoContext repo,
        int comparisonTransportMask
    ) throws IOException {
        renderer.renderDifferenceToFile(output, repo.region, repo.query, repo.heuristicField,
            TransportUsageMask.ALL_AVAILABLE, comparisonTransportMask);
        System.out.println("Wrote " + output.toAbsolutePath()
            + " [ALL_AVAILABLE vs " + formatTransportMask(comparisonTransportMask) + "]");
    }

    private static void printAttachmentChoice(AbstractGraphHeuristicField heuristicField, int packedPoint, int transportMask) {
        AbstractGraphHeuristicField.AttachmentChoice choice = heuristicField.bestAttachment(packedPoint, transportMask);
        if (choice == null) {
            System.out.println("Attachment choice for " + formatPackedPoint(packedPoint) + " [" + formatTransportMask(transportMask) + "]: none");
            return;
        }
        System.out.println("Attachment choice for " + formatPackedPoint(packedPoint) + " [" + formatTransportMask(transportMask) + "]: "
            + formatPackedPoint(choice.getAttachmentPackedPoint())
            + " source->attachment=" + choice.getSourceToAttachmentDistance()
            + " attachment->goal=" + choice.getAttachmentToGoalDistance()
            + " total=" + choice.getTotalLowerBound());
        if (!choice.getWitnessSteps().isEmpty()) {
            System.out.println("Witness path:");
            for (AbstractGraphHeuristicField.WitnessStep step : choice.getWitnessSteps()) {
                System.out.println(" - " + step.getDescription());
            }
        }
    }

    private static void printTransportMaskProbes(AbstractGraphHeuristicField heuristicField) {
        printAttachmentChoice(heuristicField, KANDARIN_PROBE, TransportUsageMask.ALL_AVAILABLE);
        printAttachmentChoice(heuristicField, KANDARIN_PROBE, TransportUsageMask.ALL_AVAILABLE & ~TransportUsageMask.FAIRY_RING);
        printAttachmentChoice(heuristicField, KANDARIN_PROBE, TransportUsageMask.ALL_AVAILABLE & ~TransportUsageMask.SPIRIT_TREE);
        printAttachmentChoice(heuristicField, KANDARIN_PROBE, 0);
    }

    private static void printAbstractGraphDiagnostics(AbstractGraphHeuristicField heuristicField) {
        AbstractTransportGraph.BuildStats buildStats = heuristicField.getGraph().getBuildStats();
        AbstractTransportGraph.ReverseSearchResult reverseStats = heuristicField.getReverseSearchResult();

        System.out.println("Abstract graph:");
        System.out.println(" - components: " + buildStats.getComponentCount());
        System.out.println(" - walkable tiles: " + buildStats.getWalkableTileCount());
        System.out.println(" - attachment nodes: " + buildStats.getAttachmentNodeCount());
        System.out.println(" - hub nodes: " + buildStats.getHubNodeCount());
        System.out.println(" - global source nodes: " + buildStats.getGlobalSourceNodeCount());
        System.out.println(" - stored edges: " + buildStats.getStoredEdgeCount());
        System.out.println(" - attachment counts per component: min="
            + buildStats.getMinAttachmentsPerComponent()
            + ", median=" + buildStats.getMedianAttachmentsPerComponent()
            + ", p95=" + buildStats.getP95AttachmentsPerComponent()
            + ", max=" + buildStats.getMaxAttachmentsPerComponent());
        System.out.println(" - total implicit walk neighbor pairs: " + buildStats.getTotalImplicitWalkNeighborPairs());
        System.out.println(" - build elapsed nanos: " + buildStats.getBuildElapsedNanos());

        if (!buildStats.getStoredEdgeCountsByKind().isEmpty()) {
            System.out.println(" - stored edges by kind:");
            for (Map.Entry<AbstractTransportGraph.EdgeKind, Integer> entry : buildStats.getStoredEdgeCountsByKind().entrySet()) {
                System.out.println("   " + entry.getKey() + ": " + entry.getValue());
            }
        }

        if (!buildStats.getHubCompressionByType().isEmpty()) {
            System.out.println(" - hub compression savings:");
            buildStats.getHubCompressionByType().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().name()))
                .forEach(entry -> System.out.println("   " + entry.getKey()
                    + ": naive=" + entry.getValue().getNaiveEdgeCount()
                    + ", compressed=" + entry.getValue().getCompressedEdgeCount()));
        }

        System.out.println("Reverse Dijkstra:");
        System.out.println(" - seeded nodes: " + reverseStats.getSeededNodeCount());
        System.out.println(" - reachable nodes: " + reverseStats.getReachableNodeCount());
        System.out.println(" - pops: " + reverseStats.getPopCount());
        System.out.println(" - stored-edge relaxations: " + reverseStats.getStoredEdgeRelaxCount());
        System.out.println(" - implicit-walk relaxations: " + reverseStats.getImplicitWalkRelaxCount());
        System.out.println(" - elapsed nanos: " + reverseStats.getElapsedNanos());

        System.out.println("Heuristic queries:");
        System.out.println(" - count: " + heuristicField.getEvaluationCount());
        System.out.println(" - total nanos: " + heuristicField.getTotalEvaluationNanos());
        System.out.println(" - average nanos: " + averageNanos(heuristicField.getTotalEvaluationNanos(), heuristicField.getEvaluationCount()));
    }

    private static long averageNanos(long totalNanos, long count) {
        return count <= 0L ? 0L : totalNanos / count;
    }

    private static final class SnapshotPathfinderConfig extends TestPathfinderConfig {
        private final TransportAvailability withoutBank;
        private final TransportAvailability withBank;

        private SnapshotPathfinderConfig(
            Client client,
            TestShortestPathConfig config,
            TransportSnapshotLoader.Snapshot snapshot
        ) {
            super(client, config);
            this.withoutBank = snapshot.getWithoutBank();
            this.withBank = snapshot.getWithBank();
        }

        @Override
        public TransportAvailability getTransportAvailability(boolean bankVisited) {
            return bankVisited ? withBank : withoutBank;
        }
    }

    private static void printPathTransports(List<shortestpath.pathfinder.PathStep> path, PathfinderConfig config) {
        List<String> transports = describePathTransports(path, config);
        System.out.println("Path transports:");
        if (transports.isEmpty()) {
            System.out.println(" - none");
            return;
        }
        for (String transport : transports) {
            System.out.println(" - " + transport);
        }
    }

    private static List<String> describePathTransports(List<shortestpath.pathfinder.PathStep> path, PathfinderConfig config) {
        List<String> steps = new ArrayList<>();
        for (int i = 1; i < path.size(); i++) {
            shortestpath.pathfinder.PathStep originStep = path.get(i - 1);
            shortestpath.pathfinder.PathStep destinationStep = path.get(i);
            int origin = originStep.getPackedPosition();
            int destination = destinationStep.getPackedPosition();
            boolean bankVisited = destinationStep.isBankVisited();

            Set<Transport> originTransports = new HashSet<>(config.getTransportsPacked(bankVisited).getOrDefault(origin, Set.of()));
            originTransports.addAll(config.getUsableTeleports(bankVisited));
            Set<String> edgeDescriptions = new HashSet<>();

            for (Transport transport : originTransports) {
                if (transport.getDestination() == destination) {
                    edgeDescriptions.add(describeTransportStep(transport, origin, destination));
                }
            }
            steps.addAll(edgeDescriptions);
        }
        return steps;
    }

    private static String describeTransportStep(Transport transport, int origin, int destination) {
        StringBuilder builder = new StringBuilder();
        builder.append(formatPackedPoint(origin))
            .append(" -> ")
            .append(formatPackedPoint(destination))
            .append(" : ")
            .append(transport.getType() != null ? transport.getType().name() : "TRANSPORT");

        if (transport.getDisplayInfo() != null && !transport.getDisplayInfo().isBlank()) {
            builder.append(" [").append(transport.getDisplayInfo()).append(']');
        } else if (transport.getObjectInfo() != null && !transport.getObjectInfo().isBlank()) {
            builder.append(" [").append(transport.getObjectInfo()).append(']');
        }

        return builder.toString();
    }
}
