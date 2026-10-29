# Underground map connectivity audit

Branch: `debug-overlay-modernise`, commit `7cad13c5`. No repository files changed.

“Excluded” means a tile has ordinary walking edges in the committed collision map but is absent from the current A* static routing graph. That graph retains areas structurally reachable from Lumbridge through the recorded walking and transport connections, without applying account requirements. Excluded areas cannot be routed through even if the player is already inside.

The scan covers all 33 named maps in PR #849. Full map coverage was extracted from the local game cache, including unshifted areas omitted from the PR correction table. All 121 committed correction rectangles matched the cache. The full coverage contains 220 rectangles; each declared source plane was checked, with overlapping tiles counted once per map.

An excluded tile is not automatically a playable area or a missing transport bug: map squares include unused rooms, decorative floor patches and potentially instanced content. The counts below measure graph coverage, not player access or in-game verification.

## Coverage by map

| Map | In graph | Excluded | Excluded % | Largest excluded connected group | Example tile in largest group |
|---|---:|---:|---:|---:|---|
| Ghorrock Prison | 0 | 3,601 | 100.0% | 2,994 | 2949, 6398, 2 |
| Kebos Underground | 0 | 7,334 | 100.0% | 1,757 | 1306, 10213, 1 |
| Neypotzli | 0 | 18,487 | 100.0% | 2,863 | 1439, 9635, 0 |
| Prifddinas Grand Library | 0 | 9,066 | 100.0% | 3,572 | 2623, 6151, 1 |
| Shrouded Ocean Underground | 0 | 10,389 | 100.0% | 3,628 | 2207, 8735, 0 |
| Western Ocean Underground | 0 | 5,333 | 100.0% | 1,749 | 1950, 9495, 0 |
| Waterbirth Dungeon | 264 | 7,974 | 96.8% | 1,542 | 2466, 10140, 0 |
| Fossil Island Underground | 1,174 | 21,586 | 94.8% | 8,124 | 3807, 10175, 0 |
| Unquiet Ocean Underground | 1,221 | 11,672 | 90.5% | 2,981 | 2678, 8862, 0 |
| Varlamore Underground | 2,834 | 16,222 | 85.1% | 4,190 | 1663, 9503, 1 |
| Yanille Underground | 1,080 | 3,222 | 74.9% | 1,897 | 2402, 9441, 0 |
| Northern Ocean Underground | 8,182 | 15,174 | 65.0% | 10,949 | 2431, 10431, 1 |
| Cam Torum | 2,492 | 3,530 | 58.6% | 156 | 1413, 9588, 3 |
| Kharidian Desert Underground | 4,285 | 4,731 | 52.5% | 3,418 | 3359, 9112, 1 |
| Feldip Hills Underground | 3,246 | 2,936 | 47.5% | 965 | 1956, 9012, 1 |
| Prifddinas Underground | 4,549 | 4,073 | 47.2% | 2,386 | 3295, 12428, 0 |
| God Wars Dungeon | 3,941 | 3,339 | 45.9% | 889 | 2874, 5211, 0 |
| Kourend Underground | 14,578 | 12,145 | 45.4% | 2,247 | 1440, 10069, 2 |
| Mor Ul Rek | 5,726 | 4,364 | 43.3% | 2,224 | 2397, 5089, 0 |
| Morytania Underground | 11,294 | 7,436 | 39.7% | 1,614 | 3487, 9696, 0 |
| Taverley Underground | 13,780 | 8,723 | 38.8% | 1,646 | 2911, 9875, 0 |
| Ardent Ocean Underground | 10,178 | 5,754 | 36.1% | 1,551 | 2628, 9535, 2 |
| Lair of Tarn Razorlor | 1,236 | 610 | 33.0% | 108 | 3185, 4585, 1 |
| Wilderness Dungeons | 15,874 | 7,284 | 31.5% | 1,233 | 3041, 10140, 0 |
| Misthalin Underground | 11,540 | 3,160 | 21.5% | 845 | 3168, 9749, 0 |
| Dwarven Mines | 4,295 | 1,030 | 19.3% | 204 | 3720, 5649, 0 |
| Asgarnia Ice Cave | 3,368 | 728 | 17.8% | 85 | 2925, 9649, 0 |
| Ancient Cavern | 1,550 | 327 | 17.4% | 276 | 1747, 5303, 1 |
| Fremennik Slayer Cave | 2,634 | 554 | 17.4% | 293 | 2722, 9971, 1 |
| Keldagrim | 7,971 | 590 | 6.9% | 305 | 2784, 10148, 1 |
| Troll Stronghold | 2,460 | 126 | 4.9% | 49 | 2858, 10055, 1 |
| Dorgesh-Kaan | 5,256 | 245 | 4.5% | 144 | 2741, 5311, 1 |
| Stronghold of Security | 5,761 | 18 | 0.3% | 5 | 2338, 5201, 0 |

## Waterbirth control

Both `(2467, 10157, 0)` and `(2480, 10157, 0)` are walkable entrance-room tiles, but both return a negative `RoutingStatic.searchIndex`. A direct A* query between them ended with `SEARCH_EXHAUSTED` and a one-step path. This bypasses world-map coordinate conversion. The entrance room forms a 1,542-tile excluded walking component.

## Reproduction data

- `summary.tsv`: one row per map.
- `components.tsv`: one row per excluded connected group, with a representative coordinate and bounds.
- `selected-map-areas.tsv`: full cache map coverage used for the scan.
- `UndergroundConnectivity.java`: read-only survey source; uses the repository’s collision and routing graph builders.

No transport fixes were made. No live client or account-specific routes were tested.
