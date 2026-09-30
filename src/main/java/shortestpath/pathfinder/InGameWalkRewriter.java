package shortestpath.pathfinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import shortestpath.WorldPointUtil;

/**
 * Rewrites the walking legs of a finished route so they follow the tiles the game's own walking
 * pathfinder would take.
 * <p>
 * Clicking a tile makes the game run a breadth-first search over a 128x128 window centred on the
 * player, expanding neighbours in the order W, E, S, N, SW, SE, NW, NE, and walks the path traced
 * back from the clicked tile along each tile's first discoverer. A route search picks any of the
 * equally short walking paths, so its tiles generally differ from the ones the player walks.
 * <p>
 * Each walking leg (a maximal run of single-tile walking steps between transports) is split into
 * clicks: from the current tile, the click is the furthest tile along the leg within
 * {@link #clickRadius} whose in-game path is no longer than the route's, and the leg up to it is
 * replaced by that in-game path. Every click is on the original route, so the rewritten route
 * costs no more than the original. Transport steps and bank-state changes are kept as they are.
 */
public final class InGameWalkRewriter
{
	/** Roughly the reach of a minimap click, in tiles. */
	public static final int DEFAULT_CLICK_RADIUS = 15;

	private static final int WINDOW = 128;
	private static final int HALF_WINDOW = WINDOW / 2;
	private static final int UNVISITED = -1;
	// CollisionMap.ordinaryWalkingMask bits (0-N, 1-NE, 2-E, 3-SE, 4-S, 5-SW, 6-W, 7-NW) in the
	// game's expansion order: W, E, S, N, SW, SE, NW, NE.
	private static final int[] ORDER_BITS = {6, 2, 4, 0, 5, 3, 7, 1};
	private static final int[] ORDER_DX = {-1, 1, 0, 0, -1, 1, -1, 1};
	private static final int[] ORDER_DY = {0, 0, -1, 1, -1, -1, 1, 1};

	private final CollisionMap map;
	private final int clickRadius;
	private final int[] distance = new int[WINDOW * WINDOW];
	private final int[] parent = new int[WINDOW * WINDOW];
	private final int[] queue = new int[WINDOW * WINDOW];

	public InGameWalkRewriter(CollisionMap map)
	{
		this(map, DEFAULT_CLICK_RADIUS);
	}

	public InGameWalkRewriter(CollisionMap map, int clickRadius)
	{
		if (map == null) throw new NullPointerException();
		if (clickRadius < 1) throw new IllegalArgumentException("click radius must be at least 1");
		this.map = map;
		this.clickRadius = clickRadius;
	}

	/** A rewritten route and the indices of its steps the player clicks to walk it. */
	public static final class Result
	{
		private final List<PathStep> path;
		private final List<Integer> clickPoints;

		Result(List<PathStep> path, List<Integer> clickPoints)
		{
			this.path = path;
			this.clickPoints = clickPoints;
		}

		public List<PathStep> path()
		{
			return path;
		}

		/** Ascending indices into {@link #path()} of the tiles to click, one per click. */
		public List<Integer> clickPoints()
		{
			return clickPoints;
		}
	}

	public Result rewrite(List<PathStep> route)
	{
		if (route.size() < 2) return new Result(route, List.of());
		List<PathStep> path = new ArrayList<>(route.size());
		List<Integer> clicks = new ArrayList<>();
		path.add(route.get(0));
		int legStart = 0;
		while (legStart < route.size() - 1)
		{
			int legEnd = legStart;
			while (legEnd + 1 < route.size() && isWalk(route.get(legEnd), route.get(legEnd + 1))) legEnd++;
			if (legEnd == legStart)
			{
				// A transport or bank step: kept as is.
				path.add(route.get(legStart + 1));
				legStart++;
			}
			else
			{
				rewriteLeg(route, legStart, legEnd, path, clicks);
				legStart = legEnd;
			}
		}
		return new Result(List.copyOf(path), List.copyOf(clicks));
	}

