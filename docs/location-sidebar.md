# Location sidebar

Open **Shortest Path locations** in RuneLite's sidebar. Click a row, or select it
and press Enter, to route to that coordinate and centre the open world map there.
Selection requires being logged in. The normal target marker identifies the tile.

Search matches all space-separated terms against coordinates and descriptions,
ignoring case. It uses Shortest Clue's 150 ms debounce and batched list updates.

The initial list contains the 141 non-fairy-ring blocked origins from the saved
transport investigation. This is a historical snapshot, not a fresh scan or a
record of verified locations. Descriptions include interactions and whether the
saved scan found an open neighbour or an incoming transport.

**Load TSV…** replaces the list with any UTF-8 file in this format (one literal tab
between the coordinate and description):

```tsv
# x y plane	Description
3200 3200 0	A location to inspect
3201 3200 1	An upstairs location
```

Blank lines and lines beginning with `#` are ignored. Coordinates must have x/y
between 0 and 32767 and plane between 0 and 3. Invalid files show the failing line
and leave the previous list intact. A loaded list lasts until the plugin stops;
the bundled list is `src/main/resources/debug/locations.tsv`.
