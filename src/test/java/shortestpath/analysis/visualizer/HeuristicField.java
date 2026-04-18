package shortestpath.analysis.visualizer;

public interface HeuristicField {
    HeuristicSample sample(int packedPoint, TileStateQuery.TileState tileState);
}
