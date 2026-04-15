package shortestpath.benchmark;

import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;
import org.mockito.Mockito;
import shortestpath.TeleportationItem;
import shortestpath.WorldPointUtil;
import shortestpath.benchmark.generated.GeneratedReachabilityBenchmarkScenarios;
import shortestpath.pathfinder.PathStep;
import shortestpath.pathfinder.Pathfinder;
import shortestpath.pathfinder.PathfinderResult;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.mockito.ArgumentMatchers.any;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MILLISECONDS)
public class ReachabilityBenchmark {
    private static final int START = WorldPointUtil.packWorldPoint(3185, 3436, 0);

    @State(Scope.Benchmark)
    public static class BenchmarkState extends GeneratedReachabilityBenchmarkScenarios.ScenarioState {
        private BenchmarkPathfinderConfig pathfinderConfig;
        private int targetPoint;
        private int expectedPathLength;
        private boolean expectedReached;
        private boolean expectedBankVisited;
        private long expectedElapsedNanos;
        private int expectedNodesChecked;
        private int expectedTransportsChecked;

        @Setup(Level.Trial)
        public void setup() {
            Client client = Mockito.mock(Client.class);
            ReachabilityBenchmarkConfig config = new ReachabilityBenchmarkConfig();
            config.setCalculationCutoffValue(Integer.getInteger("reachability.benchmark.cutoff", 500));
            config.setUseTeleportationItemsValue(TeleportationItem.ALL);

            Mockito.when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
            Mockito.when(client.getClientThread()).thenReturn(Thread.currentThread());
            Mockito.when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
            Mockito.when(client.getTotalLevel()).thenReturn(2277);

            pathfinderConfig = new BenchmarkPathfinderConfig(client, config);
            pathfinderConfig.refresh();
            GeneratedReachabilityBenchmarkScenarios.Scenario scenario =
                GeneratedReachabilityBenchmarkScenarios.byId(scenarioId);
            targetPoint = WorldPointUtil.packWorldPoint(scenario.getX(), scenario.getY(), scenario.getPlane());

            Pathfinder verificationPathfinder = new Pathfinder(pathfinderConfig, START, Set.of(targetPoint));
            verificationPathfinder.run();
            PathfinderResult verificationResult = verificationPathfinder.getResult();
            expectedReached = verificationResult != null && verificationResult.isReached();
            expectedPathLength = verificationResult != null ? verificationResult.getPathSteps().size() : 0;
            expectedBankVisited = verificationResult != null
                && verificationResult.getPathSteps().stream().anyMatch(PathStep::isBankVisited);
            expectedElapsedNanos = verificationResult != null ? verificationResult.getElapsedNanos() : 0L;
            expectedNodesChecked = verificationResult != null ? verificationResult.getNodesChecked() : 0;
            expectedTransportsChecked = verificationResult != null ? verificationResult.getTransportsChecked() : 0;
            System.out.printf(
                "Scenario %s: reached=%s, pathLength=%d, bankVisited=%s, elapsedNanos=%d, nodesChecked=%d, transportsChecked=%d%n",
                scenarioId,
                expectedReached,
                expectedPathLength,
                expectedBankVisited,
                expectedElapsedNanos,
                expectedNodesChecked,
                expectedTransportsChecked);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            pathfinderConfig = null;
        }
    }

    @Benchmark
    public void benchmarkScenario(BenchmarkState state, Blackhole blackhole) {
        Pathfinder pathfinder = new Pathfinder(state.pathfinderConfig, START, Set.of(state.targetPoint));
        pathfinder.run();
        PathfinderResult result = pathfinder.getResult();
        if (result == null || !result.isReached()) {
            throw new IllegalStateException("Benchmark scenario failed: " + state.scenarioId);
        }
        blackhole.consume(result);
    }
}
