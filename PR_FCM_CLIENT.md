# Receive release notifications via Firebase Cloud Messaging

Client half of the release-notification pipeline. The server push lives in the migration's `release.yml` (`.github/scripts/send_fcm.py`), which fires **one data message per release** onto `eloquence_updates` (stable) or `eloquence_updates_dev` (pre-release, only when the tag carries a `-`). This PR adds the client that receives it and surfaces the update dialog — independently written, faithful to MorpheApp/morphe-manager's capability (not the same code).

## What it does

- **`FcmUpdateReceiver`** (`FirebaseMessagingService`): on token refresh, subscribes to both `eloquence_updates` and `eloquence_updates_dev` so a device receives pushes for whichever channel applies. On a data push (`type=release`, `version=<tag>`), posts a single high-importance notification carrying the version.
- **Tap → dialog**: the notification's content intent opens `SettingsActivity` with `EXTRA_OPEN_UPDATE_DIALOG`, which routes into the exact `UpdateActions.showCheckDialog` a manual "Check for updates" uses. One notification per release, with the version.
- **POST_NOTIFICATIONS** (API 33+): permission added + one-time runtime request on launch so the push can actually surface on modern Android; harmless no-op on API 28–32.
- **Gradle**: `firebase-messaging` via the existing `firebase-bom:33.7.0` — no extra SDK init, no Application subclass.

## Why no dispatcher-safe crash

`Notification.Builder` requires a small icon resource, which the app currently has none of; uses the system `android.R.drawable.ic_dialog_info` (dependency-free) and guards `notify()` with a `SecurityException` → Toast fallback if the runtime permission was revoked.

## Note

Also depends on the FCM server secrets being added to the repo's Actions secrets (same pattern as the keystore): `FCM_PROJECT_ID` and `FCM_SERVICE_ACCOUNT_JSON` — required by the `release.yml` FCM step and the manual `test_fcm.yml` tester.

**Stacked on** `feat/gradle-crashlytics` (#354) — merge that first.