package shortestpath.pathfinder.astar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import org.junit.BeforeClass;
import org.junit.Test;
import shortestpath.PrimitiveIntHashMap;
import shortestpath.TestShortestPathConfig;
import shortestpath.WorldPointUtil;
import shortestpath.items.ItemStateService;
import shortestpath.pathfinder.AStarPathfinder;
import shortestpath.pathfinder.BankVisitState;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.PathStep;
import shortestpath.pathfinder.Pathfinder;
import shortestpath.pathfinder.PathfinderConfig;
import shortestpath.pathfinder.TestPathfinderConfig;
import shortestpath.pathfinder.TestWorldData;
import shortestpath.settings.TeleportationItem;
import shortestpath.transport.Transport;

/**
 * Routes starting in the Wilderness, where the A* heuristic is capped by {@link WildernessHeuristic}:
 * the cost matches legacy, the route takes the intended chain of transports, and along it the capped
 * estimate never exceeds the cost left. Real transport data and Wilderness coordinates throughout.
 *
 * <p>Wilderness levels by y above ground: 1 at 3525, 20 up to 3679, 21-30 from 3680, 31+ from 3760.
 * Wilderness tablets can be read up to level 20 and land at levels 21-30 (Dareeyak) or 31+ (Annakarl,
 * Revenant cave), so from a start above level 20 the route must first walk down to level 20.
 */
public class WildernessHeuristicTest
{
	private static final int GLORY = ItemID.AMULET_OF_GLORY_6, VARROCK_TABLET = 8007, DAREEYAK_TABLET = 12777,
		ANNAKARL_TABLET = 12775, REVENANT_TABLET = 21802, DUELING_RING = 2552;
	private static final int VARROCK = point(3212, 3424), EDGEVILLE = point(3094, 3491), DRAYNOR = point(3093, 3244),
		ARDOUGNE = point(2561, 3311), FEROX = point(3150, 3635), MAGE_BANK = point(2539, 4712);

	private static RoutingStatic routingStatic;

	@BeforeClass
	public static void build()
	{
		routingStatic = TestWorldData.routingStatic();
	}

	// Start above level 30, target outside the Wilderness.

	@Test
	public void deepStartWalksDownToLevelTwentyBeforeReadingATablet()
	{
		// Level 48, no obelisks or levers: the walk to level 20 is most of the route.
		Route route = route(3300, 3900, VARROCK, new Account().items(VARROCK_TABLET));
		route.castFrom("Varrock tablet", TeleportCapability.OVER_0, TeleportCapability.ALL);
		route.startEstimateIsExact();
	}

	@Test
	public void deepStartWalksToLevelThirtyForGloryThenTeleportsAgain()
	{
		// Two casts: glory as soon as level 30 allows it, then the tablet outside the Wilderness.
		Route route = route(3300, 3900, VARROCK, new Account().items(GLORY, VARROCK_TABLET));
		route.castFrom("Amulet of glory: Al Kharid", TeleportCapability.OVER_20, TeleportCapability.ALL);
		route.castFrom("Varrock tablet", TeleportCapability.ALL, TeleportCapability.ALL);
		route.startEstimateIsExact();
	}

	@Test
	public void deepStartWithoutTeleportsWalksOut()
	{
		Route route = route(3300, 3900, VARROCK, new Account());
		assertFalse(route.casts());
	}

	@Test
	public void obeliskToLowerLevelThenTablet()
	{
		// Level 50 obelisk to level 13, where the tablet works.
		Route with = route(3307, 3920, VARROCK, new Account().obelisks().items(VARROCK_TABLET));
		with.uses("1: Level 13 Wilderness");
		with.castFrom("Varrock tablet", TeleportCapability.OVER_0, TeleportCapability.ALL);
		// Without obelisks the same account walks down instead, and its escapes follow its own transports.
		Route without = route(3307, 3920, VARROCK, new Account().items(VARROCK_TABLET));
		without.castFrom("Varrock tablet", TeleportCapability.OVER_0, TeleportCapability.ALL);
		assertTrue(without.cost > with.cost + 200);
		without.startEstimateIsExact();
	}

	// Start at levels 21-30.

