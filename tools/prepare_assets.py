#!/usr/bin/env python3
"""Content-aware generation; --verify rebuilds and compares committed artifacts."""
import argparse
import hashlib
import os
import subprocess
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STAMP = 'META-INF/elqm-generator.sha256'


def digest(paths, options):
    h = hashlib.sha256(options.encode())
    for path in sorted(paths):
        h.update(str(path.relative_to(ROOT)).encode() + b'\0')
        h.update(path.read_bytes())
    return h.hexdigest()


def canonical_jar(source, output, fingerprint):
    with zipfile.ZipFile(source) as jar, zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as result:
        entries = {n: jar.read(n) for n in jar.namelist() if n != STAMP}
        entries[STAMP] = (fingerprint + '\n').encode()
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            result.writestr(info, data)


def publish(candidate, target, verify):
    same = target.exists() and candidate.read_bytes() == target.read_bytes()
    if verify:
        if not same:
            raise SystemExit(f'Stale generated artifact: {target.relative_to(ROOT)}; run tools/prepare_assets.py')
    elif not same:
        os.replace(candidate, target)


def main(verify=False):
    os.chdir(ROOT)
    model = ROOT / 'language-models/models.elqm'
    manifest = model.with_suffix('.elqm.inputs.sha256')
    generator = ROOT / 'tools/prepare_assets.py'
    fingerprint = digest([generator, ROOT / 'tools/model_pack.py', *ROOT.glob('language-models/**/*.json')], 'pack --prune; min_count=2')
    with tempfile.TemporaryDirectory(prefix='.assets-', dir=ROOT) as scratch:
        tmp = Path(scratch)
        if verify or not model.exists() or not manifest.exists() or manifest.read_text().strip() != fingerprint:
            subprocess.run(['python3', 'tools/model_pack.py', 'pack', '--prune', 'language-models', str(tmp / 'models.elqm')], check=True)
            publish(tmp / 'models.elqm', model, verify)
            stamp = tmp / 'model-inputs.sha256'
            stamp.write_text(fingerprint + '\n')
            publish(stamp, manifest, verify)
        jar = ROOT / 'libs/lingua-slim.jar'
        sources = [ROOT / 'tools/p3_golden/ElqmBridge.java', ROOT / 'tools/p3_golden/PatchLingua.java']
        asm = ROOT / 'tools/p3_golden/asm.jar'
        fingerprint = digest([generator, asm, *sources], 'javac --release 11; jar canonical v1')
        with zipfile.ZipFile(jar) as existing:
            previous = existing.read(STAMP).decode().strip() if STAMP in existing.namelist() else ''
        if verify or previous != fingerprint:
            classes = tmp / 'classes'
            classes.mkdir()
            subprocess.run(['javac', '--release', '11', '-cp', str(asm), '-d', str(classes), *map(str, sources)], check=True)
            subprocess.run(['java', '-cp', f'{classes}:{asm}', 'PatchLingua', str(jar), str(tmp / 'patched.jar'),
                            str(classes / 'com/github/pemistahl/lingua/internal/ElqmBridge.class')], check=True)
            canonical_jar(tmp / 'patched.jar', tmp / 'canonical.jar', fingerprint)
            publish(tmp / 'canonical.jar', jar, verify)
    print('PASS: generated models and Lingua bridge ' + ('match committed content' if verify else 'are current'))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--verify', action='store_true')
    main(parser.parse_args().verify)
