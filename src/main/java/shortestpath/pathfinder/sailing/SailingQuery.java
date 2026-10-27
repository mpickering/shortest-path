package shortestpath.pathfinder.sailing;

import java.util.List;
import lombok.Getter;
import lombok.experimental.Accessors;
import shortestpath.WorldPointUtil;

/**
 * One sailing search's inputs: where the boat starts and which way it faces, the boat, what to minimise, and what to
 * look for. A search can look for three kinds of end: targets, which the boat reaches once its hull covers one; goal
 * tiles, which the boat must sail onto; and exits, moorings it can dock at. Without an estimate, a search with exits
 * orders its queue by cost alone, so it reports them in cost order; one without them heads for its targets.
 */
@Getter
@Accessors(fluent = true)
public final class SailingQuery
{
	/** A step the search may not take, from one packed tile to another. */
	@FunctionalInterface
	public interface EdgeGate
	{
		EdgeGate NONE = (from, to) -> true;

		boolean allowed(int from, int to);
	}

	/** Where the boat starts, or {@link WorldPointUtil#UNDEFINED} for a search fed by {@link SailingSolver#addSource}. */
	private final int start;
	/** One of the 16 headings (0 south, 8 north), or -1 when unknown. */
	private final int initialHeading;
	private final SailingMoves moves;
	/** The hull every move must fit, or {@code null} for the boat's centre alone. */
	private final BoatHull hull;
	private final SailingObjective objective;
	private final int[] targets;
	private final int[] goals;
	private final List<SailingMooring> exits;
	private final EdgeGate gate;
	/**
	 * A lower bound on the cost from each boat tile to the end the caller wants, or {@code null}: with one, the search
	 * heads for it, even with exits, and leaves out tiles it answers {@link SailingSolver#NO_ROUTE} for.
	 */
	private final java.util.function.IntUnaryOperator estimate;
	/** The tiles a move may end on, or {@code null} for any the hull fits. */
	private final java.util.function.IntPredicate landing;

	public SailingQuery(int start, int initialHeading, SailingMoves moves, BoatHull hull, SailingObjective objective,
		int[] targets, int[] goals, List<SailingMooring> exits, EdgeGate gate)
	{
		this(start, initialHeading, moves, hull, objective, targets, goals, exits, gate, null);
	}

	public SailingQuery(int start, int initialHeading, SailingMoves moves, BoatHull hull, SailingObjective objective,
		int[] targets, int[] goals, List<SailingMooring> exits, EdgeGate gate,
		java.util.function.IntUnaryOperator estimate)
	{
		this(start, initialHeading, moves, hull, objective, targets, goals, exits, gate, estimate, null);
	}

	private SailingQuery(int start, int initialHeading, SailingMoves moves, BoatHull hull, SailingObjective objective,
		int[] targets, int[] goals, List<SailingMooring> exits, EdgeGate gate,
		java.util.function.IntUnaryOperator estimate, java.util.function.IntPredicate landing)
	{
		if (moves == null || objective == null || targets == null || goals == null || exits == null || gate == null)
			throw new NullPointerException();
		this.start = start;
		this.initialHeading = initialHeading;
		this.moves = moves;
		this.hull = hull;
		this.objective = objective;
		this.targets = targets.clone();
		this.goals = goals.clone();
		this.exits = List.copyOf(exits);
		this.gate = gate;
		this.estimate = estimate;
		this.landing = landing;
	}

	/** This query, with every move ending on a tile {@code landing} accepts. */
	public SailingQuery landingOn(java.util.function.IntPredicate landing)
	{
		return new SailingQuery(start, initialHeading, moves, hull, objective, targets, goals, exits, gate, estimate,
			landing);
	}

	/** Whether the queue is ordered towards the targets (an estimate, or no exits) or by cost alone. */
	boolean directed()
	{
		return estimate != null || exits.isEmpty();
	}
}
