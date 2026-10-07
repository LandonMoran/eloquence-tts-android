# Languages, voices, and capabilities

[`VoiceRegistry.kt`](../src/com/xw/vvtts/engine/VoiceRegistry.kt) is the
executable source of truth for the Android locales advertised as available.
[`KonaVoice.kt`](../src/com/xw/vvtts/utils/KonaVoice.kt) defines the eight
named voice presets and their base parameter values. Native locale parity is
checked by [`tests/contracts.py`](../tests/contracts.py).

## Shipped locales

The Android app advertises 14 locales:

| Locale | Language | Native dialect |
| --- | --- | --- |
| `en-US` | English (United States) | `0x10000` |
| `en-GB` | English (United Kingdom) | `0x10001` |
| `de-DE` | German (Germany) | `0x40000` |
| `fr-FR` | French (France) | `0x30000` |
| `fr-CA` | French (Canada) | `0x30001` |
| `es-ES` | Spanish (Spain) | `0x20000` |
| `es-US` | Spanish (United States) | `0x20001` |
| `es-MX` | Spanish (Mexico) | `0x20002` |
| `it-IT` | Italian (Italy) | `0x50000` |
| `ja-JP` | Japanese (Japan) | `0x80000` |
| `pl-PL` | Polish (Poland) | `0x110000` |
| `pt-BR` | Portuguese (Brazil) | `0x70000` |
| `fi-FI` | Finnish (Finland) | `0x90000` |
| `zh-CN` | Chinese (Simplified, China) | `0x60000` |

Traditional Chinese (`zh-TW`) and Korean (`ko-KR`) dialect constants and
resources exist in parts of the repository, but they are not in
`VoiceRegistry.kt` and are not advertised or synthesized by this Android build.

## Voice presets and parameters

The app defines eight named presets: Reed, Shelley, Sandy, Rocko, Flo, Grandma,
Grandpa, and Eddy. The selected preset supplies a standard voice row and a
baseline parameter set; a user voice profile can override parameter values.
The native mapping explicitly accounts for Eddy's different row number in
`openevv`.

The eight ECI voice parameters are:

1. gender
2. head size
3. pitch baseline
4. pitch fluctuation
5. roughness
6. breathiness
7. speed
8. volume

Parameter ranges and language-specific native behavior are defined by
[`EloquenceEngine.kt`](../src/com/xw/vvtts/engine/EloquenceEngine.kt),
[`KonaVoice.kt`](../src/com/xw/vvtts/utils/KonaVoice.kt), and the native
[`jni/README.md`](../jni/README.md). The preset list is not a promise that
every locale has identical sound or supports identical native data.

## Runtime capabilities

- The language detector uses Unicode rules, an in-memory n-gram model, and
  Lingua classification. Mixed-language text is segmented before synthesis.
- The local engine has no speech-service network round trip.
- Android system TTS supplies speech rate and pitch; app settings supply voice
  and volume options. Voice profile controls tune the ECI parameters.
- Direct Boot is supported by the TTS service declaration and device-protected
  engine/settings storage. See `AndroidManifest.xml` and
  `src/com/xw/vvtts/services/VvTtsService.kt`.
- The update checker uses GitHub Releases and ABI-specific APK names. Its exact
  version metadata and signing requirements are in [`RELEASES.md`](../RELEASES.md).

When this list changes, update the registry and its native parity checks first,
then keep user-facing documentation and release notes in sync.
