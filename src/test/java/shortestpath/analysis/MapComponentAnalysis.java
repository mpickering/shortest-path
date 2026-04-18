package shortestpath.analysis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import shortestpath.WorldPointUtil;
import shortestpath.dashboard.ComponentDashboardAssetWriter;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.SplitFlagMap;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportLoader;
import shortestpath.transport.TransportType;

public class MapComponentAnalysis {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int DEFAULT_MAX_EDGE_EXAMPLES = 3;
    private static final String DEFAULT_REPORT_NAME = "report.json";

    public static void main(String[] args) throws IOException {
        Arguments parsed = Arguments.parse(args);
        AnalysisResult result = analyze(parsed.includeTileComponents, parsed.maxEdgeExamples);
        String json = parsed.pretty ? GSON.toJson(result) : new Gson().toJson(result);
        TeleportTargetReport teleportTargetReport = buildTeleportTargetReport();
        String teleportJson = parsed.pretty ? GSON.toJson(teleportTargetReport) : new Gson().toJson(teleportTargetReport);

        if (parsed.output != null) {
            Path parent = parsed.output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(parsed.output, json, StandardCharsets.UTF_8);
            Path teleportOutput = resolveTeleportOutput(parsed.output);
            Files.writeString(teleportOutput, teleportJson, StandardCharsets.UTF_8);
            if (parsed.writeDashboard) {
                new ComponentDashboardAssetWriter().writeAssets(parent != null ? parent : Paths.get("."));
            }
        } else {
            System.out.println(json);
        }

        printSummary(result, parsed);
    }

    static AnalysisResult analyze(boolean includeTileComponents, int maxEdgeExamples) {
        SplitFlagMap splitFlagMap = SplitFlagMap.fromResources();
        CollisionMap collisionMap = new CollisionMap(splitFlagMap);
        Map<Integer, Set<Transport>> transportsByOrigin = TransportLoader.loadAllFromResources();
        ComponentIndex componentIndex = computeComponents(collisionMap, splitFlagMap, includeTileComponents);
        Map<EdgeKey, EdgeAggregate> edgeAggregates = buildTransportEdges(collisionMap, componentIndex, transportsByOrigin, maxEdgeExamples);
        TransportCategoryCounts transportCategoryCounts = summarizeTransportCounts(transportsByOrigin);

        List<ComponentSummary> components = new ArrayList<>(componentIndex.componentSummaries.values());
        List<TransportEdgeSummary> transportEdges = new ArrayList<>(edgeAggregates.size());
        for (EdgeAggregate aggregate : edgeAggregates.values()) {
            TransportEdgeSummary summary = aggregate.toSummary();
            transportEdges.add(summary);
            ComponentSummary source = componentIndex.componentSummaries.get(summary.fromComponentId);
            if (source != null) {
                source.outgoingEdgeCount++;
                source.exitPoints.addAll(summary.examplesFrom);
            }
            ComponentSummary destination = componentIndex.componentSummaries.get(summary.toComponentId);
            if (destination != null) {
                destination.incomingEdgeCount++;
                destination.entryPoints.addAll(summary.examplesTo);
            }
        }
        transportEdges.sort(Comparator
            .comparingInt((TransportEdgeSummary edge) -> edge.fromComponentId)
            .thenComparingInt(edge -> edge.toComponentId)
            .thenComparingInt(edge -> edge.transportCount));
        components.sort(Comparator
            .comparingInt((ComponentSummary component) -> component.size).reversed()
            .thenComparingInt(component -> component.id));

        for (ComponentSummary component : components) {
            component.entryPoints = dedupeMarkers(component.entryPoints);
            component.exitPoints = dedupeMarkers(component.exitPoints);
        }

        AnalysisResult result = new AnalysisResult();
        result.bounds = Bounds.from(SplitFlagMap.getRegionExtents());
        result.walkableTileCount = componentIndex.walkableTileCount;
        result.componentCount = components.size();
        result.components = components;
        result.transportEdgeCount = transportEdges.size();
        result.transportEdges = transportEdges;
        result.transportCounts = transportCategoryCounts;
        result.tileComponents = includeTileComponents ? componentIndex.tileComponents : null;
        return result;
    }

