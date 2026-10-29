# Underground world topology audit

Image evidence for the underground-area topology review in Skretzo/shortest-path.

Green: walkable tiles included in the structural routing graph. Red: walkable tiles excluded from it. Hatching: no published basemap imagery beneath the collision mask. Each plane is shown separately; disconnected map sections are packed into separate panels.

The graph mask from development commit 7cad13c5 was checked byte-for-byte against the master-based checkout at db84d1e6 with PR #840 applied (a presentation-only change); they match. Account requirements are not applied to this structural graph. Some excluded floor is unused, inaccessible or instanced; these images identify investigation candidates, not confirmed missing transports.

Basemap: CollisionLab standard tiles, immutable version 2746-549df720fe90. Source tile hashes were verified. No locally generated terrain or Wiki basemap was used. Collision data is the plugin checkout’s committed collision-map.zip; its hash is in data/provenance.json.

Full component coordinates and bounds are in data/components.tsv. Map and plane source rectangles are in each area’s JSON file.

## Area images

- [Ancient Cavern](ancient-cavern.png)
- [Ardent Ocean Underground](ardent-ocean-underground.png)
- [Asgarnia Ice Cave](asgarnia-ice-cave.png)
- [Cam Torum](cam-torum.png)
- [Dorgesh-Kaan](dorgesh-kaan.png)
- [Dwarven Mines](dwarven-mines.png)
- [Feldip Hills Underground](feldip-hills-underground.png)
- [Fossil Island Underground](fossil-island-underground.png)
- [Fremennik Slayer Cave](fremennik-slayer-cave.png)
- [Ghorrock Prison](ghorrock-prison.png)
- [God Wars Dungeon](god-wars-dungeon.png)
- [Kebos Underground](kebos-underground.png)
- [Keldagrim](keldagrim.png)
- [Kharidian Desert Underground](kharidian-desert-underground.png)
- [Kourend Underground](kourend-underground.png)
- [Lair of Tarn Razorlor](lair-of-tarn-razorlor.png)
- [Misthalin Underground](misthalin-underground.png)
- [Mor Ul Rek](mor-ul-rek.png)
- [Morytania Underground](morytania-underground.png)
- [Neypotzli](neypotzli.png)
- [Northern Ocean Underground](northern-ocean-underground.png)
- [Prifddinas Grand Library](prifddinas-grand-library.png)
- [Prifddinas Underground](prifddinas-underground.png)
- [Shrouded Ocean Underground](shrouded-ocean-underground.png)
- [Stronghold of Security](stronghold-of-security.png)
- [Taverley Underground](taverley-underground.png)
- [Troll Stronghold](troll-stronghold.png)
- [Unquiet Ocean Underground](unquiet-ocean-underground.png)
- [Varlamore Underground](varlamore-underground.png)
- [Waterbirth Dungeon](waterbirth-dungeon.png)
- [Western Ocean Underground](western-ocean-underground.png)
- [Wilderness Dungeons](wilderness-dungeons.png)
- [Yanille Underground](yanille-underground.png)
