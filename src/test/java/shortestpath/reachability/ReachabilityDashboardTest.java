package shortestpath.reachability;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;
import org.junit.Assume;
import shortestpath.TeleportationItem;
import shortestpath.TestShortestPathConfig;
import shortestpath.WorldPointUtil;
import shortestpath.dashboard.PathfinderDashboardAssetWriter;
import shortestpath.dashboard.PathfinderDashboardModels;
import shortestpath.dashboard.PathfinderDashboardReportWriter;
import shortestpath.pathfinder.Pathfinder;
import shortestpath.pathfinder.PathfinderConfig;
import shortestpath.pathfinder.PathfinderResult;
import shortestpath.pathfinder.PathStep;
import shortestpath.pathfinder.TestPathfinderConfig;
import shortestpath.transport.Transport;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ReachabilityDashboardTest {
    private static final Path CLUE_LOCATIONS_CSV = Paths.get("/home/matt/shortest-path-haskell/clue_locations_full.csv");
    private static final int START = WorldPointUtil.packWorldPoint(3185, 3436, 0);
    private static final int MAX_TARGETS = Integer.getInteger("reachability.maxTargets", Integer.MAX_VALUE);
    private static final Path OUTPUT_DIRECTORY = Paths.get("build", "reports", "reachability-dashboard");
    private static final Path REPORT_PATH = OUTPUT_DIRECTORY.resolve("report.json");

    private final ReachabilityTargetLoader targetLoader = new ReachabilityTargetLoader();
    private final PathfinderDashboardReportWriter reportWriter = new PathfinderDashboardReportWriter();
    private final PathfinderDashboardAssetWriter assetWriter = new PathfinderDashboardAssetWriter();

    private Client client;
    private TestShortestPathConfig config;
    private PathfinderConfig pathfinderConfig;

    @Before
    public void setUp() {
        client = mock(Client.class);
        config = new TestShortestPathConfig();
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getClientThread()).thenReturn(Thread.currentThread());
        when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
        when(client.getTotalLevel()).thenReturn(2277);
        config.setCalculationCutoffValue(500);
        config.setUseTeleportationItemsValue(TeleportationItem.ALL);

        pathfinderConfig = new TestPathfinderConfig(client, config);
        pathfinderConfig.refresh();
    }

    @Test
    public void allTargetsReachableFromBankStart() throws IOException {
        Assume.assumeTrue("Enable with -DrunReachabilityVerification=true",
            Boolean.getBoolean("runReachabilityVerification"));

        List<ReachabilityTarget> allTargets = targetLoader.loadFromCsv(CLUE_LOCATIONS_CSV);
        List<ReachabilityTarget> targets = allTargets.subList(0, Math.min(MAX_TARGETS, allTargets.size()));
        List<PathfinderDashboardModels.RunRecord> runs = new ArrayList<>();
        Map<String, String> routeSummary = new LinkedHashMap<>();
        long started = System.currentTimeMillis();

        for (ReachabilityTarget target : targets) {
            Pathfinder pathfinder = new Pathfinder(pathfinderConfig, START, Set.of(target.getPackedPoint()));
            pathfinder.run();
            PathfinderResult result = pathfinder.getResult();
            List<PathStep> path = result != null ? result.getPathSteps() : List.of();
            int pathLength = path.size();
            double elapsedMillis = result != null ? (result.getElapsedNanos() / 1_000_000.0) : 0.0;
            int nodesChecked = result != null ? result.getNodesChecked() : 0;
            int transportsChecked = result != null ? result.getTransportsChecked() : 0;
            boolean reached = result != null
                && !path.isEmpty()
                && WorldPointUtil.distanceBetween(
                    path.get(path.size() - 1).getPackedPosition(),
                    target.getPackedPoint()) <= 1;

            routeSummary.put(
                target.getDescription(),
                String.format(
                    "%.2f ms, %d steps, %d nodes, %d transports, %s%s | start: %s | end: %s",
                    elapsedMillis,
                    pathLength,
                    nodesChecked,
                    transportsChecked,
                    result != null ? result.getTerminationReason() : "NO_RESULT",
                    reached ? "" : " [UNREACHABLE: " + (result != null ? result.getTerminationReason() : "NO_RESULT") + "]",
                    formatRouteSnippet(result, true),
                    formatRouteSnippet(result, false)));

            if (result != null) {
                List<String> details = List.of(
                    "Dataset: " + CLUE_LOCATIONS_CSV.getFileName(),
                    "Description: " + target.getDescription(),
                    "Expected reachable: true");
                runs.add(reportWriter.createRunRecord(
                    target.getDescription(),
                    "reachability",
                    details,
                    result,
                    pathfinderConfig,
                    null,
                    null));
            }
        }

        assetWriter.writeAssets(OUTPUT_DIRECTORY);
        reportWriter.writeReport(
            REPORT_PATH,
            "Pathfinder Dashboard",
            "Reachability sweep from bank start",
            System.currentTimeMillis() - started,
            runs);

        System.out.println("Reachability route summary:");
        System.out.println(" - tested targets: " + targets.size() + "/" + allTargets.size());
        for (Map.Entry<String, String> entry : routeSummary.entrySet()) {
            System.out.println(" - " + entry.getKey() + ": " + entry.getValue());
        }

        long unreachable = runs.stream().filter(run -> !run.reached).count();
        assertTrue("Unreachable targets found. See " + REPORT_PATH + " and " + OUTPUT_DIRECTORY.resolve("index.html"),
            unreachable == 0);
    }

    private String formatRouteSnippet(PathfinderResult result, boolean fromStart) {
        if (result == null || result.getPathSteps().isEmpty()) {
            return "n/a";
        }

        List<PathStep> path = result.getPathSteps();
        List<String> segments = new ArrayList<>();
        int pathSize = path.size();
        int snippetLength = Math.min(3, pathSize);
        int startIndex = fromStart ? 0 : Math.max(0, pathSize - snippetLength);
        int endIndex = fromStart ? snippetLength : pathSize;

        for (int i = startIndex; i < endIndex; i++) {
            int point = path.get(i).getPackedPosition();
            StringBuilder entry = new StringBuilder(formatPoint(point));
            if (i + 1 < pathSize && i + 1 < endIndex + (fromStart ? 1 : 0)) {
                String transport = describeTransport(point, path.get(i + 1).getPackedPosition());
                if (transport != null) {
                    entry.append(" -> ").append(transport);
                }
            }
            segments.add(entry.toString());
        }

        return String.join(" | ", segments);
    }

    private String describeTransport(int origin, int destination) {
        for (Transport transport : pathfinderConfig.getTransports().getOrDefault(origin, Set.of())) {
            if (transport.getDestination() == destination) {
                String displayInfo = transport.getDisplayInfo();
                if (displayInfo != null && !displayInfo.isBlank()) {
                    return displayInfo;
                }

                String objectInfo = transport.getObjectInfo();
                if (objectInfo != null && !objectInfo.isBlank()) {
                    return objectInfo;
                }

                return transport.getType() != null ? transport.getType().name() : "transport";
            }
        }

        return null;
    }

    private static String formatPoint(int packedPoint) {
        return String.format("(%d,%d,%d)",
            WorldPointUtil.unpackWorldX(packedPoint),
            WorldPointUtil.unpackWorldY(packedPoint),
            WorldPointUtil.unpackWorldPlane(packedPoint));
    }
}
