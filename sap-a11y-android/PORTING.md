# Porting notes

How the Windows mod maps onto Android, what the native bridge assumes, and
where the next pieces go.

## Runtime shape

```
Super Auto Pets process
├── Unity / il2cpp (libunity.so, libil2cpp.so, global-metadata.dat)
├── libsapa11y.so            loaded by the accessibility service
│     ├── waits for libil2cpp.so, resolves the il2cpp API with dlsym
│     └── JNI: snapshot(), tap(), focus(), setText()
└── SapA11yService (dex)     same process, enabled in Settings
      ├── polls a JSON snapshot every 200 ms
      ├── filters hardware keys, moves the game's EventSystem selection
      ├── speaks through TextToSpeech
      └── dictionary, help, read-screen
```

Both parts live in the game's process because an `AccessibilityService`
declared in the app's manifest runs there. That is what makes the reflection
bridge possible without root.

## Why reflection instead of offsets

`libil2cpp.so` exports the il2cpp C API (`il2cpp_domain_get`,
`il2cpp_class_from_name`, `il2cpp_class_get_method_from_name`,
`il2cpp_runtime_invoke`, ...) because `libunity.so` calls into it. On Android the
symbols are present in release builds, so the bridge can look classes and
methods up by their metadata names, exactly as Il2CppInterop does on the PC.
Nothing is tied to a version's offsets, and nothing is patched inside
`libil2cpp.so`.

If a future build strips those exports, the fallback is an Il2CppDumper offset
table; the class and method names below would still be the same.

## Snapshot schema

`SapNative.nativeSnapshot()` returns:

```json
{
  "ready": true,
  "title": "Settings",
  "nodes": [
    {"id": 1234, "role": "button", "label": "General", "value": "", "enabled": true,
     "checked": false, "focusable": true, "x": 540, "y": 300}
  ]
}
```

- `id` is Unity's `GetInstanceID`, which is stable across frames; managed
  pointers are not (a new wrapper can be returned per call), so identity is
  always the instance id.
- `role` is one of `button`, `toggle`, `slider`, `scrollbar`, `input`,
  `dropdown`, `text`.
- Values come from the component: `Toggle.isOn`, `Slider.value`,
  `InputField.text`.
- Nodes are sorted top-to-bottom then left-to-right, so the focus ring reads in
  visual order.
- Static text that is not inside a control is included as `role: "text"` and is
  skipped by the focus ring, so page and dialog bodies are read without
  cluttering navigation.

The bridge calls `UnityEngine.Object.FindObjectsOfType(Type)` (and
`FindObjectsByType` when the old name is gone), then `GetComponent(Type)` for
each type it needs. No generic method instantiation is involved.

## Actions

`tap` follows what a finger does, in order:

1. `Toggle.set_isOn(!isOn)` for toggles.
2. `OnPointerClick(PointerEventData)` on the component, with a
   `PointerEventData(EventSystem.current)`; this is the handler Unity's own
   `Button` and custom `ButtonBase`-style controls implement.
3. `OnSubmit(BaseEventData)`.
4. `Button.onClick.Invoke()` as a last resort.

`focus` calls `EventSystem.SetSelectedGameObject(go, null)`, the same call the
PC mod uses to move the game's selection.

## Class names the bridge relies on

Generic Unity types only, so this works for any UGUI game:

- `UnityEngine.Object`, `Component`, `GameObject`, `Transform`, `Behaviour`,
  `Camera`, `Screen` (assembly `UnityEngine.CoreModule`)
- `UnityEngine.UI.Selectable`, `Button`, `Toggle`, `Slider`, `Scrollbar`,
  `InputField`, `Dropdown`, `Text` (assembly `UnityEngine.UI`)
- `TMPro.TMP_Text`, `TMPro.TMP_InputField` (assembly `Unity.TextMeshPro`)
- `UnityEngine.EventSystems.EventSystem`, `PointerEventData`

Game-specific, optional:

- `Spacewood.Unity.PageManager.CurrentPage` -> page `gameObject.name`, split
  into words the way `PageManagerPatches.SplitCamelCase` does.
