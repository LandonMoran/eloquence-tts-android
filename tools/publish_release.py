#!/usr/bin/env python3
"""Publish the protected signing job's APKs and their actual Android version code."""
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

from audit_apk_abis import audit

ASSETS = ('vvtts-arm64-v8a.apk', 'vvtts-armeabi-v7a.apk', 'vvtts-universal.apk')


def run(*args):
    return subprocess.check_output(args, text=True)


def package_version(badging):
    package = next((line for line in badging.splitlines() if line.startswith('package: ')), '')
    fields = dict(re.findall(r"(\w+)='([^']*)'", package))
    if fields.get('name') != 'com.xw.vvtts':
        raise ValueError('Unexpected APK package')
    code = int(fields['versionCode'])
    major = int(fields.get('versionCodeMajor', '0'))
    if not 0 <= code <= 0x7fffffff or not 0 <= major <= 0x7fffffff:
        raise ValueError('Invalid Android version code')
    return (major << 32) | code, fields['versionName']


def publish(tag, directory, aapt):
    match = re.fullmatch(r'v(\d{1,3})\.(\d{1,3})(?:\.(\d{1,3}))?', tag)
    if not match:
        raise ValueError('Release tag must be vMAJOR.MINOR[.PATCH]')
    tag_version = tuple(int(v or 0) for v in match.groups())
    apks = [Path(directory) / name for name in ASSETS]
    versions = set()
    for apk in apks:
        audit(apk)
        versions.add(package_version(run(aapt, 'dump', 'badging', str(apk))))
    if len(versions) != 1:
        raise ValueError('Release APK versions disagree')
    code, name = versions.pop()
    name_match = re.fullmatch(r'(\d{1,3})\.(\d{1,3})(?:\.(\d{1,3}))?', name)
    if not name_match or tuple(int(v or 0) for v in name_match.groups()) != tag_version:
        raise ValueError('Release tag does not match APK versionName')
    # A failed lookup is not proof of absence: distinguish 404 from auth/network errors.
    result = subprocess.run(['gh', 'release', 'view', tag, '--json', 'body,isDraft'], capture_output=True, text=True)
    if result.returncode:
        if 'release not found' not in result.stderr.lower():
            raise RuntimeError(result.stderr)
        run('gh', 'release', 'create', tag, '--verify-tag', '--draft', '--title', tag, '--notes', '')
        notes = ''
    else:
        notes = json.loads(result.stdout)['body'] or ''
    notes = re.sub(r'<!-- android-version-code: .*? -->', '', notes).rstrip()
    notes += f'\n\n<!-- android-version-code: {code} -->\n'
    # New releases stay draft until all uploads succeed. Reruns replace the same assets.
    run('gh', 'release', 'upload', tag, *(str(apk) for apk in apks), '--clobber')
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / 'notes.md'
        path.write_text(notes)
        run('gh', 'release', 'edit', tag, '--notes-file', str(path), '--draft=false')


if __name__ == '__main__':
    publish(*sys.argv[1:])
