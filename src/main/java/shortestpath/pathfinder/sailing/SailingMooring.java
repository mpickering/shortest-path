package shortestpath.pathfinder.sailing;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.runelite.api.Quest;
import shortestpath.WorldPointUtil;
import shortestpath.requirement.model.DestinationRequirements;

/**
 * A mooring point: one place where a player boards their own boat or leaves it, the boundary between walking and
 * sailing. Ports have a gangplank, islands a mooring point; both work the same way here.
 * <p>
 * The walking side is the land tile the player uses the gangplank from; the sailing side is the berth, the dock tile
 * the boat is moored at. A berth is blocked, so it is never a boat state: boarding puts the boat anywhere within
 * {@link #DOCKING_RANGE} of it that the boat can be ({@link SailingWaters#castOff}), and docking takes it from
 * anywhere in that range.
 */
@Getter
@Accessors(fluent = true)
public final class SailingMooring
{
	/**
	 * Ticks to board a moored boat and cast off: an estimate, the GPS plugin's 20-tick boarding and docking overhead
	 * less its {@link #DISEMBARK_TICKS}.
	 */
	public static final int BOARD_TICKS = 12;
	/** Ticks to dock and step off: an estimate, the GPS plugin's figure for mooring and stepping off. */
	public static final int DISEMBARK_TICKS = 8;
	/**
	 * How close the boat's tile must come to a berth, in tiles, before it can dock there: an estimate, about the reach
	 * of the game's docking prompt.
	 */
	public static final double DOCKING_RANGE = 5;

	/** Index in {@link SailingMoorings#all()}: the mooring's identity within one data set. */
	private final int id;
	private final String name;
	private final int landTile;
	private final int berthTile;
	/** Skill levels and finished quests docking needs. */
	private final DestinationRequirements requirements;
	/** Quests docking needs started, finished or not. */
	private final Set<Quest> questsStarted;
	/** The boats that fit; every boat when the source names none. */
	private final Set<SailingVessel.Kind> vessels;

	public SailingMooring(int id, String name, int landTile, int berthTile, DestinationRequirements requirements,
		Set<Quest> questsStarted, Set<SailingVessel.Kind> vessels)
	{
		this.id = id;
		this.name = name;
		this.landTile = landTile;
		this.berthTile = berthTile;
		this.requirements = requirements == null ? DestinationRequirements.EMPTY : requirements;
		this.questsStarted = questsStarted == null || questsStarted.isEmpty() ? Collections.emptySet()
			: Collections.unmodifiableSet(EnumSet.copyOf(questsStarted));
		this.vessels = vessels == null || vessels.isEmpty()
			? Collections.unmodifiableSet(EnumSet.allOf(SailingVessel.Kind.class))
			: Collections.unmodifiableSet(EnumSet.copyOf(vessels));
	}

	/** Whether {@code vessel} fits this mooring. */
	public boolean fits(SailingVessel vessel)
	{
		// A boat of unknown kind only fits where every boat does
		return vessel.kind() == null ? vessels.size() == SailingVessel.Kind.values().length
			: vessels.contains(vessel.kind());
	}

	/** Whether a boat on {@code boatTile} is close enough to its berth to dock. */
	public boolean inDockingRange(int boatTile)
	{
		if (WorldPointUtil.unpackWorldPlane(boatTile) != WorldPointUtil.unpackWorldPlane(berthTile)) return false;
		int dx = WorldPointUtil.unpackWorldX(boatTile) - WorldPointUtil.unpackWorldX(berthTile);
		int dy = WorldPointUtil.unpackWorldY(boatTile) - WorldPointUtil.unpackWorldY(berthTile);
		return dx * dx + dy * dy <= DOCKING_RANGE * DOCKING_RANGE;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