	private boolean isWalk(PathStep from, PathStep to)
	{
		int a = from.getPackedPosition(), b = to.getPackedPosition();
		if (from.isBankVisited() != to.isBankVisited()) return false;
		if (WorldPointUtil.unpackWorldPlane(a) != WorldPointUtil.unpackWorldPlane(b)) return false;
		int dx = WorldPointUtil.unpackWorldX(b) - WorldPointUtil.unpackWorldX(a);
		int dy = WorldPointUtil.unpackWorldY(b) - WorldPointUtil.unpackWorldY(a);
		if (Math.max(Math.abs(dx), Math.abs(dy)) != 1) return false;
		int mask = map.ordinaryWalkingMask(a);
		for (int i = 0; i < ORDER_BITS.length; i++)
			if (ORDER_DX[i] == dx && ORDER_DY[i] == dy) return (mask & (1 << ORDER_BITS[i])) != 0;
		return false;
	}

	/** Appends the in-game replacement for {@code route[from..to]}, excluding {@code route[from]}. */
	private void rewriteLeg(List<PathStep> route, int from, int to, List<PathStep> path, List<Integer> clicks)
	{
		boolean banked = route.get(from).isBankVisited();
		int current = from;
		while (current < to)
		{
			int source = route.get(current).getPackedPosition();
			int sx = WorldPointUtil.unpackWorldX(source), sy = WorldPointUtil.unpackWorldY(source);
			int plane = WorldPointUtil.unpackWorldPlane(source);
			int originX = sx - HALF_WINDOW, originY = sy - HALF_WINDOW;

			// Route tiles within reach; the BFS need only run as deep as the furthest of them.
			int deepest = 0;
			for (int i = current + 1; i <= to; i++)
				if (withinReach(sx, sy, route.get(i).getPackedPosition())) deepest = i - current;
			search(originX, originY, plane, sx, sy, deepest);

			int click = current + 1;
			for (int i = current + deepest; i > current; i--)
			{
				int tile = route.get(i).getPackedPosition();
				if (!withinReach(sx, sy, tile)) continue;
				int local = localIndex(originX, originY, tile);
				if (local >= 0 && distance[local] != UNVISITED && distance[local] <= i - current)
				{
					click = i;
					break;
				}
			}

			int target = route.get(click).getPackedPosition();
			int local = localIndex(originX, originY, target);
			if (local < 0 || distance[local] == UNVISITED)
			{
				// Only possible if this walk step is not an in-game walk step; keep the route's own.
				path.add(route.get(click));
			}
			else
			{
				int[] walked = new int[distance[local]];
				for (int i = walked.length - 1, at = local; i >= 0; i--, at = parent[at]) walked[i] = at;
				for (int at : walked)
					path.add(new PathStep(WorldPointUtil.packWorldPoint(originX + at % WINDOW, originY + at / WINDOW,
						plane), banked));
			}
			clicks.add(path.size() - 1);
			current = click;
		}
	}

	private boolean withinReach(int sx, int sy, int tile)
	{
		int dx = WorldPointUtil.unpackWorldX(tile) - sx, dy = WorldPointUtil.unpackWorldY(tile) - sy;
		return dx * dx + dy * dy <= clickRadius * clickRadius;
	}

	private static int localIndex(int originX, int originY, int tile)
	{
		int x = WorldPointUtil.unpackWorldX(tile) - originX, y = WorldPointUtil.unpackWorldY(tile) - originY;
		return x < 0 || y < 0 || x >= WINDOW || y >= WINDOW ? -1 : y * WINDOW + x;
	}

	/** The game's walking BFS from {@code (sx, sy)}, stopped once every tile within {@code depth} is found. */
	private void search(int originX, int originY, int plane, int sx, int sy, int depth)
	{
		Arrays.fill(distance, UNVISITED);
		int start = (sy - originY) * WINDOW + (sx - originX);
		distance[start] = 0;
		parent[start] = start;
		int head = 0, tail = 0;
		queue[tail++] = start;
		while (head < tail)
		{
			int at = queue[head++];
			if (distance[at] >= depth) break;
			int x = at % WINDOW, y = at / WINDOW;
			int mask = map.ordinaryWalkingMask(WorldPointUtil.packWorldPoint(originX + x, originY + y, plane));
			for (int i = 0; i < ORDER_BITS.length; i++)
			{
				if ((mask & (1 << ORDER_BITS[i])) == 0) continue;
				int nx = x + ORDER_DX[i], ny = y + ORDER_DY[i];
				if (nx < 0 || ny < 0 || nx >= WINDOW || ny >= WINDOW) continue;
				int next = ny * WINDOW + nx;
				if (distance[next] != UNVISITED) continue;
				distance[next] = distance[at] + 1;
				parent[next] = at;
				queue[tail++] = next;
			}
		}
	}
}
