package shortestpath.pathfinder.astar;

import java.util.Arrays;
import java.util.function.ToIntBiFunction;
import shortestpath.pathfinder.BankVisitState;

/**
 * The cap on the heuristic of a search that starts where not every global teleport can be cast
 * (query lifetime). The prepared heuristic has no "cast a carried global here" edges, and no
 * "cast a banked global here" edges after banking, because a search starting outside the
 * Wilderness casts them where it starts or banks. From a start in the Wilderness a route may walk
 * out and cast later, so a state's heuristic is capped by a lower bound on every route that casts
 * a global teleport of its layer.
 *
 * <p>For a state s and a route from it that casts at least one global of s's layer, look at its
 * <em>last</em> such cast, made from a tile of band c. Nothing after it casts a global of the
 * layer, so the prepared heuristic bounds what is left after landing: the cast and the rest cost
 * at least {@code last[c]}, the least cast + h(destination) over the layer's globals castable in c.
 * Before it the route reaches a tile of band c, which costs at least {@code escape_c(s)} if it gets
 * there without a global, and otherwise at least the escape to its first cast's band c' and that
 * band's cheapest cast. So the route costs at least
 *
 * <pre>
 * min( min_c escape_c(s) + last[c],  min_c' (escape_c'(s) + cheapest[c']) + min_c last[c] )
 * </pre>
 *
 * <p>Teleports landing in the Wilderness, and routes casting more than once, are counted by the
 * second term, which assumes nothing about the landing tile. With every escape at 0 the formula is
 * {@code last[ALL]}, the cap the search used before the escapes existed.
 *
 * <p>The escapes follow the account's own local transports ({@link WildernessEscape}); a table not
 * yet cached for them is built here, once.
 */
final class WildernessHeuristic
{
	private static final TeleportCapability[] BANDS = TeleportCapability.values();

	private final WildernessEscape.Table escape;
	/** Per layer and band, at {@code layer * BANDS.length + band}. */
	private final int[] last, cheapest;
	/** Per layer: the least {@code last} over every band. */
	private final int[] anyBand;

	private WildernessHeuristic(WildernessEscape.Table escape, int[] last, int[] cheapest, int[] anyBand)
	{
		this.escape = escape;
		this.last = last;
		this.cheapest = cheapest;
		this.anyBand = anyBand;
	}

	/**
	 * @param destinationH the prepared heuristic of a global's destination tile in a layer, or
	 *                     {@code CostArithmetic.INF} when it is not a search state
	 */
	static WildernessHeuristic prepare(RoutingStatic stat, PreparedRoutingAccount account,
		ToIntBiFunction<Integer, BankVisitState> destinationH)
	{
		int[] last = new int[2 * BANDS.length], cheapest = new int[2 * BANDS.length], anyBand = new int[2];
		Arrays.fill(last, CostArithmetic.INF);
		Arrays.fill(cheapest, CostArithmetic.INF);
		Arrays.fill(anyBand, CostArithmetic.INF);
		for (BankVisitState banked : BankVisitState.values())
		{
			int layer = banked == BankVisitState.BANKED ? 1 : 0;
			for (TeleportCapability band : BANDS)
			{
				int slot = layer * BANDS.length + band.ordinal();
				for (int i = 0; i < account.globalCount(band, banked); i++)
				{
					int cost = account.globalCost(band, banked, i);
					cheapest[slot] = Math.min(cheapest[slot], cost);
					last[slot] = Math.min(last[slot], CostArithmetic.add(cost,
						destinationH.applyAsInt(account.globalDestination(band, banked, i), banked)));
				}
				anyBand[layer] = Math.min(anyBand[layer], last[slot]);
			}
		}
		return new WildernessHeuristic(stat.wildernessEscape().table(account), last, cheapest, anyBand);
	}

	/** The escape table, which may have been built for this search. */
	WildernessEscape.Table table()
	{
		return escape;
	}

	/**
	 * The cap for a state in {@code banked}'s layer at search tile {@code node}, or, for
	 * {@code node < 0}, at site {@code site} ({@code -1}: neither, so every escape counts as 0).
	 * {@code CostArithmetic.INF} means no route from the state that casts a global of its layer reaches
	 * a target.
	 */
	int cap(int node, int site, BankVisitState banked)
	{
		int offset = banked == BankVisitState.BANKED ? BANDS.length : 0;
		int layer = offset == 0 ? 0 : 1;
		int id = node >= 0 ? escape.nodeId(node) : site >= 0 ? escape.siteId(site) : -1;
		// Outside the boxes (or neither) every escape is 0, and then the second term is never below
		// the first.
		if (id < 0) return anyBand[layer];
		int lastCast = CostArithmetic.INF, firstOfSeveral = CostArithmetic.INF;
		for (TeleportCapability band : BANDS)
		{
			int slot = offset + band.ordinal();
			if (last[slot] == CostArithmetic.INF && cheapest[slot] == CostArithmetic.INF) continue;
			int distance = escape.escape(band, id);
			if (distance == CostArithmetic.INF) continue;
			lastCast = Math.min(lastCast, CostArithmetic.add(distance, last[slot]));
			firstOfSeveral = Math.min(firstOfSeveral, CostArithmetic.add(distance, cheapest[slot]));
		}
		return Math.min(lastCast, CostArithmetic.add(firstOfSeveral, anyBand[layer]));
	}
}
