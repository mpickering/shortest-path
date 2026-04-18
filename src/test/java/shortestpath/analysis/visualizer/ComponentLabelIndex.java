package shortestpath.analysis.visualizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import shortestpath.analysis.ComponentAnalysisSupport;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.SplitFlagMap;

public class ComponentLabelIndex {
    private final Map<Integer, Integer> tileToComponent;
    private final Map<Integer, Integer> componentSizes;
    private final long walkableTileCount;

    private ComponentLabelIndex(Map<Integer, Integer> tileToComponent, Map<Integer, Integer> componentSizes, long walkableTileCount) {
        this.tileToComponent = tileToComponent;
        this.componentSizes = componentSizes;
        this.walkableTileCount = walkableTileCount;
    }

    public static ComponentLabelIndex build(CollisionMap collisionMap, SplitFlagMap splitFlagMap) {
        ComponentAnalysisSupport.ComponentComputation computation = ComponentAnalysisSupport.computeComponents(collisionMap, splitFlagMap);
        return fromPackedPointToComponent(computation.getPackedPointToComponent(), computation.getWalkableTileCount());
    }

    public static ComponentLabelIndex fromPackedPointToComponent(Map<Integer, Integer> tileToComponent) {
        return fromPackedPointToComponent(tileToComponent, tileToComponent.size());
    }

    public static ComponentLabelIndex fromPackedPointToComponent(Map<Integer, Integer> tileToComponent, long walkableTileCount) {
        Map<Integer, Integer> copied = new HashMap<>(tileToComponent);
        Map<Integer, Integer> componentSizes = new HashMap<>();
        for (Integer componentId : copied.values()) {
            componentSizes.merge(componentId, 1, Integer::sum);
        }
        return new ComponentLabelIndex(copied, componentSizes, walkableTileCount);
    }

    public Integer getComponentId(int packedPoint) {
        return tileToComponent.get(packedPoint);
    }

    public boolean isAnalysisWall(int packedPoint) {
        return ComponentAnalysisSupport.getAnalysisBlockedTiles().contains(packedPoint);
    }

    Map<Integer, Integer> getTileToComponent() {
        return tileToComponent;
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
