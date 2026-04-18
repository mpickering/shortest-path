package shortestpath.analysis.visualizer;

import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.Node;
import shortestpath.pathfinder.PathfinderHeuristic;

public class AbstractGraphHeuristicField implements HeuristicField, PathfinderHeuristic {
    private final ComponentLabelIndex componentLabelIndex;
    private final int goal;
    private final Integer goalComponentId;
    private final AbstractTransportGraph graph;
    private final AbstractTransportGraph.ReverseSearchResult reverseSearchResult;
    private long evaluationCount;
    private long totalEvaluationNanos;

    public AbstractGraphHeuristicField(CollisionMap collisionMap, ComponentLabelIndex componentLabelIndex, int goal) {
        this(componentLabelIndex, goal, AbstractGraphBuilder.build(collisionMap, componentLabelIndex));
    }

    AbstractGraphHeuristicField(ComponentLabelIndex componentLabelIndex, int goal, AbstractTransportGraph graph) {
        this.componentLabelIndex = componentLabelIndex;
        this.goal = goal;
        this.goalComponentId = componentLabelIndex.getComponentId(goal);
        this.graph = graph;
        this.reverseSearchResult = graph.reverseDistances(goal, goalComponentId);
    }

    @Override
    public HeuristicSample sample(int packedPoint, TileStateQuery.TileState tileState) {
        long startNanos = System.nanoTime();
        HeuristicSample sample = sampleInternal(packedPoint, tileState);
        totalEvaluationNanos += System.nanoTime() - startNanos;
        evaluationCount++;
        return sample;
    }

    @Override
    public double estimate(Node node) {
        if (!node.isTile()) {
            return 0.0d;
        }
        long startNanos = System.nanoTime();
        HeuristicSample sample = sampleInternal(node.packedPosition, TileStateQuery.TileState.WALKABLE);
        totalEvaluationNanos += System.nanoTime() - startNanos;
        evaluationCount++;
        return sample.isDefined() ? sample.getValue() : 0.0d;
    }

    public AbstractTransportGraph getGraph() {
        return graph;
    }

    public AbstractTransportGraph.ReverseSearchResult getReverseSearchResult() {
        return reverseSearchResult;
    }

    public long getEvaluationCount() {
        return evaluationCount;
    }

    public long getTotalEvaluationNanos() {
        return totalEvaluationNanos;
    }

    private HeuristicSample sampleInternal(int packedPoint, TileStateQuery.TileState tileState) {
        if (tileState == TileStateQuery.TileState.MISSING || tileState == TileStateQuery.TileState.BLOCKED) {
            return HeuristicSample.undefined();
        }

        double best = Double.POSITIVE_INFINITY;
        Integer pointComponentId = componentLabelIndex.getComponentId(packedPoint);
        if (pointComponentId != null && goalComponentId != null && pointComponentId.equals(goalComponentId)) {
            best = chebyshev(packedPoint, goal);
        }

        if (pointComponentId != null) {
            for (int attachmentNodeId : graph.getAttachmentNodeIds(pointComponentId)) {
                int reverseDistance = reverseSearchResult.distanceToNode(attachmentNodeId);
                if (reverseDistance == Integer.MAX_VALUE) {
                    continue;
                }
                int attachmentPoint = graph.getNode(attachmentNodeId).getPackedPoint();
                best = Math.min(best, chebyshev(packedPoint, attachmentPoint) + reverseDistance);
            }
        }

        return Double.isFinite(best) ? HeuristicSample.defined(best) : HeuristicSample.undefined();
    }

    private static int chebyshev(int a, int b) {
        return WorldPointUtil.distanceBetween(a, b, WorldPointUtil.CHEBYSHEV_DISTANCE_METRIC);
    }
}
