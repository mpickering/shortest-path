package shortestpath.pathfinder.astar;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.BankVisitState;
import shortestpath.pathfinder.SplitFlagMap;
import shortestpath.pathfinder.TransportAvailabilityFixture;
import shortestpath.pathfinder.WildernessChecker;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportType;

/**
 * The relaxed graph built to avoid the Wilderness ({@link SiteGraph#avoidsWilderness()}) bounds
 * the searches that may not enter it: unreachable targets get an infinite heuristic, detours
 * around the Wilderness are priced, and the gated labels stay admissible and consistent between
 * the clique oracle and the sparse network.
 *
 * <p>The fixture is one routing component on real coordinates at the Wilderness's south-east
 * corner (x &lt;= 3391, y &gt;= 3525): an east pocket {@code A1, A0} outside it, a Wilderness strip
 * {@code W0..W5}, and a south pocket {@code B0, B1} outside it, walked in that order. The two
 * pockets are only joined through the Wilderness.
 */
public class WildernessGateTest
{
	private static final int A1 = tile(3393, 3530), A0 = tile(3392, 3530);
	private static final int W0 = tile(3391, 3530), W1 = tile(3391, 3529), W2 = tile(3391, 3528);
	private static final int W3 = tile(3391, 3527), W4 = tile(3391, 3526), W5 = tile(3391, 3525);
	private static final int B0 = tile(3391, 3524), B1 = tile(3391, 3523);
	private static final int[] CHAIN = {A1, A0, W0, W1, W2, W3, W4, W5, B0, B1};

	@Test
	public void fixtureTilesAreOnTheExpectedSides()
	{
		for (int tile : CHAIN)
		{
			boolean wilderness = tile != A1 && tile != A0 && tile != B0 && tile != B1;
			assertEquals(Integer.toUnsignedString(tile), wilderness, WildernessChecker.isInWilderness(tile));
		}
	}

	@Test
	public void pocketsJoinedOnlyThroughTheWildernessAreSeparatePieces() throws Exception
	{
		RoutingStatic stat = world(Map.of(), new int[0]);
		WildernessGate gate = stat.wildernessGate();
		int a1 = stat.searchIndex(A1), a0 = stat.searchIndex(A0), b0 = stat.searchIndex(B0), b1 = stat.searchIndex(B1);
		assertEquals(gate.tilePiece(a1), gate.tilePiece(a0));
		assertEquals(gate.tilePiece(b0), gate.tilePiece(b1));
		assertNotEquals(gate.tilePiece(a0), gate.tilePiece(b0));
		assertTrue(gate.tilePiece(stat.searchIndex(W2)) < 0);
		assertTrue(gate.gatedComponent(stat.routingComponent(a0)));
	}

	@Test
	public void targetBeyondTheWildernessIsUnreachableWithoutIt() throws Exception
	{
		RoutingStatic stat = world(Map.of(), new int[0]);
		PreparedRoutingAccount account = account();
		PreparedTarget gated = PreparedTarget.prepare(new SiteGraph(stat, account, true), collision(), B1);
		assertEquals(CostArithmetic.INF, gated.heuristic().estimate(A0, BankVisitState.CARRIED));
		AStarForwardSearch.Result result = search(gated, A0, true);
		assertFalse(result.reached());
		assertEquals("nothing is expanded", 0, result.counters().statesPopped());

		// The ungated heuristic only learns this by expanding the east pocket.
		PreparedTarget ungated = PreparedTarget.prepare(new SiteGraph(stat, account), collision(), B1);
		assertEquals(7, ungated.heuristic().estimate(A0, BankVisitState.CARRIED));
		AStarForwardSearch.Result flooded = search(ungated, A0, true);
		assertFalse(flooded.reached());
		assertTrue(flooded.counters().statesPopped() > 0);
		// Without the gate the walk goes straight through.
		assertEquals(8, search(ungated, A0, false).cost());
	}

