package shortestpath.pathfinder.astar;

import java.util.Arrays;
import shortestpath.pathfinder.BankVisitState;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * Immutable two-layer account-specific site graph with reverse CSR edges.
 *
 * <p>A graph built to avoid the Wilderness leaves out every edge that enters it from outside: a
 * local transport or separator crossing from a site outside the Wilderness to one inside, and the
 * banked global hub's Wilderness destinations when every bank the account may use is outside it
 * (a global cast outside the Wilderness cannot land inside, see {@link SearchRestrictions}). The
 * same flag gates the same-component walking of the target overlay and reverse search, so labels
 * computed over it bound the distances of a search that may not enter the Wilderness.
 */
public final class SiteGraph
{
	public enum EdgeKind
	{
		LOCAL,
		BANK_TRANSITION,
		BANK_GLOBAL_ENTRY,
		BANK_GLOBAL_DESTINATION,
		SEPARATOR
	}

	private final RoutingStatic stat;
	private final PreparedRoutingAccount account;
	private final boolean avoidWilderness;
	@Getter @Accessors(fluent = true) private final int nodeCount;
	@Getter @Accessors(fluent = true) private final boolean hasBankedGlobalHub;
	private final int[] reverseOffsets;
	private final int[] reverseFromStates;
	private final int[] reverseCosts;
	private final boolean[] reverseStartsGenerator;
	private final byte[] reverseKinds;

	public SiteGraph(RoutingStatic stat, PreparedRoutingAccount account)
	{
		this(stat, account, false);
	}

	/** @param avoidWilderness leave out every edge that enters the Wilderness from outside it */
	public SiteGraph(RoutingStatic stat, PreparedRoutingAccount account, boolean avoidWilderness)
	{
		this.stat = stat;
		this.account = account;
		this.avoidWilderness = avoidWilderness;
		int spatialCount = stat.siteCount();
		validateEndpoints(account, stat);
		int[] bankSites = bankSites(stat, account);
		int[] globalDestinations = globalDestinations(account, stat);
		if (avoidWilderness && !anyInWilderness(stat, bankSites))
			globalDestinations = outsideWilderness(stat, globalDestinations);
		hasBankedGlobalHub = account.allowTransports() && account.bankPathEnabled() && bankSites.length != 0
			&& globalDestinations.length != 0;
		nodeCount = spatialCount + (hasBankedGlobalHub ? 1 : 0);

		EdgeBuilder edges = new EdgeBuilder();
		addLocalEdges(edges, account.localView(BankVisitState.CARRIED), BankVisitState.CARRIED, stat, avoidWilderness);
		addLocalEdges(edges, account.localView(BankVisitState.BANKED), BankVisitState.BANKED, stat, avoidWilderness);
		if (account.bankPathEnabled())
		{
			for (int bankSite : bankSites)
			{
				edges.add(stateId(bankSite, BankVisitState.CARRIED), stateId(bankSite, BankVisitState.BANKED),
					account.bankVisitCost(), true, EdgeKind.BANK_TRANSITION);
			}
		}
		if (hasBankedGlobalHub)
		{
			int hub = spatialCount;
			for (int bankSite : bankSites)
			{
				edges.add(stateId(bankSite, BankVisitState.CARRIED), stateId(hub, BankVisitState.BANKED),
					account.bankVisitCost(), true, EdgeKind.BANK_GLOBAL_ENTRY);
			}
			for (int destination : globalDestinations)
			{
				int cost = minimumGlobalCost(account.globalView(BankVisitState.BANKED), destination);
				if (cost != CostArithmetic.INF)
				{
					edges.add(stateId(hub, BankVisitState.BANKED), stateId(stat.siteIndex(destination), BankVisitState.BANKED),
						cost, false, EdgeKind.BANK_GLOBAL_DESTINATION);
				}
			}
		}
		for (int i = 0; i < stat.crossingCount(); i++)
		{
			int from = stat.crossingFromSite(i);
			int to = stat.crossingToSite(i);
			int cost = CostArithmetic.validate(stat.crossingCost(i));
			boolean forward = !avoidWilderness || entersWildernessAllowed(stat, from, to);
			boolean backward = !avoidWilderness || entersWildernessAllowed(stat, to, from);
			for (BankVisitState banked : BankVisitState.values())
			{
				if (forward)
					edges.add(stateId(from, banked), stateId(to, banked), cost, true, EdgeKind.SEPARATOR);
				if (backward)
					edges.add(stateId(to, banked), stateId(from, banked), cost, true, EdgeKind.SEPARATOR);
			}
		}
		Csr csr = edges.build(nodeCount * 2);
		reverseOffsets = csr.offsets;
		reverseFromStates = csr.fromStates;
		reverseCosts = csr.costs;
		reverseStartsGenerator = csr.startsGenerator;
		reverseKinds = csr.kinds;
	}

