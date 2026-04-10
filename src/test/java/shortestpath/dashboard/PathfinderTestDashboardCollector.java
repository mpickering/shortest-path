package shortestpath.dashboard;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import shortestpath.pathfinder.PathfinderConfig;
import shortestpath.pathfinder.PathfinderResult;

public final class PathfinderTestDashboardCollector {
    private static final Path OUTPUT_DIRECTORY = Paths.get("build", "reports", "pathfinder-test-dashboard");
    private static final Path REPORT_PATH = OUTPUT_DIRECTORY.resolve("report.json");
    private static final PathfinderDashboardReportWriter REPORT_WRITER = new PathfinderDashboardReportWriter();
    private static final PathfinderDashboardAssetWriter ASSET_WRITER = new PathfinderDashboardAssetWriter();
    private static final List<PathfinderDashboardModels.RunRecord> RUNS = new ArrayList<>();
    private static final long STARTED = System.currentTimeMillis();

    private PathfinderTestDashboardCollector() {
    }

    public static synchronized void record(
        String scenarioLabel,
        PathfinderResult result,
        PathfinderConfig config,
        Boolean assertionPassed,
        String assertionMessage
    ) {
        if (result == null || config == null) {
            return;
        }

        List<String> details = new ArrayList<>();
        if (scenarioLabel != null && !scenarioLabel.isBlank()) {
            details.add("Scenario: " + scenarioLabel);
        }

        RUNS.add(REPORT_WRITER.createRunRecord(
            scenarioLabel,
            "pathfinder-test",
            details,
            result,
            config,
            assertionPassed,
            assertionMessage));

        writeReport();
    }

    private static void writeReport() {
        try {
            ASSET_WRITER.writeAssets(OUTPUT_DIRECTORY);
            REPORT_WRITER.writeReport(
                REPORT_PATH,
                "Pathfinder Dashboard",
                "Routes captured from PathfinderTest",
                System.currentTimeMillis() - STARTED,
                RUNS);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write pathfinder test dashboard", e);
        }
    }
}
