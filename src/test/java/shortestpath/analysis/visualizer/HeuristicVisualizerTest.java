package shortestpath.analysis.visualizer;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.PathStep;
import shortestpath.pathfinder.SplitFlagMap;
import shortestpath.pathfinder.VisitedTiles;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportLoader;
import shortestpath.transport.TransportType;

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

    @Test
    public void abstractGraphCompressesHubFamilies() {
        int originA = WorldPointUtil.packWorldPoint(100, 100, 0);
        int originB = WorldPointUtil.packWorldPoint(110, 100, 0);
        int originC = WorldPointUtil.packWorldPoint(120, 100, 0);
        int exitA = WorldPointUtil.packWorldPoint(200, 100, 0);
        int exitB = WorldPointUtil.packWorldPoint(210, 100, 0);
        int exitC = WorldPointUtil.packWorldPoint(220, 100, 0);

        Map<Integer, Integer> tileToComponent = Map.of(
            originA, 1,
            originB, 1,
            originC, 1,
            exitA, 2,
            exitB, 2,
            exitC, 2);
        ComponentLabelIndex componentLabelIndex = ComponentLabelIndex.fromPackedPointToComponent(tileToComponent);

        Map<Integer, Set<Transport>> transports = new HashMap<>();
        TransportLoader.addTransportsFromContents(transports, ""
            + "# Origin\tDestination\tDuration\tDisplay info\n"
            + "100 100 0\t\t5\tORIGIN_A\n"
            + "110 100 0\t\t5\tORIGIN_B\n"
            + "120 100 0\t\t5\tORIGIN_C\n"
            + "\t200 100 0\t5\tEXIT_A\n"
            + "\t210 100 0\t5\tEXIT_B\n"
            + "\t220 100 0\t5\tEXIT_C\n",
            TransportType.FAIRY_RING,
            0);

        AbstractTransportGraph graph = AbstractGraphBuilder.build(repoCollisionMap(), componentLabelIndex, transports);
        AbstractTransportGraph.HubCompressionStats compression =
            graph.getBuildStats().getHubCompressionByType().get(TransportType.FAIRY_RING);

        Assert.assertNotNull(compression);
        Assert.assertEquals(9, compression.getNaiveEdgeCount());
        Assert.assertEquals(6, compression.getCompressedEdgeCount());
        Assert.assertEquals(6, graph.getBuildStats().getStoredEdgeCount());
    }

    @Test
    public void abstractGraphReverseSearchUsesImplicitWalkingBetweenAttachments() {
        int source = WorldPointUtil.packWorldPoint(300, 300, 0);
        int firstExit = WorldPointUtil.packWorldPoint(310, 300, 0);
        int secondEntry = WorldPointUtil.packWorldPoint(313, 300, 0);
        int goal = WorldPointUtil.packWorldPoint(320, 300, 0);

        Map<Integer, Integer> tileToComponent = Map.of(
            source, 1,
            firstExit, 2,
            secondEntry, 2,
            goal, 3);
        ComponentLabelIndex componentLabelIndex = ComponentLabelIndex.fromPackedPointToComponent(tileToComponent);

        Map<Integer, Set<Transport>> transports = new HashMap<>();
        TransportLoader.addTransportsFromContents(transports, ""
            + "# Origin\tDestination\tDuration\n"
            + "300 300 0\t310 300 0\t5\n"
            + "313 300 0\t320 300 0\t7\n",
            TransportType.TRANSPORT,
            0);

        AbstractTransportGraph graph = AbstractGraphBuilder.build(repoCollisionMap(), componentLabelIndex, transports);
        AbstractGraphHeuristicField heuristic = new AbstractGraphHeuristicField(componentLabelIndex, goal, graph);

        HeuristicSample sample = heuristic.sample(source, TileStateQuery.TileState.WALKABLE);
        Assert.assertTrue(sample.isDefined());
        Assert.assertEquals(15.0d, sample.getValue(), 0.0d);
        Assert.assertEquals(7, heuristic.getReverseSearchResult().distanceToNode(nodeIdForPackedPoint(graph, secondEntry)));
    }

    @Test
    public void abstractGraphIgnoresGlobalTeleports() {
        int destination = WorldPointUtil.packWorldPoint(400, 400, 0);
        ComponentLabelIndex componentLabelIndex = ComponentLabelIndex.fromPackedPointToComponent(Map.of(destination, 7));

        Map<Integer, Set<Transport>> transports = new HashMap<>();
        TransportLoader.addTransportsFromContents(transports, ""
            + "# Destination\tDuration\tDisplay info\n"
            + "400 400 0\t4\tTEST_GLOBAL\n",
            TransportType.TELEPORTATION_SPELL,
            0);

        AbstractTransportGraph graph = AbstractGraphBuilder.build(repoCollisionMap(), componentLabelIndex, transports);

        Assert.assertEquals(0, graph.getBuildStats().getGlobalSourceNodeCount());
        Assert.assertEquals(0, graph.getBuildStats().getStoredEdgeCountsByKind()
            .getOrDefault(AbstractTransportGraph.EdgeKind.GLOBAL_TELEPORT, 0).intValue());
        Assert.assertEquals(0, graph.getBuildStats().getAttachmentNodeCount());
        Assert.assertEquals(0, graph.getBuildStats().getStoredEdgeCount());
    }

    private int samplePixel(BufferedImage image, TileRegion region, int packedPoint) {
        int x = WorldPointUtil.unpackWorldX(packedPoint) - region.getMinX();
        int y = region.getMaxYExclusive() - WorldPointUtil.unpackWorldY(packedPoint) - 1;
        return image.getRGB(x, y);
    }

    private int nodeIdForPackedPoint(AbstractTransportGraph graph, int packedPoint) {
        for (int nodeId = 0; nodeId < graph.getNodeCount(); nodeId++) {
            AbstractTransportGraph.AbstractNode node = graph.getNode(nodeId);
            if (node.isAttachment() && node.getPackedPoint() == packedPoint) {
                return nodeId;
            }
        }
        throw new AssertionError("Packed point not found in graph: " + packedPoint);
    }

    private CollisionMap repoCollisionMap() {
        return new CollisionMap(SplitFlagMap.fromResources());
    }
}