	public static SiteGraph compile(RoutingStatic stat, PreparedRoutingAccount account)
	{
		return new SiteGraph(stat, account);
	}

	public RoutingStatic routingStatic()
	{
		return stat;
	}
	public PreparedRoutingAccount preparedAccount()
	{
		return account;
	}
	/** Whether this graph leaves out every edge entering the Wilderness from outside it. */
	public boolean avoidsWilderness()
	{
		return avoidWilderness;
	}
	public int spatialNodeCount()
	{
		return stat.siteCount();
	}

	public int stateCount()
	{
		return nodeCount * 2;
	}

	public int bankedGlobalHubNode()
	{
		return hasBankedGlobalHub ? stat.siteCount() : -1;
	}
	public int siteTile(int node)
	{
		return stat.siteTile(node);
	}
	public int nodeForTile(int tile)
	{
		return stat.siteIndex(tile);
	}

	public int reverseEdgeCount()
	{
		return reverseFromStates.length;
	}
	public int reverseEdgeStart(int state)
	{
		return reverseOffsets[state];
	}
	public int reverseEdgeEnd(int state)
	{
		return reverseOffsets[state + 1];
	}
	public int reverseFromState(int edge)
	{
		return reverseFromStates[edge];
	}
	public int reverseCost(int edge)
	{
		return reverseCosts[edge];
	}
	public boolean reverseStartsGenerator(int edge)
	{
		return reverseStartsGenerator[edge];
	}
	public EdgeKind reverseKind(int edge)
	{
		return EdgeKind.values()[reverseKinds[edge]];
	}

	public static int stateId(int node, BankVisitState banked)
	{
		if (node < 0)
			throw new IllegalArgumentException("negative site node");
		return node * 2 + (banked == BankVisitState.BANKED ? 1 : 0);
	}

	private static void addLocalEdges(EdgeBuilder edges, PreparedRoutingAccount.View view, BankVisitState banked,
		RoutingStatic stat, boolean avoidWilderness)
	{
		for (int i = 0; i < view.count; i++)
		{
			int from = stat.siteIndex(view.origins[i]);
			int to = stat.siteIndex(view.destinations[i]);
			if (view.costs[i] != CostArithmetic.INF && (!avoidWilderness || entersWildernessAllowed(stat, from, to)))
			{
				edges.add(stateId(from, banked), stateId(to, banked), view.costs[i], true, EdgeKind.LOCAL);
			}
		}
	}

	/** Whether moving from site {@code from} to site {@code to} does not enter the Wilderness. */
	private static boolean entersWildernessAllowed(RoutingStatic stat, int from, int to)
	{
		return stat.siteInWilderness(from) || !stat.siteInWilderness(to);
	}

	private static boolean anyInWilderness(RoutingStatic stat, int[] sites)
	{
		for (int site : sites)
			if (stat.siteInWilderness(site))
				return true;
		return false;
	}

	private static int[] outsideWilderness(RoutingStatic stat, int[] destinations)
	{
		int[] result = new int[destinations.length];
		int count = 0;
		for (int destination : destinations)
			if (!stat.siteInWilderness(stat.siteIndex(destination)))
				result[count++] = destination;
		return Arrays.copyOf(result, count);
	}

	private static void validateEndpoints(PreparedRoutingAccount account, RoutingStatic stat)
	{
		for (BankVisitState banked : BankVisitState.values())
		{
			PreparedRoutingAccount.View local = account.localView(banked);
			for (int i = 0; i < local.count; i++)
			{
				requireSite(stat, local.origins[i], "local origin");
				requireSite(stat, local.destinations[i], "local destination");
			}
			PreparedRoutingAccount.View global = account.globalView(banked);
			for (int i = 0; i < global.count; i++)
				requireSite(stat, global.destinations[i], "global destination");
		}
	}

