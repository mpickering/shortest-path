# Location sidebar

Open **Shortest Path locations** in RuneLite's sidebar. Click a row, or select it
and press Enter, to route to that coordinate and centre the open world map there.
Selection requires being logged in. The normal target marker identifies the tile.

Use the category selector to focus on **Open neighbour**, **No open neighbour**,
or **All categories**. The search applies within the selected category.

Enable **Debug layers → Location list (minimap)** to draw cyan markers for the
current category and search results. Markers show nearby points on your current
floor, including mapped instance tiles, and do not require an active route or
minimap path drawing to be enabled.

Search matches all space-separated terms against coordinates and descriptions,
ignoring case. It uses Shortest Clue's 150 ms debounce and batched list updates.

The bundled list contains no origin violations from the strict collision scan:
these tiles are blocked and are not the exact destination of any transport.
An open neighbour does not satisfy this rule. Coordinates include all transport
types loaded by `ResourceLoader.transports()`. This is a saved scan, not a live
check; regenerate it after transport corrections.

**Load TSV…** replaces the list with any UTF-8 file in this format (one literal tab
between each column):

```tsv
# x y plane	Description	Category
3200 3200 0	A location to inspect	Open neighbour
3201 3200 1	An upstairs location	Arrival only
```

The category column is optional; two-column files use **General**.

Blank lines and lines beginning with `#` are ignored. Coordinates must have x/y
between 0 and 32767 and plane between 0 and 3. Invalid files show the failing line
and leave the previous list intact. A loaded list lasts until the plugin stops;
the bundled list is `src/main/resources/debug/locations.tsv`.
