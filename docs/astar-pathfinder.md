# A* pathfinder

The A* pathfinder is the plugin's tile search — its only engine. It computes
every shown path and answers every cross-plugin `query`. The legacy engine
survives only in the test sources, as a frozen oracle the parity tests and the
route corpus check A* against. If A* fails to build its routing data or run a
search, the failure is reported — a query answers with `BACKEND_FAILURE` —
rather than silently degrading.

## Layout

A* core code lives in `src/main/java/shortestpath/pathfinder/astar/`.
`AStarPathfinder` adapts it to the plugin's `ActiveSearch` contract, which the
legacy `Pathfinder` also implements. `PathStep`, `PathfinderResult`, packed
coordinates, collision data and transport types are shared with legacy.

`ShortestPathConfig` owns RuneLite user settings. `PathfinderConfig` owns
client/account refresh, requirement evaluation, transport availability, costs,
wilderness preference, bank-path enablement, bank requirements and the
calculation cutoff. A* code consumes prepared values and never evaluates
requirements or reads the client directly.

## Data lifetimes

- **Static routing data** (`RoutingStatic`) is derived once per plugin session,
  on the pathfinding thread, the first time an A* search runs
  (`AStarRoutingStaticProvider`, `RoutingStaticBuilder`). It comes from
  `collision-map.zip`, the transport and bank resources, and
  `routing-cuts.bin`.
- **Prepared account data** (`PreparedRoutingAccount`) is compiled from the
  current account state for each search. The `SiteGraph` built from it is kept
  by `AStarRoutingSession` while the account's routing fingerprint is
  unchanged.
- **Per-target data** (`TargetOverlay`, `ReverseLabels`, `PreparedHeuristic`,
  held by `PreparedTarget`) gives an admissible heuristic for one target set;
  see [astar-heuristic.md](astar-heuristic.md). The session keeps recent
  targets for reuse while the graph and collision map are unchanged.
- **Search state** (`AStarForwardSearch`) belongs to one active search.
  Costs and predecessors use 1,024-entry pages allocated on first write,
  including bank layers and teleport hubs. Reading an untouched page returns
  the initial value without allocating it. Only the small page directories
  are allocated for the full state space upfront.

## Routing cuts

Large walking areas are split into routing components along separator cut
edges stored in `src/main/resources/routing-cuts.bin`. The cuts only affect how
much preparation and search the heuristic needs: cuts that no longer match the
collision map are ignored, so routes stay exact with any cut file, including an
empty one.

