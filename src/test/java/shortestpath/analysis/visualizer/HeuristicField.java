package shortestpath.analysis.visualizer;

public interface HeuristicField {
    HeuristicSample sample(int packedPoint, TileStateQuery.TileState tileState);

    default HeuristicSample sample(int packedPoint, TileStateQuery.TileState tileState, int remainingTransportMask) {
        return sample(packedPoint, tileState);
    }
}
