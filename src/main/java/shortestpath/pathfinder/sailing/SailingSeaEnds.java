package shortestpath.pathfinder.sailing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntUnaryOperator;
import shortestpath.PrimitiveIntHashMap;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.astar.SeaEnds;
import shortestpath.sailing.LegTimes;
import shortestpath.transport.Transport;

/**
 * A route's ends at sea for one query, from the account's boats and moorings: from the boat the player is aboard to
 * each mooring it can dock at and straight to the targets at sea, and from each mooring with a boat of the player's
 * to the targets at sea. Each link's first bound is the straight line at the boat's top speed, plus boarding or
 * stepping off.
 * <p>
 * Legs to the targets are sailed towards them over a {@link SeaField} from the targets, which sees round the land the
 * straight line crosses: it grows only as far as the links the router asks about, tightening their bounds
 * ({@link #refine}) and guiding each leg's search, and it stays still while a leg is sailed, so the search's estimate
 * is consistent and the leg exact. Legs from the start to the moorings share one search by cost from the boat, which
 * goes only as far as the moorings the router reaches.
 * <p>
 * Legs between moorings are transports ({@link SailingTransports}), timed for a boat with every part at the speeds
 * hulls give. For a boat of the player's with fewer parts or another speed a leg's time only bounds the boat's, so it
 * is a crossing link ({@link #crossingLink}): the router sails it for that boat, by the search the legs are timed by,
 * a step at a time as far as its bound needs, and only once nothing cheaper is left; the crossings from one berth
 * share that search.
 * <p>
 * A link the boat can't sail is never offered, so no search runs to find that out: the bodies of water of its kind
 * ({@link SailingWaters#connects}), made with the collision map, tell a channel its hull can't get through and a
 * restricted area its parts don't let it enter.
 */
public final class SailingSeaEnds implements SeaEnds
{
	// How much further the field grows than the bound it is asked about, in ticks: a step's worth, so a link's bound
	// rises in a few steps rather than many
	private static final int GROWTH_TICKS = 32;
	// How far past a link's bound its leg's search goes each time the router asks, in ticks
	private static final int STEP_TICKS = 24;

	private enum Kind
	{
		/** From the boat the player is aboard to a mooring, ashore. */
		START,
		/** Boarded at a mooring, to a target at sea. */
		TARGET,
		/** From the boat the player is aboard straight to a target at sea. */
		DIRECT,
		/** Boarded at a mooring, to another mooring, for a transport timed for another boat. */
		CROSSING
	}

	/** One link: what it sails and, once sailed, its leg. */
	private static final class Link
	{
		final Kind kind;
		/** Where it boards (target, crossing) or docks (start); {@code null} for a direct link. */
		final SailingMooring mooring;
		/** The player's boat it boards, or {@code null} for the boat they are aboard. */
		final SailingFleet.Boat boat;
		final SailingVessel vessel;
		final BoatHull hull;
		/** The heading the boat faces setting out, or -1. */
		final int heading;
		final int bound;
		/** For a crossing: the mooring it docks at and its transport. */
		SailingMooring docksAt;
		Transport transport;
		/** The leg's search, kept between steps until it arrives or runs dry (target and direct links). */
		SailingSolver solver;
		/** Once sailed: its cost, its leg, and the target at sea it ended at. */
		int exact = -1;
		SailingLeg leg;
		int[][] tilesAndCosts;
		int reached = WorldPointUtil.UNDEFINED;

		Link(Kind kind, SailingMooring mooring, SailingFleet.Boat boat, SailingVessel vessel, BoatHull hull, int heading,
			int bound)
		{
			this.kind = kind;
			this.mooring = mooring;
			this.boat = boat;
			this.vessel = vessel;
			this.hull = hull;
			this.heading = heading;
			this.bound = bound;
		}

		/** Ticks spent boarding before the leg: the leg's costs start after them. */
		int boarding()
		{
			return kind == Kind.TARGET || kind == Kind.CROSSING ? SailingMooring.BOARD_TICKS : 0;
		}

