package shortestpath.analysis.visualizer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import shortestpath.WorldPointUtil;

public class PngTileRenderer {
    private final Palette palette;
    private ComponentLabelIndex componentLabelIndex;

    public PngTileRenderer(Palette palette) {
        this.palette = palette;
    }

    public void setComponentLabelIndex(ComponentLabelIndex componentLabelIndex) {
        this.componentLabelIndex = componentLabelIndex;
    }

    public BufferedImage render(TileRegion region, TileStateQuery query, HeuristicField heuristic, SearchOverlay overlay,
        RenderMode renderMode, ScalingMode scalingMode, Double clipMin, Double clipMax) {
        BufferedImage image = new BufferedImage(region.getWidth(), region.getHeight(), BufferedImage.TYPE_INT_ARGB);
        ValueScaler scaler = new ValueScaler(scalingMode, clipMin, clipMax);
        HeuristicSample[] samples = collectSamples(region, query, heuristic);
        ValueScaler.Bounds bounds = scaler.computeBounds(samples);
        double maxPositiveSlack = renderMode == RenderMode.CONSISTENCY_SLACK
            ? maxPositiveSlack(region, query, samples)
            : 0.0d;

        for (int x = region.getMinX(); x < region.getMaxXExclusive(); x++) {
            for (int y = region.getMinY(); y < region.getMaxYExclusive(); y++) {
                int packedPoint = WorldPointUtil.packWorldPoint(x, y, region.getPlane());
                TileStateQuery.TileState tileState = query.getTileState(x, y, region.getPlane());
                HeuristicSample sample = sampleAt(samples, region, x, y);
                int rgb = colorForBaseTile(tileState, sample, renderMode, scaler, bounds, maxPositiveSlack, x, y, region, query, samples);
                rgb = applySearchOverlay(rgb, packedPoint, overlay, renderMode);
                rgb = applyMarkers(rgb, packedPoint, query);
                image.setRGB(x - region.getMinX(), region.getMaxYExclusive() - y - 1, rgb);
            }
        }

        return image;
    }

