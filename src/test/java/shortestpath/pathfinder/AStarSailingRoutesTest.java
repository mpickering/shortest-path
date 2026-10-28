package shortestpath.pathfinder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import shortestpath.pathfinder.sailing.SailingMooring;
import shortestpath.pathfinder.sailing.SailingMoorings;
import shortestpath.pathfinder.sailing.SailingWaters;
import shortestpath.sailing.SeaArea;
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
	// Open water in the bay south of Port Sarim
	private static final int PORT_SARIM_BAY = point(3045, 3180);
	// The Mudskipper Point dive site, a sea-charting spot
	private static final int MUDSKIPPER_DIVE = point(2979, 3085);
	private static final int PANDEMONIUM_DOCK = point(3069, 2986);
	private static final int PORT_TYRAS_DOCK = point(2143, 3121);
	// A sea-charting sealed crate off Isafdar, in crystal-flecked water
	private static final int ISAFDAR_CRATE = point(2161, 3203);
	// Off the Ruins of Unkah, and past the Elid Delta islands: a raft gets between the islands, a skiff doesn't
	private static final int OFF_UNKAH = point(3143, 2847);
	private static final int PAST_THE_ELID_DELTA = point(3273, 2738);
	private static final SailingFleet ABOARD_IN_THE_BAY = new SailingFleet(List.of(), SailingFleet.BoatLocations.UNKNOWN,
		new SailingFleet.Aboard(PORT_SARIM_BAY, 8, SailingVessel.of(SailingVessel.Kind.RAFT, 2.0), 0, 0));

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
		// Before the landing: where the boat casts off, in docking range of the berth, then a point per move, the last
		// within docking range of the next berth
		int legStart = landing;
		while (legStart > 0 && path.get(legStart - 1).getTraversal() == Traversal.SAILING) legStart--;
		assertTrue("the leg has moves", landing - legStart > 2);
		assertTrue(SailingTransports.from(transport).inDockingRange(path.get(legStart).getPackedPosition()));
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
	public void aBoatAtAnotherSpeedSailsItsOwnLegs()
	{
		// The legs are timed at 2 tiles a tick: a quicker boat's are sailed for it, and the route follows them
		AStarPathfinder timed = search(RAFT_EVERYWHERE, 99, PORT_SARIM_DOCK, GREAT_CONCH_CLUE);
		AStarPathfinder quicker = search(everywhere(SailingVessel.of(SailingVessel.Kind.RAFT, 3.0)), 99,
			PORT_SARIM_DOCK, GREAT_CONCH_CLUE);
		assertTrue(quicker.getResult().getMessage(), quicker.getResult().isReached());
		assertEquals("0 legs sailed", legsSailed(timed));
		assertNotEquals("0 legs sailed", legsSailed(quicker));
		assertTrue(quicker.getResult().getPathCost() + " < " + timed.getResult().getPathCost(),
			quicker.getResult().getPathCost() < timed.getResult().getPathCost());
		// The filled-in leg is the one sailed: it docks at the mooring the transport lands at
		List<PathStep> path = quicker.getPath();
		int landing = transportIndex(path);
		SailingMooring docked = SailingTransports.to(path.get(landing).getTransport());
		assertEquals(Traversal.SAILING, path.get(landing - 1).getTraversal());
		assertTrue(WorldPointUtil.distanceBetween(path.get(landing - 1).getPackedPosition(), docked.berthTile())
			<= SailingMooring.DOCKING_RANGE + 1);
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

	@Test
	public void landToATargetAtSeaBoardsAndSailsThere()
	{
		AStarPathfinder walking = search(SailingFleet.NONE, 99, PORT_SARIM_DOCK, MUDSKIPPER_DIVE);
		assertFalse("walking alone cannot reach the sea", walking.getResult().isReached());
		AStarPathfinder sailing = search(RAFT_EVERYWHERE, 99, PORT_SARIM_DOCK, MUDSKIPPER_DIVE);
		PathfinderResult result = sailing.getResult();
		assertTrue(result.getMessage(), result.isReached());
		assertEquals("the requested target, not the boat's tile", MUDSKIPPER_DIVE, result.getTarget());
		assertEquals(MUDSKIPPER_DIVE, sailing.getSeaTarget());
		List<PathStep> path = sailing.getPath();
		assertEquals(PORT_SARIM_DOCK, path.get(0).getPackedPosition());
		assertEquals(Traversal.SAILING, path.get(path.size() - 1).getTraversal());
		assertNotNull(sailing.debugStats().get("Sea ends"));
	}

	@Test
	public void aboardToATargetAtSeaSailsStraightThere()
	{
		AStarPathfinder search = search(ABOARD_IN_THE_BAY, 99, PORT_SARIM_BAY, MUDSKIPPER_DIVE);
		PathfinderResult result = search.getResult();
		assertTrue(result.getMessage(), result.isReached());
		List<PathStep> path = search.getPath();
		assertEquals(PORT_SARIM_BAY, path.get(0).getPackedPosition());
		// No walking from the boat's tile: the route is the leg alone
		for (PathStep step : path) assertEquals(Traversal.SAILING, step.getTraversal());
		assertEquals(MUDSKIPPER_DIVE, search.getSeaTarget());
	}

	@Test
	public void aboardToLandDocksAndWalks()
	{
		AStarPathfinder search = search(ABOARD_IN_THE_BAY, 99, PORT_SARIM_BAY, WYDINS);
		PathfinderResult result = search.getResult();
		assertTrue(result.getMessage(), result.isReached());
		List<PathStep> path = search.getPath();
		assertEquals(PORT_SARIM_BAY, path.get(0).getPackedPosition());
		assertEquals(WYDINS, path.get(path.size() - 1).getPackedPosition());
		int lastSailing = lastIndexOf(path, Traversal.SAILING);
		assertTrue("docks, then walks", lastSailing > 0 && lastSailing < path.size() - 1);
		assertEquals(Traversal.WALK, path.get(path.size() - 1).getTraversal());
		// The leg's cost is the boat's ticks to docking range plus stepping off; the walk carries on from there
		int ashore = path.get(lastSailing).getPackedPosition();
		assertTrue(shortestpath.pathfinder.sailing.SailingMoorings.all().stream()
			.anyMatch(mooring -> mooring.landTile() == ashore));
	}

	@Test
	public void aMooringWithoutABoatIsNotBoardedForATargetAtSea()
	{
		SailingFleet unknown = new SailingFleet(List.of(SailingVessel.of(SailingVessel.Kind.RAFT, 2.0)),
			SailingFleet.BoatLocations.UNKNOWN, null);
		assertFalse(search(unknown, 99, PORT_SARIM_DOCK, MUDSKIPPER_DIVE).getResult().isReached());
	}

	@Test
	public void theWalksEitherSideOfASailingLegAreRewrittenAndTheLegKept()
	{
		// Walk to Port Sarim's dock, sail, then walk from the berth on the far side; and from aboard, dock and walk
		for (AStarPathfinder search : List.of(search(RAFT_EVERYWHERE, 99, WYDINS, GREAT_CONCH_CLUE),
			search(ABOARD_IN_THE_BAY, 99, PORT_SARIM_BAY, WYDINS),
			search(RAFT_EVERYWHERE, 99, WYDINS, MUDSKIPPER_DIVE)))
		{
			assertTrue(search.getResult().getMessage(), search.getResult().isReached());
			List<PathStep> path = search.getPath();
			assertTrue("every leg canonicalised: " + search.getWalkDiagnostics(), search.getWalkDiagnostics().isEmpty());
			assertFalse("the walks become clicks", search.getClickPoints().isEmpty());
			int sailing = 0;
			for (int i = 1; i < path.size(); i++)
			{
				PathStep from = path.get(i - 1), to = path.get(i);
				if (to.getTraversal() == Traversal.SAILING) sailing++;
				// A walking step is one tile from the last: the rewrite never walks across the sea
				if (to.getTraversal() == Traversal.WALK && from.getTraversal() == Traversal.WALK)
					assertTrue(i + ": " + from.getPackedPosition() + " -> " + to.getPackedPosition(),
						WorldPointUtil.distanceBetween(from.getPackedPosition(), to.getPackedPosition()) <= 1);
			}
			assertTrue("the leg is kept", sailing > 2);
			for (int kept : search.getKeptStepIndices())
				assertEquals(Traversal.WALK, path.get(kept).getTraversal());
		}
	}

	@Test
	public void aTargetInRestrictedWaterIsReachedOnlyWithThePart()
	{
		SailingVessel skiff = SailingVessel.of(SailingVessel.Kind.SKIFF, 2.0);
		AStarPathfinder without = search(everywhere(skiff), 99, PORT_TYRAS_DOCK, ISAFDAR_CRATE);
		assertFalse(without.getResult().isReached());
		assertEquals("known out of reach without sailing a leg", "0 legs sailed", legsSailed(without));
		AStarPathfinder with = search(everywhere(skiff.withAreas(Set.of(SeaArea.CRYSTALLINE))), 99, PORT_TYRAS_DOCK,
			ISAFDAR_CRATE);
		assertTrue(with.getResult().getMessage(), with.getResult().isReached());
		assertEquals(ISAFDAR_CRATE, with.getSeaTarget());
	}

	@Test
	public void aLegThroughRestrictedWaterIsSailedOnlyWithThePart()
	{
		// Straight through Tempor Tantrum with a stormy mast, round it without
		SailingVessel raft = SailingVessel.of(SailingVessel.Kind.RAFT, 2.0);
		AStarPathfinder round = search(everywhere(raft), 99, PANDEMONIUM_DOCK, DOGNOSE_MOORING);
		AStarPathfinder through = search(everywhere(raft.withAreas(Set.of(SeaArea.TEMPOR_TANTRUM))), 99,
			PANDEMONIUM_DOCK, DOGNOSE_MOORING);
		assertTrue(round.getResult().isReached());
		assertTrue(through.getResult().isReached());
		assertTrue(through.getResult().getPathCost() + " < " + round.getResult().getPathCost(),
			through.getResult().getPathCost() < round.getResult().getPathCost());
		assertTrue(sailingTransport(through).getSeaAreas().contains(SeaArea.TEMPOR_TANTRUM));
		// The filled-in leg keeps out of the water the boat can't enter
		CollisionMap map = new CollisionMap(TestWorldData.map());
		SailingWaters waters = SailingWaters.of(map, SailingMoorings.all());
		for (PathStep step : round.getPath())
		{
			if (step.getTraversal() == Traversal.SAILING) assertNull(waters.area(step.getPackedPosition()));
		}
	}

	@Test
	public void waterTheHullCannotGetThroughIsKnownOutOfReachWithoutSailing()
	{
		AStarPathfinder skiff = search(aboard(OFF_UNKAH, SailingVessel.Kind.SKIFF), 99, OFF_UNKAH, PAST_THE_ELID_DELTA);
		assertFalse(skiff.getResult().isReached());
		assertEquals("0 legs sailed", legsSailed(skiff));
		AStarPathfinder raft = search(aboard(OFF_UNKAH, SailingVessel.Kind.RAFT), 99, OFF_UNKAH, PAST_THE_ELID_DELTA);
		assertTrue(raft.getResult().getMessage(), raft.getResult().isReached());
	}

	private static SailingFleet everywhere(SailingVessel vessel)
	{
		return new SailingFleet(List.of(vessel), SailingFleet.BoatLocations.EVERY_MOORING, null);
	}

	// Aboard a boat with every part, as the plugin takes the boat the player is on to be: only its hull limits it
	private static SailingFleet aboard(int tile, SailingVessel.Kind kind)
	{
		return new SailingFleet(List.of(), SailingFleet.BoatLocations.UNKNOWN,
			new SailingFleet.Aboard(tile, -1, SailingVessel.of(kind, 2.0).withAreas(SeaArea.ALL), 0, 0));
	}

	// The legs the search sailed at sea, as its debug stats report them, or "0 legs sailed" with none offered
	private static String legsSailed(AStarPathfinder search)
	{
		String ends = search.debugStats().get("Sea ends");
		return ends == null ? "0 legs sailed" : ends.substring(ends.indexOf(", ") + 2);
	}

	private static Transport sailingTransport(AStarPathfinder search)
	{
		int index = transportIndex(search.getPath());
		assertTrue("the route sails", index >= 0);
		return search.getPath().get(index).getTransport();
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
