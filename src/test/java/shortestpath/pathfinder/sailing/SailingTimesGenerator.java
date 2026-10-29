package shortestpath.pathfinder.sailing;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.SplitFlagMap;
import shortestpath.sailing.SeaArea;

/**
 * Writes the sailing data, from the collision map and the moorings: the collision map's sailing layer
 * ({@link SailingLayerBuilder}: the sea, and each boat kind's pieces of it), then the sailing transports
 * ({@link SailingTransports}): for each boat kind at {@link SailingTransports#SPEED}, the ticks from casting off at
 * each mooring to docking at every other mooring it can, from one search by cost over the whole sea per berth. Run it
 * with the {@code sailingTimes} task whenever the collision map, the moorings or the sailing moves change, before the
 * routing cuts (whose fingerprint covers the whole collision map); {@code SailingTransportsTest} says when the
 * committed data is stale.
 * <p>
 * Each berth is searched once per kind, with every restricted {@link SeaArea} the kind can be fitted for open, and
 * each row names the areas its leg sails through: for a boat with those parts at that speed the row's time is the
 * leg's, and for any other boat a bound on it, as {@link SailingTransports} says.
 */
public final class SailingTimesGenerator
{
	private SailingTimesGenerator()
	{
	}

	public static void main(String[] args) throws Exception
	{
		Path resources = Path.of(args.length > 0 ? args[0] : "src/main/resources");
		Path zipPath = resources.resolve("collision-map.zip");
		byte[] zip = Files.readAllBytes(zipPath);
		List<SailingMooring> moorings = SailingMoorings.all();
		long started = System.nanoTime();
		String fingerprint = SailingTransports.fingerprint(zip, moorings);
		int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
		SortedMap<String, byte[]> layer = SailingLayerBuilder.build(SplitFlagMap.fromZip(new ByteArrayInputStream(zip)),
			moorings, fingerprint, threads);
		byte[] written = SailingLayerBuilder.withLayer(zip, layer);
		Files.write(zipPath, written);
		System.err.printf("wrote %s (%d KB) at %.1f s%n", zipPath, written.length / 1024, (System.nanoTime() - started) / 1e9);

		SplitFlagMap flags = SplitFlagMap.fromZip(new ByteArrayInputStream(written));
		CollisionMap shared = new CollisionMap(flags);
		SailingWaters waters = SailingWaters.of(shared);
		// Moorings whose land tile is blocked can be neither boarded nor landed at
		List<SailingMooring> usable = new ArrayList<>();
		for (SailingMooring mooring : moorings)
		{
			int land = mooring.landTile();
			if (!shared.isBlocked(WorldPointUtil.unpackWorldX(land), WorldPointUtil.unpackWorldY(land),
				WorldPointUtil.unpackWorldPlane(land))) usable.add(mooring);
		}
		ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads / 2));
		try
		{
			for (SailingVessel.Kind kind : SailingVessel.Kind.values())
			{
				SailingVessel vessel = SailingVessel.of(kind, SailingTransports.SPEED);
				List<Future<String>> rows = new ArrayList<>();
				for (SailingMooring from : usable)
				{
					if (from.fits(vessel))
						rows.add(pool.submit(() -> legsFrom(new CollisionMap(flags), waters, vessel, from, usable)));
				}
				StringBuilder file = new StringBuilder(SailingTransports.header(fingerprint));
				for (Future<String> row : rows) file.append(row.get());
				Path out = resources.resolve(SailingTransports.resource(kind).substring(1));
				Files.writeString(out, file.toString(), StandardCharsets.UTF_8);
				System.err.printf("wrote %s at %.1f s%n", out, (System.nanoTime() - started) / 1e9);
			}
		}
		finally
		{
			pool.shutdownNow();
		}
	}

	// The rows for the moorings a boat leaving from's berth can dock at
	private static String legsFrom(CollisionMap map, SailingWaters waters, SailingVessel vessel, SailingMooring from,
		List<SailingMooring> moorings)
	{
		List<SailingMooring> exits = new ArrayList<>();
		for (SailingMooring to : moorings)
		{
			if (to != from && to.fits(vessel)) exits.add(to);
		}
		List<Object[]> legs = search(map, waters, vessel.withAreas(SeaArea.ALL), from, exits);
		StringBuilder rows = new StringBuilder("# " + from.name() + "\n");
		for (int i = 0; i < exits.size(); i++)
		{
			if (legs.get(i) == null) continue;
			SailingMooring to = exits.get(i);
			@SuppressWarnings("unchecked") Set<SeaArea> through = (Set<SeaArea>) legs.get(i)[0];
			rows.append(point(from.landTile())).append('\t').append(point(to.landTile())).append('\t')
				.append(skills(from, to)).append('\t').append(quests(from, to)).append('\t')
				.append(SeaArea.format(through)).append('\t')
				.append(SailingMooring.BOARD_TICKS + (int) legs.get(i)[1] + SailingMooring.DISEMBARK_TICKS).append('\t')
				.append(to.name()).append('\n');
		}
		return rows.toString();
	}

	/**
	 * One search by cost from where {@code vessel} casts off at {@code from}. Per exit, in order: the restricted areas
	 * its leg sails through and its ticks, or {@code null} if the boat can't dock there.
	 */
	private static List<Object[]> search(CollisionMap map, SailingWaters waters, SailingVessel vessel,
		SailingMooring from, List<SailingMooring> exits)
	{
		BoatHull hull = vessel.hull(0, 0);
		// The boat casts off anywhere in docking range of the berth it fits, facing any way; every move ends on sea its
		// hull fits, in the areas the boat may enter
		SailingQuery query = new SailingQuery(WorldPointUtil.UNDEFINED, -1, vessel.moves(), hull, SailingObjective.TICKS,
			new int[0], new int[0], exits, SailingQuery.EdgeGate.NONE).landingOn(waters.landing(map, vessel, hull));
		SailingSolver solver = new SailingSolver(map, query);
		solver.castOff(waters.castOff(map, from, vessel), 0);
		List<Object[]> legs = new ArrayList<>(java.util.Collections.nCopies(exits.size(), null));
		solver.advance(Integer.MAX_VALUE, () -> false, new SailingSolver.Listener()
		{
			@Override
			public boolean exit(int exit, int node)
			{
				if (legs.get(exit) != null) return true;
				SailingLeg leg = solver.leg(node, exits.get(exit).berthTile());
				Set<SeaArea> areas = java.util.EnumSet.noneOf(SeaArea.class);
				for (int i = 0; i < leg.size(); i++)
				{
					SeaArea area = waters.area(leg.point(i));
					if (area != null) areas.add(area);
				}
				legs.set(exit, new Object[] {areas, solver.cost(node)});
				return true;
			}
		});
		solver.release();
		return legs;
	}

	private static String point(int packed)
	{
		return WorldPointUtil.unpackWorldX(packed) + " " + WorldPointUtil.unpackWorldY(packed) + " "
			+ WorldPointUtil.unpackWorldPlane(packed);
	}

	private static String skills(SailingMooring from, SailingMooring to)
	{
		List<String> skills = new ArrayList<>();
		for (Skill skill : Skill.values())
		{
			int level = Math.max(from.requirements().getSkillLevels().get(skill),
				to.requirements().getSkillLevels().get(skill));
			if (level > 1) skills.add(level + " " + skill.getName());
		}
		return String.join(";", skills);
	}

	private static String quests(SailingMooring from, SailingMooring to)
	{
		Set<String> quests = new TreeSet<>();
		for (Quest quest : from.requirements().getQuests()) quests.add(quest.getName());
		for (Quest quest : to.requirements().getQuests()) quests.add(quest.getName());
		return String.join(";", quests);
	}
}
