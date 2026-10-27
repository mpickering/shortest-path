package shortestpath.pathfinder;

import java.util.List;
import java.util.Map;

/**
 * The plugin-facing handle for an active route search.
 */
public interface ActiveSearch extends Runnable
{
	void cancel();

	int getStart();

	/** Where the route may end, with the items each target needs on arrival. */
	Targets getTargets();

	/** How the targets resolved into the tiles the search finishes on. */
	TargetGoals getTargetGoals();

	/** The targets' item checks on top of {@link #getTargetGoals()}, or null for a search that makes none. */
	default GoalStates getGoalStates()
	{
		return null;
	}

	List<PathStep> getPath();

	boolean isDone();

	/**
	 * The engine's display name for the debug overlay — the render side reads
	 * it instead of casting to a concrete backend.
	 */
	String backendName();

	/**
	 * Whether the whole route is sailing: the search is the standalone
	 * {@code SailingPathfinder}, and every step after the first is a sailing
	 * move. A route that mixes walking and sailing answers {@code false}; its
	 * sailing steps say so themselves through {@link PathStep#getTraversal()},
	 * which is what drawing goes by.
	 */
	boolean isSailing();

	/**
	 * The requested target a boat on {@code boatTile} facing {@code heading}
	 * (one of the 16 headings, -1 unknown) has arrived at by the route's sailing
	 * arrival rule — its hull covers the target — or
	 * {@link shortestpath.WorldPointUtil#UNDEFINED} if none, or if the route
	 * does not end at sea.
	 */
	default int sailingArrivedTarget(int boatTile, int heading)
	{
		return shortestpath.WorldPointUtil.UNDEFINED;
	}

	/**
	 * Whether {@link #getPath()} is still the previous route shown while the
	 * new route computes ({@link AStarPathfinder#showUntilDone}); backends
	 * without a provisional-path mode report false.
	 */
	default boolean isShowingProvisionalPath()
	{
		return false;
	}

	/**
	 * The path indices the user clicked to build this route, when the backend
	 * records them; empty for backends that do not.
	 */
	default List<Integer> getClickPoints()
	{
		return List.of();
	}

	/**
	 * Backend-specific diagnostic lines for the debug overlay, as ordered
	 * label to display text; empty for backends with nothing to report.
	 */
	default Map<String, String> debugStats()
	{
		return Map.of();
	}

	PathfinderResult getResult();

	PathfinderStats getStats();
}
