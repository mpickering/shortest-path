package shortestpath.pathfinder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import lombok.Getter;
import shortestpath.WorldPointUtil;

public class Pathfinder implements Runnable {
    private final PathfinderStats stats;
    @Getter
    private volatile boolean done = false;
    private volatile boolean cancelled = false;

    @Getter
    private final int start;
    @Getter
    private final Set<Integer> targets;

    private final PathfinderConfig config;
    private final CollisionMap map;
    private final boolean targetInWilderness;
    private final Runnable completionCallback;
    private final PathfinderHeuristic heuristic;

    private final Queue<OpenNode> open = new PriorityQueue<>(256, this::compareOpenNodes);
    private final Map<SearchStateKey, Integer> bestKnownCost = new HashMap<>(4096);
    private final VisitedTiles visited;
    private VisitedTiles visitedSnapshot;

    private List<PathStep> pathSteps = List.of();
    private boolean pathNeedsUpdate = false;
    private Node bestLastNode;
    private int bestRemainingDistance = Integer.MAX_VALUE;
    private int bestTravelledDistance = Integer.MAX_VALUE;
    private int bestX = Integer.MAX_VALUE;
    private int bestY = Integer.MAX_VALUE;
    private int reachedTarget = WorldPointUtil.UNDEFINED;
    private PathTerminationReason terminationReason;
    /**
     * Teleportation transports are updated when this changes.
     * Can be either:
     * 0 = all teleports can be used (e.g. Chronicle)
     * 20 = most teleports can be used (e.g. Varrock Teleport)
     * 30 = some teleports can be used (e.g. Amulet of Glory)
     * 31 = no teleports can be used
     */
    private int wildernessLevel;

    public Pathfinder(PathfinderConfig config, int start, Set<Integer> targets, Runnable completionCallback) {
        this(config, start, targets, PathfinderHeuristic.ZERO, completionCallback);
    }

    public Pathfinder(PathfinderConfig config, int start, Set<Integer> targets, PathfinderHeuristic heuristic, Runnable completionCallback) {
        stats = new PathfinderStats();
        this.config = config;
        this.map = config.getMap();
        this.start = start;
        this.targets = targets;
        this.completionCallback = completionCallback;
        this.heuristic = heuristic != null ? heuristic : PathfinderHeuristic.ZERO;
        visited = new VisitedTiles(map);
        targetInWilderness = WildernessChecker.isInWilderness(targets);
        wildernessLevel = 31;
    }

    public Pathfinder(PathfinderConfig config, int start, Set<Integer> targets) {
        this(config, start, targets, PathfinderHeuristic.ZERO, null);
    }

    public static Pathfinder withHeuristic(PathfinderConfig config, int start, Set<Integer> targets, PathfinderHeuristic heuristic) {
        return new Pathfinder(config, start, targets, heuristic, null);
    }

    public void cancel() {
        cancelled = true;
    }

    public PathfinderStats getStats() {
        if (stats.started && stats.ended) {
            return stats;
        }

        // Don't give incomplete results
        return null;
    }

    public List<PathStep> getPath() {
        Node lastNode = bestLastNode; // For thread safety, read bestLastNode once
        if (lastNode == null) {
            return pathSteps;
        }

        if (pathNeedsUpdate) {
            pathSteps = lastNode.getPathSteps();
            pathNeedsUpdate = false;
        }

        return pathSteps;
    }

    public PathfinderResult getResult() {
        PathfinderStats currentStats = getStats();
        if (currentStats == null) {
            return null;
        }

        List<PathStep> currentPath = getPath();
        boolean reached = reachedTarget != WorldPointUtil.UNDEFINED;
        int target = reached ? reachedTarget : (targets.isEmpty() ? WorldPointUtil.UNDEFINED : targets.iterator().next());
        int closestReachedPoint = bestLastNode != null ? bestLastNode.getClosestTilePosition() : start;
        return new PathfinderResult(
            start,
            target,
            reached,
            currentPath,
            closestReachedPoint,
            currentStats.getNodesChecked(),
            currentStats.getTransportsChecked(),
            currentStats.getElapsedTimeNanos(),
            terminationReason
        );
    }

    public VisitedTiles getVisitedSnapshot() {
        return visitedSnapshot;
    }

