# Eloquence TTS for Android 2.0.6

This patch keeps the saved English accent stable while auto-detection is
enabled. Per-request region tags (for example an `eng` request tagged `GBR`
from a page marked `en-GB`, such as the reported BBC article) no longer switch
the default voice to UK English when the saved accent is US English, and vice
versa.

## What changed

- While auto-detection is enabled, the default English voice follows the saved
  English accent instead of the request's region tag.
- Explicit in-app voices still take precedence over automatic accent selection.
- Region-based English defaults are unchanged when detection is disabled.
- Added host regressions covering the saved accent, transient synthesis state,
  and explicit voice precedence.
- Increment the Android version code to 18.