    private static TeleportTargetReport buildTeleportTargetReport() {
        SplitFlagMap splitFlagMap = SplitFlagMap.fromResources();
        CollisionMap collisionMap = new CollisionMap(splitFlagMap);
        Map<Integer, Set<Transport>> transportsByOrigin = TransportLoader.loadAllFromResources();
        ComponentIndex componentIndex = computeComponents(collisionMap, splitFlagMap, false);
        Map<Integer, ComponentTeleportGroup> groupedTeleports = new HashMap<>();
        int totalTeleports = 0;

        for (Set<Transport> transportSet : transportsByOrigin.values()) {
            for (Transport transport : transportSet) {
                TransportType type = transport.getType();
                if (type == null || !type.isTeleport()) {
                    continue;
                }

                totalTeleports++;
                Set<Integer> destinationComponents = ComponentAnalysisSupport.resolveComponents(
                    collisionMap,
                    componentIndex.packedPointToComponent,
                    transport.getDestination());
                for (Integer componentId : destinationComponents) {
                    ComponentTeleportGroup group = groupedTeleports.computeIfAbsent(
                        componentId, ComponentTeleportGroup::new);
                    group.teleports.add(TeleportTargetEntry.from(transport, componentId));
                }
            }
        }

        List<ComponentTeleportGroup> components = groupedTeleports.values().stream()
            .peek(group -> {
                group.teleports.sort(Comparator
                    .comparing((TeleportTargetEntry entry) -> entry.type)
                    .thenComparing(entry -> entry.destination)
                    .thenComparing(entry -> entry.origin)
                    .thenComparing(entry -> entry.label));
                group.teleportCount = group.teleports.size();
            })
            .sorted(Comparator
                .comparingInt((ComponentTeleportGroup group) -> group.teleportCount).reversed()
                .thenComparingInt(group -> group.componentId))
            .collect(Collectors.toList());

        LinkedHashMap<String, Integer> histogram = new LinkedHashMap<>();
        for (ComponentTeleportGroup group : components) {
            histogram.put(Integer.toString(group.componentId), group.teleportCount);
        }

        TeleportTargetReport report = new TeleportTargetReport();
        report.totalTeleports = totalTeleports;
        report.componentCount = components.size();
        report.histogram = histogram;
        report.components = components;
        return report;
    }

    private static Path resolveTeleportOutput(Path reportOutput) {
        Path parent = reportOutput.getParent();
        Path outputDirectory = parent != null ? parent : Paths.get(".");
        String filename = reportOutput.getFileName() != null ? reportOutput.getFileName().toString() : DEFAULT_REPORT_NAME;
        int extensionIndex = filename.lastIndexOf('.');
        String stem = extensionIndex >= 0 ? filename.substring(0, extensionIndex) : filename;
        return outputDirectory.resolve(stem + "-teleport-target-components.json");
    }

