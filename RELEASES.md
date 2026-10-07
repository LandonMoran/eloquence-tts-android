# Release contract

## Version and publication

- **Publication status checked 2026-10-07:** GitHub currently has no published
  Releases. Repository tags `v1.0` and `v1.1.0` exist, but tags alone do not
  provide downloadable APKs or prove that the release workflow completed.
  `AndroidManifest.xml` currently identifies the checkout as version name
  `1.0.1` and version code `2000000002`; these are source-tree values, not
  evidence of a published release. Update this status after checking GitHub
  Releases and the tag-triggered workflow.
- Release tags use `vMAJOR.MINOR[.PATCH]`; each numeric component is limited
  to three digits. The APK `versionName` must match the tag.
- Android `versionCode` must increase independently. The semantic updater
  requires exactly one `<!-- android-version-code: N -->` marker in release
  notes. The publication helper reads this value from the signed APK
  manifest; the tag alone cannot predict it.
- The `v*` tag workflow builds APKs, signs them in the protected GitHub
  `release` environment, audits their ABI contents, then publishes the signed
  assets and APK-derived metadata. Configure required reviewers, restrict the
  environment to protected release tags, and provide the signing secrets
  before using the workflow.
- Ordinary CI creates artifacts with a temporary signing identity. Those
  artifacts are not upgrade-compatible with an installation signed by the
  production key. A change in production signing identity also prevents
  in-place upgrades.
- Publication is implemented in `.github/workflows/release.yml` and
  `tools/publish_release.py`, but has not yet been validated end to end by a
  production tag. Verify a real tag-triggered release before relying on it.

## Assets

Production releases contain:

- `vvtts-arm64-v8a.apk`
- `vvtts-armeabi-v7a.apk`
- `vvtts-universal.apk`

The universal APK includes arm64-v8a, armeabi-v7a, and x86_64 libraries.
The separate `vvtts-test-x86_64.apk` is built for emulator testing and is not
published as a production asset. If the updater cannot select a compatible
asset, it falls back to the GitHub Release page rather than offering an
incompatible APK. See [`tools/audit_apk_abis.py`](tools/audit_apk_abis.py) and
[`docs/artifacts-contract.md`](docs/artifacts-contract.md).

## Release validation

Before release, check the relevant CI results and, when hardware is available,
verify:

1. Short speech in each of the 14 advertised locales and the available voice
   presets.
2. Rapid TalkBack stop/restart, voice changes, and language changes without
   stale speech or a service failure.
3. TTS before the first device unlock after reboot.
4. An upgrade from a previously installed production-signed APK using Android's
   package installer.
5. Update selection when a compatible asset is present and fallback behavior
   when it is not.

Host tests and sanitizers cannot replace listening, device lifecycle, or
installation checks.

## Historical release notes

Older release notes and issue-era test descriptions may refer to languages,
limitations, and workflows that were later changed or are not part of the
current Android product. The current locale list is maintained in
[`VoiceRegistry.kt`](src/com/xw/vvtts/engine/VoiceRegistry.kt); build and test
lanes are defined by [the CI workflows](.github/workflows/build.yml) and
summarized in [the repository audit](docs/repository-audit.md). Update this
file with release-specific notes when publishing a release, without treating
historical notes as the current capability list.
