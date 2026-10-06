#!/usr/bin/env python3
"""Cross-boundary contracts plus failing-input tests for release/oracle tooling."""
import importlib.util
import json
import re
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / f'{name}.py')
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


class Contracts(unittest.TestCase):
    def test_registry_native_parity(self):
        engine = (ROOT/'src/com/xw/vvtts/engine/EloquenceEngine.kt').read_text()
        registry = (ROOT/'src/com/xw/vvtts/engine/VoiceRegistry.kt').read_text()
        constants = dict(re.findall(r'const val (DIALECT_\w+)\s*=\s*(0x[0-9a-fA-F]+)', engine))
        kotlin = {int(constants[key],16) for key in re.findall(r'EloquenceEngine\.(DIALECT_\w+)',registry)}
        native = (ROOT/'jni/vvtts_core.c').read_text().split('static int vv_dialect_shipped')[1].split('JNIEXPORT jlong')[0]
        self.assertEqual(kotlin, {int(v,16) for v in re.findall(r'case (0x[0-9a-fA-F]+):', native)})
        self.assertEqual(len(kotlin),14)
        self.assertNotIn('buildEloquenceConfig',engine)

    def test_release_signing_isolated(self):
        for file in (ROOT/'.github/workflows').glob('*.yml'):
            text=file.read_text()
            if file.name=='release.yml':
                self.assertIn('environment: release',text)
                self.assertIn("tags: ['v*']",text)
                self.assertIn('${KS_PASS:?release keystore password required}',text)
            else:
                self.assertNotRegex(text,r'secrets\.(KEYSTORE_B64|KS_PASS|KEY_PASS)')
            self.assertNotIn('eloquence-ci',text)

    def test_apk_exact_abis(self):
        audit=module('audit_apk_abis')
        with tempfile.TemporaryDirectory() as tmp:
            for name,abis in audit.ASSETS.items():
                path=Path(tmp)/name
                with zipfile.ZipFile(path,'w') as jar:
                    for abi in abis: jar.writestr(f'lib/{abi}/libvvtts_core.so',b'ELF fixture')
                audit.audit(path)
                with zipfile.ZipFile(path,'a') as jar: jar.writestr('lib/x86/libwrong.so',b'wrong')
                with self.assertRaises(ValueError):audit.audit(path)

    def test_apk_duplicate_native_entry(self):
        audit=module('audit_apk_abis')
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'vvtts-arm64-v8a.apk'
            with zipfile.ZipFile(path,'w') as apk:
                apk.writestr('lib/arm64-v8a/libvvtts_core.so',b'first')
                import warnings
                with warnings.catch_warnings():
                    warnings.simplefilter('ignore',UserWarning)
                    apk.writestr('lib/arm64-v8a/libvvtts_core.so',b'second')
            with self.assertRaisesRegex(ValueError,'duplicate'): audit.audit(path)

    def test_oracle_all_pieces_and_missing_duplicate_empty(self):
        assemble=module('assemble_oracle').assemble
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            for piece in range(1,9):
                path=root/f'{piece}/oracle-piece-zh-cn-p{piece}/output.tsv'
                path.parent.mkdir(parents=True)
                path.write_text(f'pcm\t{piece}\tfixture{piece}\n')
            result=assemble(root,'zh-cn',list(range(1,9)),root/'assembled.tsv')
            self.assertEqual(len(result['pieces']),8)
            self.assertEqual(len((root/'assembled.tsv').read_text().splitlines()),8)
            with self.assertRaises(ValueError):assemble(root,'ja',[1,2],root/'bad.tsv')
            with self.assertRaises(ValueError):assemble(root,'zh-cn',[1,1],root/'bad.tsv')
            (root/'8/oracle-piece-zh-cn-p8/output.tsv').unlink()
            with self.assertRaises(ValueError):assemble(root,'zh-cn',list(range(1,9)),root/'bad.tsv')
            (root/'1/oracle-piece-zh-cn-p1/output.tsv').write_text('pcm\t0\n')
            with self.assertRaises(ValueError):assemble(root,'zh-cn',[1],root/'bad.tsv')

    def test_oracle_checksums_before_extract(self):
        workflow=(ROOT/'.github/workflows/oracle-decompile.yml').read_text()
        self.assertIn('b13a7c4871bb689c5c04fee88ca8905895a7b4b3da53f59d521d691cdb137f72',workflow)
        self.assertIn('12fd966431903b8e15c36e5007f19343475be7d8f2a55f082e7a929eeabc937e',workflow)
        self.assertLess(workflow.index('"$FACTORY_SHA256" | sha256sum -c -'),workflow.index('unzip -oq factory.zip'))
        self.assertLess(workflow.index('"$JADX_SHA256" | sha256sum -c -'),workflow.index('unzip -q /tmp/jadx.zip'))

    def test_signature_permission_matches(self):
        manifest=(ROOT/'AndroidManifest.xml').read_text()
        installer=(ROOT/'src/com/xw/vvtts/update/ElqUpdateInstaller.kt').read_text()
        permission=re.search(r'INSTALL_STATUS_PERMISSION = "([^"]+)"',installer)[1]
        self.assertIn(f'android:name="{permission}"',manifest)
        self.assertIn('android:protectionLevel="signature"',manifest)
        self.assertNotIn('android.permission.BIND_TEXT_TO_SPEECH',manifest)


if __name__=='__main__':unittest.main()
