package shortestpath.sailing;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The restricted sea areas: water a boat enters only with a part fitted. Everywhere else at sea is open water. Each
 * area is a family of sailing water textures in the game cache; the collision map marks the edges where one area
 * meets another, and {@code sailing/sea-areas.tsv} names the area of each piece of sea they enclose (both written by
 * the collision map dumper). Seas have no entry level: only the boat's parts decide where it may go.
 * <p>
 * The levels are those the part is built at (Sailing, then Construction), from the OSRS Wiki's Sailing hazards.
 */
public enum SeaArea
{
	TEMPOR_TANTRUM("Tempor Tantrum", "a stormy mast", 24, 11),
	FETID("fetid water", "an inoculation station", 40, 37),
	CRYSTALLINE("crystal-flecked water", "an adamant keel", 66, 62),
	KELP("tangled kelp", "an adamant helm", 72, 59),
	NORTHERN("the northern icy waters", "an eternal brazier", 78, 72);

	/** No restricted area: open water only. */
	public static final Set<SeaArea> NONE = Collections.unmodifiableSet(EnumSet.noneOf(SeaArea.class));
	/** Every restricted area. */
	public static final Set<SeaArea> ALL = Collections.unmodifiableSet(EnumSet.allOf(SeaArea.class));

	private final String label;
	private final String part;
	private final int sailingLevel;
	private final int constructionLevel;

	SeaArea(String label, String part, int sailingLevel, int constructionLevel)
	{
		this.label = label;
		this.part = part;
		this.sailingLevel = sailingLevel;
		this.constructionLevel = constructionLevel;
	}

	/** The area as players call it. */
	public String label()
	{
		return label;
	}

	/** The part a boat needs fitted to enter. */
	public String part()
	{
		return part;
	}

	/** The Sailing level the part is built at. */
	public int sailingLevel()
	{
		return sailingLevel;
	}

	/** The Construction level the part is built at. */
	public int constructionLevel()
	{
		return constructionLevel;
	}

	/** The areas whose parts a player at these levels can build: the best fitting a boat can have. */
	public static Set<SeaArea> buildable(int sailingLevel, int constructionLevel)
	{
		Set<SeaArea> areas = EnumSet.noneOf(SeaArea.class);
		for (SeaArea area : values())
		{
			if (sailingLevel >= area.sailingLevel && constructionLevel >= area.constructionLevel) areas.add(area);
		}
		return Collections.unmodifiableSet(areas);
	}

	/** {@code areas} as a transport file writes them: names separated by {@code ;}, empty for none. */
	public static String format(Set<SeaArea> areas)
	{
		StringBuilder out = new StringBuilder();
		for (SeaArea area : values())
		{
			if (!areas.contains(area)) continue;
			if (out.length() > 0) out.append(';');
			out.append(area.name());
		}
		return out.toString();
	}

	/** The areas a transport file's field names; see {@link #format}. */
	public static Set<SeaArea> parse(String field)
	{
		if (field == null || field.trim().isEmpty()) return NONE;
		Set<SeaArea> areas = EnumSet.noneOf(SeaArea.class);
		for (String name : field.split(";"))
		{
			if (!name.trim().isEmpty()) areas.add(valueOf(name.trim()));
		}
		return Collections.unmodifiableSet(areas);
	}
}
