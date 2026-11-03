from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parent.parent


class PrivateReleasePolicyTests(unittest.TestCase):
    def test_source_guard_rejects_plaintext_upload_and_release_cache(self):
        original=(ROOT/'.github/workflows/signed-release.yml').read_text()
        variants=[original.replace('cache-disabled: true','cache-disabled: false'),
                  original.replace('--no-build-cache ',''),
                  original.replace('private-delivery/forma-release.aesgcm.json\n            private-delivery/APK_IDENTITY.json','app/build/outputs/apk/release/app-release.apk\n            private-delivery/APK_IDENTITY.json')]
        for source,valid in [(original,True),*[(v,False) for v in variants]]:
            with tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp);(root/'scripts').mkdir();(root/'.github/workflows').mkdir(parents=True)
                shutil.copy(ROOT/'scripts/check-release-workflows.py',root/'scripts/check-release-workflows.py')
                (root/'.github/workflows/signed-release.yml').write_text(source)
                result=subprocess.run(['python3',str(root/'scripts/check-release-workflows.py')],capture_output=True,text=True)
                if valid:self.assertEqual(result.returncode,0,result.stderr)
                else:self.assertNotEqual(result.returncode,0)
