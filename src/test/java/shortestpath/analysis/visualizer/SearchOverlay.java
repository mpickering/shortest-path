package shortestpath.analysis.visualizer;

public interface SearchOverlay {
    SearchOverlay NONE = new SearchOverlay() {
    };

    default boolean isVisited(int packedPoint) {
        return false;
    }

    default boolean isVisitedWithBank(int packedPoint) {
        return false;
    }

    default boolean isOnPath(int packedPoint) {
        return false;
    }

    default boolean isFrontier(int packedPoint) {
        return false;
    }
}
