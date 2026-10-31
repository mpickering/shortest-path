package shortestpath.pathfinder.sailing;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * What the router may assume about the player's boats: the boats they can board on foot and where, and the boat they
 * are aboard now, if any. Every fact comes from the account or the game, never from the Sailing level.
 */
@Getter
@Accessors(fluent = true)
public final class SailingFleet
{
	/** No boats: routes walk. */
	public static final SailingFleet NONE = new SailingFleet(List.of(), null);

	/** {@link Boat#boardingCost} for a mooring the boat can't be boarded at. */
	public static final int UNAVAILABLE = -1;

	/** One of the player's boats, and what boarding it at each mooring costs. */
	@Getter
	@Accessors(fluent = true)
	public static final class Boat
	{
		/** {@link #dock} for a boat moored at every mooring. */
		private static final int EVERYWHERE = -2;

		private final SailingVessel vessel;
		/** The game's id of the dock the boat is moored at, or {@link #EVERYWHERE}; matches no mooring if unknown. */
		private final int dock;

		private Boat(SailingVessel vessel, int dock)
		{
			this.vessel = vessel;
			this.dock = dock;
		}

		/** A boat moored at the dock with the game's id {@code dock}. */
		public static Boat at(SailingVessel vessel, int dock)
		{
			return new Boat(vessel, dock);
		}

		/**
		 * A boat moored at every mooring: a declared assumption for accounts whose boat positions are not modelled, as
		 * if the boat had been moved to the mooring the route boards at before setting off.
		 */
		public static Boat everywhere(SailingVessel vessel)
		{
			return new Boat(vessel, EVERYWHERE);
		}

		/** Coins to board the boat at {@code mooring}: 0 where it is moored, else {@link #UNAVAILABLE}. */
		public int boardingCost(SailingMooring mooring)
		{
			return dock == EVERYWHERE || mooring.dock() >= 0 && mooring.dock() == dock ? 0 : UNAVAILABLE;
		}
	}

	/** The boat the player is aboard, where it is and which way it faces. */
	@Getter
	@Accessors(fluent = true)
	public static final class Aboard
	{
		private final int tile;
		/** One of the 16 headings (0 south, 8 north), or -1 when unknown. */
		private final int heading;
		private final SailingVessel vessel;
		/** Where the boat sits within its tile, in local units from the tile's centre. */
		private final int pivotX, pivotY;

		public Aboard(int tile, int heading, SailingVessel vessel, int pivotX, int pivotY)
		{
			this.tile = tile;
			this.heading = heading;
			this.vessel = vessel;
			this.pivotX = pivotX;
			this.pivotY = pivotY;
		}
	}

	/** The boats the player owns and can board on foot. */
	private final List<Boat> boats;
	/** The boat the player is aboard, or {@code null}. */
	private final Aboard aboard;

	public SailingFleet(List<Boat> boats, Aboard aboard)
	{
		this.boats = List.copyOf(boats);
		this.aboard = aboard;
	}

	/** Each of {@code vessels} moored at every mooring ({@link Boat#everywhere}), none aboard. */
	public static SailingFleet everywhere(List<SailingVessel> vessels)
	{
		List<Boat> boats = new ArrayList<>();
		for (SailingVessel vessel : vessels) boats.add(Boat.everywhere(vessel));
		return new SailingFleet(boats, null);
	}

	/** Aboard {@code aboard}, with no other boats. */
	public static SailingFleet aboard(Aboard aboard)
	{
		return new SailingFleet(List.of(), aboard);
	}

	/** This fleet with the player on foot: no boat aboard. */
	public SailingFleet ashore()
	{
		return aboard == null ? this : new SailingFleet(boats, null);
	}

	/**
	 * The cheapest of the player's boats of {@code kind} to board at {@code mooring}, if it fits there, or
	 * {@code null} if none can be boarded there.
	 */
	public Boat cheapestAt(SailingMooring mooring, SailingVessel.Kind kind)
	{
		Boat cheapest = null;
		for (Boat boat : boats)
		{
			int cost = boat.boardingCost(mooring);
			if (boat.vessel().kind() != kind || cost == UNAVAILABLE || !mooring.fits(boat.vessel())) continue;
			if (cheapest == null || cost < cheapest.boardingCost(mooring)) cheapest = boat;
		}
		return cheapest;
	}
}
