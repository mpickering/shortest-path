package shortestpath.analysis.visualizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import shortestpath.analysis.ComponentAnalysisSupport;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportLoader;
import shortestpath.transport.TransportType;

public final class AbstractGraphBuilder {
    private static final Set<TransportType> COMPRESSED_HUB_TYPES = EnumSet.of(
        TransportType.FAIRY_RING,
        TransportType.SPIRIT_TREE,
        TransportType.GNOME_GLIDER,
        TransportType.HOT_AIR_BALLOON,
        TransportType.MAGIC_MUSHTREE,
        TransportType.MINECART,
        TransportType.QUETZAL,
        TransportType.WILDERNESS_OBELISK);

    private AbstractGraphBuilder() {
    }

    public static AbstractTransportGraph build(CollisionMap collisionMap, ComponentLabelIndex componentLabelIndex) {
        return build(collisionMap, componentLabelIndex, TransportLoader.loadAllFromResources());
    }

    static AbstractTransportGraph build(
        CollisionMap collisionMap,
        ComponentLabelIndex componentLabelIndex,
        Map<Integer, Set<Transport>> transportsByOrigin
    ) {
        long startNanos = System.nanoTime();
        BuilderState state = new BuilderState(componentLabelIndex);

        for (Set<Transport> transports : transportsByOrigin.values()) {
            for (Transport transport : transports) {
                TransportType type = transport.getType();
                if (type == null) {
                    continue;
                }

                Set<Integer> destinationComponents = resolveComponents(collisionMap, componentLabelIndex, transport.getDestination());
                if (destinationComponents.isEmpty()) {
                    continue;
                }

                if (transport.getOrigin() == Transport.UNDEFINED_ORIGIN) {
                    continue;
                }

                int destinationNodeId = state.getOrCreateAttachmentNode(transport.getDestination(), destinationComponents);

                Set<Integer> originComponents = resolveComponents(collisionMap, componentLabelIndex, transport.getOrigin());
                if (originComponents.isEmpty()) {
                    continue;
                }

                int originNodeId = state.getOrCreateAttachmentNode(transport.getOrigin(), originComponents);
                if (COMPRESSED_HUB_TYPES.contains(type)) {
                    int hubNodeId = state.getOrCreateHubNode(type);
                    state.recordHubEndpoint(type, originNodeId, destinationNodeId);
                    state.addEdge(originNodeId, hubNodeId, 0,
                        AbstractTransportGraph.EdgeKind.HUB_ENTRY, type);
                    state.addEdge(hubNodeId, destinationNodeId, transport.getDuration(),
                        AbstractTransportGraph.EdgeKind.HUB_EXIT, type);
                } else {
                    state.addEdge(originNodeId, destinationNodeId, transport.getDuration(),
                        AbstractTransportGraph.EdgeKind.DIRECT_TRANSPORT, type);
                }
            }
        }

        return state.build(System.nanoTime() - startNanos);
    }

    private static Set<Integer> resolveComponents(
        CollisionMap collisionMap,
        ComponentLabelIndex componentLabelIndex,
        int packedPoint
    ) {
        return ComponentAnalysisSupport.resolveComponents(collisionMap, componentLabelIndex.getTileToComponent(), packedPoint);
    }

    private static final class BuilderState {
        private final ComponentLabelIndex componentLabelIndex;
        private final Map<Integer, AttachmentNodeBuilder> attachmentNodesByPackedPoint = new LinkedHashMap<>();
        private final EnumMap<TransportType, HubNodeBuilder> hubNodesByType = new EnumMap<>(TransportType.class);
        private final EnumMap<TransportType, HubCompressionBuilder> hubCompressionByType = new EnumMap<>(TransportType.class);
        private final Map<EdgeKey, EdgeBuilder> edges = new LinkedHashMap<>();
        private int nextNodeId = 0;
        private BuilderState(ComponentLabelIndex componentLabelIndex) {
            this.componentLabelIndex = componentLabelIndex;
        }

        private int getOrCreateAttachmentNode(int packedPoint, Set<Integer> componentIds) {
            AttachmentNodeBuilder node = attachmentNodesByPackedPoint.computeIfAbsent(
                packedPoint,
                ignored -> new AttachmentNodeBuilder(nextNodeId++, packedPoint));
            node.componentIds.addAll(componentIds);
            return node.id;
        }

