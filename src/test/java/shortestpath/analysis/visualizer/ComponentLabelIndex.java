package shortestpath.analysis.visualizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import shortestpath.analysis.ComponentAnalysisSupport;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.SplitFlagMap;

public class ComponentLabelIndex {
    private final Map<Integer, Integer> tileToComponent;

    private ComponentLabelIndex(Map<Integer, Integer> tileToComponent) {
        this.tileToComponent = tileToComponent;
    }

    public static ComponentLabelIndex build(CollisionMap collisionMap, SplitFlagMap splitFlagMap) {
        return new ComponentLabelIndex(new HashMap<>(ComponentAnalysisSupport.computeComponents(collisionMap, splitFlagMap).getPackedPointToComponent()));
    }

    public Integer getComponentId(int packedPoint) {
        return tileToComponent.get(packedPoint);
    }

    public boolean isAnalysisWall(int packedPoint) {
        return ComponentAnalysisSupport.getAnalysisBlockedTiles().contains(packedPoint);
    }

    public List<ComponentSize> largestComponents(int limit) {
        Map<Integer, Integer> sizes = new HashMap<>();
        for (Integer componentId : tileToComponent.values()) {
            sizes.merge(componentId, 1, Integer::sum);
        }

        List<ComponentSize> ranked = new ArrayList<>(sizes.size());
        for (Map.Entry<Integer, Integer> entry : sizes.entrySet()) {
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
