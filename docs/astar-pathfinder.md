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

Sailing the player's own boat between moorings is an ordinary transport, one
type per boat kind (`SAILING_RAFT`, `SAILING_SKIFF`, `SAILING_SLOOP`). A boat
leaving a berth always sets out the same way, so each leg's time doesn't depend
on how the player got there: `SailingTimesGenerator` (the `sailingTimes` task,
about two minutes) times every pair of moorings once, with searches by cost
over the whole sea from each berth, and writes them to `transports/sailing_*.tsv`.
The same searches give `sailing/sea-reach.bin`, the sea each boat kind can
reach, numbered in components: moorings whose boats reach a common tile are one
component. `SailingTransportsTest` fails when either is stale, and the
`ExtractCollisionMap` workflow makes them again with each new collision map.
`PathfinderConfig` keeps a sailing transport only when the account has a boat
of its kind moored where it boards; the live plugin assumes none on foot. Once
a route is found, `SailingRoutes` fills in each leg's moves for drawing.

Some of the sea is restricted (`SeaArea`): Tempor Tantrum, the northern icy
waters and crystal-flecked, fetid and kelp water, which a boat enters only with
a part fitted. Each is a family of water textures in the cache. The collision
map dumper marks the edges round each one with the boundary flag a door has,
leaving the movement flags alone, and writes `sailing/sea-areas.tsv`, a tile of
each piece of restricted sea per region. `SailingWaters` splits the sea into
pieces at those edges and names each from its tiles. A boat's moves end only in
pieces its parts let it enter (`SailingVessel.areas`). A leg that is quicker
through restricted water, or only possible through it, has a row of its own
whose `Sea areas` column names the areas, and only a boat with those parts may
sail it: the generator searches each berth with every area open, then again
without each area the fastest legs went through, so every set of parts has its
fastest leg among the rows. The live plugin doesn't read a boat's parts yet, so
it takes the boat the player is aboard to have them all.

A start aboard a boat, and targets at sea, attach to the search through
`SeaEnds` links: from the start to each mooring the boat can dock at, from each
mooring with a boat to the targets, and from the start straight to them.
Targets at sea join the heuristic as seeded targets at those moorings
(`TargetOverlay.withSeedCosts`). A link waits in its own queue at a lower bound
on its cost, which `SailingSeaEnds` raises step by step (a distance field from
the targets, then the leg's own search, whose frontier also bounds the leg)
until the leg is sailed, so only legs that could end up on the route are
searched, and only as far as cheaper routes leave room for. A link that can't be
sailed is never offered, so no search runs to find that out: a boat sailing
from one component of its kind's sea only reaches that component, and which
pieces of sea touch, within a move of each other, makes a graph of a few dozen
nodes that tells whether its parts let it get from one piece to another.
