package shortestpath.pathfinder.sailing;

import java.util.ArrayList;
import java.util.List;
import shortestpath.WorldPointUtil;
import shortestpath.sailing.BoatSpeed;
import shortestpath.sailing.SailingBoat;
import shortestpath.sailing.SailingState;

/** The live plugin's fleet: the player's boats as the game shows them ({@link SailingState}). */
public final class SailingFleets
{
	/**
	 * The fastest a real boat's base speed can be: double the fastest hull, for headroom. A reading above it is a stale
	 * or mis-scaled varbit rather than a faster boat, so the estimate is used instead.
	 */
	private static final int MAX_SPEED = (int) (SailingMoves.MAX_SPEED * BoatSpeed.UNITS);

	private SailingFleets()
	{
	}

	/**
	 * The player's boats from {@code state}: each boat they own moored at its dock, with its own parts and its hull's
	 * base speed, and the boat they are aboard, if any, which is then not at its dock.
	 */
	public static SailingFleet fromState(SailingState state)
	{
		List<SailingFleet.Boat> boats = new ArrayList<>();
		for (SailingBoat boat : state.getBoats())
		{
			SailingVessel.Kind kind = SailingVessel.Kind.ofGameType(boat.getType());
			if (kind == null || boat.getSlot() == state.getBoardedSlot()) continue;
			SailingVessel vessel = SailingVessel.of(kind, speed(boat.getBaseSpeed())).withAreas(boat.getSeaAreas());
			boats.add(SailingFleet.Boat.at(vessel, boat.getDock()));
		}
		return new SailingFleet(boats, aboard(state));
	}

	/** The boat the player is aboard, or {@code null} unless it is in the scene with its bounds and tile known. */
	static SailingFleet.Aboard aboard(SailingState state)
	{
		if (!state.isAboard() || !state.hasBoat() || !state.hasBounds() || state.getBoundsWidth() <= 0
			|| state.getBoundsHeight() <= 0 || state.getBoatPoint() == WorldPointUtil.UNDEFINED)
		{
			return null;
		}
		// The restricted sea areas its parts let it enter, from the side panel's resistances
		SailingVessel vessel = SailingVessel.withBounds(state.getBoundsX(), state.getBoundsY(), state.getBoundsWidth(),
			state.getBoundsHeight(), speed(state.getBaseSpeed())).withAreas(state.getSeaAreas());
		// One of the 16 headings 128 angle units apart, from the boat's target orientation
		int heading = state.getHeading() < 0 ? -1 : Math.floorMod((state.getHeading() + 64) / 128, 16);
		return new SailingFleet.Aboard(state.getBoatPoint(), heading, vessel, state.getPivotX(), state.getPivotY());
	}

	/** A base speed read from the game, or the estimate when it is unread or out of range. */
	static int speed(int read)
	{
		return read > 0 && read <= MAX_SPEED ? read : BoatSpeed.ESTIMATED;
	}
}
