package shortestpath.pathfinder.sailing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.TestWorldData;

/** The sailing solver on the real map: its tick costs, its estimate, and the moorings it reports. */
public class SailingSolverTest
{
	private static final int UNKAH_HARBOUR = WorldPointUtil.packWorldPoint(3143, 2847, 0);
	private static final int TOP_OF_ELID_DELTA = WorldPointUtil.packWorldPoint(3273, 2738, 0);
	private static final int PORT_SARIM_BAY = WorldPointUtil.packWorldPoint(3045, 3180, 0);

	private static CollisionMap map()
	{
		return new CollisionMap(TestWorldData.map());
	}

	private static SailingLeg solve(boolean directed, int start, int target)
	{
		CollisionMap map = map();
		SailingVessel raft = SailingVessel.of(SailingVessel.Kind.RAFT, 192);
		// An exit nobody docks at makes the search undirected
		List<SailingMooring> exits = directed ? List.of() : List.of(new SailingMooring(0, "nowhere",
			WorldPointUtil.packWorldPoint(1000, 1000, 0), WorldPointUtil.packWorldPoint(1000, 1000, 0), null, null, null,
			-1));
		SailingSolver solver = new SailingSolver(map, SailingQuery.of(raft.moves(), raft.hull())
			.withTargets(new int[] {target}).withExits(exits));
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
		return leg[0];
	}

	// The ticks a leg takes, from its moves
	private static int ticks(SailingLeg leg, SailingMoves moves)
	{
		int ticks = 0;
		for (int i = 1; i < leg.size(); i++)
		{
			int dx = WorldPointUtil.unpackWorldX(leg.point(i)) - WorldPointUtil.unpackWorldX(leg.point(i - 1));
			int dy = WorldPointUtil.unpackWorldY(leg.point(i)) - WorldPointUtil.unpackWorldY(leg.point(i - 1));
			int move = moves.indexOf(dx, dy);
			assertTrue("every step is one move", move >= 0);
			ticks += moves.ticks(move);
		}
		return ticks;
	}

	@Test
	public void aLegCostsItsMovesTicks()
	{
		SailingLeg leg = solve(true, UNKAH_HARBOUR, TOP_OF_ELID_DELTA);
		assertEquals(ticks(leg, SailingMoves.forSpeed(1.5)), leg.cost());
		assertEquals(TOP_OF_ELID_DELTA, leg.reached());
		assertEquals(-1, leg.heading(0));
		assertTrue(leg.heading(1) >= 0);
	}

	@Test
	public void theTickEstimateKeepsTheLegOptimal()
	{
		// A directed search (estimate) and an undirected one (cost alone) find legs of the same cost
		assertEquals(solve(false, UNKAH_HARBOUR, TOP_OF_ELID_DELTA).cost(),
			solve(true, UNKAH_HARBOUR, TOP_OF_ELID_DELTA).cost());
	}

	@Test
	public void reportsMooringsInCostOrderOnce()
	{
		CollisionMap map = map();
		SailingVessel raft = SailingVessel.of(SailingVessel.Kind.RAFT, 256);
		List<SailingMooring> moorings = SailingMoorings.all();
		SailingSolver solver = new SailingSolver(map, SailingQuery.of(raft.moves(), raft.hull()).withExits(moorings));
		solver.castOff(new int[] {PORT_SARIM_BAY}, 0);
		int[] last = {-1};
		boolean[] seen = new boolean[moorings.size()];
		int[] count = {0};
		solver.advance(400, () -> false, new SailingSolver.Listener()
		{
			@Override
			public boolean exit(int exit, int node)
			{
				assertFalse(seen[exit]);
				seen[exit] = true;
				assertTrue(solver.cost(node) >= last[0]);
				last[0] = solver.cost(node);
				assertTrue(moorings.get(exit).inDockingRange(solver.tile(node)));
				count[0]++;
				return true;
			}
		});
		assertTrue("Port Sarim and its neighbours are docked at", count[0] >= 3);
		assertTrue(seen[indexOf(moorings, "Port Sarim")]);
		solver.release();
	}

	@Test
	public void cancellingStopsTheSolver()
	{
		CollisionMap map = map();
		SailingVessel raft = SailingVessel.of(SailingVessel.Kind.RAFT, 256);
		SailingSolver solver = new SailingSolver(map, SailingQuery.of(raft.moves(), raft.hull())
			.withExits(SailingMoorings.all()));
		solver.castOff(new int[] {PORT_SARIM_BAY}, 0);
		assertEquals(SailingSolver.Stop.CANCELLED, solver.advance(Integer.MAX_VALUE, () -> true, new SailingSolver.Listener()
		{
		}));
		solver.release();
	}

	private static int indexOf(List<SailingMooring> moorings, String name)
	{
		for (int i = 0; i < moorings.size(); i++) if (moorings.get(i).name().equals(name)) return i;
		throw new AssertionError(name);
	}
}
