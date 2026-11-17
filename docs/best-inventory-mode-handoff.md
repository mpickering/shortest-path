# Best inventory mode: handoff

Where the work stood on 2026-11-17, for picking it up on another machine. The
design is in `best-inventory-mode.md`; this file covers state and next steps.
Delete it once the work is merged.

## Setup

```sh
git fetch mpickering handoff/best-inventory-mode
git worktree add ../shortest-path-best-inventory mpickering/handoff/best-inventory-mode -b feature/best-inventory-mode
cd ../shortest-path-best-inventory
nix shell nixpkgs#jdk11 --command ./gradlew test --tests shortestpath.pathfinder.AStarSearchAllTest
```

Keep worktrees next to the repo, never under `/tmp`.

## What's on the branch

- `docs/best-inventory-mode.md`: the spec.
- `src/main/resources/clue-locations.tsv`: clue tiles by tier, generated from
  RuneLite `bb0fa42b3d9329ffe31115f1f20042c455def2e3`. Rows per tier:
  BEGINNER 29, EASY 150, MEDIUM 160, HARD 179, ELITE 110, MASTER 233 (136 of
  them hot-cold, which over-weights master; fine for prototyping).
- `AStarForwardSearch.searchAll` and `PreparedTarget.searchAll`: a search that
  continues past the first target and returns the cost of every target, or
  `INF` for unreached ones.
- `AStarSearchAllTest`: checks `searchAll` against single-target searches for
  the hard tiles from three starts, on a maxed account with no items.

The generator script, `scripts/build_clue_locations.py`, is on branch
`feature/best-inventory-clues` of shortest-path-tooling. That branch has only
been committed locally, on the old machine. It is only needed to regenerate the
TSV.

## Decisions so far

- Objective: the expected cost to a target tile from a single virtual start,
  not over all pairs of target tiles (see "Why one start" in the spec).
  Not yet checked against the pairwise model.
- Targets: any tile list; a clue tier is one example. Every tile is equally
  likely.
- Pool: items the player owns (carried, worn, bank as last seen). Worn items
  take no inventory slot. Runes, the rune pouch and enablers do.
- Banking for a missing item is the fallback cost.
- No UI. Triggered in-game by `::bestinv <tier> <slots>`, writing a JSON
  report, because realistic accounts are hard to model offline.

## Measurements (hard tier, maxed account, no items)

- Single-target search: about 30 ms preparing the target plus 5–10 ms
  searching.
- An unreachable target makes the search cover the whole map: about 1.36M
  states, about 3.4 s.
- `searchAll` to the 142 reachable hard tiles: about 2.6 s. Targets spread over
  the map make the multi-target heuristic close to zero, so this is only about
  2x faster than separate searches.
- 37 of the 179 hard tiles are unreachable as raw tiles:
  - 28 are blocked and need resolving through `TargetGoals` /
    `ReachableGoalRule` (as `AStarPathfinder.run` does) before searching.
  - 9 are walkable but unreachable without items: (3081,3209), (2702,3246),
    (2699,3251), (3143,3445), (2505,3899), (3572,4372), (2347,4435),
    (2648,4729), (2073,4846). Not investigated yet; some may need an item,
    some may be collision or transport data bugs.

## Next steps

1. Resolve blocked target tiles to their goal tiles before `searchAll`.
2. Investigate the 9 walkable but unreachable hard tiles.
3. Build a `PathfinderConfig` variant with item teleports disabled for
   `free(·,·)`; look at how `PathfinderConfig` handles setting overrides.
4. List candidate items and their teleport destinations with `tp(d)`, and
   compute `free(d, ·)` for each destination with `searchAll` in parallel.
   Compute `base(·)` from the always-available teleports and the bank fallback.
5. Greedy selection with slot costs, gear slots, enabler enumeration and
   swaps.
6. The `::bestinv` chat command and the JSON report.
7. Once, offline: compare the chosen sets with the pairwise model.
