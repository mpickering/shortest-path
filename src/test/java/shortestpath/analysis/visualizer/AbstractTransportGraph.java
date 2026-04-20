package shortestpath.analysis.visualizer;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.TransportUsageMask;
import shortestpath.transport.TransportType;

public final class AbstractTransportGraph {
    private static final int[] EMPTY_INT_ARRAY = new int[0];

    private final List<AbstractNode> nodes;
    private final List<List<AbstractEdge>> incomingEdges;
    private final Map<Integer, int[]> attachmentNodeIdsByComponent;
    private final int globalSourceNodeId;
    private final BuildStats buildStats;

    AbstractTransportGraph(
        List<AbstractNode> nodes,
        List<List<AbstractEdge>> incomingEdges,
        Map<Integer, int[]> attachmentNodeIdsByComponent,
        int globalSourceNodeId,
        BuildStats buildStats
    ) {
        this.nodes = nodes;
        this.incomingEdges = incomingEdges;
        this.attachmentNodeIdsByComponent = attachmentNodeIdsByComponent;
        this.globalSourceNodeId = globalSourceNodeId;
        this.buildStats = buildStats;
    }

    public AbstractNode getNode(int nodeId) {
        return nodes.get(nodeId);
    }

    public int getNodeCount() {
        return nodes.size();
    }

    public int[] getAttachmentNodeIds(int componentId) {
        return attachmentNodeIdsByComponent.getOrDefault(componentId, EMPTY_INT_ARRAY);
    }

    public Map<Integer, int[]> getAttachmentNodeIdsByComponent() {
        return attachmentNodeIdsByComponent;
    }

    public int getGlobalSourceNodeId() {
        return globalSourceNodeId;
    }

    public BuildStats getBuildStats() {
        return buildStats;
    }

    public List<AbstractEdge> getOutgoingEdges(int nodeId) {
        java.util.ArrayList<AbstractEdge> outgoing = new java.util.ArrayList<>();
        for (List<AbstractEdge> edges : incomingEdges) {
            for (AbstractEdge edge : edges) {
                if (edge.getFromNodeId() == nodeId) {
                    outgoing.add(edge);
                }
            }
        }
        return outgoing;
    }

    public ReverseSearchResult reverseDistances(int goalPackedPoint, Integer goalComponentId) {
        long startNanos = System.nanoTime();
        int[][] distances = new int[TransportUsageMask.MASK_VARIANTS][nodes.size()];
        for (int mask = 0; mask < distances.length; mask++) {
            Arrays.fill(distances[mask], Integer.MAX_VALUE);
        }

        PriorityQueue<QueueEntry> queue = new PriorityQueue<>(Comparator.comparingInt(QueueEntry::distance));
        int seededNodeCount = 0;
        if (goalComponentId != null) {
            for (int nodeId : getAttachmentNodeIds(goalComponentId)) {
                AbstractNode node = nodes.get(nodeId);
                int seedDistance = chebyshev(node.getPackedPoint(), goalPackedPoint);
                for (int remainingMask = 0; remainingMask < TransportUsageMask.MASK_VARIANTS; remainingMask++) {
                    if (seedDistance < distances[remainingMask][nodeId]) {
                        distances[remainingMask][nodeId] = seedDistance;
                        queue.add(new QueueEntry(nodeId, remainingMask, seedDistance));
                        seededNodeCount++;
                    }
                }
            }
        }

        long popCount = 0L;
        long storedEdgeRelaxCount = 0L;
        long implicitWalkRelaxCount = 0L;

        while (!queue.isEmpty()) {
            QueueEntry current = queue.poll();
            if (current.distance != distances[current.remainingTransportMask][current.nodeId]) {
                continue;
            }
            popCount++;

            AbstractNode node = nodes.get(current.nodeId);
            if (node.isAttachment()) {
                for (int componentId : node.getComponentIds()) {
                    for (int attachmentNodeId : getAttachmentNodeIds(componentId)) {
                        if (attachmentNodeId == current.nodeId) {
                            continue;
                        }
                        implicitWalkRelaxCount++;
                        int candidate = current.distance + chebyshev(
                            node.getPackedPoint(),
                            nodes.get(attachmentNodeId).getPackedPoint());
                        if (candidate < distances[current.remainingTransportMask][attachmentNodeId]) {
                            distances[current.remainingTransportMask][attachmentNodeId] = candidate;
                            queue.add(new QueueEntry(attachmentNodeId, current.remainingTransportMask, candidate));
                        }
                    }
                }
            }

            for (AbstractEdge edge : incomingEdges.get(current.nodeId)) {
                storedEdgeRelaxCount++;
                int predecessorMask = predecessorMask(current.remainingTransportMask, edge);
                if (predecessorMask < 0) {
                    continue;
                }
                int candidate = current.distance + edge.getCost();
                if (candidate < distances[predecessorMask][edge.getFromNodeId()]) {
                    distances[predecessorMask][edge.getFromNodeId()] = candidate;
                    queue.add(new QueueEntry(edge.getFromNodeId(), predecessorMask, candidate));
                }
            }
        }

        int reachableNodeCount = 0;
        for (int mask = 0; mask < TransportUsageMask.MASK_VARIANTS; mask++) {
            for (int distance : distances[mask]) {
                if (distance != Integer.MAX_VALUE) {
                    reachableNodeCount++;
                }
            }
        }

        return new ReverseSearchResult(
            distances,
            seededNodeCount,
            popCount,
            storedEdgeRelaxCount,
            implicitWalkRelaxCount,
            reachableNodeCount,
            System.nanoTime() - startNanos);
    }

