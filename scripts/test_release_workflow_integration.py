import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
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

    def test_missing_delivery_configuration_blocks_the_workflow_before_secrets(self):
        commands=step_commands();names=list(commands)
        stop='Authorize exact reserved run and delivery recipient before secrets'
        self.assertLess(names.index(stop),names.index('Restore only the private FORMA signing key'))
        env=os.environ.copy()
        env.pop('FORMA_DELIVERY_RECIPIENT_PEM',None)
        env.pop('FORMA_RELEASE_LEDGER_COMMIT',None)
        with tempfile.TemporaryDirectory() as tmp:
            env['RUNNER_TEMP']=tmp
            self.assertNotEqual(subprocess.run(['bash','-euo','pipefail','-c',commands[stop]],cwd=ROOT,env=env,capture_output=True).returncode,0)

    def test_cli_authorization_checks_committed_provider_state_and_denies_retry(self):
        import release_ledger
        from unittest.mock import patch
        from test_release_ledger import provider_fixture
        generated=subprocess.run(['node','--input-type=module','-e',"import{generateKeyPairSync,createHash}from'node:crypto';const p=generateKeyPairSync('rsa',{modulusLength:3072}).publicKey;console.log(JSON.stringify({pem:p.export({type:'spki',format:'pem'}),sha:createHash('sha256').update(p.export({type:'spki',format:'der'})).digest('hex')}))"],capture_output=True,text=True,check=True)
        recipient=json.loads(generated.stdout)
        now=int(time.time());intent={'sourceSha':'b'*40,'runId':'12345678901','runAttempt':1,'versionCode':3,'versionName':'0.3.0','recipientSha256':recipient['sha'],'expiresAt':now+3600}
        initial=release_ledger.new_ledger();state=release_ledger.reserve(initial,intent,release_ledger.digest(initial),now)
        commit,genesis,_,provider=provider_fixture(state)
        env=os.environ.copy()
        env.update(FORMA_RELEASE_LEDGER_COMMIT=commit,FORMA_DELIVERY_RECIPIENT_PEM=recipient['pem'],FORMA_DELIVERY_RECIPIENT_SHA256=recipient['sha'],FORMA_EXPECTED_SIGNER_SHA256=PIN,FORMA_LAST_SIGNED_VERSION_CODE='0',FIRST_RELEASE_APPROVED='true',VERSION_CODE='3',VERSION_NAME='0.3.0',GITHUB_SHA='b'*40,GITHUB_RUN_ID=intent['runId'],GITHUB_RUN_ATTEMPT='1')
        with tempfile.TemporaryDirectory() as tmp:
            env['RUNNER_TEMP']=tmp
            args=['release_ledger.py','authorize','--output',str(Path(tmp)/'forma-binding.json')]
            with patch.dict(os.environ,env,clear=True),patch('sys.argv',args),patch('release_ledger.fetch_json',side_effect=provider):
                release_ledger.main()
            binding=json.loads((Path(tmp)/'forma-binding.json').read_text())
            self.assertEqual(binding['recipientSha256'],recipient['sha'])
            self.assertEqual(binding['signerSha256'],PIN)
            self.assertEqual(binding['ledgerCommit'],commit)
            env['GITHUB_RUN_ATTEMPT']='2'
            with patch.dict(os.environ,env,clear=True),patch('sys.argv',args),patch('release_ledger.fetch_json',side_effect=provider),self.assertRaises(SystemExit):
                release_ledger.main()

    def test_cli_verification_emits_only_metadata_and_rejects_v2_false(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);apk=root/'test.apk';apk.write_bytes(b'abc')
            aapt=root/'aapt';signer=root/'apksigner'
            # Controlled tool boundary; real APK cryptography remains an Android runtime gate.
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
