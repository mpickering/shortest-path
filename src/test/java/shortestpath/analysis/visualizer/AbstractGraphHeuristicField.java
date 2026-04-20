package shortestpath.analysis.visualizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.Node;
import shortestpath.pathfinder.PathfinderHeuristic;
import shortestpath.pathfinder.TransportUsageMask;
import shortestpath.transport.TransportType;

public class AbstractGraphHeuristicField implements HeuristicField, PathfinderHeuristic {
    private final ComponentLabelIndex componentLabelIndex;
    private final int goal;
    private final Set<Integer> goalComponentIds;
    private final AbstractTransportGraph graph;
    private final AbstractTransportGraph.ReverseSearchResult reverseSearchResult;
    private final Map<Integer, AttachmentData> attachmentsByComponent;
    private final List<UndefinedEstimateTrace> undefinedEstimateTraces = new ArrayList<>();
    private long evaluationCount;
    private long totalEvaluationNanos;
    private long sampleCount;
    private long sampleNanos;
    private long estimateCount;
    private long estimateNanos;
    private long attachmentScanCount;
    private long attachmentScanNanos;
    private long attachmentCandidatesExamined;
    private long reachableAttachmentCandidatesExamined;
    private long undefinedEstimateCount;

    public AbstractGraphHeuristicField(CollisionMap collisionMap, ComponentLabelIndex componentLabelIndex, int goal) {
        this(collisionMap, componentLabelIndex, goal, AbstractGraphBuilder.build(collisionMap, componentLabelIndex));
    }

    AbstractGraphHeuristicField(CollisionMap collisionMap, ComponentLabelIndex componentLabelIndex, int goal, AbstractTransportGraph graph) {
        this.componentLabelIndex = componentLabelIndex;
        this.goal = goal;
        this.goalComponentIds = componentLabelIndex.getResolvedComponentIds(goal);
        this.graph = graph;
        Integer reverseGoalComponentId = goalComponentIds.isEmpty() ? null : goalComponentIds.iterator().next();
        this.reverseSearchResult = graph.reverseDistances(goal, reverseGoalComponentId);
        this.attachmentsByComponent = buildAttachmentData(graph);
    }

    @Override
    public HeuristicSample sample(int packedPoint, TileStateQuery.TileState tileState) {
        return sample(packedPoint, tileState, TransportUsageMask.ALL_AVAILABLE);
    }

    @Override
    public HeuristicSample sample(int packedPoint, TileStateQuery.TileState tileState, int remainingTransportMask) {
        long startNanos = System.nanoTime();
        HeuristicSample sample = sampleInternal(packedPoint, tileState, remainingTransportMask);
        long elapsedNanos = System.nanoTime() - startNanos;
        totalEvaluationNanos += elapsedNanos;
        evaluationCount++;
        sampleCount++;
        sampleNanos += elapsedNanos;
        return sample;
    }

