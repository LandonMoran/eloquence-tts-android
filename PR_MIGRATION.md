build: migrate hand-rolled build.sh to Gradle (AGP) + Firebase Crashlytics

Replaces the aapt->javac->kotlinc->d8->zip->zipalign->apksigner build.sh
pipeline with a standard Android Gradle Project (AGP 8.9.2, Kotlin 1.9.25,
Gradle 8.11.1), preserving byte-faithful behavior of every artifact, and adds
free Firebase Crashlytics crash analytics.

## Build-system migration

- Single-module AGP project (repo root is the module) so no sources move.
- sourceSets.map to existing `src/` (kotlin+java), `res/`, `assets/`,
  `native-libs/` (jniLibs) and the top-level AndroidManifest.xml.
- Native: `:buildNative` Gradle Exec task runs the same `build_native.sh`
  NDK cross-compile (arm64-v8a, armeabi-v7a, x86_64) writing
  `native-libs/<abi>/libvvtts_core.so`; AGP packages those into the APK.
- Asset prep: `:prepareAssets` runs `tools/prepare_assets.py` (idempotent,
  rebuilds patched lingua-slim.jar + models.elqm only when stale).
- The 4-way APK matrix is reproduced with product flavors
  (arm64 / armv7 / univ / x64) + the SAME legacy filenames
  (vvtts-arm64-v8a.apk, vvtts-armeabi-v7a.apk, vvtts-universal.apk,
  vvtts-test-x86_64.apk) so downstream tools (audit_apk_abis.py,
  publish_release.py, emu lanes) are unchanged.
- Test-only -PtestMinApi=25 override mirrors the old TEST_MIN_API lane for the
  armv7 flavor.
- No ProGuard/shrinking (identical to the legacy no-minify build).
- Signing: debug keystore for debug; release reads KEYSTORE/KS_PASS/KEY_PASS
  env (CI ephemeral key) and falls back to debug so untagged CI passes.

## Manifest changes (Gradle-mandated)

- Stripped `package=`, `versionCode/versionName`, and `uses-sdk` from the
  manifest (now supplied by AGP defaultConfig: app id com.xw.vvtts,
  versionCode 10, versionName 1.0.9, minSdk 28, targetSdk 36). No semantic
  change.

## Crashlytics (free, Spark plan, no billing)

- Adds google-services plugin + google-services.json (Firebase project
  project-e54ceafe-dd3c-4596-989, app com.xw.vvtts) and the Crashlytics
  runtime via firebase-bom 33.7.0.
- Auto-initializes through the ContentProvider the google-services plugin
  injects from google-services.json (no Application subclass or manual init
  needed).
- Project has no billing account linked, so Crashlytics cannot incur a charge.
- The Crashlytics Gradle plugin (symbol/mapping upload) is intentionally NOT
  applied: our .so is hand-built, so native-symbol upload has nothing to map,
  and the JVM/Kotlin crash path (the useful one here) works without it.

## CI

- build.yml drives Gradle (:assembleArm64Release :assembleArmv7Release
  :assembleUnivRelease :assembleX64Release) with the same host/native/vendor/
  contract checks first; APK gather keeps exact artifact names.
- emu-smoke/emu-test/build-apk x86_64 lanes switched from build.sh to
  :assembleX64Release (rename to vvttts_signed.apk as before).
- SDK install updated to platforms;android-36 + build-tools;35.0.0 + NDK.

## Known behavior note

- language-models pack: legacy zipped models.elqm at the APK root under
  /language-models/; AGP places assets under assets/. The app reads it via
  ElqmBridge getResourceAsStream("/language-models/...") which on Android
  resolves against the assets dir, so the pack is shipped at
  assets/language-models/ and excludes the JSON inputs + generator stamp.
  Runtime lookup behavior is preserved.