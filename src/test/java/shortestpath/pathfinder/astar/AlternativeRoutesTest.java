package shortestpath.pathfinder.astar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.PathStep;
import shortestpath.pathfinder.TransportAvailabilityFixture;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportType;

public class AlternativeRoutesTest
{
	private static final int START = tile(1000);
	private static final int FAST = tile(2000);
	private static final int SLOWER = tile(3000);
	private static final int SLOWEST = tile(4000);
	private static final int GATE = tile(5000);
	private static final int NEAR_START = WorldPointUtil.packWorldPoint(1003, 1000, 0);
	private static final int TARGET = tile(6000);
	private static final int[] TILES = {START, NEAR_START, FAST, SLOWER, SLOWEST, GATE, TARGET};

	@Test
	public void penaltyPrefersAnUnusedJourneyWithinTheBudget()
	{
		// First route 100 ticks: budget 25, limit 125.
		List<AlternativeRoutes.Alternative> found = alternatives(1,
			teleport(FAST), local(FAST, TARGET, 98),
			teleport(SLOWER), local(SLOWER, TARGET, 113));

		assertEquals(1, found.size());
		assertEquals(115, found.get(0).duration());
		// In these graphs the last hop is a journey landing on the target, shared by every route.
		assertEquals(1, found.get(0).reused());
		assertEquals(List.of(START, SLOWER, TARGET), tiles(found.get(0)));
	}

	@Test
	public void routesOverTheBudgetAreNotOffered()
	{
		assertTrue(alternatives(1, teleport(FAST), local(FAST, TARGET, 98),
			teleport(SLOWER), local(SLOWER, TARGET, 124)).isEmpty());
	}

	@Test
	public void anOverBudgetRouteWithoutReuseDoesNotHideAGoodRouteWithIt()
	{
		// All sensible routes share the landing at GATE. Avoiding it takes 130 ticks and reuses only
		// the target landing, so it would outscore the other teleport's route through GATE (110
		// ticks, reusing both), but it is over the limit of 125 and the search drops it.
		List<AlternativeRoutes.Alternative> found = alternatives(1,
			teleport(FAST), local(FAST, GATE, 48),
			teleport(SLOWER), local(SLOWER, GATE, 58),
			local(GATE, TARGET, 50),
			teleport(SLOWEST), local(SLOWEST, TARGET, 128));

		assertEquals(1, found.size());
		assertEquals(110, found.get(0).duration());
		assertEquals(2, found.get(0).reused());
		assertEquals(List.of(START, SLOWER, GATE, TARGET), tiles(found.get(0)));
	}

	@Test
	public void laterRoutesAvoidEveryChosenRoute()
	{
		List<AlternativeRoutes.Alternative> found = alternatives(3,
			teleport(FAST), local(FAST, TARGET, 98),
			teleport(SLOWER), local(SLOWER, TARGET, 108),
			teleport(SLOWEST), local(SLOWEST, TARGET, 118));

		assertEquals(List.of(110, 120), found.stream().map(AlternativeRoutes.Alternative::duration)
			.collect(Collectors.toList()));
	}

	@Test
	public void aDoorIsNotAJourney()
	{
		assertFalse(ReusePenalty.isJourney(local(START, NEAR_START, 1)));
		assertTrue(ReusePenalty.isJourney(local(START, FAST, 1)));
		assertTrue(ReusePenalty.isJourney(teleport(FAST)));
	}

	@Test
	public void aRouteWithoutJourneysHasNoAlternatives()
	{
		assertTrue(alternatives(3, local(START, NEAR_START, 1), local(NEAR_START, TARGET, 2)).isEmpty());
	}

	@Test
	public void theTeleportsSpellAndTabletAreOneJourney()
	{
		// The tablet costs more but lands on the same tile, so it is reuse, not an alternative.
		assertTrue(alternatives(3, teleport(FAST), teleport(FAST, 4), local(FAST, TARGET, 98)).isEmpty());
	}

	private static List<AlternativeRoutes.Alternative> alternatives(int max, Transport... transports)
	{
		PreparedRoutingAccount account = PreparedRoutingAccount.compile(TransportAvailabilityFixture.of(transports),
			TransportAvailabilityFixture.of(), false, Set.of(), 0, true, ignored -> 0);
		TargetOverlay overlay = new TargetOverlay(new SiteGraph(routingStatic(), account), emptyCollision(), TARGET);
		PreparedHeuristic heuristic = PreparedHeuristic.prepare(overlay, ReverseLabels.compute(overlay));
		PreparedTarget target = new PreparedTarget(overlay, heuristic, 0, 0);
		AStarForwardSearch.Result first = AStarForwardSearch.search(overlay, heuristic, START);
		assertTrue(first.reached());
		return AlternativeRoutes.find(target, START, first.route(), max, SearchRestrictions.none(),
			(tile, banked) -> overlay.isTarget(tile), () -> false);
	}

	private static List<Integer> tiles(AlternativeRoutes.Alternative alternative)
	{
		return alternative.route().steps().stream().map(PathStep::getPackedPosition).collect(Collectors.toList());
	}

	private static int tile(int x)
	{
		return WorldPointUtil.packWorldPoint(x, 1000, 0);
	}

	private static Transport local(int origin, int destination, int cost)
	{
		return new Transport.TransportBuilder().origin(origin).destination(destination)
			.type(TransportType.TRANSPORT).duration(cost).build();
	}

	private static Transport teleport(int destination)
	{
		return teleport(destination, 2);
	}

	private static Transport teleport(int destination, int cost)
	{
		return new Transport.TransportBuilder().destination(destination).type(TransportType.TELEPORTATION_MINIGAME)
			.duration(cost).maxWildernessLevel(30).build();
	}

	/** One site per tile, no walking: every move is a transport. */
	private static RoutingStatic routingStatic()
	{
		int[] sites = Arrays.stream(TILES).map(t -> t ^ Integer.MIN_VALUE).sorted().map(t -> t ^ Integer.MIN_VALUE).toArray();
		int n = sites.length;
		int[] offsets = new int[n + 1], ids = new int[n], members = new int[n];
		for (int i = 0; i <= n; i++) offsets[i] = i;
		for (int i = 0; i < n; i++) members[i] = i;
		try
		{
			return RoutingStatic.takeOwnership(new int[0], new TileComponents(0, 1), new byte[0], new char[0], new char[0], sites, offsets,
				ids, 1, new int[] {0, n}, members, new int[0], new int[0], new int[0], new int[0], n, n, 0, 0, 0,
				new int[n + 1], new int[0], new int[0]);
		}
		catch (java.io.IOException e)
		{
			throw new AssertionError(e);
		}
	}

	private static CollisionMap emptyCollision()
	{
		return new CollisionMap(null)
		{
			@Override public int[] ordinaryWalkingNeighbors(int packedPoint)
			{ return new int[0];
			}
			@Override public boolean isBlocked(int x, int y, int z)
			{ return false;
			}
		};
	}
}
