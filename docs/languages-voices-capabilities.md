# Languages, voices, and capabilities

[`VoiceRegistry.kt`](../src/com/xw/vvtts/engine/VoiceRegistry.kt) is the
executable source of truth for the Android locales advertised by the app.
[`KonaVoice.kt`](../src/com/xw/vvtts/utils/KonaVoice.kt) defines the eight named
voice presets and their base parameter values. Native locale parity is checked by
[`tests/contracts.py`](../tests/contracts.py).

## Shipped locales

The app currently advertises 13 locale voices backed by all 12 native language
modules:

| Locale | Language | Native dialect |
| --- | --- | --- |
| `en-US` | English (United States) | `0x10000` |
| `en-GB` | English (United Kingdom) | `0x10001` |
| `de-DE` | German (Germany) | `0x40000` |
| `fr-FR` | French (France) | `0x30000` |
| `fr-CA` | French (Canada) | `0x30001` |
| `es-ES` | Spanish (Spain) | `0x20000` |
| `es-US` | Spanish (United States) | `0x20001` |
| `es-MX` | Spanish (Mexico) | `0x20001` |
| `it-IT` | Italian (Italy) | `0x50000` |
| `ja-JP` | Japanese (Japan) | `0x80000` |
| `pl-PL` | Polish (Poland) | `0x110000` |
| `pt-BR` | Portuguese (Brazil) | `0x70000` |
| `fi-FI` | Finnish (Finland) | `0x90000` |

`es-US` and `es-MX` share the upstream `esus` module (`0x20001`). Korean is not
shipped or advertised; the app does not register a `ko-KR` voice or synthesize
Korean text.

## Voice presets and parameters

The app defines eight named presets: Reed, Shelley, Sandy, Rocko, Flo, Grandma,
Grandpa, and Eddy. The selected preset supplies a standard voice row and a base
parameter set; a user voice profile can override parameter values.

The eight ECI voice parameters are:

1. gender
2. head size
3. pitch baseline
4. pitch fluctuation
5. roughness
6. breathiness
7. speed
8. volume

Parameter ranges and language-specific native behavior are defined in
[`EloquenceEngine.kt`](../src/com/xw/vvtts/engine/EloquenceEngine.kt),
[`KonaVoice.kt`](../src/com/xw/vvtts/utils/KonaVoice.kt), and the native
[`jni/README.md`](../jni/README.md).

## Runtime capabilities

- The language detector uses Unicode rules, an in-memory n-gram model, and
  Lingua classification. Mixed-language text is segmented before synthesis.
- Synthesis is local to the device; the app does not send speech text to a remote
  service.
- Android TTS supplies speech rate and pitch controls; app settings expose voice
  and volume controls. Voice profile controls tune the ECI parameter set.
- Direct Boot is supported by the TTS service declaration and device-protected
  storage. See `AndroidManifest.xml` and `src/com/xw/vvtts/services/VvTtsService.kt`.
- The update checker uses GitHub Releases and ABI-specific APK names. Exact
  version metadata and signing requirements are in [`RELEASES.md`](../RELEASES.md).

When these capabilities change, update the registry and its native parity checks
first, then keep user-facing documentation and release notes in sync.
