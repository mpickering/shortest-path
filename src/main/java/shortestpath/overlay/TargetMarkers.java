package shortestpath.overlay;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.ActiveSearch;
import shortestpath.pathfinder.GoalStates;
import shortestpath.pathfinder.TargetGoals;
import shortestpath.pathfinder.TargetStatus;

/**
 * The "Targets and goals" debug layer's markers: each requested target, coloured by how it
 * resolved, and the fallback goal tiles a blocked target expanded to, with the tooltip rows that
 * explain them. Built from the active search, shared by the map and game view.
 */
final class TargetMarkers
{
	static final Color COLOUR_TARGET = new Color(0, 220, 0);
	static final Color COLOUR_TARGET_BLOCKED = Color.YELLOW;
	static final Color COLOUR_TARGET_UNREACHABLE = Color.RED;
	static final Color COLOUR_TARGET_SET_ASIDE = Color.GRAY;
	static final Color COLOUR_GOAL = Color.CYAN;

	static final class Marker
	{
		final int point;
		final boolean target;
		final Color colour;
		final List<String> rows;
		/** The goal tiles of a blocked target; empty for goals and other targets. */
		final Set<Integer> goals;

		private Marker(int point, boolean target, Color colour, List<String> rows, Set<Integer> goals)
		{
			this.point = point;
			this.target = target;
			this.colour = colour;
			this.rows = rows;
			this.goals = goals;
		}
	}

	private TargetMarkers()
	{
	}

	/**
	 * The markers for {@code search}'s targets and goals, plus {@code setAside}: targets the
	 * config filtered out of the request (e.g. in the wilderness). Targets come first.
	 */
	static List<Marker> collect(ActiveSearch search, Set<Integer> setAside)
	{
		List<Marker> markers = new ArrayList<>();
		Map<Integer, List<Integer>> goalOwners = new LinkedHashMap<>();
		if (search != null)
		{
			TargetGoals tiles = search.getTargetGoals();
			GoalStates states = search.getGoalStates();
			for (int target : search.getTargets().points())
			{
				Set<Integer> goals = tiles.fallbacks(target);
				for (int goal : goals)
				{
					goalOwners.computeIfAbsent(goal, g -> new ArrayList<>(1)).add(target);
				}
				List<String> rows = new ArrayList<>();
				rows.add("Target " + format(target));
				Color colour;
				if (!tiles.isViable(target))
				{
					colour = COLOUR_TARGET_UNREACHABLE;
					rows.add("Blocked, with no goal tile nearby");
				}
				else if (!tiles.isBlocked(target))
				{
					colour = COLOUR_TARGET;
					rows.add("Walkable");
				}
				else
				{
					colour = goals.isEmpty() ? COLOUR_TARGET : COLOUR_TARGET_BLOCKED;
					rows.add(goals.isEmpty() ? "Blocked, but a transport lands on it"
						: "Blocked, resolved to " + goals.size() + " goal tile" + (goals.size() == 1 ? "" : "s"));
				}
				if (states != null)
				{
					String items = items(states.status(target), search.getTargets().get(target).hasRequiredItems());
					if (items != null)
					{
						rows.add(items);
					}
					if (!states.finishesRoute(target))
					{
						rows.add("Not used: another target can be completed");
					}
				}
				markers.add(new Marker(target, true, colour, rows, goals));
			}
		}
		for (int target : setAside)
		{
			List<String> rows = List.of("Target " + format(target), "Set aside: in the wilderness");
			markers.add(new Marker(target, true, COLOUR_TARGET_SET_ASIDE, rows, Set.of()));
		}
		for (Map.Entry<Integer, List<Integer>> entry : goalOwners.entrySet())
		{
			List<String> rows = new ArrayList<>();
			rows.add("Goal " + format(entry.getKey()));
			for (int owner : entry.getValue())
			{
				rows.add("for target " + format(owner));
			}
			markers.add(new Marker(entry.getKey(), false, COLOUR_GOAL, rows, Set.of()));
		}
		return markers;
	}

	private static String items(TargetStatus status, boolean hasRequiredItems)
	{
		if (status == null || !hasRequiredItems)
		{
			return null;
		}
		switch (status)
		{
			case CARRIED:
				return "Items: carried";
			case BANK:
				return "Items: in the bank, so the route banks first";
			case UNKNOWN:
				return "Items: missing";
			default:
				return null;
		}
	}

	static String format(int point)
	{
		WorldPoint p = WorldPointUtil.unpackWorldPoint(point);
		return p.getX() + ", " + p.getY() + ", " + p.getPlane();
	}
}