    private static TransportCategoryCounts summarizeTransportCounts(Map<Integer, Set<Transport>> transportsByOrigin) {
        Map<String, Integer> countsByType = new LinkedHashMap<>();
        int total = 0;
        int teleports = 0;
        int transports = 0;

        for (Set<Transport> transportSet : transportsByOrigin.values()) {
            for (Transport transport : transportSet) {
                total++;
                TransportType type = transport.getType();
                String typeName = type != null ? type.name() : "UNKNOWN";
                countsByType.merge(typeName, 1, Integer::sum);
                if (type != null && type.isTeleport()) {
                    teleports++;
                } else {
                    transports++;
                }
            }
        }

        Map<String, Integer> sortedCountsByType = countsByType.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (left, right) -> left,
                LinkedHashMap::new));

        TransportCategoryCounts summary = new TransportCategoryCounts();
        summary.total = total;
        summary.teleports = teleports;
        summary.transports = transports;
        summary.byType = sortedCountsByType;
        return summary;
    }

    private static ComponentIndex computeComponents(CollisionMap collisionMap, SplitFlagMap splitFlagMap, boolean includeTileComponents) {
        ComponentAnalysisSupport.ComponentComputation computation = ComponentAnalysisSupport.computeComponents(collisionMap, splitFlagMap);
        Map<Integer, Integer> packedPointToComponent = computation.getPackedPointToComponent();
        Map<Integer, ComponentSummary> componentSummaries = new LinkedHashMap<>();
        Map<String, Integer> tileComponents = includeTileComponents ? new LinkedHashMap<>() : null;
        for (Map.Entry<Integer, Integer> entry : packedPointToComponent.entrySet()) {
            int packed = entry.getKey();
            int componentId = entry.getValue();
            ComponentSummary summary = componentSummaries.computeIfAbsent(componentId, ComponentSummary::new);
            summary.accept(packed);
            if (tileComponents != null) {
                tileComponents.put(formatPointKey(packed), componentId);
            }
        }

        ComponentIndex index = new ComponentIndex();
        index.packedPointToComponent = packedPointToComponent;
        index.componentSummaries = componentSummaries;
        index.tileComponents = tileComponents;
        index.walkableTileCount = computation.getWalkableTileCount();
        return index;
    }

    private static Map<EdgeKey, EdgeAggregate> buildTransportEdges(
        CollisionMap collisionMap,
        ComponentIndex componentIndex,
        Map<Integer, Set<Transport>> transportsByOrigin,
        int maxEdgeExamples
    ) {
        Map<EdgeKey, EdgeAggregate> edgeAggregates = new LinkedHashMap<>();
        for (Set<Transport> transports : transportsByOrigin.values()) {
            for (Transport transport : transports) {
                Set<Integer> sourceComponents = ComponentAnalysisSupport.resolveComponents(
                    collisionMap,
                    componentIndex.packedPointToComponent,
                    transport.getOrigin());
                Set<Integer> destinationComponents = ComponentAnalysisSupport.resolveComponents(
                    collisionMap,
                    componentIndex.packedPointToComponent,
                    transport.getDestination());
                if (sourceComponents.isEmpty() || destinationComponents.isEmpty()) {
                    continue;
                }

                for (int sourceComponent : sourceComponents) {
                    for (int destinationComponent : destinationComponents) {
                        EdgeKey key = new EdgeKey(sourceComponent, destinationComponent);
                        EdgeAggregate aggregate = edgeAggregates.computeIfAbsent(key,
                            ignored -> new EdgeAggregate(sourceComponent, destinationComponent, maxEdgeExamples));
                        aggregate.add(transport);
                    }
                }
            }
        }
        return edgeAggregates;
    }

    private static String formatPoint(int packed) {
        return WorldPointUtil.unpackWorldX(packed) + "," + WorldPointUtil.unpackWorldY(packed) + "," + WorldPointUtil.unpackWorldPlane(packed);
    }


    private static String formatPointKey(int packed) {
        return formatPoint(packed);
    }

    private static List<ComponentMarker> dedupeMarkers(List<ComponentMarker> markers) {
        Map<String, ComponentMarker> deduped = new LinkedHashMap<>();
        for (ComponentMarker marker : markers) {
            String key = marker.point + "|" + marker.connectedComponentId + "|" + marker.transportType + "|" + marker.label;
            deduped.putIfAbsent(key, marker);
        }
        return new ArrayList<>(deduped.values());
    }

    private static void printSummary(AnalysisResult result, Arguments arguments) {
        ComponentSummary largestComponent = result.components.stream()
            .max(Comparator.comparingInt(component -> component.size))
            .orElse(null);
        Map<Integer, Integer> outgoingEdgeCounts = new HashMap<>();
        Map<Integer, Integer> incomingEdgeCounts = new HashMap<>();
        for (TransportEdgeSummary edge : result.transportEdges) {
            outgoingEdgeCounts.merge(edge.fromComponentId, 1, Integer::sum);
            incomingEdgeCounts.merge(edge.toComponentId, 1, Integer::sum);
        }

        System.err.println("Map component analysis complete.");
        System.err.println(" - walkable tiles: " + result.walkableTileCount);
        System.err.println(" - components: " + result.componentCount);
        System.err.println(" - transport edges: " + result.transportEdgeCount);
        if (result.transportCounts != null) {
            System.err.println(" - transport total: " + result.transportCounts.total
                + " (" + result.transportCounts.transports + " transports, "
                + result.transportCounts.teleports + " teleports)");
            System.err.println(" - transport categories:");
            for (Map.Entry<String, Integer> entry : result.transportCounts.byType.entrySet()) {
                System.err.println("   " + entry.getKey() + ": " + entry.getValue());
            }
        }
        if (largestComponent != null) {
            System.err.println(" - largest component: id=" + largestComponent.id
                + ", size=" + largestComponent.size
                + ", sample=" + largestComponent.samplePoint);
        }
        if (arguments.output != null) {
            System.err.println(" - output: " + arguments.output);
        } else {
            System.err.println(" - output: stdout");
        }
        if (arguments.includeTileComponents) {
            System.err.println(" - tile components: included");
        }
        System.err.println(" - component edge counts:");
        for (ComponentSummary component : result.components) {
            int outgoing = outgoingEdgeCounts.getOrDefault(component.id, 0);
            int incoming = incomingEdgeCounts.getOrDefault(component.id, 0);
            if (outgoing == 0 && incoming == 0) {
                continue;
            }
            System.err.println("   component " + component.id + ": out=" + outgoing + ", in=" + incoming);
        }
    }

    private static final class Arguments {
        private Path output;
        private boolean pretty;
        private boolean includeTileComponents;
        private boolean writeDashboard;
        private int maxEdgeExamples = DEFAULT_MAX_EDGE_EXAMPLES;

        private static Arguments parse(String[] args) {
            Arguments parsed = new Arguments();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--output":
                        parsed.output = Paths.get(args[++i]);
                        break;
                    case "--pretty":
                        parsed.pretty = true;
                        break;
                    case "--include-tile-components":
                        parsed.includeTileComponents = true;
                        break;
                    case "--write-dashboard":
                        parsed.writeDashboard = true;
                        break;
                    case "--max-edge-examples":
                        parsed.maxEdgeExamples = Integer.parseInt(args[++i]);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            if (parsed.output == null) {
                parsed.pretty = true;
                parsed.writeDashboard = false;
            } else if (parsed.output.getFileName() != null && DEFAULT_REPORT_NAME.equals(parsed.output.getFileName().toString())) {
                parsed.writeDashboard = true;
            }
            return parsed;
        }
    }

    private static final class ComponentIndex {
        private Map<Integer, Integer> packedPointToComponent;
        private Map<Integer, ComponentSummary> componentSummaries;
        private Map<String, Integer> tileComponents;
        private long walkableTileCount;
    }

    private static final class EdgeKey {
        private final int from;
        private final int to;

        private EdgeKey(int from, int to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof EdgeKey)) {
                return false;
            }
            EdgeKey that = (EdgeKey) other;
            return from == that.from && to == that.to;
        }

        @Override
        public int hashCode() {
            return 31 * from + to;
        }
    }

    private static final class EdgeAggregate {
        private final int fromComponentId;
        private final int toComponentId;
        private final int maxExamples;
        private int transportCount;
        private final Set<String> transportTypes = new HashSet<>();
        private final List<String> examples = new ArrayList<>();
        private final List<ComponentMarker> examplesFrom = new ArrayList<>();
        private final List<ComponentMarker> examplesTo = new ArrayList<>();

        private EdgeAggregate(int fromComponentId, int toComponentId, int maxExamples) {
            this.fromComponentId = fromComponentId;
            this.toComponentId = toComponentId;
            this.maxExamples = maxExamples;
        }

        private void add(Transport transport) {
            transportCount++;
            if (transport.getType() != null) {
                transportTypes.add(transport.getType().name());
            }
            if (examples.size() < maxExamples) {
                examples.add(describeTransport(transport));
            }
            if (examplesFrom.size() < maxExamples) {
                examplesFrom.add(createMarker(transport.getOrigin(), toComponentId, transport, "exit"));
            }
            if (examplesTo.size() < maxExamples) {
                examplesTo.add(createMarker(transport.getDestination(), fromComponentId, transport, "entry"));
            }
        }

        private TransportEdgeSummary toSummary() {
            TransportEdgeSummary summary = new TransportEdgeSummary();
            summary.fromComponentId = fromComponentId;
            summary.toComponentId = toComponentId;
            summary.transportCount = transportCount;
            summary.transportTypes = transportTypes.stream().sorted().collect(Collectors.toList());
            summary.examples = examples;
            summary.examplesFrom = new ArrayList<>(examplesFrom);
            summary.examplesTo = new ArrayList<>(examplesTo);
            return summary;
        }
    }

    private static ComponentMarker createMarker(int packedPoint, int connectedComponentId, Transport transport, String direction) {
        ComponentMarker marker = new ComponentMarker();
        marker.point = formatPoint(packedPoint);
        marker.connectedComponentId = connectedComponentId;
        marker.transportType = transport.getType() != null ? transport.getType().name() : "UNKNOWN";
        marker.label = transport.getDisplayInfo() != null && !transport.getDisplayInfo().isBlank()
            ? transport.getDisplayInfo()
            : transport.getObjectInfo();
        marker.direction = direction;
        return marker;
    }

    private static String describeTransport(Transport transport) {
        StringBuilder builder = new StringBuilder();
        builder.append(transport.getType() != null ? transport.getType().name() : "UNKNOWN");
        builder.append(": ").append(formatPoint(transport.getOrigin())).append(" -> ").append(formatPoint(transport.getDestination()));
        if (transport.getDisplayInfo() != null && !transport.getDisplayInfo().isBlank()) {
            builder.append(" [").append(transport.getDisplayInfo()).append(']');
        } else if (transport.getObjectInfo() != null && !transport.getObjectInfo().isBlank()) {
            builder.append(" [").append(transport.getObjectInfo()).append(']');
        }
        return builder.toString();
    }

    private static final class AnalysisResult {
        private Bounds bounds;
        private long walkableTileCount;
        private int componentCount;
        private List<ComponentSummary> components;
        private int transportEdgeCount;
        private List<TransportEdgeSummary> transportEdges;
        private TransportCategoryCounts transportCounts;
        private Map<String, Integer> tileComponents;
    }

    private static final class TransportCategoryCounts {
        private int total;
        private int teleports;
        private int transports;
        private Map<String, Integer> byType;
    }

    private static final class TeleportTargetReport {
        private int totalTeleports;
        private int componentCount;
        private Map<String, Integer> histogram;
        private List<ComponentTeleportGroup> components;
    }

    private static final class ComponentTeleportGroup {
        private final int componentId;
        private int teleportCount;
        private final List<TeleportTargetEntry> teleports = new ArrayList<>();

        private ComponentTeleportGroup(int componentId) {
            this.componentId = componentId;
        }
    }

    private static final class TeleportTargetEntry {
        private String type;
        private String origin;
        private String destination;
        private String label;
        private int targetComponentId;

        private static TeleportTargetEntry from(Transport transport, int componentId) {
            TeleportTargetEntry entry = new TeleportTargetEntry();
            entry.type = transport.getType() != null ? transport.getType().name() : "UNKNOWN";
            entry.origin = formatPoint(transport.getOrigin());
            entry.destination = formatPoint(transport.getDestination());
            entry.label = transport.getDisplayInfo() != null && !transport.getDisplayInfo().isBlank()
                ? transport.getDisplayInfo()
                : transport.getObjectInfo();
            entry.targetComponentId = componentId;
            return entry;
        }
    }

    private static final class Bounds {
        private int minRegionX;
        private int minRegionY;
        private int maxRegionX;
        private int maxRegionY;

        private static Bounds from(SplitFlagMap.RegionExtent extents) {
            Bounds bounds = new Bounds();
            bounds.minRegionX = extents.getMinX();
            bounds.minRegionY = extents.getMinY();
            bounds.maxRegionX = extents.getMaxX();
            bounds.maxRegionY = extents.getMaxY();
            return bounds;
        }
    }

    private static final class ComponentSummary {
        private int id;
        private int size;
        private String samplePoint;
        private TileBounds bounds;
        private int incomingEdgeCount;
        private int outgoingEdgeCount;
        private List<ComponentMarker> entryPoints = new ArrayList<>();
        private List<ComponentMarker> exitPoints = new ArrayList<>();

        private ComponentSummary(int id) {
            this.id = id;
            this.bounds = new TileBounds(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        }

        private void accept(int packed) {
            int x = WorldPointUtil.unpackWorldX(packed);
            int y = WorldPointUtil.unpackWorldY(packed);
            int plane = WorldPointUtil.unpackWorldPlane(packed);
            size++;
            if (samplePoint == null) {
                samplePoint = formatPoint(packed);
            }
            bounds.minX = Math.min(bounds.minX, x);
            bounds.minY = Math.min(bounds.minY, y);
            bounds.minPlane = Math.min(bounds.minPlane, plane);
            bounds.maxX = Math.max(bounds.maxX, x);
            bounds.maxY = Math.max(bounds.maxY, y);
            bounds.maxPlane = Math.max(bounds.maxPlane, plane);
        }
    }

    private static final class TileBounds {
        private int minX;
        private int minY;
        private int minPlane;
        private int maxX;
        private int maxY;
        private int maxPlane;

        private TileBounds(int minX, int minY, int minPlane, int maxX, int maxY, int maxPlane) {
            this.minX = minX;
            this.minY = minY;
            this.minPlane = minPlane;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxPlane = maxPlane;
        }
    }

    private static final class TransportEdgeSummary {
        private int fromComponentId;
        private int toComponentId;
        private int transportCount;
        private List<String> transportTypes;
        private List<String> examples;
        private List<ComponentMarker> examplesFrom;
        private List<ComponentMarker> examplesTo;
    }

    private static final class ComponentMarker {
        private String point;
        private int connectedComponentId;
        private String transportType;
        private String label;
        private String direction;
    }

}