		/** Ticks spent stepping off after the leg. */
		int disembarking()
		{
			return kind == Kind.START || kind == Kind.CROSSING ? SailingMooring.DISEMBARK_TICKS : 0;
		}
	}

	private final CollisionMap map;
	private final SailingWaters waters;
	private final SailingFleet fleet;
	private final SeaTargets targets;
	private final SailingQuery.EdgeGate gate;
	private final int start;
	// Where the boat the player is aboard sets out from: its own tile, or where it casts off from the mooring it is
	// moored at
	private final int[] startTiles;
	private final List<Link> links = new ArrayList<>();
	private final PrimitiveIntHashMap<int[]> targetLinksByLand = new PrimitiveIntHashMap<>(64);
	private int[] startLinks = EMPTY;
	private int directLink = -1;
	// Each sailing transport's crossing link, or -1
	private final Map<Transport, Integer> crossings = new IdentityHashMap<>();
	// The searches the crossings share, by the berth they board at and the boat
	private final Map<List<Object>, BerthSearch> berthSearches = new HashMap<>();
	// The search by cost from the boat the player is aboard, shared by the start links, made on first ask
	private SailingSolver startSolver;
	private int legsSailed;
	private long sailingNanos;

	/**
	 * @param seaTargets the requested targets at sea
	 * @param gate       the walking search's positional gates, applied to every sailing move too
	 */
	public SailingSeaEnds(CollisionMap map, PreparedSailingCapability capability, int start, int[] seaTargets,
		SailingQuery.EdgeGate gate)
	{
		this.map = map;
		this.waters = SailingWaters.of(map);
		this.fleet = capability.fleet();
		this.targets = new SeaTargets(map, waters, seaTargets);
		this.gate = gate;
		SailingFleet.Aboard aboard = fleet.aboard();
		this.startTiles = aboard == null ? EMPTY : startTiles(aboard, start, capability.moorings());
		this.start = startTiles.length > 0 ? start : WorldPointUtil.UNDEFINED;
		List<SailingMooring> landable = landable(capability.moorings());
		if (startsAtSea()) addStartLinks(aboard, landable);
		if (!targets.isEmpty()) addTargetLinks(capability, landable);
	}

	// The moorings whose land tile is walkable: where a boat can put the player ashore
	private List<SailingMooring> landable(List<SailingMooring> moorings)
	{
		int[] walkable = SailingMoorings.walkableLandTiles(map);
		Arrays.sort(walkable);
		List<SailingMooring> landable = new ArrayList<>();
		for (SailingMooring mooring : moorings)
		{
			if (Arrays.binarySearch(walkable, mooring.landTile()) >= 0) landable.add(mooring);
		}
		return landable;
	}

	// From the boat aboard to each mooring it can dock at, and straight to the targets
	private void addStartLinks(SailingFleet.Aboard aboard, List<SailingMooring> landable)
	{
		SailingVessel vessel = aboard.vessel();
		BoatHull hull = vessel.hull(aboard.pivotX(), aboard.pivotY());
		List<Integer> docking = new ArrayList<>();
		for (SailingMooring mooring : landable)
		{
			if (!mooring.fits(vessel)) continue;
			// Beyond a channel the hull can't get through or water it can't enter: no search would dock
			if (!waters.connects(vessel, hull, startTiles, mooring.dockingTiles())) continue;
			int sailed = ticks(vessel, Tiles.distance(startTiles, mooring.berthTile()) - SailingMooring.DOCKING_RANGE);
			docking.add(add(new Link(Kind.START, mooring, null, vessel, hull, aboard.heading(),
				SailingMooring.DISEMBARK_TICKS + sailed)));
		}
		startLinks = docking.stream().mapToInt(Integer::intValue).toArray();
		if (targets.isEmpty()) return;
		int bound = targets.straightLineTicks(vessel, hull, startTiles);
		if (bound == NO_LINK || !targets.reachable(vessel, hull, startTiles)) return;
		targets.sailedToBy(hull, vessel.moves());
		directLink = add(new Link(Kind.DIRECT, null, null, vessel, hull, aboard.heading(), bound));
	}

