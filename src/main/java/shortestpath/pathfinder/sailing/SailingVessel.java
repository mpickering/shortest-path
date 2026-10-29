package shortestpath.pathfinder.sailing;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import lombok.Getter;
import lombok.experimental.Accessors;
import shortestpath.sailing.SeaArea;

/**
 * One boat a route can sail: its kind, its hull's bounds, its base speed and the restricted sea areas its parts let
 * it enter. The bounds are the game's bounds for the boat's world entity (see {@link BoatHull}); the speed is in tiles
 * per tick.
 */
@Getter
@Accessors(fluent = true)
public final class SailingVessel
{
	/** The boat kinds, smallest first. */
	public enum Kind
	{
		RAFT(0, 0, 128, 384),
		SKIFF(0, 0, 256, 640),
		SLOOP(0, -256, 384, 1280);

		/**
		 * Whether the part for {@code area} can be fitted to a boat of this kind: a raft, by the game's boat tables,
		 * has no keel and no room for an inoculation station.
		 */
		public boolean fits(SeaArea area)
		{
			return this != RAFT || area != SeaArea.CRYSTALLINE && area != SeaArea.FETID;
		}

		// The game's world-entity bounds, in local units: across, along (negative toward the bow), width and length
		private final int boundsX, boundsY, boundsWidth, boundsHeight;

		Kind(int boundsX, int boundsY, int boundsWidth, int boundsHeight)
		{
			this.boundsX = boundsX;
			this.boundsY = boundsY;
			this.boundsWidth = boundsWidth;
			this.boundsHeight = boundsHeight;
		}
	}

	private final Kind kind;
	private final int boundsX, boundsY, boundsWidth, boundsHeight;
	private final double speed;
	/** The restricted sea areas the boat's parts let it enter; open water it always may. */
	private final Set<SeaArea> areas;

	public SailingVessel(Kind kind, int boundsX, int boundsY, int boundsWidth, int boundsHeight, double speed)
	{
		this(kind, boundsX, boundsY, boundsWidth, boundsHeight, speed, SeaArea.NONE);
	}

	private SailingVessel(Kind kind, int boundsX, int boundsY, int boundsWidth, int boundsHeight, double speed,
		Set<SeaArea> areas)
	{
		this.kind = kind;
		this.boundsX = boundsX;
		this.boundsY = boundsY;
		this.boundsWidth = boundsWidth;
		this.boundsHeight = boundsHeight;
		this.speed = speed;
		Set<SeaArea> fitted = EnumSet.noneOf(SeaArea.class);
		for (SeaArea area : areas)
		{
			if (kind == null || kind.fits(area)) fitted.add(area);
		}
		this.areas = Collections.unmodifiableSet(fitted);
	}

	/**
	 * A boat of {@code kind} with the game's bounds for it, sailing at {@code speed} tiles per tick, with no parts for
	 * the restricted areas.
	 */
	public static SailingVessel of(Kind kind, double speed)
	{
		return new SailingVessel(kind, kind.boundsX, kind.boundsY, kind.boundsWidth, kind.boundsHeight, speed);
	}

	/**
	 * This boat fitted for {@code areas}: the restricted areas whose parts it has. A part its kind can't take is left
	 * out.
	 */
	public SailingVessel withAreas(Set<SeaArea> areas)
	{
		return new SailingVessel(kind, boundsX, boundsY, boundsWidth, boundsHeight, speed, areas);
	}

	/** Whether the boat is of a standard kind, with that kind's bounds. */
	public boolean standard()
	{
		return kind != null && boundsX == kind.boundsX && boundsY == kind.boundsY && boundsWidth == kind.boundsWidth
			&& boundsHeight == kind.boundsHeight;
	}

	/** Whether the boat may sail into {@code area}; {@code null} is open water. */
	public boolean canEnter(SeaArea area)
	{
		return area == null || areas.contains(area);
	}

	/**
	 * A new hull for one search, sitting at ({@code pivotX}, {@code pivotY}) local units from its tile's centre, or
	 * {@code null} when the bounds are empty. Hulls cache what they sweep, so each search gets its own.
	 */
	public BoatHull hull(int pivotX, int pivotY)
	{
		return BoatHull.fromBounds(boundsX, boundsY, boundsWidth, boundsHeight, pivotX, pivotY);
	}

	/** The moves this boat sails at its base speed. */
	public SailingMoves moves()
	{
		return SailingMoves.forSpeed(speed);
	}

	@Override
	public String toString()
	{
		return kind + "@" + speed + (areas.isEmpty() ? "" : areas.toString());
	}
}
