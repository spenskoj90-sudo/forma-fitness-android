import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_release_guard import PIN, REPORT, BADGING

ROOT=Path(__file__).resolve().parent.parent


def step_commands():
    """Extract literal bash blocks from this workflow; no YAML dependency in CI."""
    commands={}
    name=None
    collecting=False
    for line in (ROOT/'.github/workflows/signed-release.yml').read_text().splitlines():
        if line.startswith('      - name: '):
            name=line.removeprefix('      - name: ')
            commands[name]=''
            collecting=False
        elif line=='        run: |':
            collecting=True
        elif collecting and (line.startswith('          ') or not line):
            commands[name]+=line[10:]+'\n'
        else:
            collecting=False
    return commands


class ReleaseWorkflowIntegrationTests(unittest.TestCase):
    def test_actual_preflight_step_rejects_overflow_before_key_use(self):
        preflight=step_commands()['Require an explicit, monotonic version']
        env=os.environ.copy()
        env.update(VERSION_CODE='18446744073709551619',VERSION_NAME='0.3.0',FORMA_LAST_SIGNED_VERSION_CODE='0',FIRST_RELEASE_APPROVED='true',FORMA_EXPECTED_SIGNER_SHA256=PIN)
        result=subprocess.run(['bash','-euo','pipefail','-c',preflight],cwd=ROOT,env=env,capture_output=True,text=True)
        self.assertNotEqual(result.returncode,0)
        self.assertIn('FORMA release guard FAIL',result.stderr)

    def test_incomplete_delivery_blocks_the_actual_workflow_before_secrets(self):
        commands=step_commands();names=list(commands)
        stop='Block key use until private delivery and durable ledger are reviewed'
        self.assertLess(names.index(stop),names.index('Restore only the private FORMA signing key'))
        self.assertNotEqual(subprocess.run(['bash','-c',commands[stop]],capture_output=True).returncode,0)

    def test_cli_verification_emits_only_metadata_and_rejects_v2_false(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);apk=root/'test.apk';apk.write_bytes(b'abc')
            aapt=root/'aapt';signer=root/'apksigner'
            # Controlled external-tool boundary: real APK cryptography is an Android CI gate.
            aapt.write_text('#!/usr/bin/env python3\nprint('+repr(BADGING)+')\n');aapt.chmod(0o700)
            env=os.environ.copy();env.update(VERSION_CODE='3',VERSION_NAME='0.3.0',FORMA_EXPECTED_SIGNER_SHA256=PIN)
            for valid in (True,False):
                report=REPORT if valid else REPORT.replace(': true',': false')
                signer.write_text('#!/usr/bin/env python3\nprint('+repr(report)+')\n');signer.chmod(0o700)
                result=subprocess.run(['python3',str(ROOT/'scripts/release_guard.py'),'apk','--apk',str(apk),'--aapt',str(aapt),'--apksigner',str(signer)],env=env,capture_output=True,text=True)
                self.assertNotIn('PRIVATE_TEST_ONLY',result.stdout+result.stderr)
                if valid:
                    self.assertEqual(result.returncode,0,result.stderr)
                    self.assertEqual(json.loads(result.stdout)['signerSha256'],PIN)
                else:
                    self.assertNotEqual(result.returncode,0)
                    self.assertEqual(result.stdout,'')


if __name__=='__main__':unittest.main()