The file is generated with KaHIP by the `routingCuts` task in
[shortest-path-tooling](https://github.com/osrs-pathfinding/shortest-path-tooling).
The `ExtractCollisionMap` workflow regenerates it whenever it dumps a new
collision map and commits both files together.

## Sailing

### The model

Everything below is a cache, a bound or a shortcut for this model, and must
agree with it: a transport's time is the model's cost for the boat it was
timed for, a bound is never more than the model's cost, and nothing pruned is a
state the model can reach.

- **States.** The player is on land at a tile, as when walking, or aboard a
  boat at sea: the boat (its kind, its parts and its base speed), its tile and
  its heading. The boat sits at its tile's centre throughout; the game's 16
  points within a tile aren't modelled.
- **A boat state is possible** when its hull, facing its heading, overlaps no
  tile that blocks boats (`BoatHull.fits`), on sea in an area its parts let
  it enter.
- **Board** at a mooring's land tile, with a boat of the player's moored there
  that fits the mooring, or one a shipwright there brings for coins: the boat
  is then at any possible state within docking range of the berth
  (`SailingWaters.castOff`), for the boarding ticks. Boarding mirrors docking;
  the berth itself, a blocked dock tile, is never a boat state.
- **Sail** one move (`SailingMoves`): from a possible state to another, with
  the hull's sweep along the move clear, for the move's ticks. Turning is
  free: the boat may take any heading before a move.
- **Dock** from any possible state within docking range of a mooring the boat
  fits and the player meets the requirements of: the player is on the
  mooring's land tile, for the docking ticks.
- **A start aboard** is the boat's state, or, when that isn't possible (a boat
  moored over its dock), where it casts off from the mooring nearest it. A
  target at sea is reached by a boat state that covers it.

What the model leaves out, for now: where the boat is after docking (a later
boarding goes by where the player's boats were when the route was asked for),
turning time, speeding up and slowing down. The docking range (5 tiles) and
the boarding and docking ticks are estimates.

### Transports and sea links

Sailing the player's own boat between moorings is an ordinary transport, one
type per boat kind (`SAILING_RAFT`, `SAILING_SKIFF`, `SAILING_SLOOP`). A boat
boarded at a mooring always casts off the same way, so each leg's time doesn't
depend on how the player got there: shortest-path-tooling's `sailingData`
task (about a minute) times every pair of moorings per boat kind and per base
speed a hull gives (`LegTimes.SPEEDS`: 1.5, 2, 2.5 and 3 tiles a tick), with a
search by cost over the whole sea from where the boat casts off at each, and
writes them to `transports/sailing_*.tsv`, a column per speed (`At 1.5`, ...).
The legs are timed for a boat with every part its kind can take. A row's
`Duration` is the fastest of its times, so it bounds the leg for any boat at
those speeds. The moves change with the speed, each tick's velocity being
rounded to quarter tiles, so a leg one speed sails another may not: 55 sloop
legs are sailed only at 2 tiles a tick, and 4 only at the other speeds.

Everything else sailing needs is per tile, so it is in the collision map, made
when the map is:

- `sea/<region>` and `sea/legend`, from the collision map dumper: a byte per
  tile, 0 where its floor is not sea water, else the number of its area, `OPEN`
  water or a restricted one (below), read from the game's water textures. The
  collision flags can't tell water from land: open sea is as walkable as a
  beach. Only the generator reads it.
- `sailing/tiles`, from the generator, the one layer read at runtime
  (`SailingWaters`): a number per tile packing its area (0 off the sea) and, for
  each boat kind, its body of water (0 for none). The sea is every water tile
  reachable by open tile edges between water tiles from the water round a
  berth: it decides which targets are at sea, so a target on an island's beach
  is walked to.
- `sailing/index`: the fingerprint, then each kind's bodies of water, with
  their areas and the bodies they link to.

A kind's bodies of water come from every move the kind's hull clears at its
tile's centre, at any base speed up to 8 tiles a tick: each such move joins
its two ends into one body, within one area, and a move from one area to
another links their bodies, so every route a boat of the kind sails, at
whatever speed, goes through linked bodies. Only bodies of at least 100 tiles
a boat can get to from a berth are kept, about eight per kind: one per area,
and two pools of open water behind the northern ice. Coastal pockets, and
water such as the Elid Delta or the Lum lagoon for boats bigger than a raft,
are no body, and so unreachable.

Tooling's `sailingData` task writes the sailing layer into `collision-map.zip`,
then times the legs on it. `SailingTransportsTest` fails when either is stale,
and the `ExtractCollisionMap` workflow makes them again with each new
collision map, before the routing cuts, whose fingerprint covers the whole
zip. Once a route is found, `SailingRoutes` fills in each leg's moves for
drawing.

### Restricted sea areas

Some of the sea is restricted (`SeaArea`): Tempor Tantrum, the northern icy
waters and crystal-flecked, fetid and kelp water, which a boat enters only with
a part fitted. A boat's moves end only in water its parts let it enter
(`SailingVessel.areas`). Each speed's cell names the areas its leg sails
through at that speed, and the row's `Sea areas` every one of them. For a boat
at one of the speeds with the parts for them, the cell's time is the boat's
own; for any other boat it only bounds it, and the boat's own leg is sailed
when the search wants it (below). Keeping out of water only makes a leg longer,
so with fewer parts the cell's time is the bound. A boat at a speed no hull
gives (none does today) is bounded by the straight line between the berths,
less the docking range at each end, at its top speed. So the tables grow with
the moorings, not with the ways a boat can be fitted out.

### The player's boats

What the router may assume about the player's boats is a `SailingFleet`: the
boats they own, what boarding each costs at each mooring (`Boat.boardingCost`),
and the boat they are aboard, if any. `PathfinderConfig` keeps a sailing
transport only when the account has a boat of its kind it can board where the
transport boards, prices the leg for that boat, and drops one where no water
the boat may enter joins where it casts off to where it docks.

The live plugin builds the fleet from the game (`SailingFleets.fromState`). It
reads the player's boats from their slots' varbits (`SailingService.boats`):
each owned boat's type, the dock it is moored at (`moorings.tsv`'s `Dock`
column, the game's dock ids), its hull's base speed and the restricted areas
its own parts allow. The fitted parts are option numbers in the boat type's
lists in the cache's `sailing_boat` table: an adamant keel or helm or better
for crystal-flecked water and kelp, oak and linen sails or better for Tempor
Tantrum, the eternal brazier for the icy seas, and the inoculation station
hotspot for fetid water. The boat the player is aboard is the one last boarded,
and is not at its dock; its parts come from the side panel's resistances
(`SailingService.seaAreas`): one each for fetid, crystal-flecked, kelp and icy
water, and a storm resistance level for Tempor Tantrum, where any level above
none is taken to be enough (not yet checked in game).

A boat can also be boarded at a mooring with a shipwright (`moorings.tsv`'s
`Shipwright` column), who brings it from wherever it is for the game's recovery
fee (`SailingService.recoveryCost`), when that is within the "Boat Recovery Limit"
setting and the coins the player has. The leg then needs the coins: carried, or
from a bank visit first. Speeds are in the game's units, 128ths of a tile per
tick (`BoatSpeed`), throughout.

### Sea links

A start aboard a boat, targets at sea, and legs between moorings timed for
another boat attach to the search through `SeaEnds` links: from the start to
each mooring the boat can dock at, from each mooring with a boat to the
targets, from the start straight to them, and crossings, one per such leg,
which land where the transport does.
Targets at sea join the heuristic as seeded targets at those moorings
(`TargetOverlay.withSeedCosts`). `SailingRouteQuery` puts this together for
one query: the links, which targets are walked to, the heuristic's seeds, and
the found route's legs. A link waits in its own queue at a lower bound
on its cost, which `SailingSeaEnds` raises step by step (a distance field from
the targets, then the leg's own search, whose frontier also bounds the leg)
until the leg is sailed, so only legs that could end up on the route are
searched, and only as far as cheaper routes leave room for. A link that can't be
sailed is never offered, so no search runs to find that out: a boat goes only
through linked bodies of water of its kind, in areas its parts let it into
(`SailingWaters.connects`). The bodies describe a boat of a standard kind at
its tile's centre; for any other boat they can't tell, and every link is
offered. Every sailing search, including filling in a found route's legs,
stops at the calculation cutoff, as the walking search does.