	@Test
	public void levelThirtyTeleportIsCastAtTheStart()
	{
		// Level 27 obelisk: glory works here, so it is cast without moving.
		Route route = route(3051, 3736, DRAYNOR, new Account().items(GLORY));
		route.castFrom("Amulet of glory: Draynor Village", TeleportCapability.OVER_20, TeleportCapability.ALL);
		assertEquals(1, route.index("Amulet of glory: Draynor Village"));
	}

	@Test
	public void tabletIntoLevelTwentyOneToThirty()
	{
		// Level 23 to level 24 at the far west: walk to level 20, read Dareeyak, land at level 23.
		Route route = route(3200, 3700, point(2955, 3720), new Account().items(DAREEYAK_TABLET));
		route.castFrom("Dareeyak tablet", TeleportCapability.OVER_0, TeleportCapability.OVER_20);
		Route walking = route(3200, 3700, point(2955, 3720), new Account());
		assertTrue(walking.cost > route.cost + 100);
	}

	@Test
	public void tabletIntoDeepWilderness()
	{
		// Level 25 to the Demonic Ruins at level 46: walk to level 20, read Annakarl, land deeper.
		Route route = route(3100, 3720, point(3290, 3900), new Account().items(ANNAKARL_TABLET));
		route.castFrom("Annakarl tablet", TeleportCapability.OVER_0, TeleportCapability.OVER_30);
		Route walking = route(3100, 3720, point(3290, 3900), new Account());
		assertTrue(walking.cost > route.cost + 100);
	}

	@Test
	public void teleportDeeperThenLeaveByLever()
	{
		// Level 10 to the Mage Bank, only reached by the Mage Arena lever at level 54: the revenant
		// cave tablet lands at level 40 on the way, so the route climbs through every band.
		Route route = route(3100, 3600, MAGE_BANK, new Account().levers().items(REVENANT_TABLET));
		route.castFrom("Revenant cave teleport", TeleportCapability.OVER_0, TeleportCapability.OVER_30);
		route.usesType("TELEPORTATION_LEVER");
		Route walking = route(3100, 3600, MAGE_BANK, new Account().levers());
		assertEquals(-1, walking.index("Revenant cave teleport"));
		assertTrue(walking.cost > route.cost);
	}

	@Test
	public void leverIntoDeepWildernessThenLeverOut()
	{
		// Level 10 to Ardougne by local transports alone: out of the Wilderness to the Edgeville lever,
		// into the Deserted Keep at level 53, and out again by its lever.
		Route route = route(3100, 3600, ARDOUGNE, new Account().levers().items(REVENANT_TABLET));
		route.usesType("TELEPORTATION_LEVER");
		assertFalse(route.casts());
		assertTrue(route.visits(TeleportCapability.OVER_30));
	}

	@Test
	public void bankedTeleportAfterBankingAtFerox()
	{
		// Glory in the bank only: walk to Ferox Enclave's bank, then cast it from there.
		Route route = route(3100, 3720, VARROCK, new Account().bank(GLORY));
		route.castFrom("Amulet of glory: Edgeville", TeleportCapability.ALL, TeleportCapability.ALL);
		assertTrue(route.banked());
	}

	// Routes that stay in the Wilderness.

	@Test
	public void nearbyTargetIsWalkedToWithTeleportsAvailable()
	{
		Route route = route(3144, 3772, point(3169, 3758),
			new Account().obelisks().items(GLORY, VARROCK_TABLET, ANNAKARL_TABLET, REVENANT_TABLET));
		assertFalse(route.casts());
	}

	@Test
	public void eitherOfTwoTargetsInAndOutOfTheWilderness()
	{
		// One target a walk away in the Wilderness, the other only by tablet. (Legacy reports 18 for the
		// pair but 20 for the near target alone, which is 20 tiles away; A* is compared to the latter.)
		Route route = route(3300, 3900, new int[] {VARROCK, point(3310, 3880)}, new Account().items(VARROCK_TABLET),
			false);
		assertEquals(point(3310, 3880), route.last());
		assertFalse(route.casts());
		assertEquals(route(3300, 3900, point(3310, 3880), new Account().items(VARROCK_TABLET)).cost, route.cost);
	}

	// Avoid wilderness.

	@Test
	public void avoidWildernessFromInsideLeavesByTeleportOrOnFoot()
	{
		Route glory = route(3100, 3700, EDGEVILLE, new Account().avoid().items(GLORY));
		glory.castFrom("Amulet of glory: Edgeville", TeleportCapability.OVER_20, TeleportCapability.ALL);
		assertFalse(glory.lifted);
		Route walking = route(3100, 3700, EDGEVILLE, new Account().avoid());
		assertFalse(walking.casts());
		assertFalse(walking.lifted);
	}

