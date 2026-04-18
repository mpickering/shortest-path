package shortestpath.analysis.visualizer;

public class HeuristicSample {
    private static final HeuristicSample UNDEFINED = new HeuristicSample(Double.NaN, false);

    private final double value;
    private final boolean defined;

    private HeuristicSample(double value, boolean defined) {
        this.value = value;
        this.defined = defined;
    }

    public static HeuristicSample defined(double value) {
        return new HeuristicSample(value, true);
    }

    public static HeuristicSample undefined() {
        return UNDEFINED;
    }

    public double getValue() {
        return value;
    }

    public boolean isDefined() {
        return defined;
    }
}
