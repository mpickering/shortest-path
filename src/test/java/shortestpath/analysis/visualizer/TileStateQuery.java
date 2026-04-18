package shortestpath.analysis.visualizer;

public interface TileStateQuery {
    enum TileState {
        WALKABLE,
        BLOCKED,
        MISSING
    }

    TileState getTileState(int x, int y, int plane);

    boolean isStart(int packedPoint);

    boolean isGoal(int packedPoint);

    boolean isTeleportEntry(int packedPoint);

    boolean isTeleportExit(int packedPoint);

    boolean isBank(int packedPoint);
}