    @Override
    public double estimate(Node node) {
        if (!node.isTile()) {
            return 0.0d;
        }
        long startNanos = System.nanoTime();
        HeuristicSample sample = sampleInternal(node.packedPosition, TileStateQuery.TileState.WALKABLE, node.remainingTransportMask);
        long elapsedNanos = System.nanoTime() - startNanos;
        totalEvaluationNanos += elapsedNanos;
        evaluationCount++;
        estimateCount++;
        estimateNanos += elapsedNanos;
        if (sample.isDefined()) {
            return sample.getValue();
        }
        undefinedEstimateCount++;
        if (undefinedEstimateTraces.size() < 64) {
            undefinedEstimateTraces.add(new UndefinedEstimateTrace(
                node.packedPosition,
                componentLabelIndex.getResolvedComponentIds(node.packedPosition).stream().findFirst().orElse(null),
                node.remainingTransportMask,
                node.bankVisited,
                node.cost));
        }
        return Double.POSITIVE_INFINITY;
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

    public long getSampleCount() {
        return sampleCount;
    }

    public long getSampleNanos() {
        return sampleNanos;
    }

    public long getEstimateCount() {
        return estimateCount;
    }

    public long getEstimateNanos() {
        return estimateNanos;
    }

    public long getAttachmentScanCount() {
        return attachmentScanCount;
    }

    public long getAttachmentScanNanos() {
        return attachmentScanNanos;
    }

    public long getAttachmentCandidatesExamined() {
        return attachmentCandidatesExamined;
    }

    public long getReachableAttachmentCandidatesExamined() {
        return reachableAttachmentCandidatesExamined;
    }

    public long getUndefinedEstimateCount() {
        return undefinedEstimateCount;
    }

    public List<UndefinedEstimateTrace> getUndefinedEstimateTraces() {
        return undefinedEstimateTraces;
    }

    public AttachmentChoice bestAttachment(int packedPoint, int remainingTransportMask) {
        Set<Integer> pointComponentIds = componentLabelIndex.getResolvedComponentIds(packedPoint);
        if (pointComponentIds.isEmpty()) {
            return null;
        }

        double bestValue = Double.POSITIVE_INFINITY;
        int bestAttachmentNodeId = -1;
        int pointX = WorldPointUtil.unpackWorldX(packedPoint);
        int pointY = WorldPointUtil.unpackWorldY(packedPoint);
        for (int pointComponentId : pointComponentIds) {
            AttachmentData attachmentData = attachmentsByComponent.get(pointComponentId);
            if (attachmentData == null) {
                continue;
            }
            for (int i = 0; i < attachmentData.nodeIds.length; i++) {
                int attachmentNodeId = attachmentData.nodeIds[i];
                int reverseDistance = reverseSearchResult.distanceToNode(attachmentNodeId, remainingTransportMask);
                if (reverseDistance == Integer.MAX_VALUE) {
                    continue;
                }
                double total = chebyshev(pointX, pointY, attachmentData.xs[i], attachmentData.ys[i]) + reverseDistance;
                if (total < bestValue) {
                    bestValue = total;
                    bestAttachmentNodeId = attachmentNodeId;
                }
            }
        }

        if (bestAttachmentNodeId < 0) {
            return null;
        }

        int attachmentPoint = graph.getNode(bestAttachmentNodeId).getPackedPoint();
        return new AttachmentChoice(
            bestAttachmentNodeId,
            attachmentPoint,
            chebyshev(packedPoint, attachmentPoint),
            reverseSearchResult.distanceToNode(bestAttachmentNodeId, remainingTransportMask),
            bestValue,
            buildWitness(bestAttachmentNodeId, remainingTransportMask));
    }

    private HeuristicSample sampleInternal(int packedPoint, TileStateQuery.TileState tileState, int remainingTransportMask) {
        if (tileState == TileStateQuery.TileState.MISSING || tileState == TileStateQuery.TileState.BLOCKED) {
            return HeuristicSample.undefined();
        }

        double best = Double.POSITIVE_INFINITY;
        Set<Integer> pointComponentIds = componentLabelIndex.getResolvedComponentIds(packedPoint);
        boolean sameComponent = false;
        for (int pointComponentId : pointComponentIds) {
            if (goalComponentIds.contains(pointComponentId)) {
                sameComponent = true;
                break;
            }
        }
        if (sameComponent) {
            best = chebyshev(packedPoint, goal);
        }

        if (!pointComponentIds.isEmpty()) {
            long scanStartNanos = System.nanoTime();
            attachmentScanCount++;
            int pointX = WorldPointUtil.unpackWorldX(packedPoint);
            int pointY = WorldPointUtil.unpackWorldY(packedPoint);
            for (int pointComponentId : pointComponentIds) {
                AttachmentData attachmentData = attachmentsByComponent.get(pointComponentId);
                if (attachmentData == null) {
                    continue;
                }
                for (int i = 0; i < attachmentData.nodeIds.length; i++) {
                    int attachmentNodeId = attachmentData.nodeIds[i];
                    attachmentCandidatesExamined++;
                    int reverseDistance = reverseSearchResult.distanceToNode(attachmentNodeId, remainingTransportMask);
                    if (reverseDistance == Integer.MAX_VALUE) {
                        continue;
                    }
                    reachableAttachmentCandidatesExamined++;
                    best = Math.min(best, chebyshev(pointX, pointY, attachmentData.xs[i], attachmentData.ys[i]) + reverseDistance);
                }
            }
            attachmentScanNanos += System.nanoTime() - scanStartNanos;
        }

        if (Double.isFinite(best)) {
            return HeuristicSample.defined(best);
        }
        return HeuristicSample.undefined();
    }

    private static int chebyshev(int a, int b) {
        return WorldPointUtil.distanceBetween(a, b, WorldPointUtil.CHEBYSHEV_DISTANCE_METRIC);
    }

    private static int chebyshev(int x1, int y1, int x2, int y2) {
        return Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2));
    }

    private static Map<Integer, AttachmentData> buildAttachmentData(AbstractTransportGraph graph) {
        Map<Integer, AttachmentData> attachmentDataByComponent = new HashMap<>();
        for (Map.Entry<Integer, int[]> entry : graph.getAttachmentNodeIdsByComponent().entrySet()) {
            int[] nodeIds = entry.getValue();
            int[] xs = new int[nodeIds.length];
            int[] ys = new int[nodeIds.length];
            for (int i = 0; i < nodeIds.length; i++) {
                int packedPoint = graph.getNode(nodeIds[i]).getPackedPoint();
                xs[i] = WorldPointUtil.unpackWorldX(packedPoint);
                ys[i] = WorldPointUtil.unpackWorldY(packedPoint);
            }
            attachmentDataByComponent.put(entry.getKey(), new AttachmentData(nodeIds, xs, ys));
        }
        return attachmentDataByComponent;
    }

    private List<WitnessStep> buildWitness(int startNodeId, int remainingTransportMask) {
        List<WitnessStep> steps = new ArrayList<>();
        int nodeId = startNodeId;
        int mask = remainingTransportMask;

        for (int depth = 0; depth < 64; depth++) {
            AbstractTransportGraph.AbstractNode node = graph.getNode(nodeId);
            int currentDistance = reverseSearchResult.distanceToNode(nodeId, mask);
            if (currentDistance == Integer.MAX_VALUE) {
                break;
            }
            if (node.isAttachment() && !goalComponentIds.isEmpty()) {
                Integer nodeComponent = componentLabelIndex.getComponentId(node.getPackedPoint());
                if (nodeComponent != null && goalComponentIds.contains(nodeComponent) && currentDistance == chebyshev(node.getPackedPoint(), goal)) {
                    steps.add(WitnessStep.goalAttach(node.getPackedPoint(), goal, currentDistance));
                    break;
                }
            }

            WitnessStep nextStep = implicitWalkStep(nodeId, mask, currentDistance);
            if (nextStep == null) {
                nextStep = transportStep(nodeId, mask, currentDistance);
            }
            if (nextStep == null) {
                break;
            }
            steps.add(nextStep);
            nodeId = nextStep.nextNodeId;
            mask = nextStep.nextMask;
        }

        return steps;
    }

    private WitnessStep implicitWalkStep(int nodeId, int mask, int currentDistance) {
        AbstractTransportGraph.AbstractNode node = graph.getNode(nodeId);
        if (!node.isAttachment()) {
            return null;
        }
        for (int componentId : node.getComponentIds()) {
            for (int nextNodeId : graph.getAttachmentNodeIds(componentId)) {
                if (nextNodeId == nodeId) {
                    continue;
                }
                int stepCost = chebyshev(node.getPackedPoint(), graph.getNode(nextNodeId).getPackedPoint());
                int nextDistance = reverseSearchResult.distanceToNode(nextNodeId, mask);
                if (nextDistance != Integer.MAX_VALUE && currentDistance == stepCost + nextDistance) {
                    return WitnessStep.implicitWalk(node.getPackedPoint(), graph.getNode(nextNodeId).getPackedPoint(), stepCost, nextNodeId, mask);
                }
            }
        }
        return null;
    }

    private WitnessStep transportStep(int nodeId, int mask, int currentDistance) {
        for (AbstractTransportGraph.AbstractEdge edge : graph.getOutgoingEdges(nodeId)) {
            int nextMask = TransportUsageMask.consume(mask, edge.getTransportType());
            int nextDistance = reverseSearchResult.distanceToNode(edge.getToNodeId(), nextMask);
            if (nextDistance != Integer.MAX_VALUE && currentDistance == edge.getCost() + nextDistance) {
                return WitnessStep.transport(
                    graph.getNode(nodeId),
                    graph.getNode(edge.getToNodeId()),
                    edge,
                    edge.getKind(),
                    edge.getTransportType(),
                    edge.getCost(),
                    edge.getToNodeId(),
                    nextMask);
            }
        }
        return null;
    }

    public static final class AttachmentChoice {
        private final int attachmentNodeId;
        private final int attachmentPackedPoint;
        private final int sourceToAttachmentDistance;
        private final int attachmentToGoalDistance;
        private final double totalLowerBound;
        private final List<WitnessStep> witnessSteps;

        private AttachmentChoice(
            int attachmentNodeId,
            int attachmentPackedPoint,
            int sourceToAttachmentDistance,
            int attachmentToGoalDistance,
            double totalLowerBound,
            List<WitnessStep> witnessSteps
        ) {
            this.attachmentNodeId = attachmentNodeId;
            this.attachmentPackedPoint = attachmentPackedPoint;
            this.sourceToAttachmentDistance = sourceToAttachmentDistance;
            this.attachmentToGoalDistance = attachmentToGoalDistance;
            this.totalLowerBound = totalLowerBound;
            this.witnessSteps = witnessSteps;
        }

        public int getAttachmentNodeId() {
            return attachmentNodeId;
        }

        public int getAttachmentPackedPoint() {
            return attachmentPackedPoint;
        }

        public int getSourceToAttachmentDistance() {
            return sourceToAttachmentDistance;
        }

        public int getAttachmentToGoalDistance() {
            return attachmentToGoalDistance;
        }

        public double getTotalLowerBound() {
            return totalLowerBound;
        }

        public List<WitnessStep> getWitnessSteps() {
            return witnessSteps;
        }
    }

    private static final class AttachmentData {
        private final int[] nodeIds;
        private final int[] xs;
        private final int[] ys;

        private AttachmentData(int[] nodeIds, int[] xs, int[] ys) {
            this.nodeIds = nodeIds;
            this.xs = xs;
            this.ys = ys;
        }
    }

    public static final class UndefinedEstimateTrace {
        private final int packedPoint;
        private final Integer componentId;
        private final int remainingTransportMask;
        private final boolean bankVisited;
        private final int cost;

        private UndefinedEstimateTrace(int packedPoint, Integer componentId, int remainingTransportMask, boolean bankVisited, int cost) {
            this.packedPoint = packedPoint;
            this.componentId = componentId;
            this.remainingTransportMask = remainingTransportMask;
            this.bankVisited = bankVisited;
            this.cost = cost;
        }

        public int getPackedPoint() {
            return packedPoint;
        }

        public Integer getComponentId() {
            return componentId;
        }

        public int getRemainingTransportMask() {
            return remainingTransportMask;
        }

        public boolean isBankVisited() {
            return bankVisited;
        }

        public int getCost() {
            return cost;
        }
    }

    public static final class WitnessStep {
        private final String description;
        private final int nextNodeId;
        private final int nextMask;

        private WitnessStep(String description, int nextNodeId, int nextMask) {
            this.description = description;
            this.nextNodeId = nextNodeId;
            this.nextMask = nextMask;
        }

        static WitnessStep implicitWalk(int fromPackedPoint, int toPackedPoint, int cost, int nextNodeId, int nextMask) {
            return new WitnessStep(
                "walk " + formatPackedPoint(fromPackedPoint) + " -> " + formatPackedPoint(toPackedPoint) + " cost=" + cost,
                nextNodeId,
                nextMask);
        }

        static WitnessStep transport(
            AbstractTransportGraph.AbstractNode fromNode,
            AbstractTransportGraph.AbstractNode toNode,
            AbstractTransportGraph.AbstractEdge edge,
            AbstractTransportGraph.EdgeKind kind,
            TransportType transportType,
            int cost,
            int nextNodeId,
            int nextMask
        ) {
            return new WitnessStep(
                kind + " " + transportType + " "
                    + formatNode(fromNode) + " -> " + formatNode(toNode)
                    + formatLabel(edge.getLabel()) + " cost=" + cost,
                nextNodeId,
                nextMask);
        }

        static WitnessStep goalAttach(int fromPackedPoint, int goalPackedPoint, int cost) {
            return new WitnessStep(
                "goal-attach " + formatPackedPoint(fromPackedPoint) + " -> " + formatPackedPoint(goalPackedPoint) + " cost=" + cost,
                -1,
                0);
        }

        public String getDescription() {
            return description;
        }
    }

    private static String formatNode(AbstractTransportGraph.AbstractNode node) {
        if (node.isAttachment()) {
            return formatPackedPoint(node.getPackedPoint());
        }
        return node.getKind() + "(" + node.getTransportType() + ")";
    }

    private static String formatLabel(String label) {
        return label == null || label.isBlank() ? "" : " [" + label + "]";
    }

    private static String formatPackedPoint(int packedPoint) {
        return WorldPointUtil.unpackWorldX(packedPoint)
            + "," + WorldPointUtil.unpackWorldY(packedPoint)
            + "," + WorldPointUtil.unpackWorldPlane(packedPoint);
    }

}