- `Spacewood.Unity.UI.SelectableBase` and `Spacewood.Unity.UI.ButtonBase` -> the
  game's own controls. The PC mod navigates `SelectableBase`, not
  `UnityEngine.UI.Selectable`, and treats the two as different types, so both
  families are scanned and deduplicated by `GameObject`.

All lookups are by scanning the assemblies in the domain, so an assembly rename
does not break the port.

Controls are activated in the order the PC mod uses: the pointer/submit handler
(named exactly, then by the tail of the mangled name for explicit interface
implementations such as
`UnityEngine_EventSystems_IPointerClickHandler_OnPointerClick`), then the public
`Click()` the mod also calls, then `Button.onClick`. Readiness uses
`get_interactable` when present and the game's `GetInteractable()` otherwise.

## What is ported from the C# so far

| PC source | Android |
| --- | --- |
| `TolkSpeech.Speak` (interrupt vs queue) | `SapSpeech.speak` |
| `TextExtractor.CleanText`, sprite words, `JoinSentences`, `Count` | `SapText` |
| `DictionaryMenu` (categories, entries, typed search) | `SapDictionary` |
| `SapUiSource` (control roles, labels, values, position in set) | `SapNode.speech` + the bridge's snapshot |
| `PageManagerPatches.SplitCamelCase` | `SapText.splitCamelCase` |
| `SapA11y` (tree ownership of speech) | replaced by the service + TTS |

Not ported yet, in rough order of value:

1. **Shop narration** (`ShopNarrator`, `DetailLineProvider`, `PetStatsReader`):
   pet and food lines with stats, ability, tier and experience. These read game
   fields (`Spacewood.Unity.MonoBehaviours...`), so they need the APK's
   `global-metadata.dat` to confirm field names before writing the reflection
   calls.
2. **Battle narration** (`BattleNarrator`, `GameplayPhaseDetector`): step
   through the battle with pause / fast-forward / skip.
3. **Sections** (`NavigationSection`, 2,844 lines): the mod groups a screen into
   pet shop, food shop, team and actions; the Android port currently presents
   one flat ring.
4. ~~TalkBack tree~~: implemented in `SapOverlay` (see below).
5. **Settings and the updater** (`MelonPreferences`, `AutoUpdater`): irrelevant
   shapes on Android; the port would use a small preferences screen.

### Pet card lines

A pet reads as `Weevil, level 1, 2 attack, 2 health, tier 1, Feline. Friendly ate
food. Give them +1 attack until next turn.`

- Level comes from `MinionModel.Level` and is always spoken, so a level 1 shop
  pet is not mistaken for a levelled one.