        private int getOrCreateHubNode(TransportType type) {
            HubNodeBuilder node = hubNodesByType.computeIfAbsent(type, ignored -> new HubNodeBuilder(nextNodeId++, type));
            return node.id;
        }

        private void recordHubEndpoint(TransportType type, int entryNodeId, int exitNodeId) {
            HubCompressionBuilder builder = hubCompressionByType.computeIfAbsent(type, ignored -> new HubCompressionBuilder());
            builder.entryNodeIds.add(entryNodeId);
            builder.exitNodeIds.add(exitNodeId);
        }

        private void addEdge(int fromNodeId, int toNodeId, int cost, AbstractTransportGraph.EdgeKind kind, TransportType transportType) {
            EdgeKey key = new EdgeKey(fromNodeId, toNodeId, kind);
            EdgeBuilder existing = edges.get(key);
            if (existing == null || cost < existing.cost) {
                edges.put(key, new EdgeBuilder(fromNodeId, toNodeId, cost, kind, transportType));
            }
        }

        private AbstractTransportGraph build(long buildElapsedNanos) {
            List<AbstractTransportGraph.AbstractNode> nodes = new ArrayList<>(Collections.nCopies(nextNodeId, null));
            Map<Integer, List<Integer>> attachmentNodeIdsByComponent = new HashMap<>();

            for (AttachmentNodeBuilder nodeBuilder : attachmentNodesByPackedPoint.values()) {
                int[] componentIds = nodeBuilder.componentIds.stream().mapToInt(Integer::intValue).sorted().toArray();
                nodes.set(nodeBuilder.id, new AbstractTransportGraph.AbstractNode(
                    nodeBuilder.id,
                    AbstractTransportGraph.NodeKind.ATTACHMENT,
                    nodeBuilder.packedPoint,
                    componentIds,
                    null));
                for (int componentId : componentIds) {
                    attachmentNodeIdsByComponent
                        .computeIfAbsent(componentId, ignored -> new ArrayList<>())
                        .add(nodeBuilder.id);
                }
            }

            for (HubNodeBuilder nodeBuilder : hubNodesByType.values()) {
                nodes.set(nodeBuilder.id, new AbstractTransportGraph.AbstractNode(
                    nodeBuilder.id,
                    AbstractTransportGraph.NodeKind.HUB,
                    Transport.UNDEFINED_DESTINATION,
                    new int[0],
                    nodeBuilder.transportType));
            }

            List<List<AbstractTransportGraph.AbstractEdge>> incomingEdges = new ArrayList<>(nodes.size());
            for (int i = 0; i < nodes.size(); i++) {
                incomingEdges.add(new ArrayList<>());
            }

            EnumMap<AbstractTransportGraph.EdgeKind, Integer> storedEdgeCountsByKind =
                new EnumMap<>(AbstractTransportGraph.EdgeKind.class);
            for (AbstractTransportGraph.EdgeKind kind : AbstractTransportGraph.EdgeKind.values()) {
                storedEdgeCountsByKind.put(kind, 0);
            }

            for (EdgeBuilder edgeBuilder : edges.values()) {
                AbstractTransportGraph.AbstractEdge edge = new AbstractTransportGraph.AbstractEdge(
                    edgeBuilder.fromNodeId,
                    edgeBuilder.toNodeId,
                    edgeBuilder.cost,
                    edgeBuilder.kind,
                    edgeBuilder.transportType);
                incomingEdges.get(edge.getToNodeId()).add(edge);
                storedEdgeCountsByKind.merge(edge.getKind(), 1, Integer::sum);
            }

            Map<Integer, int[]> immutableAttachmentNodeIdsByComponent = new HashMap<>();
            List<Integer> attachmentCounts = new ArrayList<>(componentLabelIndex.getComponentCount());
            long totalImplicitWalkNeighborPairs = 0L;
            for (int componentId : componentLabelIndex.getComponentIds()) {
                List<Integer> attachmentNodeIds = attachmentNodeIdsByComponent.getOrDefault(componentId, List.of());
                int[] ids = attachmentNodeIds.stream().mapToInt(Integer::intValue).toArray();
                immutableAttachmentNodeIdsByComponent.put(componentId, ids);
                attachmentCounts.add(ids.length);
                totalImplicitWalkNeighborPairs += (long) ids.length * Math.max(0, ids.length - 1);
            }

            attachmentCounts.sort(Comparator.naturalOrder());
            int minAttachments = attachmentCounts.isEmpty() ? 0 : attachmentCounts.get(0);
            int medianAttachments = percentile(attachmentCounts, 0.50d);
            int p95Attachments = percentile(attachmentCounts, 0.95d);
            int maxAttachments = attachmentCounts.isEmpty() ? 0 : attachmentCounts.get(attachmentCounts.size() - 1);

            EnumMap<TransportType, AbstractTransportGraph.HubCompressionStats> hubCompressionStats =
                new EnumMap<>(TransportType.class);
            for (Map.Entry<TransportType, HubCompressionBuilder> entry : hubCompressionByType.entrySet()) {
                int entryCount = entry.getValue().entryNodeIds.size();
                int exitCount = entry.getValue().exitNodeIds.size();
                hubCompressionStats.put(entry.getKey(), new AbstractTransportGraph.HubCompressionStats(
                    entryCount * exitCount,
                    entryCount + exitCount));
            }

            AbstractTransportGraph.BuildStats buildStats = new AbstractTransportGraph.BuildStats(
                componentLabelIndex.getComponentCount(),
                componentLabelIndex.getWalkableTileCount(),
                attachmentNodesByPackedPoint.size(),
                hubNodesByType.size(),
                0,
                edges.size(),
                storedEdgeCountsByKind,
                hubCompressionStats,
                minAttachments,
                medianAttachments,
                p95Attachments,
                maxAttachments,
                totalImplicitWalkNeighborPairs,
                buildElapsedNanos);

            return new AbstractTransportGraph(
                nodes,
                incomingEdges,
                immutableAttachmentNodeIdsByComponent,
                -1,
                buildStats);
        }

