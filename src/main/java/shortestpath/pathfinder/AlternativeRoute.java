package shortestpath.pathfinder;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** A route distinct from a search's main route, ready to show like the main route's path. */
@AllArgsConstructor
@Getter
public final class AlternativeRoute
{
	private final List<PathStep> pathSteps;
	/** The route's cost, in the same terms as {@link PathfinderResult#getPathCost()}. */
	private final int pathCost;
	/** How many journeys it shares with the routes chosen before it. */
	private final int reusedJourneys;
	private final int nodesChecked;
	private final long searchNanos;
}
