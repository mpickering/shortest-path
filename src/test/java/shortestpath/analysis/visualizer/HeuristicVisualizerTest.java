package shortestpath.analysis.visualizer;

import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.PathStep;
import shortestpath.pathfinder.VisitedTiles;

public class HeuristicVisualizerTest {
    @Test
    public void tileRegionUsesInclusiveExclusiveBounds() {
        TileRegion region = new TileRegion(10, 20, 15, 24, 0);

        Assert.assertEquals(5, region.getWidth());
        Assert.assertEquals(4, region.getHeight());
        Assert.assertTrue(region.contains(10, 20, 0));
        Assert.assertFalse(region.contains(15, 20, 0));
        Assert.assertFalse(region.contains(10, 24, 0));
    }

    @Test
    public void baseMapRendersExpectedSyntheticColours() {
        SyntheticDemoData.DemoContext demo = SyntheticDemoData.create();
        PngTileRenderer renderer = new PngTileRenderer(new Palette());

        BufferedImage image = renderer.render(demo.region, demo.query, new ZeroHeuristicField(), SearchOverlay.NONE,
            RenderMode.BASE_MAP, ScalingMode.LINEAR, null, null);

        Assert.assertEquals(demo.region.getWidth(), image.getWidth());
        Assert.assertEquals(demo.region.getHeight(), image.getHeight());

        int walkablePacked = WorldPointUtil.packWorldPoint(1, 1, 0);
        int blockedPacked = WorldPointUtil.packWorldPoint(10, 4, 0);
        Assert.assertNotEquals(samplePixel(image, demo.region, walkablePacked), samplePixel(image, demo.region, blockedPacked));
    }

    @Test
    public void visitedOverlayShowsVisitedAndPathDifferently() {
        TileRegion region = new TileRegion(0, 0, 4, 4, 0);
        SyntheticDemoData.DemoContext demo = SyntheticDemoData.create();
        SearchOverlay overlay = new SearchOverlay() {
            @Override
            public boolean isVisited(int packedPoint) {
                return packedPoint == WorldPointUtil.packWorldPoint(1, 1, 0);
            }

            @Override
            public boolean isOnPath(int packedPoint) {
                return packedPoint == WorldPointUtil.packWorldPoint(2, 1, 0);
            }
        };

        PngTileRenderer renderer = new PngTileRenderer(new Palette());
        BufferedImage image = renderer.render(region, demo.query, new ZeroHeuristicField(), overlay,
            RenderMode.VISITED_OVERLAY, ScalingMode.LINEAR, null, null);

        int visitedRgb = samplePixel(image, region, WorldPointUtil.packWorldPoint(1, 1, 0));
        int pathRgb = samplePixel(image, region, WorldPointUtil.packWorldPoint(2, 1, 0));
        Assert.assertNotEquals(visitedRgb, pathRgb);
    }

    @Test
    public void visitedTilesOverlayReflectsVisitedTilesSemantics() {
        VisitedTiles visitedTiles = new VisitedTiles(new shortestpath.pathfinder.CollisionMap(shortestpath.pathfinder.SplitFlagMap.fromResources()));
        int tile = WorldPointUtil.packWorldPoint(3200, 3200, 0);
        visitedTiles.set(tile, true);
        VisitedTilesOverlay overlay = new VisitedTilesOverlay(visitedTiles, new TileRegion(3199, 3199, 3202, 3202, 0),
            List.of(new PathStep(tile, true)));

        Assert.assertTrue(overlay.isVisited(tile));
        Assert.assertTrue(overlay.isVisitedWithBank(tile));
        Assert.assertTrue(overlay.isOnPath(tile));
    }

    @Test
    public void zeroHeuristicHasNoPositiveConsistencySlack() {
        SyntheticDemoData.DemoContext demo = SyntheticDemoData.create();
        PngTileRenderer renderer = new PngTileRenderer(new Palette());

        BufferedImage image = renderer.render(demo.region, demo.query, new ZeroHeuristicField(), SearchOverlay.NONE,
            RenderMode.CONSISTENCY_SLACK, ScalingMode.LINEAR, null, null);

        int sampleA = samplePixel(image, demo.region, WorldPointUtil.packWorldPoint(1, 1, 0));
        int sampleB = samplePixel(image, demo.region, WorldPointUtil.packWorldPoint(2, 1, 0));
        Assert.assertEquals(sampleA, sampleB);
    }

    private int samplePixel(BufferedImage image, TileRegion region, int packedPoint) {
        int x = WorldPointUtil.unpackWorldX(packedPoint) - region.getMinX();
        int y = region.getMaxYExclusive() - WorldPointUtil.unpackWorldY(packedPoint) - 1;
        return image.getRGB(x, y);
    }
}
