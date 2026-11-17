# Best inventory mode (spec)

Status: draft, no implementation yet.

Given a clue tier and a number of free inventory slots, recommend which of the
player's own teleports and transport items to carry so that the expected trip
from one clue step to the next is as cheap as possible. The question it answers
is "what should my standard clue inventory be for this tier?".

Clues are the first example, not the only one. The engine takes any list of
target tiles; a clue tier is one way to get such a list.

## Objective

Let `T` be the clue locations of one tier, deduplicated by tile, each equally
likely. Clue steps are treated as independent draws, so a leg starts at one
clue tile and ends at another. For a carried set `S`:

```text
minimise  (1 / |T|²) · Σ_{s∈T} Σ_{t∈T} cost(s → t | S)

cost(s → t | S) = min( free(s, t),
                       min over destinations d of items in S: tp(d) + free(d, t),
                       bank(s, t) )
```

- `free(a, b)` is the route cost using only transports that need no carried
  item: walking, shortcuts, unlocked transports, item-free teleports.
- `tp(d)` is the cost of the teleport to destination `d`. An item with several
  destinations, such as a games necklace, opens all of them for one slot.
- `bank(s, t)` is the fallback: detour to a bank, pay `COST_BANK_VISIT`, then
  take the best route to `t` using anything the player owns. It doesn't depend
  on `S`, so it caps what a missing item can cost. Each item is valued against
  "fetch it from the bank", not against walking across the map.

`S` is fixed for the whole session. Swapping items at the bank between legs
would turn this into a sequential decision problem, which is out of scope.

The report also gives the baseline (`S` = nothing extra carried) and the saving.

### Approximation

The decomposition assumes a carried teleport is used at the start of a leg.
Restrictions such as Wilderness levels can make that wrong: the player would
walk somewhere first and teleport from there. v1 checks the final `S` by
re-running the real pathfinder with only `S` enabled, on all pairs or a sample,
and reports any differences.

## Candidates and slots

Candidates are what the player owns: carried, worn, and the bank as last seen
(`HeldItems.carriedAndBanked`). The bank must have been opened this session.
Spell teleports count when their runes are owned.

The budget is `k` inventory slots, plus one item per gear slot. Whatever is
already worn fills its gear slot.

| Candidate | Inventory slots | Gear slot |
|---|---|---|
| Wearable teleport item (glory, dueling, …) | 0, or 1 if its gear slot is already used | its own |
| Other teleport item, or one tablet type | 1 | – |
| Spell teleport | 1 per rune type not covered by a worn staff or the rune pouch | – |
| Rune pouch | 1 (runes inside it are then free) | – |
| Enabler (dramen staff, coins, rope, …) | 1 if not wearable | its own |

Enablers aren't teleports themselves; they unlock transports partway through a
route. They interact with other choices: a dramen staff is worth nothing on its
own and a lot together with fairy rings. They also compete with other items,
for example a dramen staff and an elemental staff both want the weapon slot.

## Algorithm

1. **Precompute** `free(s, ·)` for every `s ∈ T` and `free(d, ·)` for every
   teleport destination `d`. Each is one search that runs until every tile in
   `T` is settled. The A* pathfinder currently stops at the first target it
   reaches, so this needs an "all targets" search mode, either a Dijkstra-style
   run or `Targets` with a settle-all goal rule. Also precompute `bank(s, t)`.
2. **Enumerate enabler subsets.** There are only a few enablers, so loop over
   the owned, relevant subsets `E` and recompute `free` with `E` enabled.
   Cache the results per subset.
3. **Choose greedily inside each `E`.** Repeatedly add the candidate with the
   best saving per slot, while respecting both the slot budget and the gear
   slots. Also try the best single candidate, as in budgeted max coverage.
   Then try swapping pairs of items until no swap helps.
4. **Return** the best `(E, S)`. Once `E` is fixed, the objective has
   diminishing returns (facility location), so greedy comes with a
   constant-factor guarantee. Enumerating enablers on the outside keeps it
   that way.

## Clue data

The tooling repository's `src/test/resources/scenarios/clue-locations.json`
lists 866 locations but has no tier. Each entry's `targetSource` points at a
line of RuneLite's `cluescrolls/clues/*.java`. The tier comes from the clue's
item gameval name: `TRAIL_CLUE_<TIER>…` or `TRAIL_<TIER>_…`, with `<TIER>` in
BEGINNER, EASY, MEDIUM, HARD, ELITE or MASTER.

- A clue with several item ids may belong to more than one tier.
- Clues without an item id (Falo the Bard, skill challenges, hot-cold) are
  assigned by type or left out.
- Hot-cold and other area clues are left out of v1.

A tooling script generates `clue_locations.tsv` (tier, tile, clue type,
source) into the plugin's resources, because the mode runs inside the client.

## Trigger and output

There's no UI. The mode runs in-game against the logged-in account, because a
realistic account (quests, diaries, skills, unlocks, bank) is hard to model
offline. It's triggered by a chat command:

```text
::bestinv <tier> <slots>
```

The command:

1. snapshots the live `PathfinderConfig` and `HeldItems`, and refuses if the
   bank hasn't been seen this session;
2. runs on a background thread and prints progress to chat;
3. writes the full report as JSON to the RuneLite directory and logs a short
   summary in chat.

The report contains:

- the chosen items, in the order they were picked, with each item's marginal
  saving;
- the baseline, the final expected cost per leg, and the share of legs that
  fall back to banking;
- a savings curve for 0…`slots` slots;
- the best and worst legs, each with the item it uses;
- any decomposition mismatches found by the verification run.

## Out of scope for v1

- UI.
- Swapping items between legs.
- Charges and consumables running out.
- Items the player doesn't own.
- Clue tiers weighted by real drop frequency.
- Requirements specific to a clue step, such as emote gear or a spade.

## Reading

- Krause & Golovin, *Submodular Function Maximization* (2014): introduction.
- Nemhauser, Wolsey & Fisher (1978): the greedy guarantee.
- Khuller, Moss & Naor (1999): budgets where items cost different amounts.
- Meyerson & Tagiku (2009): adding shortcuts to minimise average distance.
