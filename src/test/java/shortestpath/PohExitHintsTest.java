package shortestpath;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import shortestpath.pathfinder.PathStep;
import shortestpath.pathfinder.PathfinderConfig;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportLoader;
import shortestpath.transport.TransportType;

@RunWith(MockitoJUnitRunner.class)
public class PohExitHintsTest
{
	private static final Map<Integer, Set<Transport>> TRANSPORTS = TransportLoader.loadAllFromResources();
	private static final int POH_LANDING = WorldPointUtil.packWorldPoint(1858, 7051, 0);
	private static final int CASTLE_WARS = WorldPointUtil.packWorldPoint(2440, 3090, 0);
	private static final int CASTLE_WARS_BANK = WorldPointUtil.packWorldPoint(2443, 3083, 0);
	private static final int DKS = WorldPointUtil.packWorldPoint(2744, 3719, 0);

	@Mock
	PathfinderConfig pathfinderConfig;

	@InjectMocks
	ShortestPathPlugin plugin;

	/**
	 * House tabs in the inventory and a Dramen staff in the bank: the route tabs to the POH,
	 * leaves by the jewellery box to bank at Castle Wars, tabs back and leaves by the fairy ring.
	 * Both POH visits land on the same template tile, so the player's position in the POH matches
	 * both; only the exit of the visit the player is on should be shown, not both (#809).
	 */
	@Test
	public void pohExitHintIsOnlyTheCurrentVisitsExit()
	{
		Transport houseTab = transport(Transport.UNDEFINED_ORIGIN, POH_LANDING, TransportType.TELEPORTATION_ITEM);
		Transport jewelleryBox = transport(POH_LANDING, CASTLE_WARS, TransportType.TELEPORTATION_BOX);
		Transport fairyRing = transport(POH_LANDING, DKS, TransportType.FAIRY_RING);
		List<PathStep> path = List.of(
			new PathStep(WorldPointUtil.packWorldPoint(2830, 2960, 0), false),
			new PathStep(POH_LANDING, false, houseTab),
			new PathStep(CASTLE_WARS, false, jewelleryBox),
			new PathStep(CASTLE_WARS_BANK, false),
			new PathStep(CASTLE_WARS_BANK, true),
			new PathStep(POH_LANDING, true, houseTab),
			new PathStep(DKS, true, fairyRing));

		List<String> hints = plugin.getPohExitHints(path);

		Assert.assertEquals("exit hints shown in the POH: " + hints, 1, hints.size());
	}

	private static Transport transport(int origin, int destination, TransportType type)
	{
		for (Transport transport : TRANSPORTS.getOrDefault(origin, Set.of()))
		{
			if (transport.getDestination() == destination && type.equals(transport.getType()))
			{
				return transport;
			}
		}
		throw new AssertionError("no " + type + " transport to " + WorldPointUtil.unpackWorldX(destination)
			+ "," + WorldPointUtil.unpackWorldY(destination));
	}
}
