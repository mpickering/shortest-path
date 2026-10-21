# A* pathfinder

The A* pathfinder is an optional tile search. Select it from the native RuneLite
"Pathfinder algorithm" dropdown, which displays `Legacy` and `A*`; `Legacy`
remains the default. RuneLite persists the typed enum as `LEGACY` or `ASTAR`.
If A* fails, the failure is reported and the plugin never falls back to legacy
silently. Legacy profile settings are migrated when that configuration profile
becomes active.

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

## Avoid wilderness

With Avoid wilderness on, A* first searches for a route that never enters the Wilderness from
outside it, using a heuristic built for that rule (see
[the heuristic](astar-heuristic.md#7-avoiding-the-wilderness-wildernessgate)). If that search
finds nothing, and was neither cancelled nor cut off, A* searches again without the gate, so a
place only reachable through the Wilderness still gets a route. This covers targets inside the
Wilderness and places outside it whose only way in starts there, such as the Abyss or Ferox
Enclave without teleports. `AStarPathfinder.isWildernessGateLifted()` reports it, and the debug
panel shows it.

Legacy behaves differently: it lifts the gate only for a target inside the Wilderness, and
otherwise reports no route.

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
