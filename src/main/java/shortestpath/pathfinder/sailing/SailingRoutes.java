package shortestpath.pathfinder.sailing;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.PathStep;
import shortestpath.pathfinder.Traversal;
import shortestpath.transport.Transport;

/**
 * Fills in the sea between moorings on a route: the router takes each sailing leg as one transport step
 * ({@link SailingTransports}), and the player follows the boat's moves, so each leg is sailed again here, from the
 * berth to docking range of the next mooring, towards it. The search finds the leg the transport was timed by, as
 * it times every leg the same way.
 */
public final class SailingRoutes
{
	private SailingRoutes()
	{
	}

	/** Whether {@code path} has a sailing leg to fill in. */
	public static boolean hasLegs(List<PathStep> path)
	{
		for (PathStep step : path)
		{
			if (step.getTransport() != null && step.getTransport().getType().isSailing()) return true;
		}
		return false;
	}

	/**
	 * {@code path} with each sailing transport step preceded by its leg's moves, as {@link Traversal#SAILING}
	 * steps: the berth, then the boat's tile at the end of each move. The landing step keeps its transport.
	 */
	public static List<PathStep> expand(CollisionMap map, List<PathStep> path, BooleanSupplier cancelled)
	{
		if (!hasLegs(path)) return path;
		List<PathStep> expanded = new ArrayList<>(path.size() + 64);
		for (PathStep step : path)
		{
			Transport transport = step.getTransport();
			SailingMooring from = transport == null ? null : SailingTransports.from(transport);
			SailingMooring to = transport == null ? null : SailingTransports.to(transport);
			if (from != null && to != null)
			{
				SailingVessel vessel = SailingVessel.of(SailingTransports.kind(transport.getType()),
					SailingTransports.SPEED);
				SailingLeg leg = leg(map, vessel, from, to, cancelled);
				if (leg == null)
				{
					// Cancelled: berth to berth
					expanded.add(PathStep.sailing(from.berthTile(), step.getBankVisitState()));
					expanded.add(PathStep.sailing(to.berthTile(), step.getBankVisitState()));
				}
				else
				{
					for (int i = 0; i < leg.size(); i++)
						expanded.add(PathStep.sailing(leg.point(i), step.getBankVisitState()));
				}
			}
			expanded.add(step);
		}
		return expanded;
	}

	/** The leg a boat sails from {@code from}'s berth to docking at {@code to}, or {@code null} if it can't. */
	public static SailingLeg leg(CollisionMap map, SailingVessel vessel, SailingMooring from, SailingMooring to,
		BooleanSupplier cancelled)
	{
		SailingWaters waters = SailingWaters.of(map, SailingMoorings.all());
		BoatHull hull = vessel.hull(0, 0);
		SailingMoves moves = vessel.moves();
		double top = 0;
		for (int move = 0; move < moves.size(); move++)
			top = Math.max(top, Math.hypot(moves.dx(move), moves.dy(move)) / moves.ticks(move));
		double fastest = top;
		int bx = WorldPointUtil.unpackWorldX(to.berthTile()), by = WorldPointUtil.unpackWorldY(to.berthTile());
		// Straight to docking range at the boat's top speed: a bound that never decreases by more than a move's ticks
		// over a move, so tiles are settled at their least cost, as in the search the leg was timed by
		java.util.function.IntUnaryOperator estimate = tile ->
		{
			double distance = Math.hypot(WorldPointUtil.unpackWorldX(tile) - bx, WorldPointUtil.unpackWorldY(tile) - by);
			return (int) Math.floor(Math.max(0, distance - SailingMooring.DOCKING_RANGE) / fastest);
		};
		SailingQuery query = new SailingQuery(from.berthTile(), -1, moves, hull, SailingObjective.TICKS, new int[0],
			new int[0], List.of(to), SailingQuery.EdgeGate.NONE, estimate).landingOn(waters.forHull(map, hull)::navigable);
		SailingSolver solver = new SailingSolver(map, query);
		SailingLeg[] leg = new SailingLeg[1];
		try
		{
			solver.advance(Integer.MAX_VALUE, cancelled, new SailingSolver.Listener()
			{
				@Override
				public boolean exit(int exit, int node)
				{
					leg[0] = solver.leg(node, to.berthTile());
					return false;
				}
			});
		}
		finally
		{
			solver.release();
		}
		return leg[0];
	}
}
