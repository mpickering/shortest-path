package shortestpath.pathfinder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.DBTableID;
import org.junit.BeforeClass;
import org.junit.Test;
import shortestpath.TestShortestPathConfig;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.astar.RoutingStatic;
import shortestpath.pathfinder.sailing.SailingFleet;
import shortestpath.pathfinder.sailing.SailingTransports;
import shortestpath.pathfinder.sailing.SailingVessel;
import shortestpath.settings.TeleportationItem;
import shortestpath.transport.Transport;

/**
 * Routes that sail between moorings on the real map, through {@link AStarPathfinder}: sailing is a transport from one
 * mooring's land tile to another's, taken when it is quicker and only with a boat moored where it boards.
 */
public class AStarSailingRoutesTest
{
	private static final int PORT_SARIM_DOCK = point(3050, 3192);
	private static final int DOGNOSE_MOORING = point(3060, 2638);
	// An anagram clue inland on the Great Conch, an island only a boat reaches
	private static final int GREAT_CONCH_CLUE = point(3143, 2423);
	private static final int WITCHAVEN_DOCK = point(2746, 3304);
	// Port Sarim's food shop, a short walk from the dock
	private static final int WYDINS = point(3014, 3206);
	private static final int SUMMER_SHORE = point(3173, 2368);

	private static final SailingFleet RAFT_EVERYWHERE = new SailingFleet(
		List.of(SailingVessel.of(SailingVessel.Kind.RAFT, 2.0)), SailingFleet.BoatLocations.EVERY_MOORING, null);

	private static RoutingStatic routingStatic;

	@BeforeClass
	public static void buildRoutingStatic()
	{
		routingStatic = TestWorldData.routingStatic();
	}

	@Test
	public void aWalkCheaperThanSailingIsKept()
	{
		AStarPathfinder walking = search(SailingFleet.NONE, 99, PORT_SARIM_DOCK, WYDINS);
		AStarPathfinder sailing = search(RAFT_EVERYWHERE, 99, PORT_SARIM_DOCK, WYDINS);
		assertTrue(walking.getResult().isReached());
		assertTrue(sailing.getResult().isReached());
		assertEquals(walking.getResult().getPathCost(), sailing.getResult().getPathCost());
		assertEquals(positions(walking.getPath()), positions(sailing.getPath()));
		assertEquals(-1, lastIndexOf(sailing.getPath(), Traversal.SAILING));
	}

	@Test
	public void sailingCheaperThanWalkingIsTakenAndItsLegFilledIn()
	{
		AStarPathfinder walking = search(SailingFleet.NONE, 99, PORT_SARIM_DOCK, WITCHAVEN_DOCK);
		AStarPathfinder sailing = search(RAFT_EVERYWHERE, 99, PORT_SARIM_DOCK, WITCHAVEN_DOCK);
		assertTrue(walking.getResult().isReached());
		assertTrue(sailing.getResult().isReached());
		assertTrue(sailing.getResult().getPathCost() + " < " + walking.getResult().getPathCost(),
			sailing.getResult().getPathCost() < walking.getResult().getPathCost());
		List<PathStep> path = sailing.getPath();
		int landing = transportIndex(path);
		assertTrue("the route sails", landing > 0);
		Transport transport = path.get(landing).getTransport();
		assertEquals(SailingVessel.Kind.RAFT, SailingTransports.kind(transport.getType()));
		// Before the landing: the berth, then a point per move, the last within docking range of the next berth
		int legStart = landing;
		while (legStart > 0 && path.get(legStart - 1).getTraversal() == Traversal.SAILING) legStart--;
		assertTrue("the leg has moves", landing - legStart > 2);
		assertEquals(SailingTransports.from(transport).berthTile(), path.get(legStart).getPackedPosition());
		assertTrue(SailingTransports.to(transport).inDockingRange(path.get(landing - 1).getPackedPosition()));
		assertEquals(transport.getOrigin(), path.get(legStart - 1).getPackedPosition());
	}

	@Test
	public void anIslandOnlyABoatReachesIsReached()
	{
		assertFalse(search(SailingFleet.NONE, 99, PORT_SARIM_DOCK, GREAT_CONCH_CLUE).getResult().isReached());
		AStarPathfinder search = search(RAFT_EVERYWHERE, 99, PORT_SARIM_DOCK, GREAT_CONCH_CLUE);
		PathfinderResult result = search.getResult();
		assertTrue(result.getMessage(), result.isReached());
		List<PathStep> path = search.getPath();
		assertEquals(GREAT_CONCH_CLUE, path.get(path.size() - 1).getPackedPosition());
		assertEquals(Traversal.WALK, path.get(path.size() - 1).getTraversal());
		assertTrue("and walks after docking", transportIndex(path) < path.size() - 2);
	}

