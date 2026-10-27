package shortestpath.pathfinder.sailing;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import shortestpath.PrimitiveIntHashMap;
import shortestpath.PrimitiveIntList;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.TestWorldData;

/**
 * Writes the sailing transports ({@link SailingTransports}): for each boat kind at {@link SailingTransports#SPEED},
 * the ticks from each mooring's berth to every other mooring it can dock at, from one search by cost over the whole
 * sea per berth; and the sea each kind reaches from them all ({@link SailingReach}). Run it with the {@code sailingTimes} task whenever the collision map, the moorings or the sailing
 * moves change; {@code SailingTransportsTest} says when the committed files are stale.
 */
public final class SailingTimesGenerator
{
	private SailingTimesGenerator()
	{
	}

	public static void main(String[] args) throws Exception
	{
		Path resources = Path.of(args.length > 0 ? args[0] : "src/main/resources");
		List<SailingMooring> moorings = SailingMoorings.all();
		CollisionMap shared = new CollisionMap(TestWorldData.map());
		// Moorings whose land tile is blocked can be neither boarded nor landed at
		List<SailingMooring> usable = new ArrayList<>();
		for (SailingMooring mooring : moorings)
		{
			int land = mooring.landTile();
			if (!shared.isBlocked(WorldPointUtil.unpackWorldX(land), WorldPointUtil.unpackWorldY(land),
				WorldPointUtil.unpackWorldPlane(land))) usable.add(mooring);
		}
		// The sea and each hull's part of it, made once before the searches share them
		SailingWaters.prepare(shared);
		String fingerprint = SailingTransports.fingerprint(moorings);
		long started = System.nanoTime();
		Map<SailingVessel.Kind, PrimitiveIntHashMap<long[]>> reach = new EnumMap<>(SailingVessel.Kind.class);
		ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
		for (SailingVessel.Kind kind : SailingVessel.Kind.values())
		{
			SailingVessel vessel = SailingVessel.of(kind, SailingTransports.SPEED);
			List<Future<Object[]>> rows = new ArrayList<>();
			for (SailingMooring from : usable)
			{
				if (from.fits(vessel)) rows.add(pool.submit(() -> legsFrom(vessel, from, usable)));
			}
			StringBuilder file = new StringBuilder(SailingTransports.header(fingerprint));
			for (Future<Object[]> row : rows)
			{
				Object[] legs = row.get();
				file.append((String) legs[0]);
				int[] reached = (int[]) legs[1];
				for (int tile : reached) SailingReach.mark(reach, kind, tile);
			}
			Path out = resources.resolve(SailingTransports.resource(kind).substring(1));
			Files.writeString(out, file.toString(), StandardCharsets.UTF_8);
			System.err.printf("wrote %s at %.1f s%n", out, (System.nanoTime() - started) / 1e9);
		}
		pool.shutdown();
		java.util.TreeSet<Integer> keys = new java.util.TreeSet<>();
		for (PrimitiveIntHashMap<long[]> regions : reach.values()) for (int key : regions.keys()) keys.add(key);
		Path out = resources.resolve(SailingReach.RESOURCE.substring(1));
		Files.write(out, SailingReach.write(fingerprint, reach, keys.stream().mapToInt(Integer::intValue).toArray()));
		System.err.printf("wrote %s at %.1f s%n", out, (System.nanoTime() - started) / 1e9);
	}

	// One row per mooring a boat leaving from's berth can dock at, and the tiles the boat's moves end on
	private static Object[] legsFrom(SailingVessel vessel, SailingMooring from, List<SailingMooring> moorings)
	{
		CollisionMap map = new CollisionMap(TestWorldData.map());
		SailingWaters waters = SailingWaters.of(map, SailingMoorings.all());
		List<SailingMooring> exits = new ArrayList<>();
		for (SailingMooring to : moorings)
		{
			if (to != from && to.fits(vessel)) exits.add(to);
		}
		BoatHull hull = vessel.hull(0, 0);
		// A moored boat sits at the middle of its berth, its heading unknown; every move ends on sea its hull fits
		SailingQuery query = new SailingQuery(from.berthTile(), -1, vessel.moves(), hull, SailingObjective.TICKS,
			new int[0], new int[0], exits, SailingQuery.EdgeGate.NONE).landingOn(waters.forHull(map, hull)::navigable);
		SailingSolver solver = new SailingSolver(map, query);
		int[] ticks = new int[exits.size()];
		Arrays.fill(ticks, -1);
		PrimitiveIntList reached = new PrimitiveIntList(1 << 20);
		solver.advance(Integer.MAX_VALUE, () -> false, new SailingSolver.Listener()
		{
			@Override
			public boolean settled(int node, int tile, int cost)
			{
				reached.add(tile);
				return true;
			}

			@Override
			public boolean exit(int exit, int node)
			{
				if (ticks[exit] < 0) ticks[exit] = solver.cost(node);
				return true;
			}
		});
		solver.release();
		StringBuilder rows = new StringBuilder("# " + from.name() + "\n");
		for (int i = 0; i < exits.size(); i++)
		{
			if (ticks[i] < 0) continue;
			SailingMooring to = exits.get(i);
			rows.append(point(from.landTile())).append('\t').append(point(to.landTile())).append('\t')
				.append(skills(from, to)).append('\t').append(quests(from, to)).append('\t')
				.append(SailingMooring.BOARD_TICKS + ticks[i] + SailingMooring.DISEMBARK_TICKS).append('\t')
				.append(to.name()).append('\n');
		}
		int[] tiles = new int[reached.size()];
		for (int i = 0; i < tiles.length; i++) tiles[i] = reached.get(i);
		return new Object[] {rows.toString(), tiles};
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
