# Transport logger for development builds

The shadowJar's launcher loads **Transport Logger (development)** as a separate,
optional RuneLite plugin. Enable it in RuneLite's plugin list and open its T
sidebar button. This code is under test sources so the ordinary Shortest Path
Plugin Hub artifact does not include it. It does not depend on the debug overlays.

The numbered history records each observed jump of more than two tiles per game
tick, or any plane change. Walking and running are ignored. The last observed
pre-jump tile and first post-jump tile are recorded using canonical instance
coordinates. Loading a region preserves the observation; logging out, losing the
connection, or hopping worlds resets the sampling baseline without deleting history.

For short crossings, stand on the actual interaction tile, press **Start**, take
the transport without walking to the object first, then press
**Finish** while standing on its landing tile. Automatic capture is
suppressed during a manual capture so it does not produce duplicate rows.
**Cancel** releases the marked origin. **Clear** deletes the session
history and resets pending captures and animation measurements.

Select a numbered row to see both endpoints, the recent menu-input sequence,
object/NPC/item identifier where available, and animation evidence. Automatic capture keeps inputs from the preceding 50 ticks; a manual capture
keeps all inputs since Start, so a long dialogue does not erase the original
object click. A recent input can be unrelated; it is not a verified interaction anchor or availability rule.
Automatic jumps may include knockbacks, deaths or intermediate cutscene positions;
inspect them before turning observations into transport data.

## Destination choices

To debug routing without clicking the world map, enter `x y plane` (or comma-separated
coordinates) in **Route target** and press **Set target** or Enter. This requests a
Shortest Path route from your current position using your current routing settings.
It does not alter recorded transports. Enable Shortest Path and log in first.

The input sequence includes the exact selected right-click action on objects,
NPCs and items, for example `Weiss Boat [object 21176]`. NPCs use their definition
ID, rather than the temporary index of that NPC in the scene. If the NPC cannot
be resolved, the index is explicitly labelled as an index.

Standard numbered dialogue choices are captured both when clicked and when
selected with number keys. Each records the option number, visible text and
widget identifier, for example `Dialogue option 3: Port Khazard`. The title
"Select an Option" and unchosen destinations are not recorded as selections.
Other clicked destination interfaces include the selected widget's text or name
and identifier when available. Input sequences are included unchanged in the
sidebar and clipboard TSV; the export columns have not changed.

Keyboard dialogue capture follows the `CHATBOX_KEYINPUT_MATCHED` script hook
used by RuneLite's [PaymentTracker](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/timetracking/farming/PaymentTracker.java).
Custom interfaces with their own keyboard scripts may need additional handling;
clicking them still records their menu action and available widget information.

## Animation ticks

The logger observes `AnimationChanged` for the local player and uses the client's
game tick counter at the start and end. It never measures the time spent standing
still before the animation. Contiguous animation phases (including a switch in
the same or following tick) share one measurement and list their animation IDs.
A captured jump is associated only when its origin matches the animation's origin.
The row updates when that animation ends. A manual capture can retain a finished
animation until you press Finish.

This measures the observed animation interval in game-tick boundaries, with
sub-tick rounding; it is **not** the movement lock or a verified transport cost.
An animation already running when you enable/clear the logger has no known start.
Unmatched animations, missing starts, and animations interrupted by logout/hop
leave timing blank. If a transport consists of multiple jumps in one animation,
the animation measurement belongs to the first matched jump, not to each leg.

**Copy row** and **Copy all** copy headed TSV observations:

```text
Origin  Destination  Interaction  Animation ticks  Animation IDs  Capture
```

Coordinates use `x y plane`. These are research observations, not rows ready to
paste into a transport resource: confirm the menu action, object ID, requirements,
and usable duration separately. Clipboard export is the way to save a session;
history is in memory and is lost when the client exits. Disabling/re-enabling the
plugin keeps completed history but resets in-flight sampling.

## Build and cherry-pick

Cherry-pick the logger commit onto a Shortest Path checkout, then build with
`./gradlew shadowJar`. The only integration point is the additional builtin
plugin in `src/test/java/shortestpath/ShortestPathPluginTest.java`.

Focused checks: `./gradlew test --tests 'shortestpath.transportlogger.*'`.
The panel test also renders illustrative sample observations to
`build/transport-logger-preview.png`; they are fixtures, not in-game measurements.
