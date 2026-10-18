# Transport investigation maps

For transport coordinate reviews, show the actual RuneScape Wiki game basemap,
with a wider locator view and a close-up of the exact tiles. Mark and label the
origin and destination with coordinates, keep north up, and show only the edge
being discussed. Verify directed edges in the current TSV before drawing arrows.
A collision-only diagram does not provide enough landmarks to locate the spot
in game; use collision as an annotation on the basemap.

Existing renderer: `/tmp/dead-end-maps/render_jungle_landmarks.py`, using
`dashboard_render_pairs.TileSource` and cached Wiki tiles. Output images go in
`build/transport-origin-maps/`. Check rendered images before opening in Firefox.
