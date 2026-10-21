package shortestpath.pathfinder.astar;

/**
 * One sparse walking network in CSR form: the sites' vertices {@code 0..originalCount-1}, then
 * Steiner vertices. {@link RoutingStatic} holds the ungated one and, built on first use, the one
 * for searches that avoid the Wilderness ({@link SparseWalkingNetworkBuilder#build(int[], int[],
 * int[], boolean[])}).
 */
final class SparseNetwork
{
	final int originalCount;
	final int steinerCount;
	final int[] offsets;
	final int[] destinations;
	final int[] weights;
	/** Entries a Wilderness-gated reverse search skips; {@code null} for the ungated network. */
	private final boolean[] gatedEntries;

	SparseNetwork(int originalCount, int steinerCount, int[] offsets, int[] destinations, int[] weights,
		boolean[] gatedEntries)
	{
		this.originalCount = originalCount;
		this.steinerCount = steinerCount;
		this.offsets = offsets;
		this.destinations = destinations;
		this.weights = weights;
		this.gatedEntries = gatedEntries;
	}

	boolean gated(int entry)
	{
		return gatedEntries != null && gatedEntries[entry];
	}
}