- Tribe is `MinionModel.Tribe`. The field is read, then its enum's own constant
  table names the ordinal (`il2cpp_field_get_type` -> `il2cpp_class_from_type`
  -> the class's static fields); a build that stores a plain string there is
  read directly. An unnamed value stays silent rather than saying a number.
- The ability text is multi-line in the game. `clean_native` collapses
  newlines, which glued the trigger onto its effect, so the ability now goes
  through `ability_sentences`: each line becomes its own sentence.
- A pet whose localized name did not load falls back to `MinionModel.Type`'s
  enum name, so the card still appears.
- A pet whose localized ability did not resolve still gets a description: the
  snapshot line stops after the tier, and the controller appends the bundled
  `WikiDictionary.json` text for that pet (minus its own leading "Tier N.").

## TalkBack overlay

`SapOverlay` is the Android counterpart of the mod's UnityA11y mode. Android
cannot add nodes to another app's window, so the service adds a window of its
own:

- `TYPE_ACCESSIBILITY_OVERLAY`, full screen, `FLAG_NOT_FOCUSABLE` (the game
  keeps input focus) but touchable, which is what lets TalkBack's
explore-by-touch land on it;
- the window's `AccessibilityNodeProvider` publishes the snapshot as virtual
  nodes. Node bounds come from each control's `RectTransform.rect` multiplied
  by its `lossyScale` (the canvas scale), centred on the transform position the
  bridge reports (`x`, `y`, `w`, `h`); controls without a rect get a 160x48
  target so TalkBack can still hit them;
- the PC mod's `IsVisible` rule is applied in the bridge: a control whose
  `targetGraphic` is fully transparent and that has no text is skipped (the
  sign-in page hides a developer-console hotspot that way);
- roles map to Android classes: `Button`, `Switch` (with `checked`), `SeekBar`,
  `EditText`, `Spinner`, `TextView`. TalkBack reads the class's role and
  state from the node, which is why the node carries the native value;
- `ACTION_ACCESSIBILITY_FOCUS` moves both TalkBack's focus and the game's
  selection (through `nativeFocus`), `ACTION_CLICK` calls `nativeTap`, and
  hover events from explore-by-touch focus the node under the finger;
- the tree is refreshed from the 200 ms poll, and `TYPE_WINDOW_CONTENT_CHANGED`
  is sent only when the control signature actually changes, so TalkBack does
  not lose its place every frame.

The overlay exists only while `AccessibilityManager.isTouchExplorationEnabled()`
is true. With it off, the service speaks through TTS as before and the game
keeps normal touch input.

## Controller input

Controller buttons arrive as ordinary key events, so they go through the same
`onKeyEvent` filter as the keyboard: D-pad and shoulders move, A activates, B
backs out, X/Select reads the screen, Y opens the dictionary, Start shows help.
Those keys are consumed, so the mod owns controller navigation like it does on
the PC.

Left-stick steering uses `AccessibilityService.onMotionEvent`, which exists
from Android 14. It reads `AXIS_X`/`AXIS_Y` (falling back to `AXIS_HAT_X`/`Y`)
with a deadzone and only fires when the direction changes, so a held stick does
not flood the service. On older Android the D-pad still works; the stick does
not reach accessibility services there.

## Adding a game-specific provider

1. Confirm the class, field and method names against the game's
   `global-metadata.dat` (Il2CppDumper or Cpp2IL) — names are the same as on
   Windows, which is why the C# source is the reference.
2. Add a helper in `native/src/sapa11y.cpp` that finds the class, reads the
   field or calls the method, and appends to the node's `label` or `value`.
3. Keep every call null-checked and exception-checked: the game must not crash
   because a screen is half-built.
4. Speak through the service, not from native code, so interrupt/queue rules
   stay in one place.

## Morphe API notes (patcher 2.2.0)

The patch is written against the published patcher, and a few things differ from
the examples in older documentation:

- `patch { }` builds a `PatchDefinition`. Its `execute { }` block has no
  receiver, so the context functions are top-level and need imports:
  `app.morphe.patcher.patch.document`, `.resourceFile`, `.listApkEntries`.
- Files are added with `resourceFile(path, false)`. It does not create parent
  directories, so call `mkdirs()` before writing a new path such as
  `res/xml/`.
- `listApkEntries` indexes directories, not files, and a missing directory
  returns an empty list. IL2CPP and the shipped ABIs are therefore detected
  from `assets/bin/Data/Managed/Metadata/` and the `lib/` subdirectories.
- Files in the `.mpp` are only visible through a class that is defined in the
  bundle itself: `object {}.javaClass.classLoader`. Resolving them through a
  patcher class (for example `Compatibility::class.java.classLoader`) returns
  null because that class belongs to the application loader.
- `extendWith("extensions/sapa11y.mpe")` merges the extension dex as
  `classes2.dex`.

`tools/build_patch_bundle.sh` compiles this source against a published Morphe
jar and assembles the `.mpp`; the Gradle plugin is only needed for release
builds and requires GitHub Packages credentials.

## Debugging on device

```bash
adb logcat -s SapA11y
adb shell settings get secure enabled_accessibility_services
adb shell dumpsys accessibility | head -40
```

The bridge logs class resolution once (`classes: object=... selectable=...`) and
the service logs connection. `SapNative.nativeBackend()` reports whether
`libil2cpp.so` was found and whether the bridge reached `ready`.

### Reading a fault line

The scanner walks live Unity objects, so a pointer the game is tearing down can
fault inside a snapshot. Each recovered fault writes one line, deduplicated by
`(signal, pc)` — the first three hits, then every ten-thousandth:

```
fault: sig=4 pc=0x... lr=0x... sp=0x... addr=0x... lib=/data/app/.../libil2cpp.so off=0x... phase=shop-zones thread=12345 hits=3 scope=snapshot
```

- `pc`/`lib`/`off` name the faulting instruction; `off` is the offset into the
  named library, which is what a symbolised tombstone would resolve.
- `phase` is the scanner stage (`controls`, `spaces`, `texts`, `shop-zones`, …),
  so the line says which call was running when it faulted.
- `thread` is compared against the bootstrapping thread on every snapshot; a
  different value means the bridge is being driven from the wrong thread.
A full return-address backtrace is not captured: `_Unwind_Backtrace` is not
async-signal-safe, and a fault inside it would re-enter the handler and kill a
fault the bridge can absorb. If `lib=`/`off=` turns out to point inside
`libil2cpp.so`, the offset can be resolved against that library's symbols
off-device with `llvm-symbolizer`.

- `scope=snapshot` means the poll recovered and the game continues;
  `scope=detail` is a pet card's description being read (the card is published
  without its description, the screen still updates); `scope=action` is a
  tap/focus/submit attempt; `scope=fatal` is a fault the bridge cannot safely
  absorb and the process dies as it would without it.