	@Test
	public void avoidWildernessShortcutThroughItIsTakenOnlyWhenNeeded()
	{
		// Ferox Enclave is surrounded by the Wilderness: with a ring of dueling the gated search
		// teleports in; without, it finds nothing and the gate is lifted.
		Route ring = route(3087, 3500, FEROX, new Account().avoid().items(DUELING_RING));
		ring.castFrom("Ring of dueling: Ferox Enclave", TeleportCapability.ALL, TeleportCapability.ALL);
		assertFalse(ring.lifted);
		Route lifted = route(3087, 3500, FEROX, new Account().avoid(), false);
		assertTrue(lifted.lifted);
		assertEquals(route(3087, 3500, FEROX, new Account()).cost, lifted.cost);
	}

	// The escape table.

	@Test
	public void escapesAreLowerBoundsOnTheAccountsMoves()
	{
		for (Account account : List.of(new Account(), new Account().obelisks().levers()))
		{
			PathfinderConfig config = config(account);
			PreparedRoutingAccount prepared = config.prepareAStarRoutingAccount(true);
			WildernessEscape.Table table = routingStatic.wildernessEscape().table(prepared);
			assertSame(table, routingStatic.wildernessEscape().table(prepared));
			int exact = 0, checked = 0;
			for (int[] tile : new int[][] {{3300, 3900}, {3307, 3920}, {3051, 3736}, {3200, 3700}, {3100, 3600},
				{3144, 3772}, {2970, 3913}, {3028, 3842}, {3153, 3923}, {3376, 3890}})
			{
				int start = point(tile[0], tile[1]), node = routingStatic.searchIndex(start);
				assertTrue(node >= 0);
				for (TeleportCapability band : TeleportCapability.values())
				{
					int escape = table.escapeFromNode(band, node), walk = walkToBand(config.getMap(), prepared, start, band);
					assertTrue(band + " from " + tile[0] + "," + tile[1] + ": " + escape + " > " + walk, escape <= walk);
					exact += escape == walk ? 1 : 0;
					checked++;
				}
			}
			// The escapes model the forward search's moves, so they are mostly exact.
			assertTrue(exact + " of " + checked, exact * 10 >= checked * 9);
		}
	}

	/** The cheapest walk (and local transports) from {@code start} to a tile allowing {@code band}. */
	private static int walkToBand(CollisionMap collision, PreparedRoutingAccount account, int start,
		TeleportCapability band)
	{
		PrimitiveIntHashMap<Integer> best = new PrimitiveIntHashMap<>(1 << 16);
		PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
		queue.add(new long[] {0, start});
		best.put(start, 0);
		while (!queue.isEmpty())
		{
			long[] entry = queue.poll();
			int cost = (int) entry[0], tile = (int) entry[1];
			if (best.get(tile) != cost) continue;
			if (TeleportCapability.at(tile).allows(band)) return cost;
			List<int[]> moves = new ArrayList<>();
			for (int next : collision.ordinaryWalkingNeighbors(tile)) moves.add(new int[] {next, 1});
			for (BankVisitState banked : BankVisitState.values())
				for (int i = 0; i < account.localCount(banked); i++)
					if (account.localOrigin(banked, i) == tile)
						moves.add(new int[] {account.localDestination(banked, i), account.localCost(banked, i)});
			for (int[] move : moves)
			{
				Integer known = best.get(move[0]);
				if (known == null || cost + move[1] < known)
				{
					best.put(move[0], cost + move[1]);
					queue.add(new long[] {cost + move[1], move[0]});
				}
			}
		}
		return CostArithmetic.INF;
	}

	private static Route route(int x, int y, int target, Account account)
	{
		return route(x, y, new int[] {target}, account, true);
	}

	private static Route route(int x, int y, int target, Account account, boolean legacyParity)
	{
		return route(x, y, new int[] {target}, account, legacyParity);
	}

	private static Route route(int x, int y, int[] targets, Account account)
	{
		return route(x, y, targets, account, true);
	}

