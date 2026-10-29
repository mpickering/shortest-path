package shortestpath.pathfinder.sailing;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.DBTableID;
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
import org.mockito.junit.MockitoJUnitRunner;
import shortestpath.TestShortestPathConfig;
import shortestpath.pathfinder.BankVisitState;
import shortestpath.pathfinder.PathfinderConfig;
import shortestpath.pathfinder.TestPathfinderConfig;
import shortestpath.sailing.SeaArea;
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

	// The sailing transports the account is offered
	private List<Transport> legs(Void unused)
	{
		List<Transport> legs = new ArrayList<>();
		shortestpath.PrimitiveIntHashMap<Transport[]> packed = pathfinderConfig.getTransportsPacked(BankVisitState.CARRIED);
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