	// From each mooring with a boat of the player's to the targets
	private void addTargetLinks(PreparedSailingCapability capability, List<SailingMooring> landable)
	{
		for (SailingMooring mooring : landable)
		{
			for (SailingFleet.Boat boat : fleet.boats())
			{
				if (!capability.canBoard(mooring, boat)) continue;
				SailingVessel vessel = boat.vessel();
				BoatHull hull = vessel.hull();
				int[] castOff = waters.castOff(map, mooring, vessel);
				int sailed = targets.straightLineTicks(vessel, hull, castOff);
				if (sailed == NO_LINK) continue;
				// Beyond a channel the hull can't get through or water it can't enter: no search would arrive
				if (!targets.reachable(vessel, hull, castOff)) continue;
				// Round the land between, cell by cell over the hull's own water
				int cells = targets.cellTicks(vessel, hull, castOff);
				if (cells == NO_LINK) continue;
				targets.sailedToBy(hull, vessel.moves());
				int link = add(new Link(Kind.TARGET, mooring, boat, vessel, hull, -1,
					SailingMooring.BOARD_TICKS + Math.max(sailed, cells)));
				int[] from = targetLinksByLand.get(mooring.landTile());
				int[] grown = from == null ? new int[1] : Arrays.copyOf(from, from.length + 1);
				grown[grown.length - 1] = link;
				targetLinksByLand.put(mooring.landTile(), grown);
			}
		}
	}

	private int add(Link link)
	{
		links.add(link);
		return links.size() - 1;
	}

	/**
	 * Whether the query has anything at sea: a start aboard, targets at sea some boat can reach, or a boat of the
	 * player's whose legs between moorings were timed for another: at a speed no hull gives, or with fewer parts.
	 */
	public boolean active()
	{
		if (atSea()) return true;
		for (SailingFleet.Boat boat : fleet.boats())
		{
			SailingVessel vessel = boat.vessel();
			if (LegTimes.index(vessel.speed()) < 0 || !vessel.areas().containsAll(vessel.fullyFitted().areas()))
				return true;
		}
		return false;
	}

	/**
	 * Whether a route may be at sea at its ends: it starts aboard, or some boat can sail to the targets at sea. Only
	 * then is a target at sea reached aboard rather than walked to.
	 */
	public boolean atSea()
	{
		return startsAtSea() || targetLinksByLand.size() > 0;
	}

	/** The leg sailed for {@code transport}'s crossing link, or {@code null} if it has none or was not sailed. */
	public SailingLeg sailedLeg(Transport transport)
	{
		Integer link = crossings.get(transport);
		return link == null || link < 0 ? null : links.get(link).leg;
	}

	/**
	 * The land tiles target links board from, with the least bound of their links: the heuristic's seeds, as
	 * {@code {tiles, bounds}}.
	 */
	public int[][] boardingSeeds()
	{
		Map<Integer, Integer> least = new LinkedHashMap<>();
		for (Link link : links)
		{
			if (link.kind == Kind.TARGET) least.merge(link.mooring.landTile(), link.bound, Math::min);
		}
		int[][] seeds = new int[2][least.size()];
		int i = 0;
		for (Map.Entry<Integer, Integer> seed : least.entrySet())
		{
			seeds[0][i] = seed.getKey();
			seeds[1][i++] = seed.getValue();
		}
		return seeds;
	}

	/** Time spent sailing legs, in nanoseconds. */
	public long sailingNanos()
	{
		return sailingNanos;
	}

	@Override
	public boolean startsAtSea()
	{
		return start != WorldPointUtil.UNDEFINED;
	}

	@Override
	public int[] startLinks()
	{
		return startLinks;
	}

	@Override
	public int directLink()
	{
		return directLink;
	}

	@Override
	public int[] targetLinksFrom(int landTile)
	{
		int[] found = targetLinksByLand.get(landTile);
		return found == null ? EMPTY : found;
	}

