package shortestpath.pathfinder;

import java.util.List;

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

	PathfinderResult getResult();

	PathfinderStats getStats();
}
