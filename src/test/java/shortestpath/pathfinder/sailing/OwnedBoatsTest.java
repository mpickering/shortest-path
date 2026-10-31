package shortestpath.pathfinder.sailing;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import org.mockito.junit.MockitoJUnitRunner;
import shortestpath.TestShortestPathConfig;
import shortestpath.pathfinder.BankVisitState;
import shortestpath.pathfinder.PathfinderConfig;
import shortestpath.pathfinder.TestPathfinderConfig;
import shortestpath.sailing.SeaArea;
import shortestpath.sailing.SailingService;
import shortestpath.items.ItemStateService;
import shortestpath.settings.TeleportationItem;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportType;

/** The boats the player owns, read from their slots, as sailing transports from the moorings they are at. */
@RunWith(MockitoJUnitRunner.Silent.class)
public class OwnedBoatsTest
{
	// The game's dock ids (moorings.tsv's Dock column)
	private static final int CATHERBY = 6, PORT_ROBERTS = 21;

	@Mock
	Client client;
	private final TestShortestPathConfig config = new TestShortestPathConfig();
	private PathfinderConfig pathfinderConfig;

	@Before
	public void before()
	{
		config.set("calculationCutoff", 30);
		config.set("currencyThreshold", 10000000);
		config.set("useTeleportationItems", TeleportationItem.NONE);
		when(client.getDBTableRows(DBTableID.Quest.ID)).thenReturn(List.of());
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		pathfinderConfig = new TestPathfinderConfig(client, config);
	}

	@Test
	public void manualStartPreservesRecoveryFees()
	{
		SailingVessel vessel = SailingVessel.of(SailingVessel.Kind.RAFT, 1);
		SailingMooring mooring = mooring("Catherby");
		SailingFleet fleet = new SailingFleet(List.of(vessel), SailingFleet.BoatLocations.EVERY_MOORING,
			new SailingFleet.Aboard(0, 0, vessel, 0, 0), (dock, boat) -> 4125, 1000);
		PreparedSailingCapability onFoot = new PreparedSailingCapability(fleet, List.of(mooring)).onFoot();

		assertTrue(onFoot.fleet().aboard() == null);
		assertEquals(4125, onFoot.fleet().recoveryCost(mooring, vessel));
		assertEquals(1000, onFoot.fleet().carriedCoins());
	}

	@Test
	public void noBoatsNoSailing()
	{
		pathfinderConfig.refresh();
		assertTrue(legs(null).isEmpty());
	}

