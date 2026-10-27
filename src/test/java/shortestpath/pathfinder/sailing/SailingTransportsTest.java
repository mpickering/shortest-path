package shortestpath.pathfinder.sailing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.TestWorldData;
import shortestpath.transport.Transport;

/** The generated sailing transports: current with the data they come from, and timed as the route fills them in. */
public class SailingTransportsTest
{
	@Test
	public void theFilesAreCurrent()
	{
		String expected = SailingTransports.fingerprint(SailingMoorings.all());
		for (SailingVessel.Kind kind : SailingVessel.Kind.values())
		{
			String contents = new String(SailingTransports.readBytes(SailingTransports.resource(kind)),
				StandardCharsets.UTF_8);
			assertEquals(SailingTransports.resource(kind) + " is stale: run ./gradlew sailingTimes", expected,
				SailingTransports.fingerprintOf(contents));
		}
	}

	@Test
	public void everyTransportIsBetweenTwoMooringsTheBoatFits()
	{
		int count = 0;
		for (Set<Transport> transports : TestWorldData.rawTransports().values())
		{
			for (Transport transport : transports)
			{
				SailingVessel.Kind kind = SailingTransports.kind(transport.getType());
				if (kind == null) continue;
				count++;
				SailingMooring from = SailingTransports.from(transport), to = SailingTransports.to(transport);
				assertNotNull(from);
				assertNotNull(to);
				assertTrue(from != to);
				SailingVessel vessel = SailingVessel.of(kind, SailingTransports.SPEED);
				assertTrue(from.fits(vessel) && to.fits(vessel));
				assertTrue(transport.getDuration() > SailingMooring.BOARD_TICKS + SailingMooring.DISEMBARK_TICKS);
			}
		}
		assertTrue(count > 1000);
	}

	@Test
	public void aFilledInLegTakesTheTransportsTime()
	{
		CollisionMap map = new CollisionMap(TestWorldData.map());
		List<Transport> sample = new ArrayList<>();
		for (Map.Entry<Integer, Set<Transport>> entry : TestWorldData.rawTransports().entrySet())
		{
			for (Transport transport : entry.getValue())
			{
				if (transport.getType().isSailing() && sample.size() < 400) sample.add(transport);
			}
		}
		assertFalse(sample.isEmpty());
		// Every 20th, across kinds and lengths
		for (int i = 0; i < sample.size(); i += 20)
		{
			Transport transport = sample.get(i);
			SailingVessel vessel = SailingVessel.of(SailingTransports.kind(transport.getType()), SailingTransports.SPEED);
			SailingLeg leg = SailingRoutes.leg(map, vessel, SailingTransports.from(transport),
				SailingTransports.to(transport), () -> false);
			assertNotNull(leg);
			assertEquals(transport.getDisplayInfo(),
				transport.getDuration() - SailingMooring.BOARD_TICKS - SailingMooring.DISEMBARK_TICKS, leg.cost());
		}
	}
}