	@Test
	public void aMooringAboveTheAccountsLevelIsNotDocked()
	{
		// Dognose Island's mooring needs 40 Sailing
		assertFalse(search(RAFT_EVERYWHERE, 30, PORT_SARIM_DOCK, DOGNOSE_MOORING).getResult().isReached());
		AStarPathfinder high = search(RAFT_EVERYWHERE, 40, PORT_SARIM_DOCK, DOGNOSE_MOORING);
		assertTrue(high.getResult().getMessage(), high.getResult().isReached());
	}

	@Test
	public void aMooringWhoseQuestIsNotStartedIsNotDocked()
	{
		// The Summer Shore needs Troubled Tortugans started
		assertFalse(search(RAFT_EVERYWHERE, 99, QuestState.NOT_STARTED, PORT_SARIM_DOCK, SUMMER_SHORE)
			.getResult().isReached());
		assertTrue(search(RAFT_EVERYWHERE, 99, QuestState.IN_PROGRESS, PORT_SARIM_DOCK, SUMMER_SHORE)
			.getResult().isReached());
	}

	@Test
	public void noBoatAtAnyMooringRoutesAsWithoutBoats()
	{
		SailingFleet unknown = new SailingFleet(List.of(SailingVessel.of(SailingVessel.Kind.RAFT, 2.0)),
			SailingFleet.BoatLocations.UNKNOWN, null);
		AStarPathfinder search = search(unknown, 99, PORT_SARIM_DOCK, WITCHAVEN_DOCK);
		AStarPathfinder none = search(SailingFleet.NONE, 99, PORT_SARIM_DOCK, WITCHAVEN_DOCK);
		assertEquals(none.getResult().getPathCost(), search.getResult().getPathCost());
		assertEquals(positions(none.getPath()), positions(search.getPath()));
	}

	@Test
	public void onlyTheKindOfBoatTheAccountHasIsSailed()
	{
		SailingFleet sloop = new SailingFleet(List.of(SailingVessel.of(SailingVessel.Kind.SLOOP, 2.0)),
			SailingFleet.BoatLocations.EVERY_MOORING, null);
		AStarPathfinder search = search(sloop, 99, PORT_SARIM_DOCK, WITCHAVEN_DOCK);
		int landing = transportIndex(search.getPath());
		assertTrue(landing > 0);
		assertEquals(SailingVessel.Kind.SLOOP,
			SailingTransports.kind(search.getPath().get(landing).getTransport().getType()));
		assertNotNull(search.debugStats().get("Sailing legs"));
	}

	private static List<Integer> positions(List<PathStep> path)
	{
		return path.stream().map(PathStep::getPackedPosition).collect(java.util.stream.Collectors.toList());
	}

	private static int transportIndex(List<PathStep> path)
	{
		for (int i = 0; i < path.size(); i++)
		{
			Transport transport = path.get(i).getTransport();
			if (transport != null && transport.getType().isSailing()) return i;
		}
		return -1;
	}

	private static int lastIndexOf(List<PathStep> path, Traversal traversal)
	{
		for (int i = path.size() - 1; i >= 0; i--) if (path.get(i).getTraversal() == traversal) return i;
		return -1;
	}

	private static AStarPathfinder search(SailingFleet fleet, int levels, int start, int target)
	{
		return search(fleet, levels, QuestState.FINISHED, start, target);
	}

	private static AStarPathfinder search(SailingFleet fleet, int levels, QuestState quests, int start, int target)
	{
		PathfinderConfig config = config(fleet, levels, quests);
		AStarPathfinder search = new AStarPathfinder(config, () -> routingStatic, null, start,
			Targets.at(Set.of(target)), null, 1);
		search.run();
		assertNotEquals(PathTerminationReason.CUTOFF_REACHED, search.getResult().getTerminationReason());
		return search;
	}

	private static PathfinderConfig config(SailingFleet fleet, int levels, QuestState quests)
	{
		Client client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		when(client.getDBTableRows(DBTableID.Quest.ID)).thenReturn(List.of());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(levels);
		when(client.getRealSkillLevel(any(Skill.class))).thenReturn(levels);
		TestShortestPathConfig settings = new TestShortestPathConfig();
		settings.setUseTeleportationItemsValue(TeleportationItem.NONE);
		settings.setCalculationCutoffValue(100);
		PathfinderConfig config = new TestPathfinderConfig(client, settings, quests, true, true)
		{
			@Override
			protected SailingFleet sailingFleet()
			{
				return fleet;
			}
		};
		config.refresh();
		return config;
	}

	private static int point(int x, int y)
	{
		return WorldPointUtil.packWorldPoint(x, y, 0);
	}
}
