package shortestpath.pathfinder.sailing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Quest;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.requirement.model.DestinationRequirements;
import shortestpath.requirement.model.SkillLevels;
import shortestpath.transport.Transport;
import shortestpath.transport.parser.SkillRequirementParser;
import shortestpath.transport.parser.WorldPointParser;

/**
 * The mooring points, loaded from {@code /sailing/moorings.tsv}; see that file for where each column comes from. The
 * loader fails on any unknown quest or boat name rather than dropping a requirement.
 */
public final class SailingMoorings
{
	public static final String RESOURCE = "/sailing/moorings.tsv";
	private static volatile List<SailingMooring> resource;

	private SailingMoorings()
	{
	}

	/** The moorings in the plugin's resource, loaded once. */
	public static List<SailingMooring> all()
	{
		List<SailingMooring> loaded = resource;
		if (loaded == null)
		{
			synchronized (SailingMoorings.class)
			{
				if (resource == null)
				{
					try (InputStream in = SailingMoorings.class.getResourceAsStream(RESOURCE))
					{
						if (in == null) throw new IllegalStateException("missing " + RESOURCE);
						resource = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
					}
					catch (IOException error)
					{
						throw new IllegalStateException("cannot read " + RESOURCE, error);
					}
				}
				loaded = resource;
			}
		}
		return loaded;
	}

	/**
	 * The land tiles of the moorings that are walkable on {@code map}: where a boat can put the player ashore. A
	 * mooring whose land tile is blocked (Tear of the Soul and Wintumber Island, whose island land the collision map
	 * lacks) can't be landed at.
	 */
	public static int[] walkableLandTiles(CollisionMap map)
	{
		return all().stream().mapToInt(SailingMooring::landTile)
			.filter(tile -> !map.isBlocked(WorldPointUtil.unpackWorldX(tile), WorldPointUtil.unpackWorldY(tile),
				WorldPointUtil.unpackWorldPlane(tile)))
			.toArray();
	}

	static List<SailingMooring> parse(String contents)
	{
		WorldPointParser points = new WorldPointParser();
		SkillRequirementParser skills = new SkillRequirementParser();
		List<SailingMooring> moorings = new ArrayList<>();
		String[] header = null;
		for (String line : contents.split("\n"))
		{
			if (line.isBlank()) continue;
			if (line.startsWith("#"))
			{
				String text = line.substring(1).trim();
				if (text.startsWith("Name\t")) header = text.split("\t", -1);
				continue;
			}
			if (header == null) throw new IllegalStateException(RESOURCE + " has no header");
			String[] fields = line.split("\t", -1);
			String name = field(header, fields, "Name");
			int land = points.parse(field(header, fields, "Land"));
			int berth = points.parse(field(header, fields, "Berth"));
			if (land == Transport.LOCATION_PERMUTATION || berth == Transport.LOCATION_PERMUTATION)
				throw new IllegalStateException(RESOURCE + ": " + name + " has no land or berth tile");
			SkillLevels levels = skills.parse(field(header, fields, "Skills"));
			DestinationRequirements requirements = new DestinationRequirements(levels,
				quests(field(header, fields, "Quests")), null, null);
			String dock = field(header, fields, "Dock");
			moorings.add(new SailingMooring(moorings.size(), name, land, berth, requirements,
				quests(field(header, fields, "Quests started")), vessels(field(header, fields, "Vessels")),
				dock.isEmpty() ? -1 : Integer.parseInt(dock)));
		}
		return List.copyOf(moorings);
	}

	private static String field(String[] header, String[] fields, String column)
	{
		for (int i = 0; i < header.length; i++)
		{
			if (header[i].equals(column)) return i < fields.length ? fields[i].trim() : "";
		}
		throw new IllegalStateException(RESOURCE + " has no " + column + " column");
	}

	private static Set<Quest> quests(String value)
	{
		Set<Quest> quests = new HashSet<>();
		if (value.isEmpty()) return quests;
		for (String name : value.split(";"))
		{
			Quest found = null;
			for (Quest quest : Quest.values())
			{
				if (quest.getName().equals(name.trim())) found = quest;
			}
			if (found == null) throw new IllegalStateException(RESOURCE + ": unknown quest " + name);
			quests.add(found);
		}
		return quests;
	}

	private static Set<SailingVessel.Kind> vessels(String value)
	{
		Set<SailingVessel.Kind> kinds = EnumSet.noneOf(SailingVessel.Kind.class);
		if (value.isEmpty()) return kinds;
		for (String name : value.split(","))
		{
			kinds.add(SailingVessel.Kind.valueOf(name.trim()));
		}
		return kinds;
	}
}
