package shortestpath.sailing;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * How long one sailing leg between moorings takes at each base speed a boat's hull can give it ({@link #SPEEDS}), for
 * a boat with every part its kind can be fitted with, and the restricted {@link SeaArea}s it sails through at that
 * speed. A transport file has a column per speed ({@link #column}); a cell holds the ticks, then the areas if any
 * ({@code 960 TEMPOR_TANTRUM;FETID}), and is empty when the boat can't sail the leg at that speed at all.
 */
public final class LegTimes
{
	/** The base speeds the hulls give ({@link BoatSpeed#HULLS}), slowest first: 1.5 to 3.0 tiles a tick. */
	public static final int[] SPEEDS = Arrays.stream(BoatSpeed.HULLS).distinct().sorted().toArray();
	/** A leg the boat can't sail at that speed. */
	public static final int NONE = -1;

	private final int[] ticks;
	private final Set<SeaArea>[] areas;

	private LegTimes(int[] ticks, Set<SeaArea>[] areas)
	{
		this.ticks = ticks;
		this.areas = areas;
	}

	/** The times from each speed's cell, in {@link #SPEEDS}' order; a missing cell is a leg not sailed. */
	@SuppressWarnings("unchecked")
	public static LegTimes parse(String[] cells)
	{
		int[] ticks = new int[SPEEDS.length];
		Set<SeaArea>[] areas = new Set[SPEEDS.length];
		for (int i = 0; i < SPEEDS.length; i++)
		{
			String cell = cells[i] == null ? "" : cells[i].trim();
			int space = cell.indexOf(' ');
			ticks[i] = cell.isEmpty() ? NONE : Integer.parseInt(space < 0 ? cell : cell.substring(0, space));
			areas[i] = space < 0 ? SeaArea.NONE : SeaArea.parse(cell.substring(space + 1));
		}
		return new LegTimes(ticks, areas);
	}

	/** A cell of a transport file: {@code ticks}, then {@code through} if any, or empty for {@link #NONE}. */
	public static String format(int ticks, Set<SeaArea> through)
	{
		if (ticks == NONE) return "";
		return through.isEmpty() ? Integer.toString(ticks) : ticks + " " + SeaArea.format(through);
	}

	/** The transport file column for speed {@code SPEEDS[index]}, in tiles per tick: {@code At 1.5}. */
	public static String column(int index)
	{
		return "At " + BoatSpeed.tilesPerTick(SPEEDS[index]);
	}

	/** The index in {@link #SPEEDS} of base speed {@code speed}, or -1 for a speed no hull gives. */
	public static int index(int speed)
	{
		for (int i = 0; i < SPEEDS.length; i++)
		{
			if (SPEEDS[i] == speed) return i;
		}
		return -1;
	}

	/** The leg's ticks at {@code SPEEDS[index]}, or {@link #NONE}. */
	public int ticks(int index)
	{
		return ticks[index];
	}

	/** The restricted areas the leg sails through at {@code SPEEDS[index]}. */
	public Set<SeaArea> areas(int index)
	{
		return areas[index];
	}

	/** The fewest ticks at any speed, or {@link #NONE} if it is sailed at none. */
	public int fastest()
	{
		int fastest = NONE;
		for (int tick : ticks)
		{
			if (tick != NONE && (fastest == NONE || tick < fastest)) fastest = tick;
		}
		return fastest;
	}

	/** Every area the leg sails through at any speed. */
	public Set<SeaArea> allAreas()
	{
		Set<SeaArea> all = EnumSet.noneOf(SeaArea.class);
		for (Set<SeaArea> through : areas) all.addAll(through);
		return all.isEmpty() ? SeaArea.NONE : Collections.unmodifiableSet(all);
	}

	@Override
	public String toString()
	{
		return Arrays.toString(ticks);
	}
}