	private static void requireSite(RoutingStatic stat, int tile, String label)
	{
		if (stat.siteIndex(tile) < 0)
		{
			throw new IllegalStateException(label + " is not a static site: " + Integer.toUnsignedString(tile));
		}
	}

	/** The static bank sites this account may use. */
	private static int[] bankSites(RoutingStatic stat, PreparedRoutingAccount account)
	{
		int[] result = new int[stat.reachableBankCount()];
		int count = 0;
		for (int i = 0; i < result.length; i++)
		{
			int tile = stat.reachableBankTile(i);
			int site = stat.siteIndex(tile);
			if (site < 0)
				throw new IllegalStateException("reachable bank is not a static site");
			if (account.bankAccessible(tile))
				result[count++] = site;
		}
		return Arrays.copyOf(result, count);
	}

	private static int[] globalDestinations(PreparedRoutingAccount account, RoutingStatic stat)
	{
		PreparedRoutingAccount.View view = account.globalView(BankVisitState.BANKED);
		int[] result = new int[view.count];
		int count = 0;
		for (int i = 0; i < view.count; i++)
		{
			int destination = view.destinations[i];
			boolean present = false;
			for (int j = 0; j < count; j++)
				present |= result[j] == destination;
			if (!present)
				result[count++] = destination;
		}
		result = Arrays.copyOf(result, count);
		for (int i = 1; i < count; i++)
		{
			int value = result[i];
			int j = i - 1;
			while (j >= 0 && Integer.compareUnsigned(result[j], value) > 0)
			{
				result[j + 1] = result[j--];
			}
			result[j + 1] = value;
		}
		for (int destination : result)
			requireSite(stat, destination, "global destination");
		return result;
	}

	private static int minimumGlobalCost(PreparedRoutingAccount.View view, int destination)
	{
		int minimum = CostArithmetic.INF;
		for (int i = 0; i < view.count; i++)
		{
			if (view.destinations[i] == destination && view.costs[i] < minimum)
				minimum = view.costs[i];
		}
		return minimum;
	}

	private static final class EdgeBuilder
	{
		private int size;
		private int[] from = new int[64], to = new int[64], costs = new int[64];
		private boolean[] starts = new boolean[64];
		private byte[] kinds = new byte[64];

		void add(int fromState, int toState, int cost, boolean startsGenerator, EdgeKind kind)
		{
			CostArithmetic.validate(cost);
			if (cost == CostArithmetic.INF)
				return;
			if (size == from.length)
			{
				int capacity = size * 2;
				from = Arrays.copyOf(from, capacity);
				to = Arrays.copyOf(to, capacity);
				costs = Arrays.copyOf(costs, capacity);
				starts = Arrays.copyOf(starts, capacity);
				kinds = Arrays.copyOf(kinds, capacity);
			}
			from[size] = fromState;
			to[size] = toState;
			costs[size] = cost;
			starts[size] = startsGenerator;
			kinds[size++] = (byte) kind.ordinal();
		}

		Csr build(int stateCount)
		{
			int[] offsets = new int[stateCount + 1];
			for (int i = 0; i < size; i++)
				offsets[to[i] + 1]++;
			for (int i = 1; i < offsets.length; i++)
				offsets[i] += offsets[i - 1];
			int[] positions = Arrays.copyOfRange(offsets, 1, offsets.length);
			int[] sortedFrom = new int[size], sortedCosts = new int[size];
			boolean[] sortedStarts = new boolean[size];
			byte[] sortedKinds = new byte[size];
			for (int i = 0; i < size; i++)
			{
				int position = --positions[to[i]];
				sortedFrom[position] = from[i];
				sortedCosts[position] = costs[i];
				sortedStarts[position] = starts[i];
				sortedKinds[position] = kinds[i];
			}
			return new Csr(offsets, sortedFrom, sortedCosts, sortedStarts, sortedKinds);
		}
	}

	private static final class Csr
	{
		final int[] offsets, fromStates, costs;
		final boolean[] startsGenerator;
		final byte[] kinds;

		Csr(int[] offsets, int[] fromStates, int[] costs, boolean[] startsGenerator, byte[] kinds)
		{
			this.offsets = offsets;
			this.fromStates = fromStates;
			this.costs = costs;
			this.startsGenerator = startsGenerator;
			this.kinds = kinds;
		}
	}
}
