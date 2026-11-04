package shortestpath.pathfinder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.junit.Test;
import shortestpath.pathfinder.astar.PreparedRoutingAccount;
import shortestpath.pathfinder.astar.RoutingStaticTestFixture;
import shortestpath.presentation.RouteChoices;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportType;

public class AStarPathfinderAlternativesTest
{
	private static final int START = RoutingStaticTestFixture.A;
	private static final int NEAR = RoutingStaticTestFixture.C;
	private static final int TARGET = RoutingStaticTestFixture.D;

	@Test
	public void selectingARouteShowsItsPathAndResult() throws Exception
	{
		AStarPathfinder search = search(global(TARGET, 2), global(NEAR, 5), local(NEAR, TARGET, 3));
		List<PathStep> first = search.getPath();
		PathfinderResult firstResult = search.getResult();
		search.loadAlternatives();

		assertTrue(search.isAlternativesLoaded());
		assertEquals(1, search.getAlternatives().size());
		assertSame("loading alternatives leaves the route showing", first, search.getPath());

		search.selectRoute(1);
		AlternativeRoute alternative = search.getAlternatives().get(0);
		assertEquals(alternative.getPathSteps(), search.getPath());
		assertEquals(8, search.getResult().getPathCost());
		assertEquals(6, alternative.getExtraCost());
		assertEquals(TARGET, search.getPath().get(search.getPath().size() - 1).getPackedPosition());
		assertEquals(alternative.getClickPoints(), search.getClickPoints());

		search.selectRoute(0);
		assertSame(first, search.getPath());
		assertSame(firstResult, search.getResult());
	}

	@Test
	public void theSidebarListsEveryRouteWithItsJourneys() throws Exception
	{
		AStarPathfinder search = search(global(TARGET, 2), global(NEAR, 5), local(NEAR, TARGET, 3));
		assertTrue("nothing to choose yet", RouteChoices.choices(search).isEmpty());
		search.loadAlternatives();
		search.selectRoute(1);

		List<RouteChoices.Choice> choices = RouteChoices.choices(search);
		assertEquals(2, choices.size());
		assertEquals("Fastest", choices.get(0).getTitle());
		assertEquals("2 ticks", choices.get(0).getTicks());
		assertEquals("teleportation minigame", choices.get(0).getJourneys());
		assertFalse(choices.get(0).isSelected());
		assertEquals("+6 ticks", choices.get(1).getTitle());
		assertEquals("8 ticks", choices.get(1).getTicks());
		assertTrue(choices.get(1).isSelected());
	}

	@Test
	public void aRouteOutOfRangeIsIgnored() throws Exception
	{
		AStarPathfinder search = search(global(TARGET, 2));
		search.loadAlternatives();
		search.selectRoute(1);
		assertEquals(0, search.getSelectedRoute());
	}

	@Test
	public void theHotkeyMessageSaysWhatIsShowing() throws Exception
	{
		AStarPathfinder search = search(global(TARGET, 2), global(NEAR, 5), local(NEAR, TARGET, 3));
		assertEquals("Still looking for alternative routes.", RouteChoices.describeSelected(search));
		search.loadAlternatives();
		assertEquals("Route 1 of 2: fastest, 2 ticks.", RouteChoices.describeSelected(search));
		search.selectRoute(1);
		assertEquals("Route 2 of 2: 8 ticks, +6.", RouteChoices.describeSelected(search));

		AStarPathfinder alone = search(global(TARGET, 2));
		alone.loadAlternatives();
		assertEquals("No alternative routes for this path.", RouteChoices.describeSelected(alone));
		assertFalse(alone.getAlternatives().iterator().hasNext());
	}

	private static AStarPathfinder search(Transport... transports) throws Exception
	{
		PathfinderConfig config = mock(PathfinderConfig.class);
		CollisionMap collision = new CollisionMap(null)
		{
			@Override
			public int[] ordinaryWalkingNeighbors(int packedPoint)
			{
				return new int[0];
			}

			@Override
			public boolean isBlocked(int x, int y, int z)
			{
				return false;
			}
		};
		when(config.getMap()).thenReturn(collision);
		when(config.isTransportDestination(TARGET)).thenReturn(true);
		when(config.getCalculationCutoffMillis()).thenReturn(10_000L);
		when(config.prepareAStarRoutingAccount(true, false)).thenReturn(PreparedRoutingAccount.compile(
			TransportAvailabilityFixture.of(transports), TransportAvailabilityFixture.of(), false, Set.of(), 0, true,
			ignored -> 0));
		AStarPathfinder search = new AStarPathfinder(config, RoutingStaticTestFixture.create(), null, START,
			Set.of(TARGET), null, 1);
		search.run();
		assertTrue(search.getResult().getMessage(), search.getResult().isReached());
		return search;
	}

	private static Transport global(int destination, int duration)
	{
		return new Transport.TransportBuilder().destination(destination).type(TransportType.TELEPORTATION_MINIGAME)
			.duration(duration).maxWildernessLevel(30).build();
	}

	private static Transport local(int origin, int destination, int duration)
	{
		return new Transport.TransportBuilder().origin(origin).destination(destination)
			.type(TransportType.TRANSPORT).duration(duration).build();
	}
}