The innermost guard wins: a description read runs inside a snapshot but has its
own handler, because losing a whole screen to one unreadable card is what left
TalkBack frozen on the previous page while the game had already moved on. A
fault line with `phase=spaces` and `addr=0x10` in libil2cpp was a static field
read on an enum class whose statics did not exist yet; enum lookups now name the
class first (`type_object`), and only for classes whose parent is `Enum`.

Shop and team slots are published from the game's own `Spaces` lists, not from a
scene walk. `MinionShop`, `SpellShop` and `MinionArmy` each hold the exact Space
objects the player sees, so those lists are the source of truth for "what is on
this screen": the walk found the tabs and the scoreboard while the probe read
`shopPets=3 team=5` and the tree held no card at all. Each slot is published as
a node whose id is the Space component, so the normal injected tap hits it, and
whose line comes from `pet_detail` (`Weevil, level 1, 2 attack, 2 health, tier
1, Feline. …`); a slot with no readable model still appears as "Pet shop slot"
so it can be focused and tapped. Slot counts are logged as `slots: pets=N
food=N team=N` whenever they change, and the first three shop screens log every
slot as `slot petshop: id=... at x,y wxh detail='...'` — id 0, a 0x0 rect or an
empty detail there is the whole story of a card that is on screen in the game
but missing from the tree.

