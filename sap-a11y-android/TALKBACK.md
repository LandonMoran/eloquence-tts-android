# TalkBack discoverability — Google docs checklist, game diff, fixes

Source checklist (developer.android.com):
- Custom views: `AccessibilityNodeProvider` virtual tree, distinct stable virtual IDs, correct parent/child, bounds in screen + visibleToUser, role + label + state, advertise only supported actions and implement them, traversal in reading order, hover -> focus, send event after action (`TYPE_WINDOW_CONTENT_CHANGED`, `TYPE_WINDOW_STATE_CHANGED` for new screens).
  - Make custom views more accessible: https://developer.android.com/guide/topics/ui/accessibility/views/custom-views
  - AccessibilityNodeProvider: https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeProvider
  - AccessibilityNodeInfo + actions: https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction
  - AccessibilityEvent: https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent
  - ExploreByTouchHelper: https://developer.android.com/reference/androidx/customview/widget/ExploreByTouchHelper
  - TV custom-view requirements (bounds + visibility, applies generally): https://developer.android.com/training/tv/accessibility/custom-views

## Decompiled game (no jadx on this box — runtime class dump instead)

The APK is IL2CPP (`libil2cpp.so` + `global-metadata.dat`, no game dex).
`libsapa11y.so` dumps exact members at runtime (`diag_class` -> `sapa11y-report.txt`):
`HangarMain`, `HangarOverlay`, `MinionShop`, `SpellShop`, `MinionArmy`,
`Space`, `MinionView`, `BattleController`, `UIBattle`, `BoardModel`,
`MinionModel`, `ConsentManager`, `ConsentData`, plus
`Spacewood.Unity.UI.SelectableBase/ButtonBase`, `PageManager.CurrentPage`.

Diff vs bridge (`sapa11y.cpp`):
- Controls: UGUI `Selectable/Button/Toggle/Slider/Scrollbar/InputField/Dropdown`
  + `SelectableBase/ButtonBase` both scanned (`FindObjectsOfType` ->
  `FindObjectsByType` -> `Resources.FindObjectsOfTypeAll` -> scene walk ->
  singleton roots). Matches game: menus are `ButtonBase`, not UGUI `Button`.
- Labels: `ButtonBase.Label/LabelRight` + `LocalizedTextMesh.TextMesh` (consent
  window captions are empty on first frame without this). Matches dump.
- Zones: `MinionShop/SpellShop/MinionArmy Spaces` -> petshop/foodshop/team,
  overlay buttons -> actions, sorted zone order then y/x. Matches PC S/F/T/D.
- Shop state read-only via `HangarMain.Overlay/MinionShop/SpellShop/MinionArmy`,
  battle via `BattleController/UIBattle`, scoreboard open flag. Nothing drives
  game logic except tap/focus/text/value submit handlers.
- Consent only via `ConsentManager._instance` data write, never the window
  close path (deadlocks off Unity thread). Matches dumped API.

## Discoverability review (fresh pass): simpler event path, fragile bits removed

1. Status banner stole focus: `status` TextView was
   `IMPORTANT_FOR_ACCESSIBILITY_YES`, so the first swipe landed on
   "Super Auto Pets - N controls" instead of the first control. Fixed to `NO`.
   Kept — one line, clearly correct.
2. New-screen signalling, simplified: the previous pass added a parallel path
   (`titleChanged` flag in the controller + `onNewScreen()` + a re-fired event
   in the `pendingAnnounce` block on top of the 2s coalescing timer in the
   overlay). Two timers, two event sites, one flag spanning classes — fragile
   and easy to double-announce. Replaced with one rule in one place:
   `SapOverlay.update()` sends exactly one event per real change,
   `STATE_CHANGED` when the title changed, `CONTENT_CHANGED` otherwise.
   The controller only calls `update()` once the tree is stable and never
   re-fires; speech-only narration stays in the `pendingAnnounce` block.
   Net: deleted `titleChanged`, `sendContentChanged()`, `onNewScreen()`, and
   the overlay `lastChangeMs` coalescer (the controller's stable gate already
   does that job).
3. Missing `boundsInParent`: only `boundsInScreen` was set. TV requirements
   list both; added `setBoundsInParent` with the same rect (parent is the
   full-screen host at 0,0, so the rects coincide). Kept — trivially safe.
4. Sliders/scrollbars stay CLICK-only: the snapshot carries only the formatted
   value string, never a min/max, so there is nothing correct to advertise —
   no adjustable slider sits on a TalkBack-critical path today.
5. Custom actions gave no confirmation: Roll/Freeze/Sell/End turn/Details/Board
   performed the game action but sent no event, so TalkBack stayed silent.
   Kept the one-line `TYPE_VIEW_CLICKED` after each custom action — matches
   the docs rule (send an event after an action that normally generates one).
6. Zero-size controls no longer blank the screen: the native layout gate used
   to return `{"ready":false}` whenever half the controls had no size (the
   reports show 86/104 and 151/193 on live screens), so TalkBack stayed on a
   stale tree, every double-tap hit a dead id, and the banner sat on "waiting
   for the game". Now 0x0 controls are dropped individually and the screen is
   only held back when nothing at all is laid out yet (real boot).
7. Roll / End turn reachable on any screen: the four global actions (Roll shop,
   End turn, Read shop status, Read details) were attached only to laid-out
   controls, so a screen whose own buttons were not exposed left TalkBack with
   no way to act. They are now on every non-input node, text included; they do
   not depend on the node at all.
8. A click can no longer kill the game: `tap()` and `focus()` ran game code
   unguarded, so a control whose page was torn down under the tap faulted and
   took the process with it (the report ends mid-screen with no tap line). Both
   now run under the same `sigsetjmp` guard as the submit fallback: the click
   aborts, the game survives.
9. A plain double-tap now drives the same controls as double-tap-and-hold.
   `tapOrInject` called `nativeTap` first, and that synthetic UI-event path
   reports success on the game's own custom buttons without running them: the
   tap line was logged and the game did nothing, which is why a plain
   double-tap seemed dead while the long-press (a genuine held touch) always
   worked. A real injected touch is now the primary path, gated by
   `nativeTapCheck` so it can only land on the control still at those pixels;
   the synthetic click and the submit handlers remain as fallbacks when
   nothing consumed the touch. Report lines: `tap touched` / `tap synthetic`.
10. A pet card no longer disappears from the spoken tree. Shop and team slots
   are `Space` objects whose card lives in a child `MinionView` field, so the
   parent search found no minion and an occupied slot was read as empty; and
   the node description skipped `detail` whenever it "contained" the label —
   which is always true for a blank label, the pet case. Both are fixed, so
   every shop pet is now a focusable node whose description is its name,
   stats, tier and ability.
11. No false headings: deliberately not marking every static text as a heading
   (docs: mark actual headings only). The game exposes no heading flag, so the
   host title announcement carries screen context instead. Kept as-is.

Kept: stable game instance IDs as virtual IDs (never list positions), disabled
controls advertise no CLICK, `ACTION_SET_TEXT` only on editable inputs with
typing-hold (value churn doesn't rebuild the tree), transparent host passes
touches through when TalkBack is off, zones keep PC reading order.
