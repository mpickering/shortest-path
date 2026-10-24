# A* pathfinder heuristic

How the A* pathfinder builds the A* heuristic for a target, and why the result
is admissible. Classes are in `src/main/java/shortestpath/pathfinder/astar/`.

## What the heuristic is

The heuristic is the exact distance to the target in a *relaxed* graph. That
graph keeps every transport, bank and separator edge but ignores collision
inside a routing component. For a tile `x` in routing component `c`, with
`banked` the search state's bank layer:

```text
h(x, banked) = min over sites s attached to c with a finite label:
                   label(s, banked) + chebyshev(x, s)
```

`label(s, banked)` is the relaxed shortest cost from site `s` to the target.
Walking costs 1 per step, diagonals included, so on one plane the real walking
distance is never below the Chebyshev distance. Any real route out of `c` has
to leave through a site: a transport origin, a separator crossing endpoint or
a bank. That makes `h` a lower bound. If `h` is `INF`, the relaxed graph proves
that no route exists, and the search prunes the state rather than treating it
as 0.

A tile whose component cannot reach the target through any site has every
bucket empty, so it is pruned without being expanded.

## Pipeline and lifetimes

| Stage | Lifetime | Class | Produces |
|---|---|---|---|
| Sites, components, sparse network | plugin session | `RoutingStaticBuilder` → `RoutingStatic` | sites, attachments, `ExactWalks`, `SparseWalkingNetworkBuilder` CSR |
| Relaxed account graph | account fingerprint | `SiteGraph` | two-layer reverse CSR of explicit edges |
| Target overlay | target set | `TargetOverlay` | target nodes and attachment edges |
| Reverse search | target set | `ReverseLabels` | `label(node, banked)` and generator provenance |
| Seed and generator tables | target set | `PreparedHeuristic` | per-`(component, banked)` buckets |
| Lookup | search | `AStarForwardSearch.effectiveHeuristic` | `h` for each pushed state |
| Wilderness gate | plugin session, built with the static data | `WildernessGate` | pieces and the gated sparse network ([section 7](#7-avoiding-the-wilderness-wildernessgate)) |

`AStarRoutingSession` caches the graph by account fingerprint, and
`PreparedTarget` holds the overlay, labels and heuristic. Its cache identity includes each
unsigned target coordinate and its accepted bank-layer mask, so changing held target items
can select different preparation without invalidating an otherwise unchanged account graph.
Input order and duplicates do not matter; duplicate coordinates union their layer masks.
Nothing before the lookup
depends on the start tile.

## 1. Static sites (`RoutingStatic`)

Sites are the union of all transport origins and destinations (local and
global), separator crossing endpoints, and structurally reachable banks. Each
site records **every** routing component it attaches to: its own component if it
is walkable, otherwise the components of its walkable neighbours (a blocked
fairy ring, for example, can attach to several). A site with no attachment
still exists as a node, which is how pure transport chains work.

## 2. Relaxed account graph (`SiteGraph`)

Each node has two states, `stateId(node, banked) = node * 2 + (banked ? 1 : 0)`.
The graph stores the *reverse* CSR of these explicit edges (`EdgeKind`):

| Edge | From → to | Cost | Starts generator |
|---|---|---|---|
| `LOCAL` | `(origin, b)` → `(dest, b)` for each available transport in layer `b` | duration + penalty | yes |
| `BANK_TRANSITION` | `(bank, false)` → `(bank, true)` for each bank the account may use | bank visit cost | yes |
| `BANK_GLOBAL_ENTRY` | `(bank, false)` → `(hub, true)` for the same banks | bank visit cost | yes |
| `BANK_GLOBAL_DESTINATION` | `(hub, true)` → `(dest, true)` | cheapest banked global to `dest` | **no** |
| `SEPARATOR` | both directions, both layers | crossing cost (1) | yes |

The single abstract **hub** node replaces a banks × destinations bipartite
graph. It exists only when transports and bank paths are enabled, the account
may use at least one bank, and at least one banked global destination exists.

`RoutingStatic` holds every bank in the world. Which of them the account may
use is `PreparedRoutingAccount.bankAccessible`, compiled from the same set
legacy uses (`PathfinderConfig.bankAccessible`). The forward search applies
the same rule when it enters the bank layer.

Carried global teleports are deliberately **absent**. A global can be used from
anywhere, so using it later is never cheaper than using it at the start, and
the forward search seeds carried global destinations at the start instead.
Banked globals are only reachable through a bank, hence the hub. For
Wilderness starts, where globals are not usable at the start, see
[the restricted cap](#4-lookup-in-the-forward-search).

Which globals a tile can cast follows legacy's global-teleport abstract nodes:
`TeleportCapability` is `OVER_30` above level 30, `OVER_20` at levels 21–30,
`OVER_0` at levels 1–20 and `ALL` outside the Wilderness. A global is castable
with a capability when `Transport.isUsableAtWildernessLevel` allows its cast
level (31, 30, 20 or 0), and `PreparedRoutingAccount` compiles one global view
per capability. Each capability can cast everything the one before it can. The
forward search seeds `ALL`'s globals at a start outside the Wilderness;
elsewhere it opens one hub per capability and bank layer, the first time it
reaches a tile with that capability. The relaxed graph's banked hub uses
`ALL`'s globals, the most permissive set, so it stays a lower bound.

## 3. Target overlay (`TargetOverlay`)

A target that is already a site reuses that site's node. Any other target gets
a **synthetic** node after the graph's nodes, with:

- its routing components from `RoutingStatic.attachments` (all walkable
  neighbours' components if the tile is blocked);
- attachment edges to every site in those components, costing
  `max(chebyshev(target, site), walk(site, target))`. A site shared by several
  target components is emitted once, under the first shared component.

`walk` comes from `TargetWalkDistances`, which runs a breadth-first search
backwards from the target over the static walking edges. It stays within the
target's routing components and is capped at 65,536 tiles. A site the search did
not reach gets the frontier's distance, and a site that is not a search tile (a
blocked transport origin) steps to its nearest reached neighbour. A target that
is not itself a search tile keeps the Chebyshev costs.

The walking bound stays admissible because sites are every transport endpoint,
crossing endpoint and bank, and leaving a routing component crosses a cut whose
endpoints are sites. So the part of a route after its last site is a pure walk
inside the target's components.

It matters for targets deep in winding areas (dungeons, tunnels, mountain
paths). There a Chebyshev attachment makes the entrance look close to the
target, every reverse label inherits that shortfall, and the forward search
floods everything within it. On the full benchmark corpus the walk costs about
0.3 ms per target (p50) and at most about 5 ms.

Several targets are allowed (multi-target "find closest"). `GoalStates` supplies each
resolved tile's accepted layers: carried = 1, banked = 2, either = 3. This includes snapped
fallbacks, shared associations and the existing unknown-item fallback. Overlays own a copy
of these masks; APIs without masks retain the either-layer default.

## 4. Reverse search (`ReverseLabels`)

Dijkstra seeds only accepted target states at cost 0, over the
reverse explicit edges plus same-component walking. There are two
implementations, and their labels must be equal:

A bank-only target is not a carried-layer terminal seed. Carried sites can still get finite
labels through the directed bank transition into the banked layer. Without an accessible
bank path, those labels remain `INF`, allowing the forward search to reject the start.

- **`computeClique`** is the reference oracle. On settling a site it relaxes
  every other site in each of its components at Chebyshev cost, or at the
  exact walking distance in a component with exact walks (below). This is
  quadratic in component size.
- **`computeSparse`** is the production path (`compute` uses it whenever a
  sparse network exists). It walks the static sparse network instead.

### The sparse walking network (`SparseWalkingNetworkBuilder`)

The network reproduces the component-wise Chebyshev clique exactly, with
O(n log n) edges:

- Rotate coordinates: `a = x + y`, `b = x - y`. Then `|Δa| + |Δb| = 2 ·
  chebyshev`, so all sparse weights are in **doubled** units.
- Per component, recursively split the points at the most balanced strict gap
  on axis `a`, or on `b` if all `a` are equal, at `m = floor((l + r) / 2)`.
  Each point gets a Steiner projection vertex on the line `axis = m`, joined
  by a spoke of cost `|coord − m|`. The projections are chained in order of
  the other coordinate.
- For `p` and `q` on opposite sides, the spoke, chain and spoke path costs
  exactly `|Δa| + |Δb|`. Same-side pairs are handled by the recursion, and
  coincident points are joined by 0-cost edges.
- A multi-component site is one shared vertex in every component's tree.

Steiner vertices get reverse states in both layers too. Explicit edge and
target attachment costs are doubled on entry (`CostArithmetic.twice`), and each
label is halved once at the end (`CostArithmetic.halve`). Halving is exact for
sites, because their doubled distances are always even.

### Exact walks in small components (`ExactWalks`)

Chebyshev distance badly undercuts walks in winding components. The Ice
Queen's Lair tunnel ends are 30 tiles apart but about 185 on foot. The
shortfall reaches every reverse label behind the component, and the forward
search floods everything within it, often far away through transports.

So every component with 2 to 256 sites (`ExactWalks.MAX_SITES`) joins its sites
by exact walks instead of a tree:

- When the static data is built, one forward breadth-first walk runs from each
  site over the component's own tiles, stopping once it has reached every
  site. A site that is not a search tile (a blocked transport origin) is walked
  from and to through its neighbours in the component.
- A pair's weight is the shorter of its two walks, but at least its Chebyshev
  distance, and is left out (`INF`) when neither can walk to the other inside
  the component.
- An edge is dropped when a path through a third site of the component is no
  longer. Weights are positive, so the remaining edges keep every pair
  distance, and `computeClique` checks them against the full table.

This is a lower bound for the same reason as the target walk above: a walk
leaves a component only across a cut, whose endpoints are sites. Larger
components (the Wilderness, open surface areas) keep their trees. Their pair
counts grow quadratically, and they wind less. Over a graph that avoids the
Wilderness, components that touch it keep their trees as well (section 7).

On the real map, 944 components qualify and keep 43,219 edges (about 0.5 MB).
The walks take about 1.2 s once, at plugin load. On the full benchmark corpus,
p99 fell from 132 to 41 ms and p50 from 24 to 16 ms, with identical route costs.
p50 fell because the reverse search has fewer Steiner vertices to settle. Tiles
still estimate from Chebyshev distance to the sites of their component: the
exact labels already stop the floods outside it.

### Generator provenance

`computeSparse` also records, for each state, the **generator** that its label
comes from:

- an edge that starts a generator (see the table in step 2) sets
  `origin = next` and `weight = newCost`;
- walking, Steiner and target attachment edges copy the predecessor's
  generator;
- each target seed is its own generator.

The hub → destination leg does not start a generator, so the abstract hub
never creates a geometric cone of its own.

## 5. Seed and generator tables (`PreparedHeuristic.prepare`)

There is one bucket per `(component, banked)`, with key `component * 2 +
banked`.

- **Raw seeds:** every spatial site, plus each synthetic target, with a finite
  label is added as `(tile, label)` to the bucket of **each** of its components.
- **Generators:** each seed is replaced by its generator `g` when
  `validOrigin` holds:
  - `g` is in the same layer;
  - `g` is a spatial site or a synthetic target;
  - `g` is attached to this component;
  - `label(s) = weight(g) + chebyshev(g, s)` holds exactly, i.e. the sparse
    path from `g` is a straight Chebyshev geodesic.

  Otherwise the seed stays its own generator. Each bucket is de-duplicated by
  generator state.

Generators are an optimisation, not an approximation. If `label(s) =
label(g) + cheb(g, s)`, the triangle inequality gives `label(g) + cheb(x, g) ≤
label(s) + cheb(x, s)` for every `x`. The cone of `s` is therefore dominated,
and dropping it leaves the minimum unchanged. Lookups scan the generator
bucket, falling back to the raw bucket if it is empty, and `estimateRaw` is
kept as the oracle for differential tests.

## 6. Lookup in the forward search

`AStarForwardSearch.heuristic` picks the lookup path:

- **Base tiles** (in the static search arrays) have exactly one component.
  They use `estimateBaseNode`.
- **Extras** are blocked transport endpoints, synthetic targets and a start
  outside the search arrays. They use `estimate`, which takes the minimum over
  all attached components.

Both paths also take the exact `label` when the tile is itself a site or a
target. Every bucket scan computes `min(label_i + chebyshev(x, tile_i))`.

The value depends only on the state, so each search caches it in pages
allocated on first touch (`SearchSpace.heuristicPage`). It is computed once per
state rather than at every improving relaxation, every pop, and for the banked
twin of a bank-dominated state.

`effectiveHeuristic` then adjusts the value for search state:

- **Bank dominance:** `bestBankCost` is the cheapest `g` seen at an unbanked
  reachable bank. For an unbanked state with `g > bestBankCost`, that state's
  own future bank/global opportunity is dominated. The search uses `max(h(x,
  false), h(x, true))`, ignoring the second term if it is `INF`. A popped state
  whose key has risen is re-queued (rekey).
- **Restricted cap:** in Wilderness-restricted queries, until all globals are
  activated, `h = min(h, globalBound[layer])`. Here `globalBound` is
  `min(globalCost + h(destination))` over that layer's `ALL` globals, computed once
  per search by `globalBounds`. This restores admissibility, because the
  relaxed graph assumes globals are usable sooner than the restricted state
  allows. The cap ignores the walk to a band where globals can be cast, so it is
  loose for states deep in the Wilderness. When the capped value is `INF`, no route
  reaches a target from the state, casting or not, and it is pruned.
- **Weight:** the priority is `g + round(weight · h)`. A weight of 1 is exact,
  and anything greater is an explicitly inexact mode.

## 7. Avoiding the Wilderness (`WildernessGate`)

With Avoid wilderness on, the forward search may not enter the Wilderness from outside it: no
walking step, local transport or global cast outside it may land inside (`SearchRestrictions`).
Leaving it is allowed. A heuristic over the ungated graph is still admissible, but it does not
know about the gate. A target the gate makes unreachable then gets a finite estimate, and the
search floods everything it can reach before giving up (about 1.15M states). A detour around the
Wilderness looks as cheap as walking through it.

So the A* pathfinder searches with a graph built to avoid the Wilderness (`new SiteGraph(stat,
account, true)`), with labels that bound the restricted search:

- **Explicit edges.** `LOCAL` and `SEPARATOR` edges from a site outside the Wilderness to one
  inside are left out. The banked hub loses its Wilderness destinations unless a bank the account
  may use is itself inside the Wilderness.
- **Pieces.** A routing component can straddle the Wilderness line, and two parts of it outside
  the Wilderness may only be joined through it (Ferox Enclave and Edgeville, say). A *piece* is a
  maximal set of non-Wilderness tiles of one component that walk to each other without entering
  it. From outside the Wilderness, walking within a component only reaches the own piece; from
  inside, the whole component (an over-approximation, so still a lower bound). A component is
  *gated* when it has a tile or site inside the Wilderness, or several pieces. `WildernessGate`
  holds each tile's piece, each site's pieces (a blocked site takes the pieces of its neighbouring
  search tiles) and which components are gated. It is built once per `RoutingStatic`, the first
  time a search avoids the Wilderness.
- **Sparse network.** Gated components keep their full tree, but its spokes into non-Wilderness
  sites are marked gated, and the reverse search never relaxes them, so those sites never walk out
  through it. Each piece of a gated component gets a tree of its own over its sites. Other
  components are unchanged. `computeClique` applies the same rule pair by pair
  (`WildernessGate.walkAllowed`), so clique and sparse labels stay equal.
- **Target attachments** keep only sites that may walk to the target under that rule, and
  `TargetWalkDistances` walks only steps that do not enter the Wilderness. A finished gated walk
  that never reaches a site proves the site cannot walk to the target, and its attachment is `INF`.
- **Lookup.** Seeds are also bucketed by gated piece. A tile outside the Wilderness in a gated
  component scans its piece's bucket; every other tile scans its component's bucket. A generator
  stands in for a seed only if it is in the same bucket, so the generator and raw scans stay equal.

The session keeps the gated and ungated graphs, and their prepared targets, side by side.

### Costs and open questions (2026-10-24)

This branch is kept apart from the landing stack while these are weighed.

What it buys, on the full corpus (cold, 1 repetition):
- **Targets cut off by the gate.** The seven targets only the Wilderness reaches with Avoid
  wilderness on (six clue locations in the Abyss and similar places, plus the #744 Abyss case)
  are rejected before any expansion: about 0.5 s and 1.15M states each → about 10 ms and 0.
- **Overall:** p99 38 → 34 ms and maximum 563 → 120 ms against the landing stack. Route costs and
  reachability are unchanged, with the same failing checks.

What it costs:
- **Static memory, the main cost.** `WildernessGate` retains about 7.6 MB, a quarter of the
  routing static data (31.7 MB with it). About 5.8 MB of that is `tilePieces`, one `int` for every
  search tile (1.45M), though it only matters for tiles in components that touch the Wilderness.
  Since it is built with the static data, everyone pays it, including players who never avoid the
  Wilderness. A sparse layout would bring it under 1 MB: one array per gated component, or
  `char` ids.
- **Startup:** about 0.1–0.2 s for the gate.
- **Session memory.** The session keeps gated and ungated graphs (0.3 MB each) and their prepared
  targets (about 0.7 MB each, up to 16 per slot). A player who switches between the two can hold
  twice the cached targets: about 22 MB at worst, against 11 MB.
- **Exact walks lose some reach.** Components touching the Wilderness keep their Steiner trees in
  the gated network, so on gated searches they get no exact walks.

Open questions:
- Whether Avoid wilderness should fall back to a route through the Wilderness when nothing avoids
  it. That is the old commit "Lift Avoid wilderness in A* when no route avoids it" (`250e2eea`, on
  `exact-site-walks`); see `exact-site-walks-handoff.md`. This branch keeps master's semantics.
- Whether to make the gate sparse before landing, and whether to cap the target cache per slot.

## Invariants to preserve

1. The relaxed graph must contain every real edge, or a cheaper equivalent.
   Never drop an attachment or a transport.
2. `INF` means prune. Never turn a missing value into 0.
3. Clique and sparse labels must be equal, and the
   generator scan must equal the raw scan (`PreparedHeuristic.estimateRaw`).
   `SparseManhattanTest` and `MultiTargetTest` check both.
4. Packed tiles are signed `int`s, and planes 2 and 3 are negative. Anything
   ordered by tile must use `Integer.compareUnsigned`: the routing data is
   sorted in unsigned order, and a signed comparison misplaces those planes.
5. Cost arithmetic goes through `CostArithmetic`, which saturates at `INF` instead
   of overflowing.

## Implementation notes

- **Target attachments:** the reverse search relaxes only a synthetic target's own
  attachment edges. Non-terminal synthetic target layers are also estimated from their
  component's finite site labels by `PreparedHeuristic`, including valid bank detours.
- **Generator lists:** `GeneratorList` de-duplicates with a linear scan and
  sorts with an insertion sort, so building a bucket is quadratic in its size.
  This is worth checking if heuristic preparation shows up in profiles.
