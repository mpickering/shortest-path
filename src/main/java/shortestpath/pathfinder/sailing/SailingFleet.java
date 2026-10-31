package shortestpath.pathfinder.sailing;

import java.util.List;
import java.util.function.ToIntBiFunction;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * What the router may assume about the player's boats: the boats it can board, where it can board them, and the boat
 * the player is aboard now, if any. Every fact comes from the account or the game, never from the Sailing level.
 * <p>
 * The live plugin reads each owned boat's dock and allows recovery at shipwrights within the configured limit.
 */
@Getter
@Accessors(fluent = true)
public final class SailingFleet
{
	/** Where boats can be boarded, including an allowed recovery to that mooring. */
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
	private final ToIntBiFunction<SailingMooring, SailingVessel> recoveryCosts;

	public SailingFleet(List<SailingVessel> vessels, BoatLocations locations, Aboard aboard)
	{
		this(vessels, locations, aboard, (mooring, vessel) -> 0);
	}

	public SailingFleet(List<SailingVessel> vessels, BoatLocations locations, Aboard aboard,
		ToIntBiFunction<SailingMooring, SailingVessel> recoveryCosts)
	{
		this.vessels = List.copyOf(vessels);
		this.locations = locations == null ? BoatLocations.UNKNOWN : locations;
		this.aboard = aboard;
		this.recoveryCosts = recoveryCosts;
	}

	public int recoveryCost(SailingMooring mooring, SailingVessel vessel)
	{
		return recoveryCosts.applyAsInt(mooring, vessel);
	}
}