    private void addNeighbors(Node node) {
        List<Node> nodes = map.getNeighbors(node, visited, config, wildernessLevel, targetInWilderness);
        for (Node neighbor : nodes) {
            if (node.isTile() && neighbor.isTile()
                && config.avoidWilderness(node.packedPosition, neighbor.packedPosition, targetInWilderness)) {
                continue;
            }

            SearchStateKey key = SearchStateKey.of(neighbor);
            Integer knownCost = bestKnownCost.get(key);
            if (knownCost != null && knownCost <= neighbor.cost) {
                continue;
            }
            bestKnownCost.put(key, neighbor.cost);
            open.add(OpenNode.of(neighbor, heuristic));
            if (neighbor instanceof TransportNode) {
                ++stats.transportsChecked;
            } else {
                ++stats.nodesChecked;
            }
        }
    }

    /**
     * Pathfinding to an unreachable target is slightly different from normal pathfinding.
     * Straight-line movement before diagonal movement is no longer prioritized, because the
     * original target is moved to the closest reachable tile. To avoid having to move the
     * original target we instead do the following to favour the closest reachable tile:
     * - 1) Pick the path with the minimum Euclidean distance (no need to use square root though)
     * - 2) If a tie occurs, pick the path with minimum travelled distance
     * - 3) If another tie occurs, pick the path with minimum x-coordinate
     * - 4) If another tie occurs, pick the path with minimum y-coordinate
     */
    private boolean updateBestPathWhenUnreachable(Node node) {
        boolean update = false;

        for (int target : targets) {
            int remainingDistance = WorldPointUtil.distanceBetween(target, node.packedPosition, WorldPointUtil.EUCLIDEAN_SQUARED_DISTANCE_METRIC);
            int travelledDistance = node.cost;
            int x = WorldPointUtil.unpackWorldX(node.packedPosition);
            int y = WorldPointUtil.unpackWorldY(node.packedPosition);
            if ((remainingDistance < bestRemainingDistance) ||
                (remainingDistance == bestRemainingDistance && travelledDistance < bestTravelledDistance) ||
                (remainingDistance == bestRemainingDistance && travelledDistance == bestTravelledDistance && x < bestX) ||
                (remainingDistance == bestRemainingDistance && travelledDistance == bestTravelledDistance && x == bestX && y < bestY)) {
                bestRemainingDistance = remainingDistance;
                bestTravelledDistance = travelledDistance;
                bestX = x;
                bestY = y;
                bestLastNode = node;
                pathNeedsUpdate = true;
                update = true;
            }
        }

        return update;
    }

    /**
     * Update wilderness level based on the current node position.
     */
    private void updateWildernessLevel(Node node) {
        if (wildernessLevel > 0) {
            // These are overlapping boundaries, so if the node isn't in level 30, it's in 0-29
            // likewise, if the node isn't in level 20, it's in 0-19
            if (wildernessLevel > 30 && !WildernessChecker.isInLevel30Wilderness(node.packedPosition)) {
                wildernessLevel = 30;
            }
            if (wildernessLevel > 20 && !WildernessChecker.isInLevel20Wilderness(node.packedPosition)) {
                wildernessLevel = 20;
            }
            if (wildernessLevel > 0 && !WildernessChecker.isInWilderness(node.packedPosition)) {
                wildernessLevel = 0;
            }
        }
    }

