# SuperAutoAccessibility for Android

An Android port of [SuperAutoAccessibility](https://github.com/GreenBeanGravy/SuperAutoAccessibility),
the mod that makes Super Auto Pets playable with a screen reader, packaged as a
[Morphe](https://github.com/MorpheApp/morphe-patcher) patch.

The patch is applied to your own copy of the Super Auto Pets APK
(`com.teamwood.spacewood.unity`). It injects a native game bridge
(`libsapa11y.so`) and an accessibility extension into that APK; nothing else is
installed, and no root or Xposed is involved. There is **no accessibility
service**: the virtual tree is added to the game's own window, so nothing has to
be enabled in Android settings.

## Why the port is not "copy the DLL"

The Windows mod is a MelonLoader plugin: ~24,700 lines of C# that reach the game
through Il2CppInterop, speak through Tolk (NVDA/JAWS/SAPI) and publish a real
UIA tree through UnityA11y/AccessKit. None of that exists on Android:

| Windows | Android (this port) |
| --- | --- |
| MelonLoader + Il2CppInterop | `libsapa11y.so` resolves the exported `il2cpp_*` reflection API from the already-loaded `libil2cpp.so` and walks live game objects |
| Tolk / NVDA / SAPI | Android `TextToSpeech` (Eloquence or any installed engine) |
| UnityA11y + AccessKit UIA tree | a TalkBack virtual tree in an accessibility overlay (explore by touch, swipe, double-tap), or TTS when no screen reader runs |
| XInput gamepad, keyboard binds | hardware key events filtered inside the game process, plus left-stick steering |
| MelonPreferences | fixed defaults (settings UI is phase 2) |

The game logic that the mod adds — shop lines, battle narration, pet stats,
sections — stays in C# on the PC. This port currently ships the platform layer
plus a working menu experience and the keyword dictionary; see
[Status](#status) and [PORTING.md](PORTING.md).

## Status

Phase 1. The patch bundle builds and applies end-to-end (see [Verified](#verified));
nothing has run against the real game yet:

- [x] Morphe patch: injects the native bridge per ABI, the extension dex, the
      keyword dictionary and the startup report provider
- [x] Native bridge: il2cpp API resolution, UGUI snapshot (buttons, toggles,
      sliders, inputs, scrollbars, static text), tap/focus/value/text actions
- [x] In-game extension: polling, focus movement, activation, back, read
      screen, help, key filtering
- [x] Text cleaning and the keyword dictionary with categories and typed search
      (ported from `TextExtractor` and `DictionaryMenu`)
- [ ] Shop and battle narration (`ShopNarrator`, `BattleNarrator`,
      `PetStatsReader`, `DetailLineProvider`)
- [ ] Sections and the full key map of the PC mod
- [x] TalkBack virtual tree: an accessibility overlay publishes every control
      with bounds, reading order, roles and actions, so TalkBack explores and
      activates the game itself
- [x] Gamepad and keyboard navigation, left-stick steering, dictionary and
      read-screen on controller buttons
- [ ] Settings screen and auto-updater

## Verified

- The patch compiles against `morphe-patcher` 2.2.0 and loads in the Morphe
  Desktop CLI (`list-patches`).
- Applying it to a stand-in APK with the game's package name, a dummy
  `libil2cpp.so` per ABI and `global-metadata.dat` succeeds. The output contains
  `libsapa11y.so` for both ABIs, `classes2.dex` with the extension classes, the
  keyword dictionary, and the manifest entry for the startup report provider.
  No service XML and no accessibility-service manifest entry are produced: the
  tree lives in the game window. Checked with `aapt2 dump` and `dexdump`.
  (An earlier build did write `res/xml/sapa11y_service.xml` and a service entry;
  both were removed when the service was dropped, and the dead resource is gone
  from the patch.)
- Checked against the real APK (`com.teamwood.spacewood.unity` v204,
  arm64-v8a only, MD5 `35c170bd0264e2b9f30a5568bad9b4c3`): the patch applies and
  signs in about 8 seconds. `libil2cpp.so` exports 241 `il2cpp_*` functions and
  all 22 the bridge resolves are present; `global-metadata.dat` is version 39 and
  contains `PageManager`, `CurrentPage`, `SelectableBase`, `ButtonBase`,
  `GetInteractable`, `OnPointerClick`, `EventSystem`, `FindObjectsByType`,
  `UnityEngine.UI` and `TextMeshPro`. The signed result is
  `out/SuperAutoPets-204-sapa11y.apk` (gitignored).
- Not verified: anything at runtime. The il2cpp lookups, the menu snapshot and
  speech have not been exercised on a device, so the first launch is the real
  test. `out/` holds the patched build used for these checks and is gitignored.

## Layout

```
sap-a11y-android/
  native/                 libsapa11y.so: il2cpp bridge + UGUI snapshot + actions
  extension/              Android extension: controller, injected tree, speech, dictionary
  patch/                  Morphe patch bundle (Kotlin/Gradle)
  tools/build_extension.sh    javac + d8 -> artifacts/extensions/sapa11y.mpe
  tools/build_patch_bundle.sh kotlinc + jar -> artifacts/patch/sapa11y.mpp
  artifacts/              build inputs the patch packages into the .mpp
```

## Build

Requirements: JDK 17, Android SDK platform 34 and build-tools 35.0.0, Android
NDK r26 (or newer), and Kotlin 2.4 to compile the patch.

```bash
# 1. native bridge, both ABIs
bash native/build.sh

# 2. extension dex
bash tools/build_extension.sh

# 3. the patch bundle -> artifacts/patch/sapa11y.mpp
MORPHE_JAR=~/morphe/morphe-desktop-2.2.0-all.jar bash tools/build_patch_bundle.sh
```

Steps 1 and 2 need the SDK and NDK; step 3 only needs a published Morphe jar
(`morphe-desktop-*-all.jar` from the Morphe Desktop releases, which bundles
`morphe-patcher`) and a Kotlin compiler that can read its metadata, so it runs
without GitHub credentials.

The Gradle project in `patch/` is still the canonical release build
(`cd patch && ./gradlew buildAndroid`), but it resolves the patcher from
`maven.pkg.github.com/MorpheApp/registry`, which needs a token with
`read:packages` in `~/.gradle/gradle.properties` (`gpr.user` / `gpr.key`, or
`GITHUB_ACTOR` / `GITHUB_TOKEN`).

## Apply

```bash
java -jar morphe-desktop-*-all.jar patch -p artifacts/patch/sapa11y.mpp SuperAutoPets.apk
```

The CLI signs the output with its own key unless you pass `--unsigned` (an
unsigned APK will not install). The Morphe Desktop app can load the same `.mpp`
from its patches screen.

The APK must be the IL2CPP build (it contains `lib/arm64-v8a/libil2cpp.so` and
`assets/bin/Data/Managed/Metadata/global-metadata.dat`). If your copy is an
`.xapk`, patch the base APK together with the `arm64-v8a` split, or install a
merged APK.

Then install the patched APK and start the game; there is nothing to enable in
Settings. With TalkBack running, swipe through the injected tree. With a hardware
keyboard or a gamepad attached, the extension filters the keys below and leaves
everything else to the game.

## Keys

The extension consumes only these keys, so the game keeps every other input.

| Key | Action |
| --- | --- |
| Up / Down, Left / Right | Previous / next control |
| Tab / Shift+Tab | Next / previous control |
| Home / End | First / last control |
| Enter, Space, D-pad centre | Activate the focused control |
| Escape | The screen's Back / Close / Cancel button |
| R | Read the whole screen |
| H | Help |
| J | Keyword dictionary |

In the dictionary: Up/Down move, Left/Right change category, Enter reads the
entry, type letters to search, Backspace deletes, Escape closes.

On a controller, matching the PC mod's layout: D-pad or left stick moves, A
activates, B backs out, X reads the focused item again, Y reads the screen,
View shows help, Start goes back, the left stick click opens the dictionary and
the right stick click repeats the current line. The shoulder buttons step
through items (the PC mod's section jumps need game-specific grouping, which is
not ported yet). Controller keys are consumed while the game is in front, so
the mod owns navigation exactly as it does on the PC.

## TalkBack

When touch exploration is on (TalkBack, or any screen reader that uses it), the
service switches modes: it adds a full-screen `TYPE_ACCESSIBILITY_OVERLAY`
window whose `AccessibilityNodeProvider` exposes the live control list as
virtual nodes, and stops speaking through TTS so nothing is announced twice.
TalkBack then reads the control under your finger, swipes through them in
reading order, and double-tap activates through the native bridge. Arrow keys
and the controller move TalkBack's focus too. With touch exploration off the
overlay is removed, the game keeps normal touch input, and speech goes through
TTS.

The overlay is transparent and does not take input focus. While it is attached
it does take touch events, which is what makes explore-by-touch work; turn
TalkBack off to use the game by touch.

## Modification checks

The build was audited for the kind of startup integrity checks that break
patched APKs. Nothing of that sort is present:

- no Play Integrity, SafetyNet or attestation APIs, and no Play Games signature
  checks; `com.google.android.gms.security` is only the Conscrypt provider
  installer;
- the only `getPackageInfo`/`signingInfo` users are Play Billing and
  `androidx` font loading, and `GoogleSignatureVerifier` (GMS's own check of
  Google apps) — the game's C# never calls PackageManager: `getPackageInfo`,
  `signatures` and `signingInfo` do not appear in `global-metadata.dat` at all;
- no anti-cheat or packer libraries: the APK carries only Unity's own
  `libil2cpp`, `libunity`, `libmain`, `libc++_shared` and `libswappywrapper`;
- no root, emulator, debugger or hook detection strings in the dex or metadata;
  the `CheckSignature`/`IsModified` names in metadata belong to the bundled
  BouncyCastle/BestHTTP crypto, not to a tamper check;
- `libil2cpp.so` does not reference Unity's `Application.genuine` checks.

This is a static audit: it cannot prove a check is absent, only that none of
the usual mechanisms are present. The APK is re-signed, so anything that
compares the certificate would fail — nothing in this build does.

## Notes

- Only IL2CPP builds are supported; a Mono build has no `libil2cpp.so` and the
  patch stops with an explanation.
- The bridge polls the live control list four times a second, the same rate the
  PC mod refreshes its scene objects; it does not hook or patch game code.
- The service talks to the game inside the game's own process, so speech starts
  only after Unity has loaded `libil2cpp.so`; the bridge waits for it.
- The compatibility declaration is experimental for all versions until the port
  has been tested on an exact build.

## Provenance

The keyword dictionary (`artifacts/assets/sapa11y/WikiDictionary.json`) and the
speech/formatting behaviour ported into `SapText` and `SapDictionary` come from
the SuperAutoAccessibility repository. That repository has no license file at
its root, so check with its author before redistributing this port; its
`THIRD-PARTY-NOTICES.txt` covers the components it bundles.

SuperAutoAccessibility is a fan-made mod and is not affiliated with or endorsed
by Team Wood Games.
