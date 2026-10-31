package shortestpath.pathfinder.sailing;

import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * One sailing search's inputs: the boat's moves and hull, which way it faces where it sets out, and what to look for.
 * A search can look for two kinds of end: targets, which the boat reaches once its hull passes over one, and exits,
 * moorings it can dock at. Every move costs its ticks. Without an estimate, a search with exits orders its queue by
 * cost alone, so it reports them in cost order; one without them heads straight for its targets.
 * <p>
 * Where the boat sets out from is given to the solver ({@link SailingSolver#castOff}), not here. Made with
 * {@link #of} and the {@code with} methods, each of which returns a new query.
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

	private final SailingMoves moves;
	private final BoatHull hull;
	/** One of the 16 headings (0 south, 8 north) the boat faces where it sets out, or -1 when unknown. */
	private final int initialHeading;
	private final int[] targets;
	private final List<SailingMooring> exits;
	private final EdgeGate gate;
	/**
	 * A lower bound on the ticks from each boat tile to the end the caller wants, or {@code null}: with one, the search
	 * heads for it, even with exits, and leaves out tiles it answers {@link SailingSolver#NO_ROUTE} for.
	 */
	private final IntUnaryOperator estimate;
	/** The tiles a move may end on, or {@code null} for any the hull fits. */
	private final IntPredicate landing;

	private SailingQuery(SailingMoves moves, BoatHull hull, int initialHeading, int[] targets,
		List<SailingMooring> exits, EdgeGate gate, IntUnaryOperator estimate, IntPredicate landing)
	{
		if (moves == null || hull == null || targets == null || exits == null || gate == null)
			throw new NullPointerException();
		this.moves = moves;
		this.hull = hull;
		this.initialHeading = initialHeading;
		this.targets = targets;
		this.exits = exits;
		this.gate = gate;
		this.estimate = estimate;
		this.landing = landing;
	}

	/** A search for a boat with {@code moves} and {@code hull}, heading unknown, that looks for nothing yet. */
	public static SailingQuery of(SailingMoves moves, BoatHull hull)
	{
		return new SailingQuery(moves, hull, -1, new int[0], List.of(), EdgeGate.NONE, null, null);
	}

	/** This query with the boat facing {@code heading} where it sets out (-1 unknown). */
	public SailingQuery withHeading(int heading)
	{
		return new SailingQuery(moves, hull, heading, targets, exits, gate, estimate, landing);
	}

	/** This query looking for {@code targets}. */
	public SailingQuery withTargets(int[] targets)
	{
		return new SailingQuery(moves, hull, initialHeading, targets.clone(), exits, gate, estimate, landing);
	}

	/** This query looking for the moorings {@code exits}. */
	public SailingQuery withExits(List<SailingMooring> exits)
	{
		return new SailingQuery(moves, hull, initialHeading, targets, List.copyOf(exits), gate, estimate, landing);
	}

	/** This query with every move checked against {@code gate}. */
	public SailingQuery withGate(EdgeGate gate)
	{
		return new SailingQuery(moves, hull, initialHeading, targets, exits, gate, estimate, landing);
	}

	/** This query heading for its ends by {@code estimate}. */
	public SailingQuery withEstimate(IntUnaryOperator estimate)
	{
		return new SailingQuery(moves, hull, initialHeading, targets, exits, gate, estimate, landing);
	}

	/** This query with every move ending on a tile {@code landing} accepts. */
	public SailingQuery landingOn(IntPredicate landing)
	{
		return new SailingQuery(moves, hull, initialHeading, targets, exits, gate, estimate, landing);
	}

	/** Whether the queue is ordered towards the ends (an estimate, or no exits) or by cost alone. */
	boolean directed()
	{
		return estimate != null || exits.isEmpty();
	}
}