	@Override
	public int crossingLink(Transport transport)
	{
		if (!transport.getType().isSailing()) return -1;
		Integer known = crossings.get(transport);
		if (known != null) return known;
		SailingFleet.Boat boat = SailingTransports.boat(transport, fleet);
		int index = -1;
		if (boat != null && !SailingTransports.timedFor(transport, boat.vessel()))
		{
			Link link = new Link(Kind.CROSSING, SailingTransports.from(transport), boat, boat.vessel(),
				boat.vessel().hull(), -1, transport.getDuration());
			link.docksAt = SailingTransports.to(transport);
			link.transport = transport;
			index = add(link);
		}
		crossings.put(transport, index);
		return index;
	}

	@Override
	public int landTile(int link)
	{
		Link sailed = links.get(link);
		return (sailed.kind == Kind.CROSSING ? sailed.docksAt : sailed.mooring).landTile();
	}

	@Override
	public int bound(int link)
	{
		return links.get(link).bound;
	}

	@Override
	public int refine(int index, int bound, BooleanSupplier cancelled)
	{
		Link link = links.get(index);
		if (link.exact >= 0) return link.exact;
		long started = System.nanoTime();
		try
		{
			switch (link.kind)
			{
				case START:
					return refineStart(link, bound, cancelled);
				case CROSSING:
					return refineCrossing(link, bound, cancelled);
				default:
					return refineTarget(link, bound, cancelled);
			}
		}
		finally
		{
			sailingNanos += System.nanoTime() - started;
		}
	}

	/**
	 * A start link's leg: the search by cost from the boat sails on until it docks there, or until its frontier has
	 * passed this bound, timing every mooring it docks at on the way.
	 */
	private int refineStart(Link link, int bound, BooleanSupplier cancelled)
	{
		if (startSolver == null) startSolver = startSolver();
		startSolver.advance(bound - SailingMooring.DISEMBARK_TICKS + STEP_TICKS, cancelled, new SailingSolver.Listener()
		{
			@Override
			public boolean exit(int exit, int node)
			{
				Link docked = links.get(startLinks[exit]);
				if (docked.exact < 0) record(docked, startSolver.leg(node, docked.mooring.berthTile()));
				return true;
			}
		});
		if (link.exact >= 0) return link.exact;
		int frontier = startSolver.frontier();
		return frontier == SailingSolver.EXHAUSTED ? NO_LINK : Math.max(bound, SailingMooring.DISEMBARK_TICKS + frontier);
	}

	// The search by cost from the boat, docking at the moorings of the start links in their order
	private SailingSolver startSolver()
	{
		List<SailingMooring> exits = new ArrayList<>();
		for (int link : startLinks) exits.add(links.get(link).mooring);
		Link first = links.get(startLinks[0]);
		SailingSolver solver = new SailingSolver(map, SailingQuery.of(first.vessel.moves(), first.hull)
			.withHeading(first.heading).withExits(exits).withGate(gate)
			.landingOn(waters.landing(map, first.vessel, first.hull)));
		solver.castOff(startTiles, 0);
		return solver;
	}

	/**
	 * A target or direct link's leg: first the field, cheap, which sees round the land the straight line crosses; then
	 * the leg's own search, a step at a time. Its frontier bounds the leg, so a leg that never arrives costs only as
	 * much search as the routes cheaper than it leave room for.
	 */
	private int refineTarget(Link link, int bound, BooleanSupplier cancelled)
	{
		int boarding = link.boarding();
		if (link.solver == null)
		{
			SeaField field = targets.field(link.vessel.moves());
			field.growTo(bound - boarding + GROWTH_TICKS);
			int ticks = SeaTargets.fieldTicks(field, setsOutFrom(link));
			if (ticks == Integer.MAX_VALUE) return link.exact = NO_LINK;
			if (boarding + ticks > bound) return boarding + ticks;
			link.solver = targetSolver(link, field);
		}
		SailingSolver solver = link.solver;
		SailingLeg[] leg = new SailingLeg[1];
		solver.advance(bound - boarding + STEP_TICKS, cancelled, new SailingSolver.Listener()
		{
			@Override
			public boolean target(int target, int node)
			{
				leg[0] = solver.leg(node, target);
				return false;
			}
		});
		if (leg[0] != null)
		{
			record(link, leg[0]);
			releaseSolver(link);
			return link.exact;
		}
		if (cancelled.getAsBoolean()) return bound;
		int frontier = solver.frontier();
		if (frontier == SailingSolver.EXHAUSTED)
		{
			releaseSolver(link);
			return link.exact = NO_LINK;
		}
		return Math.max(bound, boarding + frontier);
	}

