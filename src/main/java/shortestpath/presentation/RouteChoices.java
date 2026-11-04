package shortestpath.presentation;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import shortestpath.pathfinder.ActiveSearch;
import shortestpath.pathfinder.AlternativeRoute;
import shortestpath.pathfinder.BankVisitState;
import shortestpath.pathfinder.PathStep;
import shortestpath.pathfinder.astar.ReusePenalty;
import shortestpath.transport.Transport;

/** How a search's routes read to the player. */
public final class RouteChoices
{
	private RouteChoices()
	{
	}

	/** One route as the sidebar lists it. */
	@AllArgsConstructor
	@Getter
	public static final class Choice
	{
		private final int route;
		private final String title;
		private final String ticks;
		/** The route's journeys and bank visits in the order they are taken. */
		private final String journeys;
		private final boolean selected;
	}

	/** The routes to choose between, in the search's order; empty unless it has alternatives. */
	public static List<Choice> choices(ActiveSearch search)
	{
		List<AlternativeRoute> alternatives = search == null ? List.of() : search.getAlternatives();
		if (alternatives.isEmpty())
		{
			return List.of();
		}
		int selected = search.getSelectedRoute();
		AlternativeRoute any = alternatives.get(0);
		List<Choice> choices = new ArrayList<>();
		choices.add(new Choice(0, "Fastest", (any.getPathCost() - any.getExtraCost()) + " ticks",
			journeys(search.getRoutePath(0)), selected == 0));
		for (int i = 0; i < alternatives.size(); i++)
		{
			AlternativeRoute alternative = alternatives.get(i);
			choices.add(new Choice(i + 1, "+" + alternative.getExtraCost() + " ticks", alternative.getPathCost() + " ticks",
				journeys(alternative.getPathSteps()), selected == i + 1));
		}
		return choices;
	}

	static String journeys(List<PathStep> path)
	{
		List<String> parts = new ArrayList<>();
		BankVisitState layer = BankVisitState.CARRIED;
		for (PathStep step : path)
		{
			if (step.getBankVisitState() != layer)
			{
				layer = step.getBankVisitState();
				parts.add("Bank");
			}
			Transport transport = step.getTransport();
			if (transport != null && ReusePenalty.isJourney(transport))
			{
				parts.add(label(transport));
			}
		}
		return parts.isEmpty() ? "Walk" : String.join(" > ", parts);
	}

	private static String label(Transport transport)
	{
		String info = transport.getDisplayInfo();
		if (info != null && !info.isEmpty())
		{
			return info;
		}
		String object = transport.getObjectInfo();
		// Object info reads "menuOption menuTarget objectID"; the id means nothing to the player.
		return object != null && !object.isEmpty() ? object.replaceAll(" \\d+$", "")
			: transport.getType().name().toLowerCase().replace('_', ' ');
	}

	/** What the next-route hotkey says it did: which route is showing, or why there is only one. */
	public static String describeSelected(ActiveSearch search)
	{
		List<AlternativeRoute> alternatives = search.getAlternatives();
		if (alternatives.isEmpty())
		{
			return search.isAlternativesLoaded() ? "No alternative routes for this path."
				: "Still looking for alternative routes.";
		}
		int selected = search.getSelectedRoute();
		String route = "Route " + (selected + 1) + " of " + (alternatives.size() + 1) + ": ";
		if (selected == 0)
		{
			return route + "fastest, " + search.getResult().getPathCost() + " ticks.";
		}
		AlternativeRoute alternative = alternatives.get(selected - 1);
		return route + alternative.getPathCost() + " ticks, +" + alternative.getExtraCost() + ".";
	}
}
