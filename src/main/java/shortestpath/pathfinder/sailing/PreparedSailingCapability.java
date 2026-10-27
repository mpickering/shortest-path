package shortestpath.pathfinder.sailing;

import java.util.List;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * One query's sailing capability: what the router needs to know about the player's sailing, prepared from the
 * account before the search. The moorings are those whose requirements the account meets; the fleet says which
 * boats can be boarded where, and which the player is aboard.
 * <p>
 * Hazards (storms, fetid water, crystal-flecked water, kelp and ice) and the ship parts that let a boat through them
 * are not modelled yet; they belong here, with the boats, so that adding them leaves the search unchanged.
 */
@Getter
@Accessors(fluent = true)
public final class PreparedSailingCapability
{
	public static final PreparedSailingCapability NONE = new PreparedSailingCapability(SailingFleet.NONE, List.of());

	private final SailingFleet fleet;
	/** The moorings the account may dock at. */
	private final List<SailingMooring> moorings;

	public PreparedSailingCapability(SailingFleet fleet, List<SailingMooring> moorings)
	{
		this.fleet = fleet;
		this.moorings = List.copyOf(moorings);
	}

	/** The same capability for a route that starts on foot, wherever the player is. */
	public PreparedSailingCapability onFoot()
	{
		return fleet.aboard() == null ? this
			: new PreparedSailingCapability(new SailingFleet(fleet.vessels(), fleet.locations(), null), moorings);
	}

	/** Whether {@code vessel}, moored at {@code mooring}, can be boarded there. */
	public boolean canBoard(SailingMooring mooring, SailingVessel vessel)
	{
		return mooring.fits(vessel) && fleet.locations().moored(mooring, vessel);
	}

	/** Whether {@code vessel} can dock at {@code mooring}. */
	public boolean canDock(SailingMooring mooring, SailingVessel vessel)
	{
		return mooring.fits(vessel);
	}

	/** Whether any route can sail: the player is aboard, or some boat can be boarded somewhere. */
	public boolean active()
	{
		if (fleet.aboard() != null) return true;
		for (SailingVessel vessel : fleet.vessels())
		{
			for (SailingMooring mooring : moorings)
			{
				if (canBoard(mooring, vessel)) return true;
			}
		}
		return false;
	}
}