        private static int percentile(List<Integer> values, double percentile) {
            if (values.isEmpty()) {
                return 0;
            }
            int index = (int) Math.ceil(percentile * values.size()) - 1;
            index = Math.max(0, Math.min(values.size() - 1, index));
            return values.get(index);
        }
    }

    private static final class AttachmentNodeBuilder {
        private final int id;
        private final int packedPoint;
        private final Set<Integer> componentIds = new HashSet<>();

        private AttachmentNodeBuilder(int id, int packedPoint) {
            this.id = id;
            this.packedPoint = packedPoint;
        }
    }

    private static final class HubNodeBuilder {
        private final int id;
        private final TransportType transportType;

        private HubNodeBuilder(int id, TransportType transportType) {
            this.id = id;
            this.transportType = transportType;
        }
    }

    private static final class HubCompressionBuilder {
        private final Set<Integer> entryNodeIds = new HashSet<>();
        private final Set<Integer> exitNodeIds = new HashSet<>();
    }

    private static final class EdgeBuilder {
        private final int fromNodeId;
        private final int toNodeId;
        private final int cost;
        private final AbstractTransportGraph.EdgeKind kind;
        private final TransportType transportType;

        private EdgeBuilder(int fromNodeId, int toNodeId, int cost, AbstractTransportGraph.EdgeKind kind, TransportType transportType) {
            this.fromNodeId = fromNodeId;
            this.toNodeId = toNodeId;
            this.cost = cost;
            this.kind = kind;
            this.transportType = transportType;
        }
    }

    private static final class EdgeKey {
        private final int fromNodeId;
        private final int toNodeId;
        private final AbstractTransportGraph.EdgeKind kind;

        private EdgeKey(int fromNodeId, int toNodeId, AbstractTransportGraph.EdgeKind kind) {
            this.fromNodeId = fromNodeId;
            this.toNodeId = toNodeId;
            this.kind = kind;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof EdgeKey)) {
                return false;
            }
            EdgeKey other = (EdgeKey) obj;
            return fromNodeId == other.fromNodeId
                && toNodeId == other.toNodeId
                && kind == other.kind;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(new Object[]{fromNodeId, toNodeId, kind});
        }
    }
}