    @Override
    public void run() {
        stats.start();
        Node startNode = new Node(start, null, 0, false);
        open.add(OpenNode.of(startNode, heuristic));
        bestKnownCost.put(SearchStateKey.of(startNode), 0);

        long cutoffDurationMillis = config.getCalculationCutoffMillis();
        long cutoffTimeMillis = System.currentTimeMillis() + cutoffDurationMillis;

        while (!cancelled && !open.isEmpty()) {
            OpenNode openNode = open.poll();
            if (openNode == null) {
                break;
            }
            Node node = openNode.node;

            SearchStateKey key = SearchStateKey.of(node);
            Integer knownCost = bestKnownCost.get(key);
            if (knownCost != null && node.cost > knownCost) {
                continue;
            }
            if (visited.get(node)) {
                continue;
            }
            visited.set(node);

            if (node.isTile()) {
                updateWildernessLevel(node);
            }

            if (node.isTile() && targets.contains(node.packedPosition)) {
                bestLastNode = node;
                pathNeedsUpdate = true;
                reachedTarget = node.packedPosition;
                terminationReason = PathTerminationReason.TARGET_REACHED;
                break;
            }

            if (node.isTile() && updateBestPathWhenUnreachable(node)) {
                cutoffTimeMillis = System.currentTimeMillis() + cutoffDurationMillis;
            }

            if (System.currentTimeMillis() > cutoffTimeMillis) {
                terminationReason = PathTerminationReason.CUTOFF_REACHED;
                break;
            }

            addNeighbors(node);
        }

        if (cancelled) {
            terminationReason = PathTerminationReason.CANCELLED;
        } else if (terminationReason == null) {
            terminationReason = PathTerminationReason.SEARCH_EXHAUSTED;
        }

        done = !cancelled;
        visitedSnapshot = visited.snapshot();

        open.clear();
        bestKnownCost.clear();
        visited.clear();

        stats.end(); // Include cleanup in stats to get the total cost of pathfinding

        if (completionCallback != null) {
            completionCallback.run();
        }
    }

    private int compareOpenNodes(OpenNode left, OpenNode right) {
        int fCompare = Double.compare(left.fScore, right.fScore);
        if (fCompare != 0) {
            return fCompare;
        }
        int costCompare = Integer.compare(left.node.cost, right.node.cost);
        if (costCompare != 0) {
            return costCompare;
        }
        return Integer.compare(stateOrdinal(left.node), stateOrdinal(right.node));
    }

    private int stateOrdinal(Node node) {
        if (node.isTile()) {
            return 31 * node.packedPosition + node.remainingTransportMask;
        }
        return Integer.MAX_VALUE - (31 * node.abstractKind.ordinal() + node.remainingTransportMask);
    }

    private static final class SearchStateKey {
        private final int packedPosition;
        private final boolean bankVisited;
        private final int remainingTransportMask;
        private final Node.Type type;
        private final AbstractNodeKind abstractKind;

        private SearchStateKey(int packedPosition, boolean bankVisited, int remainingTransportMask, Node.Type type, AbstractNodeKind abstractKind) {
            this.packedPosition = packedPosition;
            this.bankVisited = bankVisited;
            this.remainingTransportMask = remainingTransportMask;
            this.type = type;
            this.abstractKind = abstractKind;
        }

        private static SearchStateKey of(Node node) {
            return new SearchStateKey(node.packedPosition, node.bankVisited, node.remainingTransportMask, node.type, node.abstractKind);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SearchStateKey)) {
                return false;
            }
            SearchStateKey that = (SearchStateKey) other;
            return packedPosition == that.packedPosition
                && bankVisited == that.bankVisited
                && remainingTransportMask == that.remainingTransportMask
                && type == that.type
                && abstractKind == that.abstractKind;
        }

        @Override
        public int hashCode() {
            int result = packedPosition;
            result = 31 * result + Boolean.hashCode(bankVisited);
            result = 31 * result + remainingTransportMask;
            result = 31 * result + type.hashCode();
            result = 31 * result + (abstractKind != null ? abstractKind.hashCode() : 0);
            return result;
        }
    }

    private static final class OpenNode {
        private final Node node;
        private final double fScore;

        private OpenNode(Node node, double fScore) {
            this.node = node;
            this.fScore = fScore;
        }

        private static OpenNode of(Node node, PathfinderHeuristic heuristic) {
            double heuristicValue = heuristic.estimate(node);
            return new OpenNode(node, node.cost + heuristicValue);
        }
    }

    public static class PathfinderStats {
        @Getter
        private int nodesChecked = 0, transportsChecked = 0;
        private long startNanos, endNanos;
        private volatile boolean started = false, ended = false;

        public int getTotalNodesChecked() {
            return nodesChecked + transportsChecked;
        }

        public long getElapsedTimeNanos() {
            return endNanos - startNanos;
        }

        private void start() {
            started = true;
            nodesChecked = 0;
            transportsChecked = 0;
            startNanos = System.nanoTime();
        }

        private void end() {
            endNanos = System.nanoTime();
            ended = true;
        }
    }
}