	@Test
	public void detourAroundTheWildernessIsPriced() throws Exception
	{
		RoutingStatic stat = world(Map.of(A1, Set.of(local(A1, B1, 20))), new int[0]);
		PreparedRoutingAccount account = account(local(A1, B1, 20));
		PreparedTarget gated = PreparedTarget.prepare(new SiteGraph(stat, account, true), collision(), B1);
		PreparedTarget ungated = PreparedTarget.prepare(new SiteGraph(stat, account), collision(), B1);
		assertEquals(21, gated.heuristic().estimate(A0, BankVisitState.CARRIED));
		assertEquals(7, ungated.heuristic().estimate(A0, BankVisitState.CARRIED));
		assertEquals(21, search(gated, A0, true).cost());
		assertEquals(21, search(ungated, A0, true).cost());
		assertTrue("the tight bound never expands more",
			search(gated, A0, true).counters().statesPopped() <= search(ungated, A0, true).counters().statesPopped());
	}

	@Test
	public void leavingTheWildernessIsNotGated() throws Exception
	{
		RoutingStatic stat = world(Map.of(), new int[0]);
		PreparedTarget gated = PreparedTarget.prepare(new SiteGraph(stat, account(), true), collision(), B1);
		assertEquals(5, gated.heuristic().estimate(W2, BankVisitState.CARRIED));
		assertEquals(5, search(gated, W2, true).cost());
	}

	@Test
	public void wildernessTargetIsUnreachableFromOutsideButNotFromInside() throws Exception
	{
		RoutingStatic stat = world(Map.of(), new int[0]);
		PreparedTarget gated = PreparedTarget.prepare(new SiteGraph(stat, account(), true), collision(), W3);
		assertEquals(CostArithmetic.INF, gated.heuristic().estimate(A0, BankVisitState.CARRIED));
		assertEquals(CostArithmetic.INF, gated.heuristic().estimate(B1, BankVisitState.CARRIED));
		assertEquals(3, gated.heuristic().estimate(W0, BankVisitState.CARRIED));
		assertEquals(3, search(gated, W0, true).cost());
	}

	@Test
	public void localTransportIntoTheWildernessIsLeftOut() throws Exception
	{
		Transport jump = local(B1, W5, 2);
		RoutingStatic stat = world(Map.of(B1, Set.of(jump)), new int[0]);
		PreparedRoutingAccount account = account(jump);
		SiteGraph gated = new SiteGraph(stat, account, true);
		SiteGraph ungated = new SiteGraph(stat, account);
		assertEquals(ungated.reverseEdgeCount() - 2, gated.reverseEdgeCount());
		PreparedTarget target = PreparedTarget.prepare(gated, collision(), W2);
		assertEquals(CostArithmetic.INF, target.heuristic().estimate(B1, BankVisitState.CARRIED));
		assertFalse(search(target, B1, true).reached());
	}

	@Test
	public void separatorCrossingsIntoTheWildernessAreOneWay() throws Exception
	{
		// Cut the walk between W5 and B0: the south pocket becomes its own routing component.
		RoutingStatic stat = world(Map.of(), new int[] {W5, B0});
		assertNotEquals(stat.routingComponent(stat.searchIndex(W5)), stat.routingComponent(stat.searchIndex(B0)));
		PreparedRoutingAccount account = account();
		PreparedTarget intoWilderness = PreparedTarget.prepare(new SiteGraph(stat, account, true), collision(), W2);
		assertEquals(CostArithmetic.INF, intoWilderness.heuristic().estimate(B1, BankVisitState.CARRIED));
		PreparedTarget outOfWilderness = PreparedTarget.prepare(new SiteGraph(stat, account, true), collision(), B1);
		assertEquals(5, outOfWilderness.heuristic().estimate(W2, BankVisitState.CARRIED));
		assertEquals(5, search(outOfWilderness, W2, true).cost());
	}

