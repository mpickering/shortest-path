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
import shortestpath.PrimitiveIntList;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.TestWorldData;
import shortestpath.sailing.SeaArea;

/**
 * Writes the sailing transports ({@link SailingTransports}): for each boat kind at {@link SailingTransports#SPEED},
 * the ticks from each mooring's berth to every other mooring it can dock at, from searches by cost over the whole sea
 * per berth; and the sea each kind reaches from them, in components ({@link SailingReach}). Run it with the
 * {@code sailingTimes} task whenever the collision map, the moorings or the sailing moves change;
 * {@code SailingTransportsTest} says when the committed files are stale.
 * <p>
 * Each berth's first search may enter every restricted {@link SeaArea}. Where the fastest legs sail through some, the
 * berth is searched again without each of those areas in turn, and so on: so for every set of parts a boat can have,
 * one of the rows is its fastest leg, and a row is kept only if no row needing fewer areas is as quick.
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
		SailingReach.Builder reach = new SailingReach.Builder(fingerprint);
		ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
		try
		{
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
					reach.addMooring(kind, (SailingMooring) legs[2], (int[]) legs[1]);
				}
				pockets(vessel.withAreas(SeaArea.ALL), reach);
				Path out = resources.resolve(SailingTransports.resource(kind).substring(1));
				Files.writeString(out, file.toString(), StandardCharsets.UTF_8);
				System.err.printf("wrote %s at %.1f s%n", out, (System.nanoTime() - started) / 1e9);
			}
		}
		finally
		{
			pool.shutdownNow();
		}
		Path out = resources.resolve(SailingReach.RESOURCE.substring(1));
		Files.write(out, reach.build().write());
		System.err.printf("wrote %s at %.1f s%n", out, (System.nanoTime() - started) / 1e9);
	}

	// The rows for the moorings a boat leaving from's berth can dock at, the tiles its moves end on with every area
	// open, and the mooring
	private static Object[] legsFrom(SailingVessel vessel, SailingMooring from, List<SailingMooring> moorings)
	{
		CollisionMap map = new CollisionMap(TestWorldData.map());
		SailingWaters waters = SailingWaters.of(map, SailingMoorings.all());
		List<SailingMooring> exits = new ArrayList<>();
		for (SailingMooring to : moorings)
		{
			if (to != from && to.fits(vessel)) exits.add(to);
		}
		// Per exit: the legs found, as the areas each sails through and its ticks
		List<Map<Set<SeaArea>, Integer>> legs = new ArrayList<>();
		for (int i = 0; i < exits.size(); i++) legs.add(new java.util.HashMap<>());
		PrimitiveIntList reached = new PrimitiveIntList(1 << 20);
		Set<Set<SeaArea>> searched = new java.util.HashSet<>();
		java.util.ArrayDeque<Set<SeaArea>> toSearch = new java.util.ArrayDeque<>();
		toSearch.add(SeaArea.ALL);
		while (!toSearch.isEmpty())
		{
			Set<SeaArea> open = toSearch.poll();
			if (!searched.add(open)) continue;
			Set<SeaArea> through = search(map, waters, vessel.withAreas(open), from, exits, legs,
				open.equals(SeaArea.ALL) ? reached : null);
			// The areas the fastest legs went through, each kept out in turn
			for (SeaArea area : through)
			{
				Set<SeaArea> fewer = java.util.EnumSet.noneOf(SeaArea.class);
				fewer.addAll(open);
				fewer.remove(area);
				toSearch.add(java.util.Collections.unmodifiableSet(fewer));
			}
		}
		StringBuilder rows = new StringBuilder("# " + from.name() + "\n");
		for (int i = 0; i < exits.size(); i++)
		{
			SailingMooring to = exits.get(i);
			List<Map.Entry<Set<SeaArea>, Integer>> found = new ArrayList<>(legs.get(i).entrySet());
			found.sort(java.util.Comparator.comparing((Map.Entry<Set<SeaArea>, Integer> leg) -> leg.getKey().size())
				.thenComparing(Map.Entry::getValue).thenComparing(leg -> SeaArea.format(leg.getKey())));
			List<Map.Entry<Set<SeaArea>, Integer>> kept = new ArrayList<>();
			for (Map.Entry<Set<SeaArea>, Integer> leg : found)
			{
				boolean beaten = false;
				for (Map.Entry<Set<SeaArea>, Integer> other : kept)
				{
					if (leg.getKey().containsAll(other.getKey()) && other.getValue() <= leg.getValue()) beaten = true;
				}
				if (!beaten) kept.add(leg);
			}
			for (Map.Entry<Set<SeaArea>, Integer> leg : kept)
			{
				rows.append(point(from.landTile())).append('\t').append(point(to.landTile())).append('\t')
					.append(skills(from, to)).append('\t').append(quests(from, to)).append('\t')
					.append(SeaArea.format(leg.getKey())).append('\t')
					.append(SailingMooring.BOARD_TICKS + leg.getValue() + SailingMooring.DISEMBARK_TICKS).append('\t')
					.append(to.name()).append('\n');
			}
		}
		int[] tiles = new int[reached.size()];
		for (int i = 0; i < tiles.length; i++) tiles[i] = reached.get(i);
		return new Object[] {rows.toString(), tiles, from};
	}

	/**
	 * The tiles reached from each pocket of water {@code vessel} can sail that no mooring's boat reaches: a search from
	 * a tile of the pocket, then from a tile no search has reached yet, until every tile the boat can end a move on has
	 * been reached by one. A pocket's search goes no further than the moorings' tiles: beyond them, what they reach.
	 */
	private static void pockets(SailingVessel vessel, SailingReach.Builder reach)
	{
		CollisionMap map = new CollisionMap(TestWorldData.map());
		SailingWaters waters = SailingWaters.of(map, SailingMoorings.all());
		BoatHull hull = vessel.hull(0, 0);
		java.util.function.IntPredicate landing = waters.landing(map, vessel, hull);
		PrimitiveIntList unreached = new PrimitiveIntList(1 << 16);
		waters.forEachTile(tile ->
		{
			if (landing.test(tile) && !reach.reached(vessel.kind(), tile)) unreached.add(tile);
		});
		int pockets = 0;
		for (int i = 0; i < unreached.size(); i++)
		{
			int start = unreached.get(i);
			if (reach.reached(vessel.kind(), start)) continue;
			PrimitiveIntList tiles = new PrimitiveIntList(64), edges = new PrimitiveIntList(16);
			tiles.add(start);
			SailingQuery query = new SailingQuery(start, -1, vessel.moves(), hull, SailingObjective.TICKS, new int[0],
				new int[0], List.of(), SailingQuery.EdgeGate.NONE).landingOn(tile ->
				{
					if (!landing.test(tile)) return false;
					if (!reach.reachedFromMooring(vessel.kind(), tile)) return true;
					// The moorings' water: what they reach, the pocket's boat reaches from here, so no further
					edges.add(tile);
					return false;
				});
			SailingSolver solver = new SailingSolver(map, query);
			solver.advance(Integer.MAX_VALUE, () -> false, new SailingSolver.Listener()
			{
				@Override
				public boolean settled(int node, int tile, int cost)
				{
					tiles.add(tile);
					return true;
				}
			});
			solver.release();
			int[] pocket = new int[tiles.size()];
			for (int t = 0; t < pocket.length; t++) pocket[t] = tiles.get(t);
			int[] came = new int[edges.size()];
			for (int t = 0; t < came.length; t++) came[t] = edges.get(t);
			reach.addPocket(vessel.kind(), pocket, came);
			pockets++;
		}
		System.err.printf("%s: %d pockets no mooring reaches, %d tiles%n", vessel.kind(), pockets, unreached.size());
	}

	/**
	 * One search by cost from {@code from}'s berth for {@code vessel}, adding each exit's leg to {@code legs} and, if
	 * asked, every tile a move ends on to {@code reached}. Returns the restricted areas the legs sailed through.
	 */
	private static Set<SeaArea> search(CollisionMap map, SailingWaters waters, SailingVessel vessel, SailingMooring from,
		List<SailingMooring> exits, List<Map<Set<SeaArea>, Integer>> legs, PrimitiveIntList reached)
	{
		BoatHull hull = vessel.hull(0, 0);
		// A moored boat sits at the middle of its berth, its heading unknown; every move ends on sea its hull fits, in
		// the areas the boat may enter
		SailingQuery query = new SailingQuery(from.berthTile(), -1, vessel.moves(), hull, SailingObjective.TICKS,
			new int[0], new int[0], exits, SailingQuery.EdgeGate.NONE).landingOn(waters.landing(map, vessel, hull));
		SailingSolver solver = new SailingSolver(map, query);
		boolean[] docked = new boolean[exits.size()];
		Set<SeaArea> through = java.util.EnumSet.noneOf(SeaArea.class);
		solver.advance(Integer.MAX_VALUE, () -> false, new SailingSolver.Listener()
		{
			@Override
			public boolean settled(int node, int tile, int cost)
			{
				if (reached != null) reached.add(tile);
				return true;
			}

			@Override
			public boolean exit(int exit, int node)
			{
				if (docked[exit]) return true;
				docked[exit] = true;
				SailingLeg leg = solver.leg(node, exits.get(exit).berthTile());
				Set<SeaArea> areas = java.util.EnumSet.noneOf(SeaArea.class);
				for (int i = 0; i < leg.size(); i++)
				{
					SeaArea area = waters.area(leg.point(i));
					if (area != null) areas.add(area);
				}
				Set<SeaArea> key = java.util.Collections.unmodifiableSet(areas);
				legs.get(exit).merge(key, solver.cost(node), Math::min);
				through.addAll(areas);
				return true;
			}
		});
		solver.release();
		return through;
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
