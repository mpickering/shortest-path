package shortestpath.pathfinder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import shortestpath.WorldPointUtil;

public class InGameWalkRewriterTest
{
	private static final int BASE_X = 3200, BASE_Y = 3200;

	@Test
	public void walkingFollowsTheGameExpansionOrder()
	{
		OpenGrid grid = new OpenGrid(Set.of());
		// Diagonal first, as a route search may choose; the game walks east first, then diagonally.
		List<PathStep> route = steps(0, 0, 1, 1, 2, 1);

		InGameWalkRewriter.Result result = new InGameWalkRewriter(grid).rewrite(route);

		assertEquals(describe(steps(0, 0, 1, 0, 2, 1)), describe(result.path()));
		assertEquals(List.of(2), result.clickPoints());
	}

	@Test
	public void longLegsAreSplitIntoClicksWithinReach()
	{
		OpenGrid grid = new OpenGrid(Set.of());
		List<PathStep> route = new ArrayList<>();
		for (int x = 0; x <= 40; x++) route.add(step(x, 0));

		InGameWalkRewriter.Result result = new InGameWalkRewriter(grid, 15).rewrite(route);

		assertEquals(describe(route), describe(result.path()));
		assertEquals(List.of(15, 30, 40), result.clickPoints());
	}

	@Test
	public void clicksStayOnPathsNoLongerThanTheRoute()
	{
		// A wall at x = 5 from y = -10..10 forces the route around its north end.
		Set<Integer> wall = new HashSet<>();
		for (int y = -10; y <= 10; y++) wall.add(pack(5, y));
		OpenGrid grid = new OpenGrid(wall);
		List<PathStep> route = new ArrayList<>();
		for (int y = 0; y <= 11; y++) route.add(step(4, y));
		route.add(step(5, 11));
		for (int y = 11; y >= 0; y--) route.add(step(6, y));

		InGameWalkRewriter.Result result = new InGameWalkRewriter(grid, 15).rewrite(route);

		assertWalkable(grid, result.path());
		assertEquals(route.size(), result.path().size());
		assertEquals(describe(route.subList(route.size() - 1, route.size())),
			describe(result.path().subList(result.path().size() - 1, result.path().size())));
		List<String> routeTiles = describe(route);
		for (int click : result.clickPoints())
		{
			assertTrue(routeTiles.contains(describe(result.path().subList(click, click + 1)).get(0)));
		}
	}

	@Test
	public void suboptimalWalksAreShortened()
	{
		OpenGrid grid = new OpenGrid(Set.of());
		List<PathStep> route = steps(0, 0, 0, 1, 1, 1, 2, 1, 2, 0, 3, 0);

		InGameWalkRewriter.Result result = new InGameWalkRewriter(grid).rewrite(route);

		assertEquals(describe(steps(0, 0, 1, 0, 2, 0, 3, 0)), describe(result.path()));
		assertEquals(List.of(3), result.clickPoints());
	}

	@Test
	public void transportsAndBankStepsAreKept()
	{
		OpenGrid grid = new OpenGrid(Set.of());
		List<PathStep> route = new ArrayList<>(steps(0, 0, 1, 1, 2, 1));
		// Bank at (2,1), then teleport far away and walk on.
		route.add(new PathStep(pack(2, 1), true));
		route.add(new PathStep(pack(100, 100), true));
		route.add(new PathStep(pack(101, 101), true));
		route.add(new PathStep(pack(102, 101), true));

		InGameWalkRewriter.Result result = new InGameWalkRewriter(grid).rewrite(route);

		List<PathStep> expected = new ArrayList<>(steps(0, 0, 1, 0, 2, 1));
		expected.add(new PathStep(pack(2, 1), true));
		expected.add(new PathStep(pack(100, 100), true));
		expected.add(new PathStep(pack(101, 100), true));
		expected.add(new PathStep(pack(102, 101), true));
		assertEquals(describe(expected), describe(result.path()));
		assertEquals(List.of(2, 6), result.clickPoints());
	}

	private static void assertWalkable(CollisionMap grid, List<PathStep> path)
	{
		for (int i = 1; i < path.size(); i++)
		{
			int from = path.get(i - 1).getPackedPosition();
			int to = path.get(i).getPackedPosition();
			boolean found = false;
			for (int next : grid.ordinaryWalkingNeighbors(from)) found |= next == to;
			assertTrue("step " + i + " is not a walk", found);
		}
	}

	private static List<String> describe(List<PathStep> path)
	{
		List<String> tiles = new ArrayList<>();
		for (PathStep step : path)
		{
			int packed = step.getPackedPosition();
			tiles.add((WorldPointUtil.unpackWorldX(packed) - BASE_X) + "," + (WorldPointUtil.unpackWorldY(packed) - BASE_Y)
				+ (step.isBankVisited() ? " banked" : ""));
		}
		return tiles;
	}

	private static List<PathStep> steps(int... xy)
	{
		List<PathStep> steps = new ArrayList<>();
		for (int i = 0; i < xy.length; i += 2) steps.add(step(xy[i], xy[i + 1]));
		return steps;
	}

	private static PathStep step(int x, int y)
	{
		return new PathStep(pack(x, y), false);
	}

	private static int pack(int x, int y)
	{
		return WorldPointUtil.packWorldPoint(BASE_X + x, BASE_Y + y, 0);
	}

	/** Open ground except for fully blocked tiles; diagonals need both flanking tiles open. */
	private static final class OpenGrid extends CollisionMap
	{
		private final Set<Integer> blocked;

		OpenGrid(Set<Integer> blocked)
		{
			super(null);
			this.blocked = blocked;
		}

		@Override
		public boolean isBlocked(int x, int y, int z)
		{
			return blocked.contains(WorldPointUtil.packWorldPoint(x, y, z));
		}

		@Override
		public byte ordinaryWalkingMask(int packedPoint)
		{
			int x = WorldPointUtil.unpackWorldX(packedPoint), y = WorldPointUtil.unpackWorldY(packedPoint);
			if (isBlocked(x, y, 0)) return 0;
			boolean n = !isBlocked(x, y + 1, 0), e = !isBlocked(x + 1, y, 0);
			boolean s = !isBlocked(x, y - 1, 0), w = !isBlocked(x - 1, y, 0);
			int mask = 0;
			if (n) mask |= 1;
			if (n && e && !isBlocked(x + 1, y + 1, 0)) mask |= 1 << 1;
			if (e) mask |= 1 << 2;
			if (s && e && !isBlocked(x + 1, y - 1, 0)) mask |= 1 << 3;
			if (s) mask |= 1 << 4;
			if (s && w && !isBlocked(x - 1, y - 1, 0)) mask |= 1 << 5;
			if (w) mask |= 1 << 6;
			if (n && w && !isBlocked(x - 1, y + 1, 0)) mask |= 1 << 7;
			return (byte) mask;
		}
	}
}