    private static int chebyshev(int a, int b) {
        return WorldPointUtil.distanceBetween(a, b, WorldPointUtil.CHEBYSHEV_DISTANCE_METRIC);
    }

    private static int predecessorMask(int currentMask, AbstractEdge edge) {
        int consumedMaskBit = consumedMaskBit(edge);
        if (consumedMaskBit == 0) {
            return currentMask;
        }
        if ((currentMask & consumedMaskBit) != 0) {
            return -1;
        }
        return currentMask | consumedMaskBit;
    }

    private static int consumedMaskBit(AbstractEdge edge) {
        if (edge.getKind() == EdgeKind.HUB_ENTRY || edge.getKind() == EdgeKind.DIRECT_TRANSPORT) {
            if (TransportType.FAIRY_RING.equals(edge.getTransportType())) {
                return TransportUsageMask.FAIRY_RING;
            }
            if (TransportType.SPIRIT_TREE.equals(edge.getTransportType())) {
                return TransportUsageMask.SPIRIT_TREE;
            }
        }
        return 0;
    }

    public enum NodeKind {
        ATTACHMENT,
        HUB,
        GLOBAL_SOURCE
    }

    public enum EdgeKind {
        DIRECT_TRANSPORT,
        HUB_ENTRY,
        HUB_EXIT,
        GLOBAL_TELEPORT
    }

    public static final class AbstractNode {
        private final int id;
        private final NodeKind kind;
        private final int packedPoint;
        private final int[] componentIds;
        private final TransportType transportType;

        AbstractNode(int id, NodeKind kind, int packedPoint, int[] componentIds, TransportType transportType) {
            this.id = id;
            this.kind = kind;
            this.packedPoint = packedPoint;
            this.componentIds = componentIds;
            this.transportType = transportType;
        }

        public int getId() {
            return id;
        }

        public NodeKind getKind() {
            return kind;
        }

        public int getPackedPoint() {
            return packedPoint;
        }

        public int[] getComponentIds() {
            return componentIds;
        }

        public TransportType getTransportType() {
            return transportType;
        }

        public boolean isAttachment() {
            return kind == NodeKind.ATTACHMENT;
        }
    }

    public static final class AbstractEdge {
        private final int fromNodeId;
        private final int toNodeId;
        private final int cost;
        private final EdgeKind kind;
        private final TransportType transportType;
        private final String label;

        AbstractEdge(int fromNodeId, int toNodeId, int cost, EdgeKind kind, TransportType transportType, String label) {
            this.fromNodeId = fromNodeId;
            this.toNodeId = toNodeId;
            this.cost = cost;
            this.kind = kind;
            this.transportType = transportType;
            this.label = label;
        }

        public int getFromNodeId() {
            return fromNodeId;
        }

        public int getToNodeId() {
            return toNodeId;
        }

        public int getCost() {
            return cost;
        }

        public EdgeKind getKind() {
            return kind;
        }

        public TransportType getTransportType() {
            return transportType;
        }

        public String getLabel() {
            return label;
        }
    }

    public static final class HubCompressionStats {
        private final int naiveEdgeCount;
        private final int compressedEdgeCount;

        HubCompressionStats(int naiveEdgeCount, int compressedEdgeCount) {
            this.naiveEdgeCount = naiveEdgeCount;
            this.compressedEdgeCount = compressedEdgeCount;
        }

        public int getNaiveEdgeCount() {
            return naiveEdgeCount;
        }

        public int getCompressedEdgeCount() {
            return compressedEdgeCount;
        }
    }

    public static final class BuildStats {
        private final int componentCount;
        private final long walkableTileCount;
        private final int attachmentNodeCount;
        private final int hubNodeCount;
        private final int globalSourceNodeCount;
        private final int storedEdgeCount;
        private final EnumMap<EdgeKind, Integer> storedEdgeCountsByKind;
        private final EnumMap<TransportType, HubCompressionStats> hubCompressionByType;
        private final int minAttachmentsPerComponent;
        private final int medianAttachmentsPerComponent;
        private final int p95AttachmentsPerComponent;
        private final int maxAttachmentsPerComponent;
        private final long totalImplicitWalkNeighborPairs;
        private final long buildElapsedNanos;

