#!/usr/bin/env python3
"""Send a Firebase Cloud Messaging (FCM v1) push for an Eloquence TTS release.

Reimplementation of the reference approach used in MorpheApp/morphe-manager
(dced36b) — same capability, written independently for this project.

A release tag is published by CI as a single FCM data message to the
`eloquence_updates` topic (or `eloquence_updates_dev` when the tag is a
pre-release/beta build). Subscribed clients post a system notification from
the payload so every release surfaces exactly one update alert with the
version.

Environment (all required):
  FCM_PROJECT_ID           Firebase project number/id (Firebase Console).
  FCM_SERVICE_ACCOUNT_JSON the Service Account JSON as a single-line string.

Optional:
  RELEASE_TAG   current git tag (e.g. v1.2.3). Version = tag with a leading "v"
                stripped. When absent, a manual/test notification is sent with
                the version taken from FCM_VERSION instead.
  FCM_VERSION   version string for manual/test dispatches (ignored when a
                RELEASE_TAG is present).
  FCM_TOPIC     explicit destination topic; overrides branch/prerelease routing
                (used by the manual test workflow).
  IS_PRERELEASE "true" routes to the *_dev topic (otherwise stable topic).
"""

import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding as rsa_padding
except ImportError:  # pragma: no cover - exercised only on a runner without deps
    print("ERROR: 'cryptography' is required. Run: pip install cryptography",
          file=sys.stderr)
    sys.exit(1)

# Stability: warn on anything we could not read rather than silently skipping.
_WARN_TPL = "WARN: {0} is not set"

STABLE_TOPIC = "eloquence_updates"
DEV_TOPIC = "eloquence_updates_dev"


def _get_var(name, required=True):
    value = os.environ.get(name, "").strip()
    if required and not value:
        print(f"ERROR: {name} is required", file=sys.stderr)
        sys.exit(1)
    if not value:
        print(_WARN_TPL.format(name), file=sys.stderr)
    return value


def _to_urlsafe_b64(raw: bytes) -> str:
    """URL-safe base64 without padding, as required by JWT segments."""
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _load_service_account(raw_json: str) -> dict:
    try:
        sa = json.loads(raw_json)
    except json.JSONDecodeError as exc:
        print(f"ERROR: FCM_SERVICE_ACCOUNT_JSON is not valid JSON: {exc}", file=sys.stderr)
        sys.exit(1)
    for key in ("client_email", "private_key", "token_uri"):
        if key not in sa:
            print(f"ERROR: service account is missing '{key}'", file=sys.stderr)
            sys.exit(1)
    return sa


def _sign_assertion(sa: dict) -> str:
    header = _to_urlsafe_b64(json.dumps({"alg": "RS256", "typ": "JWT"}).encode("utf-8"))
    now = int(time.time())
    claims = {
        "iss": sa["client_email"],
        "sub": sa["client_email"],
        "aud": sa["token_uri"],
        "iat": now,
        "exp": now + 3600,
        "scope": "https://www.googleapis.com/auth/firebase.messaging",
    }
    payload = _to_urlsafe_b64(json.dumps(claims).encode("utf-8"))
    signing_input = f"{header}.{payload}".encode("utf-8")
    try:
        private = serialization.load_pem_private_key(
            sa["private_key"].encode("utf-8"), password=None)
        signature = private.sign(signing_input, rsa_padding.PKCS1v15(), hashes.SHA256())
    except Exception as exc:  # malformed PEM / wrong key material
        print(f"ERROR: failed to read/sign the service-account private key: {exc}",
              file=sys.stderr)
        sys.exit(1)
    return f"{header}.{payload}.{_to_urlsafe_b64(signature)}"


def _fetch_access_token(sa: dict, assertion: str) -> str:
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion,
    }).encode("utf-8")
    request = urllib.request.Request(sa["token_uri"], data=body)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            token = json.loads(response.read().decode("utf-8"))["access_token"]
    except Exception as exc:
        print(f"ERROR: could not exchange JWT for an OAuth2 token: {exc}", file=sys.stderr)
        sys.exit(1)
    return token


def _send(project_id: str, access_token: str, message: dict, topic: str) -> bool:
    base = os.environ.get("FCM_SEND_BASE_URL", "https://fcm.googleapis.com").rstrip("/")
    url = f"{base}/v1/projects/{project_id}/messages:send"
    body = json.dumps({"message": message}).encode("utf-8")
    request = urllib.request.Request(
        url, data=body,
        headers={"Authorization": f"Bearer {access_token}",
                 "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            status = response.status
            text = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        status = exc.code
        text = exc.read().decode("utf-8")
    except Exception as exc:  # DNS/TLS/timeout: report the failure, never a traceback
        print(f"ERROR: could not reach the FCM send endpoint: {exc}", file=sys.stderr)
        return False

    print(f"FCM response (HTTP {status}): {text}")
    if status != 200:
        print("ERROR: FCM push failed. Confirm the service account has the "
              "'Firebase Cloud Messaging Admin' role and the project id is "
              "correct.", file=sys.stderr)
        return False
    print(f"FCM notification sent to topic '{topic}'")
    return True


def main() -> int:
    project_id = _get_var("FCM_PROJECT_ID")
    sa = _load_service_account(_get_var("FCM_SERVICE_ACCOUNT_JSON"))

    tag = _get_var("RELEASE_TAG", required=False)
    explicit = _get_var("FCM_TOPIC", required=False)
    prerelease = os.environ.get("IS_PRERELEASE", "").strip().lower() == "true"
    fallback_version = _get_var("FCM_VERSION", required=False)

    if tag:
        # removeprefix, not lstrip: lstrip('v') eats every leading "v" ("vv1.0" -> "1.0").
        version = tag.removeprefix("v")
        topic = DEV_TOPIC if prerelease else STABLE_TOPIC
    else:
        version = fallback_version
        topic = DEV_TOPIC if prerelease else STABLE_TOPIC
    if explicit:
        topic = explicit
        # The client uses this flag to enforce prerelease opt-outs, so keep it
        # aligned with the actual destination when a caller overrides routing.
        prerelease = topic == DEV_TOPIC

    print(f"topic   : {topic}")
    print(f"version : {version or '(none)'}")

    assertion = _sign_assertion(sa)
    token = _fetch_access_token(sa, assertion)
    print("OAuth2 access token obtained")

    # "prerelease" is authoritative for the client: a receiver cannot tell which
    # topic a message arrived on (RemoteMessage.from is the sender, not the topic),
    # so the channel it was sent to has to travel in the payload.
    payload = {"type": "release", "prerelease": "true" if prerelease else "false"}
    if version:
        payload["version"] = version
    # high priority wakes the device from Doze via Play Services.
    message = {"topic": topic, "data": payload, "android": {"priority": "high"}}

    return 0 if _send(project_id, token, message, topic) else 1


if __name__ == "__main__":
    sys.exit(main())