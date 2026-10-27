package shortestpath.pathfinder.sailing;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * One boat a route can sail: its kind, its hull's bounds and its base speed. The bounds are the game's bounds for the
 * boat's world entity (see {@link BoatHull}); the speed is in tiles per tick.
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

	public SailingVessel(Kind kind, int boundsX, int boundsY, int boundsWidth, int boundsHeight, double speed)
	{
		this.kind = kind;
		this.boundsX = boundsX;
		this.boundsY = boundsY;
		this.boundsWidth = boundsWidth;
		this.boundsHeight = boundsHeight;
		this.speed = speed;
	}

	/** A boat of {@code kind} with the game's bounds for it, sailing at {@code speed} tiles per tick. */
	public static SailingVessel of(Kind kind, double speed)
	{
		return new SailingVessel(kind, kind.boundsX, kind.boundsY, kind.boundsWidth, kind.boundsHeight, speed);
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
		return kind + "@" + speed;
	}
}
