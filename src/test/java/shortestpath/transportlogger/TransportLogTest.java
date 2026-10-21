package shortestpath.transportlogger;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TransportLogTest
{
	private static final WorldPoint A = new WorldPoint(3200, 3200, 0);
	private static final WorldPoint B = new WorldPoint(3300, 3300, 0);
	private final TransportLog log = new TransportLog();

	@Test
	public void idleTimeDoesNotInflateAnimationTicks()
	{
		log.sample(A, 1);
		log.sample(A, 200);
		log.interaction("Rub Amulet [item 1704]", 200);
		log.animation(A, 714, 201);
		log.sample(B, 203);
		assertNull(log.entries().get(0).animationTicks);
		log.animation(B, -1, 205);
		TransportLog.Entry entry = log.entries().get(0);
		assertEquals(A, entry.origin);
		assertEquals(B, entry.destination);
		assertEquals(Integer.valueOf(4), entry.animationTicks);
		assertEquals("[714]", entry.animationIds);
	}

	@Test
	public void walkingAndRunningDoNotCreateEntries()
	{
		log.sample(A, 1);
		log.sample(A.dx(1), 2);
		log.sample(A.dx(3).dy(2), 3);
		assertTrue(log.entries().isEmpty());
	}

	@Test
	public void planeChangeAtSameCoordinatesIsRecordedWithoutInventedTiming()
	{
		log.sample(A, 1);
		log.sample(A.dz(1), 2);
		assertEquals(1, log.entries().size());
		assertNull(log.entries().get(0).animationTicks);
		assertEquals("", log.entries().get(0).animationIds);
	}

	@Test
	public void approachWalkingDoesNotBecomeTheOriginOrAnimationStart()
	{
		log.sample(A, 1);
		log.interaction("Climb-down Ladder [object 123]", 1);
		log.sample(A.dx(2), 2);
		log.sample(A.dx(4), 3);
		log.animation(A.dx(4), 828, 4);
		log.sample(B, 5);
		log.animation(B, -1, 7);
		assertEquals(A.dx(4), log.entries().get(0).origin);
		assertEquals(Integer.valueOf(3), log.entries().get(0).animationTicks);
	}

	@Test
	public void contiguousArrivalAnimationUpdatesTheSameImmutableEntry()
	{
		log.sample(A, 1);
		log.animation(A, 1, 2);
		log.sample(B, 4);
		List<TransportLog.Entry> snapshot = log.entries();
		log.animation(B, -1, 5);
		log.animation(B, 2, 5);
		log.animation(B, -1, 7);
		assertNull(snapshot.get(0).animationTicks);
		assertEquals(Integer.valueOf(5), log.entries().get(0).animationTicks);
		assertEquals("[1, 2]", log.entries().get(0).animationIds);
		assertEquals(1, log.entries().size());
	}

	@Test
	public void unrelatedOrOldAnimationIsNotUsed()
	{
		log.sample(A, 1);
		log.animation(A, 1, 1);
		log.animation(A, -1, 3);
		log.sample(A, 10);
		log.sample(B, 11);
		assertNull(log.entries().get(0).animationTicks);
		log.animation(B, 2, 12);
		log.animation(B, -1, 15);
		assertNull(log.entries().get(0).animationTicks);
	}

	@Test
	public void shortManualCrossingKeepsFinishedAnimationUntilDestinationIsMarked()
	{
		log.markOrigin(A, 10);
		log.interaction("Cross Rocks [object 456]", 10);
		log.animation(A, 1, 11);
		log.sample(A.dx(1), 12);
		log.animation(A.dx(1), -1, 14);
		log.sample(A.dx(1), 20);
		assertTrue(log.entries().isEmpty());
		assertTrue(log.markDestination(A.dx(1), 25));
		assertEquals(Integer.valueOf(3), log.entries().get(0).animationTicks);
		assertEquals("Manual", log.entries().get(0).capture);
		assertFalse(log.markDestination(B, 26));
	}

	@Test
	public void manualCaptureSuppressesDuplicateAutomaticJump()
	{
		log.markOrigin(A, 1);
		log.sample(B, 2);
		assertTrue(log.markDestination(B, 3));
		assertEquals(1, log.entries().size());
		assertEquals(B, log.entries().get(0).destination);
	}

	@Test
	public void clearResetsHistoryManualCaptureAndRunningAnimation()
	{
		log.sample(A, 1);
		log.animation(A, 1, 2);
		log.sample(B, 3);
		log.markOrigin(B, 4);
		log.clear();
		log.animation(B, -1, 5);
		log.sample(A, 6);
		assertTrue(log.entries().isEmpty());
		assertNull(log.manualOrigin());
		log.sample(B, 7);
		assertNull(log.entries().get(0).animationTicks);
	}

	@Test
	public void sessionResetDoesNotLogLoginOrWorldHopButKeepsHistory()
	{
		log.sample(A, 1);
		log.sample(B, 2);
		log.resetSession();
		log.sample(A, 1);
		assertEquals(1, log.entries().size());
	}

	@Test
	public void destinationDialogueDoesNotOverwriteTheClickedObjectEvidence()
	{
		log.sample(A, 1);
		log.interaction("Travel Boat [object 21177]", 2);
		log.interaction("Continue", 3);
		log.interaction("Rellekka", 4);
		log.sample(B, 5);
		assertEquals("Travel Boat [object 21177] → Continue → Rellekka", log.entries().get(0).interaction);
	}

	@Test
	public void manualCaptureRetainsInteractionEvidenceThroughLongDialogue()
	{
		log.markOrigin(A, 1);
		log.interaction("Travel Boat [object 21177]", 2);
		log.interaction("Continue", 100);
		log.markDestination(B, 101);
		assertEquals("Travel Boat [object 21177] → Continue", log.entries().get(0).interaction);
	}

	@Test
	public void copiedRowsPreserveEmptyTimingAndNeverContainInjectedTsvColumns()
	{
		log.sample(A, 1);
		log.interaction("Travel\tBoat\n123", 2);
		log.sample(B, 3);
		String tsv = log.entries().get(0).tsv();
		assertEquals("3200 3200 0\t3300 3300 0\tTravel Boat 123\t\t\tAutomatic jump", tsv);
		assertEquals(6, tsv.split("\t", -1).length);
	}
}
