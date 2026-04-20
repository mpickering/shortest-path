package shortestpath.analysis.visualizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import shortestpath.analysis.ComponentAnalysisSupport;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.SplitFlagMap;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportLoader;

public class ComponentLabelIndex {
    private final Map<Integer, Integer> tileToComponent;
    private final Map<Integer, Set<Integer>> resolvedComponentsByPackedPoint;
    private final Map<Integer, Integer> componentSizes;
    private final long walkableTileCount;

    private ComponentLabelIndex(
        Map<Integer, Integer> tileToComponent,
        Map<Integer, Set<Integer>> resolvedComponentsByPackedPoint,
        Map<Integer, Integer> componentSizes,
        long walkableTileCount
    ) {
        this.tileToComponent = tileToComponent;
        this.resolvedComponentsByPackedPoint = resolvedComponentsByPackedPoint;
        this.componentSizes = componentSizes;
        this.walkableTileCount = walkableTileCount;
    }

    public static ComponentLabelIndex build(CollisionMap collisionMap, SplitFlagMap splitFlagMap) {
        ComponentAnalysisSupport.ComponentComputation computation = ComponentAnalysisSupport.computeComponents(collisionMap, splitFlagMap);
        Map<Integer, Integer> tileToComponent = computation.getPackedPointToComponent();
        return new ComponentLabelIndex(
            new HashMap<>(tileToComponent),
            buildResolvedComponents(collisionMap, tileToComponent),
            buildComponentSizes(tileToComponent),
            computation.getWalkableTileCount());
    }

    public static ComponentLabelIndex fromPackedPointToComponent(Map<Integer, Integer> tileToComponent) {
        return fromPackedPointToComponent(tileToComponent, tileToComponent.size());
    }

    public static ComponentLabelIndex fromPackedPointToComponent(Map<Integer, Integer> tileToComponent, long walkableTileCount) {
        Map<Integer, Integer> copied = new HashMap<>(tileToComponent);
        return new ComponentLabelIndex(copied, Map.of(), buildComponentSizes(copied), walkableTileCount);
    }

    public Integer getComponentId(int packedPoint) {
        return tileToComponent.get(packedPoint);
    }

    public Set<Integer> getResolvedComponentIds(int packedPoint) {
        Integer direct = tileToComponent.get(packedPoint);
        if (direct != null) {
            return Set.of(direct);
        }
        return resolvedComponentsByPackedPoint.getOrDefault(packedPoint, Set.of());
    }

    public boolean isAnalysisWall(int packedPoint) {
        return ComponentAnalysisSupport.getAnalysisBlockedTiles().contains(packedPoint);
    }

    Map<Integer, Integer> getTileToComponent() {
        return tileToComponent;
    }

    private static Map<Integer, Integer> buildComponentSizes(Map<Integer, Integer> tileToComponent) {
        Map<Integer, Integer> componentSizes = new HashMap<>();
        for (Integer componentId : tileToComponent.values()) {
            componentSizes.merge(componentId, 1, Integer::sum);
        }
        return componentSizes;
    }

    private static Map<Integer, Set<Integer>> buildResolvedComponents(
        CollisionMap collisionMap,
        Map<Integer, Integer> tileToComponent
    ) {
        Map<Integer, Set<Integer>> resolved = new HashMap<>();
        for (Set<Transport> transports : TransportLoader.loadAllFromResources().values()) {
            for (Transport transport : transports) {
                addResolvedPoint(resolved, collisionMap, tileToComponent, transport.getOrigin());
                addResolvedPoint(resolved, collisionMap, tileToComponent, transport.getDestination());
            }
        }
        return Collections.unmodifiableMap(resolved);
    }

    private static void addResolvedPoint(
        Map<Integer, Set<Integer>> resolved,
        CollisionMap collisionMap,
        Map<Integer, Integer> tileToComponent,
        int packedPoint
    ) {
        if (packedPoint == Transport.UNDEFINED_ORIGIN
            || packedPoint == Transport.UNDEFINED_DESTINATION
            || packedPoint == Transport.LOCATION_PERMUTATION
            || tileToComponent.containsKey(packedPoint)
            || resolved.containsKey(packedPoint)) {
            return;
        }
        Set<Integer> componentIds = ComponentAnalysisSupport.resolveComponents(collisionMap, tileToComponent, packedPoint);
        if (!componentIds.isEmpty()) {
            resolved.put(packedPoint, Set.copyOf(componentIds));
        }
    }

    public int getComponentCount() {
        return componentSizes.size();
    }

    public long getWalkableTileCount() {
        return walkableTileCount;
    }

    public Set<Integer> getComponentIds() {
        return new HashSet<>(componentSizes.keySet());
    }

    public List<ComponentSize> largestComponents(int limit) {
        List<ComponentSize> ranked = new ArrayList<>(componentSizes.size());
        for (Map.Entry<Integer, Integer> entry : componentSizes.entrySet()) {
            ranked.add(new ComponentSize(entry.getKey(), entry.getValue()));
        }
        ranked.sort(Comparator
            .comparingInt(ComponentSize::getSize).reversed()
            .thenComparingInt(ComponentSize::getComponentId));
        return ranked.subList(0, Math.min(limit, ranked.size()));
    }

    public static final class ComponentSize {
        private final int componentId;
        private final int size;

        private ComponentSize(int componentId, int size) {
            this.componentId = componentId;
            this.size = size;
        }

        public int getComponentId() {
            return componentId;
        }

        public int getSize() {
            return size;
        }
    }
}
