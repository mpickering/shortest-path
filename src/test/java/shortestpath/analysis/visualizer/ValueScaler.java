package shortestpath.analysis.visualizer;

import java.util.ArrayList;
import java.util.List;

public class ValueScaler {
    private final ScalingMode scalingMode;
    private final Double explicitMin;
    private final Double explicitMax;

    public ValueScaler(ScalingMode scalingMode, Double explicitMin, Double explicitMax) {
        this.scalingMode = scalingMode;
        this.explicitMin = explicitMin;
        this.explicitMax = explicitMax;
    }

    public Bounds computeBounds(HeuristicSample[] samples) {
        List<Double> values = new ArrayList<>();
        for (HeuristicSample sample : samples) {
            if (sample != null && sample.isDefined()) {
                values.add(transform(sample.getValue()));
            }
        }

        double min = explicitMin != null ? transform(explicitMin) : Double.POSITIVE_INFINITY;
        double max = explicitMax != null ? transform(explicitMax) : Double.NEGATIVE_INFINITY;
        if (explicitMin == null || explicitMax == null) {
            for (double value : values) {
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }
        if (!Double.isFinite(min)) {
            min = 0.0d;
        }
        if (!Double.isFinite(max)) {
            max = min;
        }
        return new Bounds(min, max);
    }

    public double scale(double value, Bounds bounds) {
        double transformed = transform(value);
        if (bounds.max <= bounds.min) {
            return 0.0d;
        }
        double clamped = Math.max(bounds.min, Math.min(bounds.max, transformed));
        return (clamped - bounds.min) / (bounds.max - bounds.min);
    }

    private double transform(double value) {
        if (scalingMode == ScalingMode.LOG) {
            return Math.log1p(Math.max(0.0d, value));
        }
        return value;
    }

    public static class Bounds {
        final double min;
        final double max;

        Bounds(double min, double max) {
            this.min = min;
            this.max = max;
        }
    }
}
