package shortestpath.pathfinder.astar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;
import shortestpath.pathfinder.PathStep;

/**
 * Finds routes distinct from a first route: each search makes reusing a chosen route's journeys
 * cost as much as the whole budget, so within the budget it prefers the fewest reused journeys and
 * then the fastest route. A route is kept when its journeys differ from those of every chosen route.
 */
public final class AlternativeRoutes
{
	private static final int MIN_BUDGET = 20;
	private static final int BUDGET_PERCENT = 25;

	private AlternativeRoutes()
	{
	}

	/** How many ticks slower than the first route an alternative may be. */
	static int budget(int firstDuration)
	{
		return Math.max(MIN_BUDGET, (int) Math.min(CostArithmetic.INF, (firstDuration * (long) BUDGET_PERCENT + 99) / 100));
	}

	/**
	 * Up to {@code maxAlternatives} routes after {@code first}, most distinct first, stopping as soon
	 * as a search finds nothing new.
	 */
	public static List<Alternative> find(PreparedTarget target, int start, AStarRoute first, int maxAlternatives,
		SearchRestrictions restrictions, GoalTest goal, BooleanSupplier cancelled)
	{
		int firstDuration = first.cost(first.size() - 1);
		int budget = budget(firstDuration);
		int limit = CostArithmetic.add(firstDuration, budget);
		List<Set<Integer>> chosenSets = new ArrayList<>();
		Set<Integer> chosen = new HashSet<>(ReusePenalty.journeyLandings(first));
		chosenSets.add(Set.copyOf(chosen));
		List<Alternative> alternatives = new ArrayList<>();
		while (alternatives.size() < maxAlternatives && !chosen.isEmpty())
		{
			ReusePenalty penalty = new ReusePenalty(chosen, budget, limit);
			long started = System.nanoTime();
			AStarForwardSearch.Result result = AStarForwardSearch.search(target.overlay(), target.heuristic(), start,
				cancelled, false, 1, restrictions, AStarForwardSearch.NO_PROGRESS, goal, penalty);
			long nanos = System.nanoTime() - started;
			if (!result.reached()) break;
			Set<Integer> landings = ReusePenalty.journeyLandings(result.route());
			if (chosenSets.contains(landings)) break;
			int reused = 0;
			for (PathStep step : result.route().steps())
				reused += penalty.reuses(step.getTransport());
			alternatives.add(new Alternative(result.route(), reused, result.counters(), nanos));
			chosenSets.add(Set.copyOf(landings));
			chosen.addAll(landings);
		}
		return alternatives;
	}

	/** One route found, with how many of the routes chosen before it its journeys reuse. */
	@Getter
	@Accessors(fluent = true)
	@AllArgsConstructor
	public static final class Alternative
	{
		private final AStarRoute route;
		private final int reused;
		private final AStarForwardSearch.Counters counters;
		private final long searchNanos;

		public int duration()
		{
			return route.cost(route.size() - 1);
		}
	}
}
