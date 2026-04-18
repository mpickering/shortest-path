package shortestpath.pathfinder;

public interface PathfinderHeuristic {
    PathfinderHeuristic ZERO = node -> 0.0d;

    double estimate(Node node);
}