A slot is never dropped for having no measurable rect (the sizeless filter that
clears Unity's 0x0 leftovers now only applies outside a shop/team/food zone),
and a slot whose own transform has no rect takes its bounds from the pet's
`MinionView` child instead. Class metadata in the diagnostics is preview-only
(600 characters per class): naming every method and field made the dump alone
larger than a megabyte, so the real play log was truncated out of the report.

The published report is also capped to its tails (`copyTail`: 400 KB of the
native log, 200 KB of each other log, starting on a line break). The native log
accumulates every launch, so publishing all of it meant the newest session —
which is at the end — was the part that fell off once the file got large. Every
launch now writes `bridge build <tag>` as the first line, and the controller
names the same tag in `injected into the game window (... mod build <tag>)`, so a
report says which build produced it without having to infer it from behaviour.

The scan is also gated on the engine's own scene state, which covers loads the
controller did not start (a reconnect, an end-of-turn resolve, a battle
preload). `SceneManager.get_sceneCount` is a static read with no object walk, so
it is safe to ask while a load is in flight: when the count rises above the last
stable value the scan is held, logged as `swap: 2 scenes (stable 1), scan held
N ms`, and released when the count settles. The hold is capped at 15 s so a mode
that legitimately keeps an extra scene loaded cannot freeze the reader; after
that the new count is adopted as normal.

`nativeProbe` obeys the same pause and the same scene gate. It was the one
walk still running inside a load: the controller probes every couple of seconds
during the ten-second transition watch, so a session that hung right after
"Continue" ended with the pause markers and then nothing at all. It now answers
`probe: paused, the page is loading` without touching any game object.

The board is the slot chooser: activating a shop card or a board pet picks it up
(`Weevil picked up. Swipe to the board slot you want and double-tap it.`), and
activating any board slot drops it there. That replaces the PC mod's "arrows to
the slot, then Enter" with the thing a TalkBack user already does — swipe and
double-tap the slot itself — so there is no separate dialog to dismiss. A hold
expires after 12 s and is dropped when the phase stops being the shop, so an old
pick-up cannot move the wrong pet.

Placement is a drag, because that is what the game itself does: a tap can only
append to the first free slot. Double-tap a shop card (the card is remembered as
the held pet), then use "Place held pet here" on the board slot you want. That
runs `injectDragSync` — a real touch that presses on the card, slides in six
steps so UGUI's drag threshold starts, and releases on the slot. Logged as
`inject drag id=... from x,y to x,y consumedDown=...` and `place: 'Weevil' -> slot
2 sent=true`.

The scan is also paused from the Java side around the taps that start a load.
`SapNative.nativeQuiet(5000)` runs before a play-like control is delivered, so
`quiet: scan paused 5000 ms by the controller` marks the window; nothing walks
the object graph while the scene is being built. Reading the graph on the UI
thread during that build is the deadlock shape: the UI thread blocks inside the
engine call, input never returns, and Android kills the app for not responding
(which looks like "it hangs 30 seconds and then closes", with the last tree left
on screen and no new one ever published).

After a fault the next five polls are skipped (a fault means the graph was
mid-swap, so re-entering one 200 ms tick later only faults again), doubling per
consecutive fault up to 16 polls (~3 s); the backoff resets as soon as a
snapshot completes. The
handler writes with `write(2)` on a pre-opened descriptor, never stdio: a
`FILE*` lock held by the interrupted thread would turn a logged fault into a
frozen game.

### Quiet window while a page loads

Every recovered fault in the reports lands in the control sweep on the frame
right after `Loading menu...`, while Unity is tearing the old page down and
building the next one. The scan is a cross-thread read of exactly the objects
being replaced, so there is no single call to blame and no way to make the walk
safe: the fix is not to walk a half-swapped hierarchy at all. The text sweep
never faulted in that state, so it is the detector — when the screen is nothing
but a loading/connecting caption, the scan is held for 1.5 s and the lines to
look for are:

```
quiet: loading screen, holding the scan for 1.5 s
quiet: scan held 900 ms more (page still loading)
```

Holding a scan also cannot leak an engine lock, which longjmp-ing out of a
half-run control sweep can; that leak is a plausible way for a recovered fault
to leave the game frozen on a loading screen. The caption is looked for every
poll while the previous tree was sparse (boot) and once a second otherwise, so
a swap that follows a busy screen is still caught.

The hold is capped at 6 s of continuous holding (`quiet: loading caption still
up after ... ms, scanning anyway`). Without the cap, a caption that outlives the
swap would leave TalkBack on the previous screen for good, which reads as the
mod not following the game's state change at all.

Controls with no label, value or detail line are dropped and counted as
`unlabeled dropped=N` in the snapshot line. An unlabelled icon button is
unusable with a screen reader and is also a trap: the first focusable control
on the main menu was the blank 85x85 store icon, so the first double-tap opened
the purchase page unbidden.

`nativeStats()` carries `proj=overlay/canvas/main/none` and `screenPx=WxH`, so a
screen whose coordinates all come from the main-camera fallback is visible
without guessing. `nativeProbe()` reports what the game state is behind the
tree: `hangar=`, `phase=`, `gold=`, `team=`, `shopPets=`, `nodes=`. The
controller logs it for ten seconds after any play/start/continue control, which
separates "no match started" from "match started, nothing found" from "found,
never published".

## The game is not decompiled, and the dumpers reject this build

The extracted APK pieces live in `~/sap/apk/ext` (`lib/arm64-v8a/libil2cpp.so`,
`assets/bin/Data/Managed/Metadata/global-metadata.dat`) plus its `classes.dex`.
Extracted, not decompiled: an IL2CPP build ships no C#, so there is no game
source on this box. Tried and failed:

- Il2CppDumper 6.7.46 (net7 build on the installed .NET 8 runtime):
  `ERROR: Metadata file supplied is not a supported version[39]` — Unity 6
  metadata, past what the dumper knows.
- Cpp2IL: newest release is 2022.1.0-pre-release.21 and does not read v39.

The only view of the game is the runtime one the mod already has: `diag_class`
prints real members from live metadata, `nativeProbe` prints live shop state.
Behaviour questions have to be answered from a report.

What the member names do settle, though: `Space` exposes
`SetPlayMinionIndicator`, `SetAimIndicator` and `SetOrderMinionIndicator`, and
`HangarMain` exposes `HandleMinionShopClick`, `HandleMinionArmyClick`,
`HandleMinionArmyDragEnd`, `PlayMinionAsync/3`, `SellMinionAsync/1`. The mobile
build therefore places pets through the same pointer handlers as a finger, and
it lights up valid slots while a pet is held — which is what the pick-up /
double-tap-a-slot flow in `SapController` drives.

## Tests

`bash tests/run_tests.sh` first resolves every class and member the shop and
board paths call against `tests/reference/members.json` (the decompiled game's
signatures, produced by `tools/extract_reference.py`; see the reference check
below), then compiles the pure classes (`SapNode`, `SapText`, `ShopRules`,
`ShopView`, `BoardView`) with `javac -Xlint:all -Werror`, runs the native
reading-order test on the host, and then runs four suites in parallel:

* `SapTreeTest` — the node JSON the native side writes (a full shop snapshot,
  truncated/garbage payloads, the `-10000` no-rect sentinel), the geometry a tap
  is aimed with, and how a shop slot is spoken.
* `ShopRulesTest` — the rules the port applies itself: one-gold rolls spent
  after banked free rolls, unknown gold never blocking a roll, sell refunds of
  one gold per level (not per tier), and level thresholds of 3 and 6 copies in
  total, 2 and 3 more.
* `ShopSnapshotTest` — post-action state read from before/after snapshots: roll,
  buy, sell, freeze, food, duplicate merge and end-of-turn refresh.
* `ShopBoardTest` — board-state extraction: the battle team line, per-slot
  damage/faint detection, zone names in slot order, empty-slot placeholders, the
  status line and shop affordability.
* `tests/native/test_order.cpp` — the snapshot reading order: zone order, rows,
  and the transitivity that the previous comparator lacked.
* `tests/native/test_optional.cpp` — the `Nullable<int>` unwrap behind
  `MinionModel.SellValue` (and the same shape as `Tribe`): present, absent,
  zero, negative, padded layouts and a layout that does not fit the buffer, so
  an absent value can never be reported as 0.
* `tests/reference/check.py` — the IL2CPP surface: every member behind board
  state, gold/turn/roll counters, tier, lives, wins/losses, pet stats, level
  and experience, sell value, freeze state and every interaction the
  controller performs is asserted against the decompiled game with its kind,
  arity, parameter types and static/instance shape; every `find_class` and
  every member-name literal in `sapa11y.cpp` must resolve in that reference.
  The artifact records the dump's line count and SHA-256 so a reader can tell
  which reference it came from.

They need a JDK, `tests/lib/json.jar` (`org.json:json:20240303`), `python3` and
a host C++ compiler. The game cannot run in a build environment, so nothing
here exercises the game's own shop code, and the reference check cannot decide
how a member behaves or whether the object handed to a call is of the right
runtime type; that needs a device and a report.