    public void renderToFile(Path output, TileRegion region, TileStateQuery query, HeuristicField heuristic, SearchOverlay overlay,
        RenderMode renderMode, ScalingMode scalingMode, Double clipMin, Double clipMax) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ImageIO.write(render(region, query, heuristic, overlay, renderMode, scalingMode, clipMin, clipMax), "png", output.toFile());
    }

    private HeuristicSample[] collectSamples(TileRegion region, TileStateQuery query, HeuristicField heuristic) {
        HeuristicSample[] samples = new HeuristicSample[region.getWidth() * region.getHeight()];
        for (int x = region.getMinX(); x < region.getMaxXExclusive(); x++) {
            for (int y = region.getMinY(); y < region.getMaxYExclusive(); y++) {
                int packed = WorldPointUtil.packWorldPoint(x, y, region.getPlane());
                samples[sampleIndex(region, x, y)] = heuristic.sample(packed, query.getTileState(x, y, region.getPlane()));
            }
        }
        return samples;
    }

    private int colorForBaseTile(TileStateQuery.TileState tileState, HeuristicSample sample, RenderMode renderMode,
        ValueScaler scaler, ValueScaler.Bounds bounds, double maxPositiveSlack, int x, int y, TileRegion region,
        TileStateQuery query, HeuristicSample[] samples) {
        if (tileState == TileStateQuery.TileState.MISSING) {
            return palette.missingRgb();
        }
        if (renderMode == RenderMode.BASE_MAP) {
            return tileState == TileStateQuery.TileState.BLOCKED ? palette.blockedRgb() : palette.walkableRgb();
        }
        if (renderMode == RenderMode.COMPONENTS) {
            int packedPoint = WorldPointUtil.packWorldPoint(x, y, region.getPlane());
            if (componentLabelIndex != null && componentLabelIndex.isAnalysisWall(packedPoint)) {
                return palette.analysisWallRgb();
            }
            if (tileState == TileStateQuery.TileState.BLOCKED) {
                return palette.blockedRgb();
            }
            Integer componentId = componentLabelIndex != null
                ? componentLabelIndex.getComponentId(packedPoint)
                : null;
            return componentId != null ? palette.componentRgb(componentId) : palette.undefinedRgb();
        }
        if (renderMode == RenderMode.CONSISTENCY_SLACK) {
            if (tileState == TileStateQuery.TileState.BLOCKED) {
                return palette.blockedRgb();
            }
            double slack = consistencySlackAt(x, y, region, query, samples);
            if (slack <= 0.0d || maxPositiveSlack <= 0.0d) {
                return palette.slackRgb(0.0d);
            }
            return palette.slackRgb(slack / maxPositiveSlack);
        }
        if (tileState == TileStateQuery.TileState.BLOCKED) {
            return palette.blockedRgb();
        }
        if (!sample.isDefined()) {
            return palette.undefinedRgb();
        }

        double scaled = scaler.scale(sample.getValue(), bounds);
        if (renderMode == RenderMode.HEURISTIC_BANDED) {
            double buckets = 8.0d;
            scaled = Math.floor(scaled * buckets) / Math.max(1.0d, buckets - 1.0d);
        }
        return palette.baseValueRgb(scaled);
    }

    private int applySearchOverlay(int currentRgb, int packedPoint, SearchOverlay overlay, RenderMode renderMode) {
        if (overlay == null || renderMode != RenderMode.VISITED_OVERLAY) {
            return currentRgb;
        }
        if (overlay.isVisitedWithBank(packedPoint)) {
            currentRgb = palette.visitedWithBankRgb();
        } else if (overlay.isVisited(packedPoint)) {
            currentRgb = palette.visitedRgb();
        } else if (overlay.isFrontier(packedPoint)) {
            currentRgb = palette.frontierRgb();
        }

        if (overlay.isOnPath(packedPoint)) {
            currentRgb = palette.pathRgb();
        }
        return currentRgb;
    }

    private int applyMarkers(int currentRgb, int packedPoint, TileStateQuery query) {
        if (query.isTeleportEntry(packedPoint)) {
            currentRgb = palette.teleportEntryRgb();
        }
        if (query.isTeleportExit(packedPoint)) {
            currentRgb = palette.teleportExitRgb();
        }
        if (query.isBank(packedPoint)) {
            currentRgb = palette.bankRgb();
        }
        if (query.isStart(packedPoint)) {
            currentRgb = palette.startRgb();
        }
        if (query.isGoal(packedPoint)) {
            currentRgb = palette.goalRgb();
        }
        return currentRgb;
    }

    private double maxPositiveSlack(TileRegion region, TileStateQuery query, HeuristicSample[] samples) {
        double max = 0.0d;
        for (int x = region.getMinX(); x < region.getMaxXExclusive(); x++) {
            for (int y = region.getMinY(); y < region.getMaxYExclusive(); y++) {
                max = Math.max(max, consistencySlackAt(x, y, region, query, samples));
            }
        }
        return max;
    }

    private double consistencySlackAt(int x, int y, TileRegion region, TileStateQuery query, HeuristicSample[] samples) {
        TileStateQuery.TileState tileState = query.getTileState(x, y, region.getPlane());
        if (tileState != TileStateQuery.TileState.WALKABLE) {
            return 0.0d;
        }

        HeuristicSample source = sampleAt(samples, region, x, y);
        if (!source.isDefined()) {
            return 0.0d;
        }

        double maxSlack = 0.0d;
        int[][] deltas = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] delta : deltas) {
            int nx = x + delta[0];
            int ny = y + delta[1];
            if (!region.contains(nx, ny, region.getPlane())) {
                continue;
            }
            TileStateQuery.TileState neighborState = query.getTileState(nx, ny, region.getPlane());
            if (neighborState != TileStateQuery.TileState.WALKABLE) {
                continue;
            }
            HeuristicSample neighbor = sampleAt(samples, region, nx, ny);
            if (!neighbor.isDefined()) {
                continue;
            }
            maxSlack = Math.max(maxSlack, source.getValue() - 1.0d - neighbor.getValue());
        }
        return maxSlack;
    }

    private HeuristicSample sampleAt(HeuristicSample[] samples, TileRegion region, int x, int y) {
        return samples[sampleIndex(region, x, y)];
    }

    private int sampleIndex(TileRegion region, int x, int y) {
        return (x - region.getMinX()) * region.getHeight() + (y - region.getMinY());
    }
}
