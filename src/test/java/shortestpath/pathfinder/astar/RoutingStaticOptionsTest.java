package shortestpath.pathfinder.astar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;
import shortestpath.pathfinder.TestWorldData;

public class RoutingStaticOptionsTest
{
	@Test
	public void parsesNamedAndExplicitOptions()
	{
		assertSame(RoutingStaticBuilder.Options.PLUGIN, RoutingStaticBuilder.Options.parse(null));
		assertSame(RoutingStaticBuilder.Options.PLUGIN, RoutingStaticBuilder.Options.parse("plugin"));
		assertSame(RoutingStaticBuilder.Options.SERVER, RoutingStaticBuilder.Options.parse("SERVER"));
		RoutingStaticBuilder.Options explicit = RoutingStaticBuilder.Options.parse("512, 1024, 8");
		assertEquals(512, explicit.exactWalkMaxSites);
		assertEquals(1024, explicit.targetWalkTileBudget);
		assertEquals(8, explicit.wildernessTableCapacity);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsMalformedOptions()
	{
		RoutingStaticBuilder.Options.parse("512,1024");
	}

	@Test
	public void thePluginBuildsWithThePluginOptions()
	{
		RoutingStatic stat = TestWorldData.routingStatic();
		assertSame(RoutingStaticBuilder.Options.PLUGIN, stat.options());
		assertEquals(ExactWalks.MAX_SITES, stat.options().exactWalkMaxSites);
		assertEquals(TargetWalkDistances.TILE_BUDGET, stat.options().targetWalkTileBudget);
	}
}