	/**
	 * Routes with legacy and A*, checks they agree (unless the A* search lifts Avoid wilderness,
	 * which legacy does not), and checks the capped estimate along the route.
	 */
	private static Route route(int x, int y, int[] targets, Account account, boolean legacyParity)
	{
		int start = point(x, y);
		Set<Integer> targetSet = new LinkedHashSet<>();
		for (int target : targets) targetSet.add(target);
		PathfinderConfig config = config(account);
		AStarPathfinder aStar = new AStarPathfinder(config, routingStatic, null, start, targetSet, null, 1);
		aStar.run();
		assertTrue("A* reached", aStar.getResult().isReached());
		if (legacyParity)
		{
			Pathfinder legacy = new Pathfinder(config, start, targetSet);
			legacy.run();
			assertTrue("legacy reached", legacy.getResult().isReached());
			assertEquals("cost against legacy", legacy.getResult().getPathCost(), aStar.getResult().getPathCost());
		}
		Route route = new Route(aStar.getPath(), aStar.getResult().getPathCost(), aStar.isWildernessGateLifted());
		route.checkEstimates(config, start, targets, account.avoid && !route.lifted);
		return route;
	}

	private static int point(int x, int y)
	{
		return WorldPointUtil.packWorldPoint(x, y, 0);
	}

	private static final class Route
	{
		final List<PathStep> steps;
		final int cost;
		final boolean lifted;
		int startEstimate;

		Route(List<PathStep> steps, int cost, boolean lifted)
		{
			this.steps = steps;
			this.cost = cost;
			this.lifted = lifted;
		}

		/**
		 * Along the A* search's own route (with its exact step costs), the capped estimate of every
		 * step is at most the cost left: the heuristic is admissible where it matters.
		 */
		void checkEstimates(PathfinderConfig config, int start, int[] targets, boolean avoid)
		{
			PreparedRoutingAccount account = config.prepareAStarRoutingAccount(true);
			PreparedTarget target = PreparedTarget.prepare(new SiteGraph(routingStatic, account, avoid), config.getMap(),
				targets);
			AStarForwardSearch.Result result = target.search(start, () -> false, 1, SearchRestrictions.of(avoid, null),
				AStarForwardSearch.NO_PROGRESS);
			assertTrue(result.reached());
			assertEquals(cost, result.cost());
			PreparedHeuristic heuristic = target.heuristic();
			WildernessHeuristic wilderness = WildernessHeuristic.prepare(routingStatic, account,
				(destination, banked) -> heuristic.estimate(config.getMap(), destination, banked));
			AStarRoute route = result.route();
			for (int i = 0; i < route.size(); i++)
			{
				PathStep step = route.steps().get(i);
				int estimate = estimate(config, heuristic, wilderness, step.getPackedPosition(), step.getBankVisitState());
				int left = result.cost() - route.cost(i);
				assertTrue("step " + i + " estimate " + estimate + " > " + left, estimate <= left);
			}
			startEstimate = estimate(config, heuristic, wilderness, start, BankVisitState.CARRIED);
		}

		private static int estimate(PathfinderConfig config, PreparedHeuristic heuristic, WildernessHeuristic wilderness,
			int tile, BankVisitState banked)
		{
			int node = routingStatic.searchIndex(tile);
			return Math.min(heuristic.estimate(config.getMap(), tile, banked),
				wilderness.cap(node, node >= 0 ? -1 : routingStatic.siteIndex(tile), banked));
		}

		/** The search started deep in the Wilderness and still knew the exact cost from the start. */
		void startEstimateIsExact()
		{
			assertEquals(cost, startEstimate);
		}

		boolean casts()
		{
			for (PathStep step : steps)
			{
				Transport transport = step.getTransport();
				if (transport != null && transport.getOrigin() == WorldPointUtil.UNDEFINED) return true;
			}
			return false;
		}

		boolean banked()
		{
			return steps.get(steps.size() - 1).getBankVisitState() == BankVisitState.BANKED;
		}

		boolean visits(TeleportCapability band)
		{
			for (PathStep step : steps)
				if (TeleportCapability.at(step.getPackedPosition()) == band) return true;
			return false;
		}

		int last()
		{
			return steps.get(steps.size() - 1).getPackedPosition();
		}

		void uses(String displayInfo)
		{
			assertTrue(displayInfo + " in " + this, index(displayInfo) >= 0);
		}

