package shortestpath.pathfinder.sailing;

import java.util.List;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * What the router may assume about the player's boats: the boats it can board, where it can board them, and the boat
 * the player is aboard now, if any. Every fact comes from the account or the game, never from the Sailing level.
 * <p>
 * A boat is a single object in the world: after one sailing leg it is moored where the player left it. Where each
 * boat is moored is not read from the game yet, so the live plugin boards nothing on foot; a benchmark account
 * declares where its boats can be boarded instead.
 */
@Getter
@Accessors(fluent = true)
public final class SailingFleet
{
	/** Where an account's boats can be boarded from the mooring they are at. */
	@FunctionalInterface
	public interface BoatLocations
	{
		/** No boat is known to be moored anywhere. */
		BoatLocations UNKNOWN = (mooring, vessel) -> false;
		/**
		 * Every boat is moored at every mooring: a declared assumption for accounts whose boat positions are not
		 * modelled, as if each boat had been moved to the mooring the route boards at before setting off.
		 */
		BoatLocations EVERY_MOORING = (mooring, vessel) -> true;

		boolean moored(SailingMooring mooring, SailingVessel vessel);
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

	/** No boats: routes walk. */
	public static final SailingFleet NONE = new SailingFleet(List.of(), BoatLocations.UNKNOWN, null);

	/** The boats the player owns and can board on foot. */
	private final List<SailingVessel> vessels;
	private final BoatLocations locations;
	/** The boat the player is aboard, or {@code null}. */
	private final Aboard aboard;

	public SailingFleet(List<SailingVessel> vessels, BoatLocations locations, Aboard aboard)
	{
		this.vessels = List.copyOf(vessels);
		this.locations = locations == null ? BoatLocations.UNKNOWN : locations;
		this.aboard = aboard;
	}
}