	/**
	 * The search for a target or direct link's leg, towards the targets over their field. It estimates each tile at
	 * the field's ticks, but at most what the field had reached when the search began: the field grows on for other
	 * links, and the cap keeps this search's estimate the same throughout, so consistent.
	 */
	private SailingSolver targetSolver(Link link, SeaField field)
	{
		int cap = field.reachedTicks();
		IntUnaryOperator estimate = tile ->
		{
			int ticks = field.ticks(tile);
			return ticks == Integer.MAX_VALUE ? SailingSolver.NO_ROUTE : Math.min(ticks, cap);
		};
		SailingSolver solver = new SailingSolver(map, SailingQuery.of(link.vessel.moves(), link.hull)
			.withHeading(link.heading).withTargets(targets.tiles()).withGate(gate).withEstimate(estimate)
			.landingOn(waters.landing(map, link.vessel, link.hull)));
		solver.castOff(setsOutFrom(link), 0);
		return solver;
	}

	private static void releaseSolver(Link link)
	{
		if (link.solver != null) link.solver.release();
		link.solver = null;
	}

	/**
	 * A crossing's leg, a step past its bound at a time. The crossings from one berth for one boat share a search by
	 * cost from it, which docks at every mooring the boat fits as it reaches them: its frontier bounds every leg it
	 * has not docked yet, and it goes only as far as the dearest crossing the router has asked about.
	 */
	private int refineCrossing(Link link, int bound, BooleanSupplier cancelled)
	{
		int ends = SailingMooring.BOARD_TICKS + SailingMooring.DISEMBARK_TICKS;
		BerthSearch search = berthSearches.computeIfAbsent(List.of(link.mooring, link.vessel),
			key -> new BerthSearch(link.mooring, link.vessel));
		if (search.legs.get(link.docksAt) == null && !search.exhausted)
			search.advance(bound - ends + STEP_TICKS, cancelled);
		SailingLeg leg = search.legs.get(link.docksAt);
		if (leg != null)
		{
			record(link, leg);
			return link.exact;
		}
		if (search.exhausted) return link.exact = NO_LINK;
		if (cancelled.getAsBoolean()) return bound;
		return Math.max(bound, ends + search.solver.frontier());
	}

	/** The search by cost from one berth for one boat, shared by the crossings that board there. */
	private final class BerthSearch
	{
		private final List<SailingMooring> exits = new ArrayList<>();
		private final Map<SailingMooring, SailingLeg> legs = new HashMap<>();
		private final SailingSolver solver;
		private boolean exhausted;

		BerthSearch(SailingMooring from, SailingVessel vessel)
		{
			for (SailingMooring mooring : SailingMoorings.all())
			{
				if (mooring != from && mooring.fits(vessel)) exits.add(mooring);
			}
			BoatHull hull = vessel.hull();
			// As the legs are timed: the boat casts off in docking range of the berth, every move ending on sea the
			// hull fits, in water the boat's parts let it enter
			solver = new SailingSolver(map, SailingQuery.of(vessel.moves(), hull).withExits(exits)
				.landingOn(waters.landing(map, vessel, hull)));
			solver.castOff(waters.castOff(map, from, vessel), 0);
		}

