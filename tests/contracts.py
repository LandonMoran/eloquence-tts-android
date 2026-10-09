#!/usr/bin/env python3
"""Cross-boundary contracts plus failing-input tests for release/oracle tooling."""
import importlib.util
import json
import re
import tempfile
import unittest
import zipfile
import sys
from unittest.mock import patch
from types import SimpleNamespace
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def module(name):
    """Load a repository tool by filename without requiring an installed package."""
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / f'{name}.py')
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


class Contracts(unittest.TestCase):
    def test_no_tracked_signing_keys(self):
        """Reject tracked signing-key files so release credentials cannot enter the checkout."""
        import subprocess
        tracked = subprocess.check_output(
            ['git', 'ls-files', '-z', '--', '*.jks', '*.keystore', '*.p12', '*.pfx'], cwd=ROOT)
        self.assertFalse(tracked, 'Signing key material must not be tracked')

    def test_registry_native_parity(self):
        """Require the Kotlin voice catalog to exactly match the JNI dialect whitelist."""
        engine = (ROOT/'src/com/xw/vvtts/engine/EloquenceEngine.kt').read_text()
        registry = (ROOT/'src/com/xw/vvtts/engine/VoiceRegistry.kt').read_text()
        constants = dict(re.findall(r'const val (DIALECT_\w+)\s*=\s*(0x[0-9a-fA-F]+)', engine))
        kotlin = {int(constants[key],16) for key in re.findall(r'EloquenceEngine\.(DIALECT_\w+)',registry)}
        native = (ROOT/'jni/vvtts_core.c').read_text().split('static int vv_dialect_shipped')[1].split('JNIEXPORT jlong')[0]
        self.assertEqual(kotlin, {int(v,16) for v in re.findall(r'case (0x[0-9a-fA-F]+):', native)})
        self.assertEqual(len(kotlin),14)
        self.assertNotIn('buildEloquenceConfig',engine)

    def test_release_signing_isolated(self):
        """Require production signing secrets to stay in the protected release workflow."""
        for file in (ROOT/'.github/workflows').glob('*.yml'):
            text=file.read_text()
            if file.name=='release.yml':
                self.assertIn('environment: release',text)
                self.assertIn("tags: ['v*']",text)
                self.assertIn('${KS_PASS:?release keystore password required}',text)
            else:
                self.assertNotRegex(text,r'secrets\.(KEYSTORE_B64|KS_PASS|KEY_PASS)')
            self.assertNotIn('eloquence-ci',text)

    def test_release_publication_helper(self):
        """Verify release versions, asset selection, lookup failures, and draft/upload ordering."""
        with patch.dict(sys.modules, {'audit_apk_abis': module('audit_apk_abis')}):
            publisher = module('publish_release')
        # Helper contract: create/update/error/version/asset behavior. CI
        # integration of this helper is asserted by the workflow test below.
        self.assertEqual(set(publisher.ASSETS), {'vvtts-arm64-v8a.apk', 'vvtts-armeabi-v7a.apk', 'vvtts-universal.apk'})
        badging = "package: name='com.xw.vvtts' versionCode='2000000003' versionName='1.0.2'"
        self.assertEqual(publisher.package_version(badging), (2000000003, '1.0.2'))
        self.assertEqual(publisher.package_version(badging+" versionCodeMajor='1'")[0], (1 << 32)+2000000003)
        with self.assertRaises(ValueError):
            publisher.package_version(badging.replace('com.xw.vvtts','other.package'))
        calls = []
        def command(*args):
            """Record publication commands and inspect the edited release notes without contacting GitHub."""
            calls.append(args)
            if args[0]=='aapt': return badging
            if args[:3]==('gh','release','edit'):
                notes=Path(args[args.index('--notes-file')+1]).read_text()
                self.assertIn('Existing notes', notes)
                self.assertEqual(notes.count('android-version-code:'), 1)
                self.assertIn('android-version-code: 2000000003', notes)
            return ''
        with patch.object(publisher, 'audit'), patch.object(publisher, 'run', side_effect=command), patch.object(publisher.subprocess, 'run') as lookup:
            lookup.return_value=SimpleNamespace(returncode=0,stdout=json.dumps({'body':'Existing notes\n<!-- android-version-code: 1 -->','isDraft':False}))
            publisher.publish('v1.0.2','signed','aapt')
            upload=next(c for c in calls if c[:3]==('gh','release','upload'))
            self.assertEqual(upload[4:-1],tuple('signed/'+name for name in publisher.ASSETS))
            self.assertEqual(upload[-1],'--clobber')
            self.assertEqual(calls[-1][-1],'--draft=false')
            calls.clear()
            with self.assertRaises(ValueError): publisher.publish('v1.0.3','signed','aapt')
            self.assertFalse(any(c[0]=='gh' for c in calls))
            lookup.return_value=SimpleNamespace(returncode=1,stderr='authentication failed')
            with self.assertRaises(RuntimeError): publisher.publish('v1.0.2','signed','aapt')
            self.assertFalse(any(c[0]=='gh' for c in calls))
            lookup.return_value=SimpleNamespace(returncode=1,stderr='release not found')
            calls.clear()
            # A new release is created as draft before upload and published last.
            def fresh_command(*args):
                """Record commands for a new release while supplying fixture APK version metadata."""
                calls.append(args)
                return badging if args[0]=='aapt' else ''
            with patch.object(publisher,'run',side_effect=fresh_command):
                publisher.publish('v1.0.2','signed','aapt')
            create=next(i for i,c in enumerate(calls) if c[:3]==('gh','release','create'))
            upload=next(i for i,c in enumerate(calls) if c[:3]==('gh','release','upload'))
            self.assertIn('--draft',calls[create]); self.assertIn('--verify-tag',calls[create])
            self.assertLess(create,upload)

    def test_release_publication_integration(self):
        """Verify that publication depends on signing and has narrowly scoped write permission."""
        workflow=(ROOT/'.github/workflows/release.yml').read_text()
        self.assertNotIn('eloquence-ci',workflow)
        idx=workflow.index('  publish:\n')
        publish=workflow[idx:]
        sign=workflow[:idx]
        # The publication job depends on signing and downloads its artifact.
        self.assertIn('needs: sign',publish)
        self.assertIn('name: release-signed-apks',publish)
        self.assertIn('path: signed',publish)
        # It runs the helper with the tag, the signed directory and the aapt path.
        self.assertIn('python3 tools/publish_release.py "$RELEASE_TAG" signed "$ANDROID_HOME/build-tools/35.0.0/aapt"',publish)
        self.assertIn('GH_TOKEN: ${{ github.token }}',publish)
        self.assertIn('RELEASE_TAG: ${{ github.ref_name }}',publish)
        # Write permission is granted to the publication job only.
        self.assertEqual(workflow.count('contents: write'),1)
        self.assertIn('contents: write',publish)
        self.assertNotIn('contents: write',sign)
        # Signing stays behind the protected release environment; production
        # signing secrets are referenced from the release workflow only. The
        # names in build.yml are an ephemeral CI key (openssl rand), not
        # production identity.
        self.assertIn('environment: release',sign)
        for secret in ('KEYSTORE_B64','KS_PASS','KEY_PASS'):
            self.assertNotIn(secret,publish)
            self.assertNotIn('${{ secrets.%s }}' % secret,(ROOT/'.github/workflows/build.yml').read_text())
        # The helper, with its own three-asset audit contract, is the sole
        # upload path: no APK name is hard-coded in the publication job.
        self.assertFalse(re.findall(r'vvtts-[a-z0-9-]*\.apk',publish))
        self.assertEqual(workflow.count('release-signed-apks'),2)  # sign upload + publish download

    def test_apk_exact_abis(self):
        """Accept exact ABI contents for each APK asset and reject additional native libraries."""
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
        """Reject duplicate native ZIP entries even when their names match the expected ABI."""
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
        """Require exactly the requested nonempty oracle pieces and reject invalid selections."""
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
        """Require pinned archive checksums to be verified before extraction."""
        workflow=(ROOT/'.github/workflows/oracle-decompile.yml').read_text()
        self.assertIn('b13a7c4871bb689c5c04fee88ca8905895a7b4b3da53f59d521d691cdb137f72',workflow)
        self.assertIn('12fd966431903b8e15c36e5007f19343475be7d8f2a55f082e7a929eeabc937e',workflow)
        self.assertLess(workflow.index('"$FACTORY_SHA256" | sha256sum -c -'),workflow.index('unzip -oq factory.zip'))
        self.assertLess(workflow.index('"$JADX_SHA256" | sha256sum -c -'),workflow.index('unzip -q /tmp/jadx.zip'))

    def test_signature_permission_matches(self):
        """Match the install-status permission and reject the nonexistent TTS binding permission."""
        manifest=(ROOT/'AndroidManifest.xml').read_text()
        installer=(ROOT/'src/com/xw/vvtts/update/ElqUpdateInstaller.kt').read_text()
        permission=re.search(r'INSTALL_STATUS_PERMISSION = "([^"]+)"',installer)[1]
        self.assertIn(f'android:name="{permission}"',manifest)
        self.assertIn('android:protectionLevel="signature"',manifest)
        self.assertNotIn('android.permission.BIND_TEXT_TO_SPEECH',manifest)

    def test_synthesis_timing_chain_invariant(self):
        """Guard the stall-fix constants so a regression cannot re-land the two failure
        signatures: a per-call hang watchdog that does not outlast the retire window
        (stacked-retire ~24s dead zone), a service stall guard that sits outside the
        benign window, or a native cancelled-drain bound large enough to wedge a fresh
        generation for ~20s."""

        engine = (ROOT/'src/com/xw/vvtts/engine/EloquenceEngine.kt').read_text()
        service = (ROOT/'src/com/xw/vvtts/services/VvTtsService.kt').read_text()
        core = (ROOT/'jni/vvtts_core.c').read_text()
        hang_s = int(re.search(r'val HANG_TIMEOUT_S = (\d+)L',engine).group(1))
        grace_ms = int(re.search(r'val ZOMBIE_GRACE_MS = (\d+)L',engine).group(1))
        stall_ms = int(re.search(r'const val UTT_STALL_MS = (\d+)L',service).group(1))
        stop_drain_iters = int(re.search(r'#define VV_STOP_DRAIN_MAX_ITERS (\d+)',core).group(1))

        # A watchdog fire must not be able to stack a retire window onto a legitimate chunk.
        self.assertGreater(hang_s * 1000, grace_ms)

        # The stall guard must fire before the per-call watchdog (a cascading null run errors
        # out promptly), and after the ~3s pacing lead (normal pacing is never a false stall).
        self.assertLess(stall_ms, hang_s * 1000)
        self.assertGreater(stall_ms, 3000)

        # The cancelled-drain bound (0.5ms per iteration) must stay well under ~2s, else a stale
        # cancelled generation delays a fresh one ~20s again: the 24s residual = 20s drain + reopen.
        self.assertLess(stop_drain_iters * 0.5, 2000.0)
        self.assertGreater(stop_drain_iters, 0)


if __name__=='__main__':unittest.main()
