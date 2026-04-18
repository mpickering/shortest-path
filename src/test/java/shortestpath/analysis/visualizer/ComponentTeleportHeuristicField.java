package shortestpath.analysis.visualizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.Node;
import shortestpath.pathfinder.PathfinderHeuristic;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportLoader;

public class ComponentTeleportHeuristicField implements HeuristicField, PathfinderHeuristic {
    private final ComponentLabelIndex componentLabelIndex;
    private final int goal;
    private final Integer goalComponentId;
    private final double goalExitLowerBound;
    private final Map<Integer, NearestPointIndex> teleportEntriesByComponent;
    private final Map<Integer, NearestPointIndex> teleportExitsByComponent;

    public ComponentTeleportHeuristicField(ComponentLabelIndex componentLabelIndex, int goal) {
        this.componentLabelIndex = componentLabelIndex;
        this.goal = goal;
        this.goalComponentId = componentLabelIndex.getComponentId(goal);
        this.teleportEntriesByComponent = new HashMap<>();
        this.teleportExitsByComponent = new HashMap<>();
        indexTeleports();
        this.goalExitLowerBound = goalComponentId != null
            ? nearestDistanceWithinComponent(goal, teleportExitsByComponent.get(goalComponentId))
            : Double.POSITIVE_INFINITY;
    }

    @Override
    public HeuristicSample sample(int packedPoint, TileStateQuery.TileState tileState) {
        if (tileState == TileStateQuery.TileState.MISSING) {
            return HeuristicSample.undefined();
        }

        Integer pointComponentId = componentLabelIndex.getComponentId(packedPoint);
        if (pointComponentId == null || goalComponentId == null) {
            return HeuristicSample.undefined();
        }

        double best = Double.POSITIVE_INFINITY;
        if (pointComponentId.equals(goalComponentId)) {
            best = chebyshev(packedPoint, goal);
        }

        double entryLowerBound = nearestDistanceWithinComponent(packedPoint, teleportEntriesByComponent.get(pointComponentId));
        if (Double.isFinite(entryLowerBound) && Double.isFinite(goalExitLowerBound)) {
            best = Math.min(best, entryLowerBound + goalExitLowerBound);
        }

        return Double.isFinite(best) ? HeuristicSample.defined(best) : HeuristicSample.undefined();
    }

    @Override
    public double estimate(Node node) {
        if (!node.isTile()) {
            return 0.0d;
        }
        Integer pointComponentId = componentLabelIndex.getComponentId(node.packedPosition);
        if (pointComponentId == null || goalComponentId == null) {
            return 0.0d;
        }

        double best = Double.POSITIVE_INFINITY;
        if (pointComponentId.equals(goalComponentId)) {
            best = chebyshev(node.packedPosition, goal);
        }

        double entryLowerBound = nearestDistanceWithinComponent(node.packedPosition, teleportEntriesByComponent.get(pointComponentId));
        if (Double.isFinite(entryLowerBound) && Double.isFinite(goalExitLowerBound)) {
            best = Math.min(best, entryLowerBound + goalExitLowerBound);
        }

        return Double.isFinite(best) ? best : 0.0d;
    }

    public int countEntriesInComponent(int componentId) {
        return teleportEntriesByComponent.getOrDefault(componentId, emptyIndex()).size();
    }

    public int countExitsInComponent(int componentId) {
        return teleportExitsByComponent.getOrDefault(componentId, emptyIndex()).size();
    }

    private void indexTeleports() {
        Map<Integer, Set<Transport>> transportsByOrigin = TransportLoader.loadAllFromResources();
        Map<Integer, List<Integer>> entryPoints = new HashMap<>();
        Map<Integer, List<Integer>> exitPoints = new HashMap<>();
        for (Set<Transport> transports : transportsByOrigin.values()) {
            for (Transport transport : transports) {
                if (transport.getOrigin() == Transport.UNDEFINED_ORIGIN) {
                    continue;
                }
                Integer originComponentId = componentLabelIndex.getComponentId(transport.getOrigin());
                Integer destinationComponentId = componentLabelIndex.getComponentId(transport.getDestination());
                if (originComponentId != null) {
                    entryPoints.computeIfAbsent(originComponentId, ignored -> new ArrayList<>())
                        .add(transport.getOrigin());
                }
                if (destinationComponentId != null) {
                    exitPoints.computeIfAbsent(destinationComponentId, ignored -> new ArrayList<>())
                        .add(transport.getDestination());
                }
            }
        }
        for (Map.Entry<Integer, List<Integer>> entry : entryPoints.entrySet()) {
            teleportEntriesByComponent.put(entry.getKey(), KdTreeNearestPointIndex.fromPackedPoints(entry.getValue()));
        }
        for (Map.Entry<Integer, List<Integer>> entry : exitPoints.entrySet()) {
            teleportExitsByComponent.put(entry.getKey(), KdTreeNearestPointIndex.fromPackedPoints(entry.getValue()));
        }
    }

    private static double nearestDistanceWithinComponent(int source, NearestPointIndex index) {
        if (index == null || index.size() == 0) {
            return Double.POSITIVE_INFINITY;
        }
        return index.nearestDistance(source);
    }

    private static NearestPointIndex emptyIndex() {
        return KdTreeNearestPointIndex.fromPackedPoints(List.of());
    }

    private static int chebyshev(int a, int b) {
        return WorldPointUtil.distanceBetween(a, b, WorldPointUtil.CHEBYSHEV_DISTANCE_METRIC);
    }
}
