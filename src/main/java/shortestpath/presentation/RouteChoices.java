package shortestpath.presentation;

import java.util.List;
import shortestpath.pathfinder.ActiveSearch;
import shortestpath.pathfinder.AlternativeRoute;

/** How a search's routes read to the player. */
public final class RouteChoices
{
	private RouteChoices()
	{
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
