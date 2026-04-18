package shortestpath.analysis.visualizer;

public class TileRegion {
    private final int minX;
    private final int minY;
    private final int maxXExclusive;
    private final int maxYExclusive;
    private final int plane;

    public TileRegion(int minX, int minY, int maxXExclusive, int maxYExclusive, int plane) {
        if (maxXExclusive <= minX) {
            throw new IllegalArgumentException("maxXExclusive must be greater than minX");
        }
        if (maxYExclusive <= minY) {
            throw new IllegalArgumentException("maxYExclusive must be greater than minY");
        }
        this.minX = minX;
        this.minY = minY;
        this.maxXExclusive = maxXExclusive;
        this.maxYExclusive = maxYExclusive;
        this.plane = plane;
    }

    public int getMinX() {
        return minX;
    }

    public int getMinY() {
        return minY;
    }

    public int getMaxXExclusive() {
        return maxXExclusive;
    }

    public int getMaxYExclusive() {
        return maxYExclusive;
    }

    public int getPlane() {
        return plane;
    }

    public int getWidth() {
        return maxXExclusive - minX;
    }

    public int getHeight() {
        return maxYExclusive - minY;
    }

    public boolean contains(int x, int y, int plane) {
        return this.plane == plane
            && x >= minX && x < maxXExclusive
            && y >= minY && y < maxYExclusive;
    }
}
