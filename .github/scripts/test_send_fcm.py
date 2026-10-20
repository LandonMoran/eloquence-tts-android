"""Local functional test for send_fcm.py.

Generates a throwaway RSA key + service-account JSON (real PEM, real crypto),
starts a tiny HTTP server that impersonates both the OAuth token endpoint and
the FCM v1 send endpoint, then runs send_fcm.py against it and asserts the
request flow is correct. Verifies the script works without any network.
"""
import json
import http.server
import socketserver
import os
import subprocess
import sys
import threading
import tempfile
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization

# Resolve next to this file: an absolute path would only work on one machine.
SCRIPT = str(Path(__file__).resolve().parent / "send_fcm.py")
# Get an ephemeral port so repeated harness runs never collide.
_tmp_sock = socketserver.TCPServer(("127.0.0.1", 0), None)
PORT = _tmp_sock.server_address[1]
_tmp_sock.server_close()


class Handler(http.server.BaseHTTPRequestHandler):
    captured = []

    def _read(self):
        n = int(self.headers.get("Content-Length", 0))
        return self.rfile.read(n) if n else b""

    def _reply(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        raw = self._read()
        ctype = self.headers.get("Content-Type", "")
        if "application/x-www-form-urlencoded" in ctype:
            import urllib.parse
            body = urllib.parse.parse_qs(raw.decode())
            body = {k: v[0] for k, v in body.items()}
        else:
            body = json.loads(raw)
        rec = {"path": self.path, "auth": self.headers.get("Authorization"), "body": body}
        Handler.captured.append(rec)
        if self.path.endswith("/token"):
            self._reply(200, {"access_token": "FAKE_TOKEN_123"})
        elif self.path.endswith("/messages:send"):
            # Mirror back what was sent so the test can assert exactness.
            self._reply(200, {"name": "projects/fake/messages/FAKE_MSG", "request": rec["body"]})
        else:
            self._reply(404, {"error": "not found"})

    def log_message(self, fmt, *args):
        pass


def make_sa(tmp):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.TraditionalOpenSSL,
        serialization.NoEncryption(),
    ).decode()
    sa = {
        "type": "service_account",
        "project_id": "fake-project",
        "private_key_id": "k1",
        "private_key": pem,
        "client_email": "fcm@fake-project.iam.gserviceaccount.com",
        "client_id": "123",
        "auth_uri": f"http://127.0.0.1:{PORT}/auth",
        "token_uri": f"http://127.0.0.1:{PORT}/token",
    }
    path = os.path.join(tmp, "sa.json")
    with open(path, "w") as fh:
        fh.write(json.dumps(sa))
    return path


class ReusableTCPServer(socketserver.TCPServer):
    allow_reuse_address = True


def main():
    tmp = tempfile.mkdtemp()
    sa_path = make_sa(tmp)

    server = ReusableTCPServer(("127.0.0.1", PORT), Handler)
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()

    env = os.environ.copy()
    env["FCM_PROJECT_ID"] = "fake-project"
    env["FCM_SEND_BASE_URL"] = f"http://127.0.0.1:{PORT}"
    with open(sa_path) as fh:
        env["FCM_SERVICE_ACCOUNT_JSON"] = fh.read()
    env["RELEASE_TAG"] = "v1.0.10"
    env["FCM_VERSION"] = "ignored-because-tag"

    proc = subprocess.run([sys.executable, SCRIPT], env=env, capture_output=True, text=True)
    out = proc.stdout + proc.stderr
    print(f"exit={proc.returncode}\n{out}")

    assert proc.returncode == 0, "expected success"
    assert "version : 1.0.10" in out, "version should strip leading v from tag"
    assert "topic   : eloquence_updates" in out, "release on stable topic expected"

    # token request
    tokens = [c for c in Handler.captured if c["path"].endswith("/token")]
    assert len(tokens) == 1, "exactly one token exchange"
    assert "grant_type" in tokens[0]["body"], "JWT bearer grant"
    assert "assertion" in tokens[0]["body"], "assertion must carry the JWT"
    # decode the JWT header to be sure it's a valid structure
    jwt_parts = tokens[0]["body"]["assertion"].split(".")
    assert len(jwt_parts) == 3, "JWT has three segments"

    # send request
    sends = [c for c in Handler.captured if c["path"].endswith("/messages:send")]
    assert len(sends) == 1, "exactly one FCM send"
    assert sends[0]["auth"] == "Bearer FAKE_TOKEN_123", "uses the OAuth token"
    msg = sends[0]["body"]["message"]
    assert msg["topic"] == "eloquence_updates"
    assert msg["data"] == {"type": "release", "prerelease": "false", "version": "1.0.10"}, msg["data"]
    assert msg["android"] == {"priority": "high"}
    assert "projects/fake-project/messages:send" in sends[0]["path"]

    # re-run with IS_PRERELEASE=true -> dev topic
    Handler.captured.clear()
    env2 = dict(env)
    env2["IS_PRERELEASE"] = "true"
    env2.pop("RELEASE_TAG")
    env2["FCM_VERSION"] = "9.9.9-beta"
    pr = subprocess.run([sys.executable, SCRIPT], env=env2, capture_output=True, text=True)
    assert pr.returncode == 0, pr.stdout + pr.stderr
    assert "topic   : eloquence_updates_dev" in pr.stdout
    assert "version : 9.9.9-beta" in pr.stdout
    s2 = [c for c in Handler.captured if c["path"].endswith("/messages:send")]
    # The client cannot infer the topic, so the payload must say it was a prerelease.
    assert s2[0]["body"]["message"]["data"] == {
        "type": "release", "prerelease": "true", "version": "9.9.9-beta"
    }, s2[0]["body"]["message"]["data"]

    # missing required env -> nonzero
    bad = subprocess.run([sys.executable, SCRIPT], env={}, capture_output=True, text=True)
    assert bad.returncode != 0, "must fail without FCM_PROJECT_ID"

    print("\nALL FCM FUNCTIONAL TESTS PASSED")
    server.shutdown()


if __name__ == "__main__":
    main()