		void advance(int limit, BooleanSupplier cancelled)
		{
			solver.advance(limit, cancelled, new SailingSolver.Listener()
			{
				@Override
				public boolean exit(int exit, int node)
				{
					legs.putIfAbsent(exits.get(exit), solver.leg(node, exits.get(exit).berthTile()));
					return true;
				}
			});
			exhausted = solver.frontier() == SailingSolver.EXHAUSTED;
		}
	}

	@Override
	public int exact(int index, BooleanSupplier cancelled)
	{
		// The search reaches here once refine has stopped raising the bound, which it does only once the leg is found
		Link link = links.get(index);
		int bound = link.bound;
		while (link.exact < 0 && !cancelled.getAsBoolean())
		{
			int raised = refine(index, bound, cancelled);
			if (raised == NO_LINK) return NO_LINK;
			bound = Math.max(bound + STEP_TICKS, raised);
		}
		return link.exact < 0 ? NO_LINK : link.exact;
	}

	@Override
	public int[][] leg(int link)
	{
		int[][] leg = links.get(link).tilesAndCosts;
		if (leg == null) throw new IllegalStateException("link " + link + " not sailed");
		return leg;
	}

	@Override
	public Transport boardingTransport(int index)
	{
		Link link = links.get(index);
		if (link.kind != Kind.TARGET) return null;
		return new Transport.TransportBuilder().origin(link.mooring.landTile()).destination(link.reached)
			.type(link.vessel.kind().transportType()).duration(SailingMooring.BOARD_TICKS)
			.displayInfo("Board " + link.vessel.kind().name().toLowerCase(Locale.ROOT) + " at " + link.mooring.name())
			.build();
	}

	@Override
	public int seaTarget(int link)
	{
		return links.get(link).reached;
	}

	@Override
	public int legsSailed()
	{
		return legsSailed;
	}

	// Keeps a link's leg and its cost, boarding and stepping off included
	private void record(Link link, SailingLeg leg)
	{
		legsSailed++;
		int boarding = link.boarding();
		int[] tiles = new int[leg.size()], costs = new int[leg.size()];
		for (int i = 0; i < leg.size(); i++)
		{
			tiles[i] = leg.point(i);
			costs[i] = boarding + leg.costAt(i);
		}
		link.leg = leg;
		link.tilesAndCosts = new int[][] {tiles, costs};
		link.reached = leg.reached();
		link.exact = boarding + leg.cost() + link.disembarking();
	}

	// Where a target or direct link's boat sets out from: where it casts off for a target link, its own tiles aboard
	private int[] setsOutFrom(Link link)
	{
		return link.kind == Kind.TARGET ? waters.castOff(map, link.mooring, link.vessel) : startTiles;
	}

	/**
	 * Where the boat the player is aboard sets out from: its tile, when the boat can be there; otherwise it is moored,
	 * over the dock, and casts off from the mooring nearest it in docking range; none if neither.
	 */
	private int[] startTiles(SailingFleet.Aboard aboard, int tile, List<SailingMooring> moorings)
	{
		SailingVessel vessel = aboard.vessel();
		BoatHull hull = vessel.hull(aboard.pivotX(), aboard.pivotY());
		if (waters.contains(tile) && waters.allows(vessel, tile) && hull.fits(map, WorldPointUtil.unpackWorldX(tile),
			WorldPointUtil.unpackWorldY(tile), WorldPointUtil.unpackWorldPlane(tile))) return new int[] {tile};
		SailingMooring nearest = null;
		for (SailingMooring mooring : moorings)
		{
			if (!mooring.inDockingRange(tile) || !mooring.fits(vessel)) continue;
			if (nearest == null || Tiles.distance(tile, mooring.berthTile()) < Tiles.distance(tile, nearest.berthTile()))
				nearest = mooring;
		}
		return nearest == null ? EMPTY : waters.castOff(map, nearest, vessel);
	}

	// The ticks a boat needs to sail a straight line of tiles at its top speed, rounded down
	private static int ticks(SailingVessel vessel, double tiles)
	{
		return (int) Math.floor(Math.max(0, tiles) / vessel.moves().topSpeed());
	}
}