	@Test
	public void gatedCliqueAndSparseLabelsAgreeAndGeneratorsMatchRawSeeds() throws Exception
	{
		Transport detour = local(A1, B1, 20), jump = local(B1, W5, 2), back = local(W0, A1, 4);
		Map<Integer, Set<Transport>> transports = new HashMap<>();
		transports.put(A1, Set.of(detour));
		transports.put(B1, Set.of(jump));
		transports.put(W0, Set.of(back));
		for (int[] cuts : new int[][] {new int[0], new int[] {W5, B0}, new int[] {W1, W2}})
		{
			RoutingStatic stat = world(transports, cuts);
			for (PreparedRoutingAccount account : new PreparedRoutingAccount[] {account(), account(detour, jump, back)})
			{
				SiteGraph graph = new SiteGraph(stat, account, true);
				for (int target : CHAIN)
				{
					TargetOverlay overlay = new TargetOverlay(graph, collision(), target);
					ReverseLabels sparse = ReverseLabels.computeSparse(overlay);
					assertArrayEquals("target " + Integer.toUnsignedString(target),
						ReverseLabels.computeClique(overlay).labels(), sparse.labels());
					PreparedHeuristic heuristic = PreparedHeuristic.prepare(overlay, sparse);
					for (int tile : CHAIN)
					{
						int[] components = stat.attachments(tile, collision());
						for (BankVisitState banked : BankVisitState.values())
							assertEquals(heuristic.estimateRaw(tile, banked, components),
								heuristic.estimate(tile, banked, components));
						// Admissible: never above the true cost of a search that avoids the Wilderness.
						AStarForwardSearch.Result exact = search(PreparedTarget.prepare(new SiteGraph(stat, account),
							collision(), target), tile, true);
						int h = heuristic.estimate(tile, BankVisitState.CARRIED, components);
						if (exact.reached()) assertTrue(h <= exact.cost());
						else assertEquals(CostArithmetic.INF, h);
					}
				}
			}
		}
	}

	private static AStarForwardSearch.Result search(PreparedTarget target, int start, boolean avoidWilderness)
	{
		return target.search(start, () -> false, 1,
			avoidWilderness ? SearchRestrictions.of(true, null) : SearchRestrictions.none(),
			AStarForwardSearch.NO_PROGRESS);
	}

	private static RoutingStatic world(Map<Integer, Set<Transport>> transports, int[] cuts) throws Exception
	{
		return RoutingStaticBuilder.build(collision(), transports, Set.of(), cuts, A1).routingStatic;
	}

	private static SyntheticCollisionMap collision()
	{
		int[][] edges = new int[CHAIN.length - 1][];
		for (int i = 0; i + 1 < CHAIN.length; i++)
			edges[i] = new int[] {CHAIN[i], CHAIN[i + 1]};
		Set<Integer> walkable = new HashSet<>();
		for (int tile : CHAIN) walkable.add(tile);
		return new SyntheticCollisionMap(walkable, SyntheticCollisionMap.symmetricAdjacency(edges), 1)
		{
			// The fixture's regions, x 52..53 and y 55, instead of the default region 0.
			@Override
			public SplitFlagMap.RegionExtent regionExtent()
			{
				return new SplitFlagMap.RegionExtent(52, 55, 53, 55);
			}

			@Override
			public byte getRegionPlaneCounts(int regionIndex)
			{
				return 1;
			}
		};
	}

	private static PreparedRoutingAccount account(Transport... transports)
	{
		return PreparedRoutingAccount.compile(TransportAvailabilityFixture.of(transports),
			TransportAvailabilityFixture.of(transports), false, Set.of(), 0, true, ignored -> 0);
	}

	private static Transport local(int origin, int destination, int cost)
	{
		return new Transport.TransportBuilder().origin(origin).destination(destination)
			.type(TransportType.TRANSPORT).duration(cost).build();
	}

	private static int tile(int x, int y)
	{
		return WorldPointUtil.packWorldPoint(x, y, 0);
	}
}
