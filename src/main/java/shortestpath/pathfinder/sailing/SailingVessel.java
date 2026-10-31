package shortestpath.pathfinder.sailing;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import lombok.Getter;
import lombok.experimental.Accessors;
import shortestpath.sailing.BoatSpeed;
import shortestpath.sailing.SailingBoat;
import shortestpath.sailing.SeaArea;
import shortestpath.transport.TransportType;

/**
 * One boat a route can sail: its kind, its hull's bounds, its base speed and the restricted sea areas its parts let
 * it enter. The bounds are the game's bounds for the boat's world entity (see {@link BoatHull}); the base speed is in
 * the game's units ({@link BoatSpeed}).
 */
@Getter
@Accessors(fluent = true)
public final class SailingVessel
{
	/** The boat kinds, smallest first. */
	public enum Kind
	{
		RAFT(SailingBoat.RAFT, TransportType.SAILING_RAFT, 0, 0, 128, 384),
		SKIFF(SailingBoat.SKIFF, TransportType.SAILING_SKIFF, 0, 0, 256, 640),
		SLOOP(SailingBoat.SLOOP, TransportType.SAILING_SLOOP, 0, -256, 384, 1280);

		/** The game's number for the boat type, as a boat slot's type varbit and the sailing_boat table hold it. */
		private final int gameType;
		/** The transport type of sailing a boat of this kind between moorings. */
		private final TransportType transportType;
		// The game's world-entity bounds, in local units: across, along (negative toward the bow), width and length
		private final int boundsX, boundsY, boundsWidth, boundsHeight;

		Kind(int gameType, TransportType transportType, int boundsX, int boundsY, int boundsWidth, int boundsHeight)
		{
			this.gameType = gameType;
			this.transportType = transportType;
			this.boundsX = boundsX;
			this.boundsY = boundsY;
			this.boundsWidth = boundsWidth;
			this.boundsHeight = boundsHeight;
		}

		/** The transport type of sailing a boat of this kind between moorings. */
		public TransportType transportType()
		{
			return transportType;
		}

		/** The kind the game's boat type {@code gameType} is, or {@code null} for a boat the router doesn't sail. */
		public static Kind ofGameType(int gameType)
		{
			for (Kind kind : values()) if (kind.gameType == gameType) return kind;
			return null;
		}

		/** The kind a sailing transport type sails, or {@code null} for any other type. */
		public static Kind ofTransport(TransportType type)
		{
			for (Kind kind : values()) if (kind.transportType == type) return kind;
			return null;
		}

		/** The standard kind with exactly these bounds, or {@code null}. */
		public static Kind ofBounds(int boundsX, int boundsY, int boundsWidth, int boundsHeight)
		{
			for (Kind kind : values())
			{
				if (kind.boundsX == boundsX && kind.boundsY == boundsY && kind.boundsWidth == boundsWidth
					&& kind.boundsHeight == boundsHeight) return kind;
			}
			return null;
		}

		/**
		 * Whether the part for {@code area} can be fitted to a boat of this kind: a raft, by the game's boat tables,
		 * has no keel and no room for an inoculation station.
		 */
		public boolean fits(SeaArea area)
		{
			return this != RAFT || area != SeaArea.CRYSTALLINE && area != SeaArea.FETID;
		}
	}

	/** The boat's kind, or {@code null} for a boat of no standard kind (bounds the router doesn't know). */
	private final Kind kind;
	private final int boundsX, boundsY, boundsWidth, boundsHeight;
	/** Base speed, in {@link BoatSpeed} units. */
	private final int speed;
	/** The restricted sea areas the boat's parts let it enter; open water it always may. */
	private final Set<SeaArea> areas;

	private SailingVessel(Kind kind, int boundsX, int boundsY, int boundsWidth, int boundsHeight, int speed,
		Set<SeaArea> areas)
	{
		if (boundsWidth <= 0 || boundsHeight <= 0) throw new IllegalArgumentException("a boat needs a hull");
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
	 * A boat of {@code kind} with the game's bounds for it, at base speed {@code speed} ({@link BoatSpeed} units), with no
	 * parts for the restricted areas.
	 */
	public static SailingVessel of(Kind kind, int speed)
	{
		return new SailingVessel(kind, kind.boundsX, kind.boundsY, kind.boundsWidth, kind.boundsHeight, speed,
			SeaArea.NONE);
	}

	/**
	 * A boat with the bounds the game gives its world entity: of the standard kind with those bounds, if any, else of
	 * no kind. The bounds must not be empty.
	 */
	public static SailingVessel withBounds(int boundsX, int boundsY, int boundsWidth, int boundsHeight, int speed)
	{
		return new SailingVessel(Kind.ofBounds(boundsX, boundsY, boundsWidth, boundsHeight), boundsX, boundsY,
			boundsWidth, boundsHeight, speed, SeaArea.NONE);
	}

	/**
	 * This boat fitted for {@code areas}: the restricted areas whose parts it has. A part its kind can't take is left
	 * out.
	 */
	public SailingVessel withAreas(Set<SeaArea> areas)
	{
		return new SailingVessel(kind, boundsX, boundsY, boundsWidth, boundsHeight, speed, areas);
	}

	/** This boat with every part its kind can take. */
	public SailingVessel fullyFitted()
	{
		return withAreas(SeaArea.ALL);
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

	/** Base speed in tiles per tick. */
	public double tilesPerTick()
	{
		return BoatSpeed.tilesPerTick(speed);
	}

	/**
	 * A new hull for one search, sitting at ({@code pivotX}, {@code pivotY}) local units from its tile's centre. Hulls
	 * cache what they sweep, so each search gets its own.
	 */
	public BoatHull hull(int pivotX, int pivotY)
	{
		return BoatHull.fromBounds(boundsX, boundsY, boundsWidth, boundsHeight, pivotX, pivotY);
	}

	/** A new hull for one search, at its tile's centre. */
	public BoatHull hull()
	{
		return hull(0, 0);
	}

	/** The moves this boat sails at its base speed. */
	public SailingMoves moves()
	{
		return SailingMoves.forSpeed(tilesPerTick());
	}

	@Override
	public String toString()
	{
		return kind + "@" + tilesPerTick() + (areas.isEmpty() ? "" : areas.toString());
	}
}
