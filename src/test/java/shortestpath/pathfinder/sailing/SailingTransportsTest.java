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
		assertEquals("the collision map's sailing layer is stale: run ./gradlew sailingTimes", expected,
			SailingWaters.of(new CollisionMap(TestWorldData.map())).inputs());
	}

	@Test
	public void theRaftReachesFurtherThanTheSloop()
	{
		// Lum Lagoon, behind a channel the sloop's hull can't get through
		int lagoon = shortestpath.WorldPointUtil.packWorldPoint(3277, 3135, 0);
		CollisionMap map = new CollisionMap(TestWorldData.map());
		SailingWaters waters = SailingWaters.of(map);
		SailingMooring sarim = SailingMoorings.all().stream().filter(m -> m.name().equals("Port Sarim")).findFirst()
			.orElseThrow();
		for (SailingVessel.Kind kind : new SailingVessel.Kind[] {SailingVessel.Kind.RAFT, SailingVessel.Kind.SLOOP})
		{
			SailingVessel vessel = SailingVessel.of(kind, SailingTransports.SPEED).withAreas(shortestpath.sailing.SeaArea.ALL);
			assertEquals(kind.toString(), kind == SailingVessel.Kind.RAFT, waters.connects(vessel, vessel.hull(0, 0),
				waters.castOff(map, sarim, vessel), SailingWaters.tilesNear(lagoon, 3)));
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
			// With the parts for the areas its row names, and no others, the leg takes the row's time
			SailingVessel vessel = SailingVessel.of(SailingTransports.kind(transport.getType()), SailingTransports.SPEED)
				.withAreas(transport.getSeaAreas());
			SailingLeg leg = SailingRoutes.leg(map, vessel, SailingTransports.from(transport),
				SailingTransports.to(transport), () -> false);
			assertNotNull(leg);
			assertEquals(transport.getDisplayInfo(),
				transport.getDuration() - SailingMooring.BOARD_TICKS - SailingMooring.DISEMBARK_TICKS, leg.cost());
		}
	}

	@Test
	public void aLegsTimeBoundsABoatWithFewerPartsOrAnotherSpeed()
	{
		CollisionMap map = new CollisionMap(TestWorldData.map());
		List<Transport> through = new ArrayList<>();
		for (Set<Transport> transports : TestWorldData.rawTransports().values())
		{
			for (Transport transport : transports)
			{
				if (transport.getType().isSailing() && !transport.getSeaAreas().isEmpty()) through.add(transport);
			}
		}
		through.sort(java.util.Comparator.comparingInt(Transport::getOrigin).thenComparingInt(Transport::getDestination)
			.thenComparing(Transport::getType));
		int ends = SailingMooring.BOARD_TICKS + SailingMooring.DISEMBARK_TICKS, sailed = 0;
		for (int i = 0; i < through.size(); i += through.size() / 12)
		{
			Transport transport = through.get(i);
			SailingVessel.Kind kind = SailingTransports.kind(transport.getType());
			// No parts: kept out of the water the timed leg sails through, the boat goes round or not at all
			for (SailingVessel vessel : List.of(SailingVessel.of(kind, SailingTransports.SPEED),
				SailingVessel.of(kind, 1.5).withAreas(shortestpath.sailing.SeaArea.ALL),
				SailingVessel.of(kind, 3.5).withAreas(shortestpath.sailing.SeaArea.ALL)))
			{
				assertFalse(SailingTransports.timedFor(transport, vessel));
				SailingLeg leg = SailingRoutes.leg(map, vessel, SailingTransports.from(transport),
					SailingTransports.to(transport), () -> false);
				if (leg == null) continue;
				sailed++;
				int bound = SailingTransports.boundFor(transport, vessel).getDuration();
				assertTrue(vessel + " " + transport.getDisplayInfo() + ": " + bound + " <= " + (ends + leg.cost()),
					bound <= ends + leg.cost());
			}
		}
		assertTrue(sailed > 12);
	}

	@Test
	public void aBoatCastsOffWhereItCouldDock()
	{
		CollisionMap map = new CollisionMap(TestWorldData.map());
		SailingWaters waters = SailingWaters.of(map);
		for (SailingMooring mooring : SailingMoorings.all())
		{
			for (SailingVessel.Kind kind : SailingVessel.Kind.values())
			{
				SailingVessel vessel = SailingVessel.of(kind, SailingTransports.SPEED).withAreas(shortestpath.sailing.SeaArea.ALL);
				if (!mooring.fits(vessel)) continue;
				BoatHull hull = vessel.hull(0, 0);
				for (int tile : waters.castOff(map, mooring, vessel))
				{
					// Never the berth: a boat state, in docking range, its hull clear facing some heading
					assertTrue(mooring.inDockingRange(tile));
					assertTrue(waters.allows(vessel, tile));
					assertTrue(hull.fits(map, shortestpath.WorldPointUtil.unpackWorldX(tile),
						shortestpath.WorldPointUtil.unpackWorldY(tile), shortestpath.WorldPointUtil.unpackWorldPlane(tile)));
				}
			}
		}
	}

	@Test
	public void aSlowBoatLeavesItsBerth()
	{
		// At 1.5 every move is short: a skiff moored at the Isle of Bones used to make one move and still have its
		// stern over the dock. Cast off clear of it, it sails on.
		CollisionMap map = new CollisionMap(TestWorldData.map());
		SailingMooring from = mooring("Isle of Bones");
		for (Object[] leg : new Object[][] {{SailingVessel.Kind.SKIFF, "Charred Island"}, {SailingVessel.Kind.SLOOP, "Port Roberts"}})
		{
			SailingVessel vessel = SailingVessel.of((SailingVessel.Kind) leg[0], 1.5).withAreas(shortestpath.sailing.SeaArea.ALL);
			assertNotNull(leg[0].toString(), SailingRoutes.leg(map, vessel, from, mooring((String) leg[1]), () -> false));
		}
	}

	private static SailingMooring mooring(String name)
	{
		return SailingMoorings.all().stream().filter(mooring -> mooring.name().equals(name)).findFirst().orElseThrow();
	}
}
