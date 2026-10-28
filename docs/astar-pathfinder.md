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
  tile that blocks boats (`BoatHull.fits`), on sea in a piece of water its
  parts let it enter.
- **Board** at a mooring's land tile, with a boat of the player's moored there
  that fits the mooring: the boat is then at any possible state within docking
  range of the berth (`SailingWaters.castOff`), for the boarding ticks.
  Boarding mirrors docking; the berth itself, a blocked dock tile, is never a
  boat state.
- **Sail** one move (`SailingMoves`): from a possible state to another, with
  the hull's sweep along the move clear, for the move's ticks. Turning is
  free: the boat may take any heading before a move.
- **Dock** from any possible state within docking range of a mooring the boat
  fits and the player meets the requirements of: the player is on the
  mooring's land tile, for the docking ticks.
- **A start aboard** is the boat's state, or, when that isn't possible (a boat
  moored over its dock), where it casts off from the mooring nearest it. A
  target at sea is reached by a boat state that covers it.

What the model leaves out, for now: where the boat is after docking (a boat
can be paid to move to any port, so a later boarding doesn't depend on it),
turning time, speeding up and slowing down. The docking range (5 tiles) and
the boarding and docking ticks are estimates.

### Transports and sea links

Sailing the player's own boat between moorings is an ordinary transport, one
type per boat kind (`SAILING_RAFT`, `SAILING_SKIFF`, `SAILING_SLOOP`). A boat
boarded at a mooring always casts off the same way, so each leg's time doesn't
depend on how the player got there: `SailingTimesGenerator` (the `sailingTimes`
task, about half a minute) times every pair of moorings once per boat kind,
with a search by cost over the whole sea from where the boat casts off at each,
and writes them to
`transports/sailing_*.tsv`. The legs are timed for a boat with every part its
kind can take, at 2 tiles a tick.
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
pieces its parts let it enter (`SailingVessel.areas`). Each row's `Sea areas`
column names the areas its leg sails through. For a boat with the parts for
them, at the speed the legs are timed at, the row's time is the boat's own; for
any other boat it only bounds it, and the boat's own leg is sailed when the
search wants it (below). Keeping out of water only makes a leg longer, so with
fewer parts the row's time is the bound. At another speed the moves themselves
change, each tick's velocity being rounded to quarter tiles, so the bound is the
straight line between the berths, less the docking range at each end, at the
boat's top speed. So the tables grow
with the moorings, not with the ways a boat can be fitted out. `PathfinderConfig`
prices each leg for the account's boat, and drops one where no water the boat
may enter joins where it casts off to where it docks. The live plugin doesn't read a boat's parts yet, so it
takes the boat the player is aboard to have them all.

A start aboard a boat, targets at sea, and legs between moorings timed for
another boat attach to the search through `SeaEnds` links: from the start to
each mooring the boat can dock at, from each mooring with a boat to the
targets, from the start straight to them, and crossings, one per such leg,
which land where the transport does.
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
