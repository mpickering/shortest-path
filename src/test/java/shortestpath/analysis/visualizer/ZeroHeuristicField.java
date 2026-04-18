package shortestpath.analysis.visualizer;

public class ZeroHeuristicField implements HeuristicField {
    @Override
    public HeuristicSample sample(int packedPoint, TileStateQuery.TileState tileState) {
        if (tileState == TileStateQuery.TileState.MISSING) {
            return HeuristicSample.undefined();
        }
        return HeuristicSample.defined(0.0d);
    }
}