        BuildStats(
            int componentCount,
            long walkableTileCount,
            int attachmentNodeCount,
            int hubNodeCount,
            int globalSourceNodeCount,
            int storedEdgeCount,
            EnumMap<EdgeKind, Integer> storedEdgeCountsByKind,
            EnumMap<TransportType, HubCompressionStats> hubCompressionByType,
            int minAttachmentsPerComponent,
            int medianAttachmentsPerComponent,
            int p95AttachmentsPerComponent,
            int maxAttachmentsPerComponent,
            long totalImplicitWalkNeighborPairs,
            long buildElapsedNanos
        ) {
            this.componentCount = componentCount;
            this.walkableTileCount = walkableTileCount;
            this.attachmentNodeCount = attachmentNodeCount;
            this.hubNodeCount = hubNodeCount;
            this.globalSourceNodeCount = globalSourceNodeCount;
            this.storedEdgeCount = storedEdgeCount;
            this.storedEdgeCountsByKind = storedEdgeCountsByKind;
            this.hubCompressionByType = hubCompressionByType;
            this.minAttachmentsPerComponent = minAttachmentsPerComponent;
            this.medianAttachmentsPerComponent = medianAttachmentsPerComponent;
            this.p95AttachmentsPerComponent = p95AttachmentsPerComponent;
            this.maxAttachmentsPerComponent = maxAttachmentsPerComponent;
            this.totalImplicitWalkNeighborPairs = totalImplicitWalkNeighborPairs;
            this.buildElapsedNanos = buildElapsedNanos;
        }

        public int getComponentCount() {
            return componentCount;
        }

        public long getWalkableTileCount() {
            return walkableTileCount;
        }

        public int getAttachmentNodeCount() {
            return attachmentNodeCount;
        }

        public int getHubNodeCount() {
            return hubNodeCount;
        }

        public int getGlobalSourceNodeCount() {
            return globalSourceNodeCount;
        }

        public int getStoredEdgeCount() {
            return storedEdgeCount;
        }

        public Map<EdgeKind, Integer> getStoredEdgeCountsByKind() {
            return Collections.unmodifiableMap(storedEdgeCountsByKind);
        }

        public Map<TransportType, HubCompressionStats> getHubCompressionByType() {
            return Collections.unmodifiableMap(hubCompressionByType);
        }

        public int getMinAttachmentsPerComponent() {
            return minAttachmentsPerComponent;
        }

        public int getMedianAttachmentsPerComponent() {
            return medianAttachmentsPerComponent;
        }

        public int getP95AttachmentsPerComponent() {
            return p95AttachmentsPerComponent;
        }

        public int getMaxAttachmentsPerComponent() {
            return maxAttachmentsPerComponent;
        }

        public long getTotalImplicitWalkNeighborPairs() {
            return totalImplicitWalkNeighborPairs;
        }

        public long getBuildElapsedNanos() {
            return buildElapsedNanos;
        }
    }

    public static final class ReverseSearchResult {
        private final int[][] distances;
        private final int seededNodeCount;
        private final long popCount;
        private final long storedEdgeRelaxCount;
        private final long implicitWalkRelaxCount;
        private final int reachableNodeCount;
        private final long elapsedNanos;

        ReverseSearchResult(
            int[][] distances,
            int seededNodeCount,
            long popCount,
            long storedEdgeRelaxCount,
            long implicitWalkRelaxCount,
            int reachableNodeCount,
            long elapsedNanos
        ) {
            this.distances = distances;
            this.seededNodeCount = seededNodeCount;
            this.popCount = popCount;
            this.storedEdgeRelaxCount = storedEdgeRelaxCount;
            this.implicitWalkRelaxCount = implicitWalkRelaxCount;
            this.reachableNodeCount = reachableNodeCount;
            this.elapsedNanos = elapsedNanos;
        }

        public int distanceToNode(int nodeId) {
            return distanceToNode(nodeId, TransportUsageMask.ALL_AVAILABLE);
        }

        public int distanceToNode(int nodeId, int remainingTransportMask) {
            return distances[remainingTransportMask][nodeId];
        }

        public int[][] getDistances() {
            return distances;
        }

        public int getSeededNodeCount() {
            return seededNodeCount;
        }

        public long getPopCount() {
            return popCount;
        }

        public long getStoredEdgeRelaxCount() {
            return storedEdgeRelaxCount;
        }

        public long getImplicitWalkRelaxCount() {
            return implicitWalkRelaxCount;
        }

        public int getReachableNodeCount() {
            return reachableNodeCount;
        }

        public long getElapsedNanos() {
            return elapsedNanos;
        }
    }

    private static final class QueueEntry {
        private final int nodeId;
        private final int remainingTransportMask;
        private final int distance;

        private QueueEntry(int nodeId, int remainingTransportMask, int distance) {
            this.nodeId = nodeId;
            this.remainingTransportMask = remainingTransportMask;
            this.distance = distance;
        }

        private int distance() {
            return distance;
        }
    }
}
