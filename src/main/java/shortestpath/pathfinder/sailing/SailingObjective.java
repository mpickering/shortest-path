package shortestpath.pathfinder.sailing;

/**
 * What a sailing search minimises.
 */
public enum SailingObjective
{
	/**
	 * The distance sailed ({@link SailingMoves#length}), plus a thousandth of a tile per change of heading so that of
	 * the equally short routes the one with the fewest, longest legs wins. The standalone sailing search's objective.
	 */
	DISTANCE,
	/**
	 * Game ticks ({@link SailingMoves#ticks}), the unit every other route edge is priced in, so a sailing leg composes
	 * with walking and transports. No turn penalty: it would not be a tick.
	 */
	TICKS
}
