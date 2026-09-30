package shortestpath.pathfinder;

import java.util.List;
import lombok.Getter;

@Getter
public class PathfinderResult
{
	public static final int NO_PATH_COST = -1;

	private final int start;
	private final int target;
	private final boolean reached;
	private final List<PathStep> pathSteps;
	private final int closestReachedPoint;
	private final int pathCost;
	private final int nodesChecked;
	private final int transportsChecked;
	private final long elapsedNanos;
	private final PathTerminationReason terminationReason;
	private final String message;
	/**
	 * Ascending indices into {@link #getPathSteps()} of the tiles the player clicks to walk the
	 * route in game; empty when the backend does not compute them.
	 */
	private final List<Integer> clickPoints;

	public PathfinderResult(
		int start,
		int target,
		boolean reached,
		List<PathStep> pathSteps,
		int closestReachedPoint,
		int nodesChecked,
		int transportsChecked,
		long elapsedNanos,
		PathTerminationReason terminationReason)
	{
		this(
			start,
			target,
			reached,
			pathSteps,
			closestReachedPoint,
			NO_PATH_COST,
			nodesChecked,
			transportsChecked,
			elapsedNanos,
			terminationReason,
			null
		);
	}

	public PathfinderResult(
		int start,
		int target,
		boolean reached,
		List<PathStep> pathSteps,
		int closestReachedPoint,
		int pathCost,
		int nodesChecked,
		int transportsChecked,
		long elapsedNanos,
		PathTerminationReason terminationReason)
	{
		this(start, target, reached, pathSteps, closestReachedPoint, pathCost, nodesChecked,
			transportsChecked, elapsedNanos, terminationReason, null);
	}

	public PathfinderResult(
		int start,
		int target,
		boolean reached,
		List<PathStep> pathSteps,
		int closestReachedPoint,
		int pathCost,
		int nodesChecked,
		int transportsChecked,
		long elapsedNanos,
		PathTerminationReason terminationReason,
		String message)
	{
		this(start, target, reached, pathSteps, closestReachedPoint, pathCost, nodesChecked,
			transportsChecked, elapsedNanos, terminationReason, message, List.of());
	}

	public PathfinderResult(
		int start,
		int target,
		boolean reached,
		List<PathStep> pathSteps,
		int closestReachedPoint,
		int pathCost,
		int nodesChecked,
		int transportsChecked,
		long elapsedNanos,
		PathTerminationReason terminationReason,
		String message,
		List<Integer> clickPoints)
	{
		this.start = start;
		this.target = target;
		this.reached = reached;
		this.pathSteps = pathSteps;
		this.closestReachedPoint = closestReachedPoint;
		this.pathCost = pathCost;
		this.nodesChecked = nodesChecked;
		this.transportsChecked = transportsChecked;
		this.elapsedNanos = elapsedNanos;
		this.terminationReason = terminationReason;
		this.message = message;
		this.clickPoints = clickPoints;
	}
}