		void usesType(String type)
		{
			for (PathStep step : steps)
				if (step.getTransport() != null && step.getTransport().getType().name().equals(type)) return;
			throw new AssertionError(type + " in " + this);
		}

		/** The teleport is cast from a tile of band {@code from} and lands in band {@code to}. */
		void castFrom(String displayInfo, TeleportCapability from, TeleportCapability to)
		{
			int index = index(displayInfo);
			assertTrue(displayInfo + " in " + this, index > 0);
			Transport transport = steps.get(index).getTransport();
			assertNotNull(transport);
			int castAt = steps.get(index - 1).getPackedPosition();
			assertEquals(displayInfo + " cast from", from, TeleportCapability.at(castAt));
			assertTrue(displayInfo + " castable there", from.canCast(transport));
			assertEquals(displayInfo + " lands in", to, TeleportCapability.at(steps.get(index).getPackedPosition()));
		}

		/** The step taking the transport with this display text, or -1. */
		int index(String displayInfo)
		{
			for (int i = 0; i < steps.size(); i++)
			{
				Transport transport = steps.get(i).getTransport();
				if (transport != null && displayInfo.equals(transport.getDisplayInfo())) return i;
			}
			return -1;
		}

		@Override
		public String toString()
		{
			List<String> transports = new ArrayList<>();
			for (PathStep step : steps)
				if (step.getTransport() != null)
					transports.add(step.getTransport().getType() + " " + step.getTransport().getDisplayInfo());
			return transports + " cost " + cost;
		}
	}

	/** The account a scenario routes for: its settings and items. */
	private static final class Account
	{
		final boolean avoid, obelisks, levers;
		final int[] items, bank;

		Account()
		{
			this(false, false, false, new int[0], new int[0]);
		}

		private Account(boolean avoid, boolean obelisks, boolean levers, int[] items, int[] bank)
		{
			this.avoid = avoid;
			this.obelisks = obelisks;
			this.levers = levers;
			this.items = items;
			this.bank = bank;
		}

		Account avoid()
		{
			return new Account(true, obelisks, levers, items, bank);
		}

		Account obelisks()
		{
			return new Account(avoid, true, levers, items, bank);
		}

		Account levers()
		{
			return new Account(avoid, obelisks, true, items, bank);
		}

		Account items(int... ids)
		{
			return new Account(avoid, obelisks, levers, ids, bank);
		}

		Account bank(int... ids)
		{
			return new Account(avoid, obelisks, levers, items, ids);
		}
	}

	/**
	 * Every quest and skill; only the given items, obelisks and levers, and no spells, minigame or home
	 * teleports, so each scenario's teleports are exactly the ones it names.
	 */
	private static PathfinderConfig config(Account account)
	{
		Client client = mock(Client.class);
		TestShortestPathConfig settings = new TestShortestPathConfig();
		ItemContainer inventory = mock(ItemContainer.class), empty = mock(ItemContainer.class),
			bank = mock(ItemContainer.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		when(client.getDBTableRows(DBTableID.Quest.ID)).thenReturn(List.of());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		doReturn(items(account.items)).when(inventory).getItems();
		doReturn(new Item[0]).when(empty).getItems();
		doReturn(items(account.bank)).when(bank).getItems();
		doReturn(inventory).when(client).getItemContainer(InventoryID.INV);
		doReturn(empty).when(client).getItemContainer(InventoryID.WORN);
		settings.set("calculationCutoff", 500);
		settings.set("currencyThreshold", 10000000);
		settings.set("useTeleportationItems", account.bank.length != 0 ? TeleportationItem.INVENTORY_AND_BANK
			: TeleportationItem.INVENTORY);
		settings.set("useTeleportationSpells", false);
		settings.set("useTeleportationSpellsHome", false);
		settings.set("useTeleportationMinigames", false);
		settings.set("useWildernessObelisks", account.obelisks);
		settings.set("useTeleportationLevers", account.levers);
		settings.set("avoidWilderness", account.avoid);
		PathfinderConfig config = new TestPathfinderConfig(client, settings, QuestState.FINISHED, true, true);
		ItemStateService itemState = ItemStateService.forTesting();
		config.setItemStateService(itemState);
		itemState.noteBankContainer(bank);
		config.refresh();
		return config;
	}

	private static Item[] items(int[] ids)
	{
		return Arrays.stream(ids).mapToObj(id -> new Item(id, 1)).toArray(Item[]::new);
	}
}
