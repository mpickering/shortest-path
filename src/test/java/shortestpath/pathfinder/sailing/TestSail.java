package shortestpath.pathfinder.sailing;

import java.util.ArrayList;
import java.util.List;
import shortestpath.pathfinder.BankVisitState;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.PathStep;

/** Sails one leg in a test: from a boat tile to the first target its hull passes over, by ticks. */
final class TestSail
{
	private TestSail()
	{
	}

	/**
	 * The boat's tile at the start and at the end of each move, from {@code start} facing {@code heading} (-1 unknown)
	 * until its hull passes over {@code target}, or {@code null} if it can't.
	 */
	static List<PathStep> sail(CollisionMap map, SailingMoves moves, BoatHull hull, int heading, int start, int target)
	{
		SailingSolver solver = new SailingSolver(map, SailingQuery.of(moves, hull).withHeading(heading)
			.withTargets(new int[] {target}));
		solver.castOff(new int[] {start}, 0);
		SailingLeg[] leg = new SailingLeg[1];
		solver.advance(Integer.MAX_VALUE, () -> false, new SailingSolver.Listener()
		{
			@Override
			public boolean target(int reached, int node)
			{
				leg[0] = solver.leg(node, reached);
				return false;
			}
		});
		solver.release();
		if (leg[0] == null) return null;
		List<PathStep> path = new ArrayList<>();
		for (int i = 0; i < leg[0].size(); i++) path.add(PathStep.sailing(leg[0].point(i), BankVisitState.CARRIED));
		return path;
	}
}
