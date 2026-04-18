package shortestpath.analysis.visualizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import shortestpath.WorldPointUtil;

public final class KdTreeNearestPointIndex implements NearestPointIndex {
    private static final KdTreeNearestPointIndex EMPTY = new KdTreeNearestPointIndex(null, 0);

    private final Node root;
    private final int size;

    private KdTreeNearestPointIndex(Node root, int size) {
        this.root = root;
        this.size = size;
    }

    public static KdTreeNearestPointIndex fromPackedPoints(List<Integer> packedPoints) {
        if (packedPoints == null || packedPoints.isEmpty()) {
            return EMPTY;
        }
        List<Point> points = new ArrayList<>(packedPoints.size());
        for (int packedPoint : packedPoints) {
            points.add(new Point(packedPoint));
        }
        return new KdTreeNearestPointIndex(build(points, 0), points.size());
    }

    @Override
    public double nearestDistance(int packedPoint) {
        if (root == null) {
            return Double.POSITIVE_INFINITY;
        }
        Point query = new Point(packedPoint);
        return nearest(root, query, Integer.MAX_VALUE);
    }

    @Override
    public int size() {
        return size;
    }

    private static Node build(List<Point> points, int axis) {
        if (points.isEmpty()) {
            return null;
        }
        points.sort(axis == 0 ? Comparator.comparingInt(Point::getX) : Comparator.comparingInt(Point::getY));
        int medianIndex = points.size() / 2;
        Point median = points.get(medianIndex);
        Node left = build(new ArrayList<>(points.subList(0, medianIndex)), axis ^ 1);
        Node right = build(new ArrayList<>(points.subList(medianIndex + 1, points.size())), axis ^ 1);
        return new Node(median, axis, left, right);
    }

    private static int nearest(Node node, Point query, int best) {
        if (node == null) {
            return best;
        }

        int distance = chebyshev(query, node.point);
        int nextBest = Math.min(best, distance);

        int delta = node.axis == 0 ? query.getX() - node.point.getX() : query.getY() - node.point.getY();
        Node nearer = delta <= 0 ? node.left : node.right;
        Node further = delta <= 0 ? node.right : node.left;

        nextBest = nearest(nearer, query, nextBest);
        if (Math.abs(delta) < nextBest) {
            nextBest = nearest(further, query, nextBest);
        }
        return nextBest;
    }

    private static int chebyshev(Point a, Point b) {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    private static final class Node {
        private final Point point;
        private final int axis;
        private final Node left;
        private final Node right;

        private Node(Point point, int axis, Node left, Node right) {
            this.point = point;
            this.axis = axis;
            this.left = left;
            this.right = right;
        }
    }

    private static final class Point {
        private final int x;
        private final int y;

        private Point(int packedPoint) {
            this.x = WorldPointUtil.unpackWorldX(packedPoint);
            this.y = WorldPointUtil.unpackWorldY(packedPoint);
        }

        private int getX() {
            return x;
        }

        private int getY() {
            return y;
        }
    }
}
