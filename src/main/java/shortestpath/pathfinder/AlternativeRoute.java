package shortestpath.pathfinder;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** A route distinct from a search's main route, ready to show like the main route. */
@AllArgsConstructor
@Getter
public final class AlternativeRoute
{
	private final PathfinderResult result;
	/** Indices into the route's steps of the tiles the player clicks, as {@link ActiveSearch#getClickPoints()}. */
	private final List<Integer> clickPoints;
	/** How many journeys it shares with the routes chosen before it. */
	private final int reusedJourneys;
	/** How much more it costs than the search's own route. */
	private final int extraCost;
	private final long searchNanos;

	public List<PathStep> getPathSteps()
	{
		return result.getPathSteps();
	}

	/** The route's cost, in the same terms as {@link PathfinderResult#getPathCost()}. */
	public int getPathCost()
	{
		return result.getPathCost();
	}

	public int getNodesChecked()
	{
		return result.getNodesChecked();
	}
}