	@Test
	public void aMooredBoatSailsFromItsMooringOnly()
	{
		// A skiff in slot 1, moored at Catherby, with a camphor hull (2.5 tiles a tick)
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_OWNED)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_TYPE)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_PORT)).thenReturn(CATHERBY);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_HULL)).thenReturn(4);
		pathfinderConfig.refresh();

		List<Transport> legs = legs(null);
		assertFalse(legs.isEmpty());
		SailingMooring catherby = mooring("Catherby");
		for (Transport leg : legs)
		{
			assertEquals(TransportType.SAILING_SKIFF, leg.getType());
			assertEquals(catherby, SailingTransports.from(leg));
		}
	}

	@Test
	public void eachBoatEntersOnlyTheWaterItsOwnPartsAllow()
	{
		// Two sloops: slot 1 at Port Roberts with no parts, slot 2 at Catherby with an adamant keel
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_OWNED)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_TYPE)).thenReturn(2);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_PORT)).thenReturn(PORT_ROBERTS);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_2_OWNED)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_2_TYPE)).thenReturn(2);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_2_PORT)).thenReturn(CATHERBY);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_2_KEEL)).thenReturn(4);
		pathfinderConfig.refresh();

		SailingFleet fleet = pathfinderConfig.prepareSailingCapability().fleet();
		assertEquals(2, fleet.vessels().size());
		SailingMooring roberts = mooring("Port Roberts"), catherby = mooring("Catherby");
		for (SailingVessel vessel : fleet.vessels())
		{
			boolean keeled = vessel.canEnter(SeaArea.CRYSTALLINE);
			assertEquals(keeled, fleet.locations().moored(catherby, vessel));
			assertEquals(!keeled, fleet.locations().moored(roberts, vessel));
			assertFalse(vessel.canEnter(SeaArea.KELP));
		}
	}

	@Test
	public void theBoatAboardIsNotAtItsDock()
	{
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_OWNED)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_TYPE)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_PORT)).thenReturn(CATHERBY);
		when(client.getVarbitValue(VarbitID.SAILING_BOARDED_BOAT)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.SAILING_LAST_PERSONAL_BOAT_BOARDED)).thenReturn(1);
		pathfinderConfig.refresh();

		assertTrue(pathfinderConfig.prepareSailingCapability().fleet().vessels().isEmpty());
	}

	@Test
	public void sailOnBoatsOffLeavesTheBoatsMoored()
	{
		config.set("useSailingMoves", false);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_OWNED)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_TYPE)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_PORT)).thenReturn(CATHERBY);
		pathfinderConfig.refresh();

		assertTrue(legs(null).isEmpty());
	}

	@Test
	public void recoveryUsesAnInclusiveGpLimitAndOnlyShipwrightPorts()
	{
		recoverableSkiff(4125);
		config.set("boatRecoveryThreshold", 4124);
		pathfinderConfig.refresh();
		assertFalse(canBoardAt("Port Roberts"));
		assertTrue(canBoardAt("Catherby"));

		config.set("boatRecoveryThreshold", 4125);
		pathfinderConfig.refresh();
		assertTrue(canBoardAt("Port Roberts"));
		assertFalse(canBoardAt("Dognose Island"));
		assertTrue(legs(null).stream().filter(t -> SailingTransports.from(t).name().equals("Port Roberts"))
			.anyMatch(t -> t.getDisplayInfo().startsWith("Recover boat (4125 gp)")));

		config.set("boatRecoveryThreshold", 0);
		pathfinderConfig.refresh();
		assertFalse(canBoardAt("Port Roberts"));
	}

	@Test
	public void recoveryRequiresEnoughCoinsAndAKnownFee()
	{
		recoverableSkiff(4124);
		config.set("boatRecoveryThreshold", 4125);
		pathfinderConfig.refresh();
		assertFalse(canBoardAt("Port Roberts"));

		when(client.getDBTableField(DBTableID.SailingBoat.Row.SAILING_BOAT_SKIFF,
			DBTableID.SailingBoat.COL_RETRIEVAL_COST, 0)).thenReturn(null);
		coins(100000);
		pathfinderConfig.refresh();
		assertFalse(canBoardAt("Port Roberts"));
		assertTrue(canBoardAt("Catherby"));
	}

	@Test
	public void bankCoinsCanPayRecoveryWithBankTeleportsDisabled()
	{
		recoverableSkiff(0);
		config.set("boatRecoveryThreshold", 4125);
		ItemContainer bank = mock(ItemContainer.class);
		when(bank.getItems()).thenReturn(new Item[]{new Item(ItemID.COINS, 4125)});
		ItemStateService items = ItemStateService.forTesting();
		items.onContainerChanged(InventoryID.BANK, bank);
		pathfinderConfig.setItemStateService(items);
		pathfinderConfig.refresh();
		assertTrue(canBoardAt("Port Roberts"));
		assertTrue("bank routing enabled for recovery", pathfinderConfig.prepareAStarRoutingAccount(true).bankPathEnabled());
		assertTrue(legs(null).stream().noneMatch(t -> SailingTransports.from(t).name().equals("Port Roberts")));
		Transport recovery = legs(BankVisitState.BANKED).stream()
			.filter(t -> SailingTransports.from(t).name().equals("Port Roberts")).findFirst().orElseThrow();
		assertEquals(Long.valueOf(4125), pathfinderConfig.getEligibility().bankPickupPlan(recovery).items.get(ItemID.COINS));

		coins(1000);
		pathfinderConfig.refresh();
		recovery = legs(BankVisitState.BANKED).stream()
			.filter(t -> SailingTransports.from(t).name().equals("Port Roberts")).findFirst().orElseThrow();
		assertEquals(Long.valueOf(3125), pathfinderConfig.getEligibility().bankPickupPlan(recovery).items.get(ItemID.COINS));

		coins(4125);
		pathfinderConfig.refresh();
		assertTrue(legs(null).stream().anyMatch(t -> SailingTransports.from(t).name().equals("Port Roberts")));

		coins(Integer.MAX_VALUE);
		pathfinderConfig.refresh();
		assertTrue(legs(BankVisitState.BANKED).stream().anyMatch(t -> SailingTransports.from(t).name().equals("Port Roberts")));
	}

	@Test
	public void aBoatAlreadyAtTheDockIsPreferredToPayingRecovery()
	{
		recoverableSkiff(4125);
		config.set("boatRecoveryThreshold", 4125);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_2_OWNED)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_2_TYPE)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_2_PORT)).thenReturn(PORT_ROBERTS);
		pathfinderConfig.refresh();
		List<Transport> roberts = new ArrayList<>();
		for (Transport t : legs(null)) if (SailingTransports.from(t).name().equals("Port Roberts")) roberts.add(t);
		assertFalse(roberts.isEmpty());
		assertTrue(roberts.stream().noneMatch(t -> t.getDisplayInfo().startsWith("Recover boat")));
	}

	private boolean canBoardAt(String name)
	{
		PreparedSailingCapability capability = pathfinderConfig.prepareSailingCapability();
		return capability.fleet().vessels().stream().anyMatch(v -> capability.canBoard(mooring(name), v));
	}

	private void recoverableSkiff(int coins)
	{
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_OWNED)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_TYPE)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.SAILING_BOAT_1_PORT)).thenReturn(CATHERBY);
		when(client.getDBTableField(DBTableID.SailingBoat.Row.SAILING_BOAT_SKIFF,
			DBTableID.SailingBoat.COL_RETRIEVAL_COST, 0)).thenReturn(new Object[]{3750});
		when(client.getDBTableField(DBTableID.SailingBoat.Row.SAILING_BOAT_SKIFF,
			DBTableID.SailingBoat.COL_HULL_OPTION, 0)).thenReturn(new Object[]{100});
		when(client.getDBTableField(100, DBTableID.SailingBoatHull.COL_FACILITY_STATS, 0))
			.thenReturn(new Object[]{101});
		when(client.getDBTableField(101, DBTableID.SailingBoatFacilityStats.COL_BOAT_ADDITIONAL_RECOVERY_COST_PERCENTAGE, 0))
			.thenReturn(new Object[]{10});
		pathfinderConfig.setSailingService(SailingService.forTesting(client));
		coins(coins);
	}

	private void coins(int amount)
	{
		ItemContainer inventory = mock(ItemContainer.class);
		when(inventory.getItems()).thenReturn(new Item[]{new Item(ItemID.COINS, amount)});
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
	}

	// The sailing transports the account is offered
	private List<Transport> legs(BankVisitState banked)
	{
		List<Transport> legs = new ArrayList<>();
		shortestpath.PrimitiveIntHashMap<Transport[]> packed = pathfinderConfig.getTransportsPacked(banked == null ? BankVisitState.CARRIED : banked);
		for (int origin : packed.keys())
		{
			for (Transport transport : packed.get(origin)) if (transport.getType().isSailing()) legs.add(transport);
		}
		return legs;
	}

	private static SailingMooring mooring(String name)
	{
		return SailingMoorings.all().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
	}
}
