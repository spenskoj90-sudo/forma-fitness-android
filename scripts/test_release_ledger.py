import copy
import base64
import hashlib
import json
import unittest

try:
    import release_ledger as ledger
except ImportError:
    ledger=None

NOW=1800000000
PIN='a13104307fbd01a38a4e1fbdf7aafdf3f2e76be7cd18a1ac986a9b775d35cf7b'
INTENT={'sourceSha':'b'*40,'runId':'12345678901','runAttempt':1,'versionCode':3,'versionName':'0.3.0','recipientSha256':'c'*64,'expiresAt':NOW+3600}


def provider_fixture(state):
    commit='d'*40;genesis=ledger.LEDGER_GENESIS
    def content(value):
        raw=json.dumps(value,sort_keys=True,separators=(',',':')).encode()
        return {'type':'file','path':'release/ledger.json','encoding':'base64','size':len(raw),'content':base64.b64encode(raw).decode(),'sha':hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()}
    mapping={ledger.REF_URL:{'ref':'refs/heads/ops/forma-release-ledger','object':{'type':'commit','sha':commit}},
             ledger.API+'/contents/release/ledger.json?ref='+commit:content(state),
             ledger.API+'/contents/release/ledger.json?ref='+genesis:content(ledger.new_ledger()),
             ledger.API+'/compare/'+genesis+'...'+commit+'?per_page=1':{'status':'ahead','behind_by':0,'ahead_by':state['generation']},
             ledger.API+'/git/commits/'+commit:{'parents':[{'sha':genesis}]}}
    def get(url):
        if url not in mapping:raise ValueError('Unexpected test provider URL')
        return copy.deepcopy(mapping[url])
    return commit,genesis,mapping,get


class LedgerTests(unittest.TestCase):
    def setUp(self):self.assertIsNotNone(ledger,'ledger not implemented')

    def reserve(self,state=None,intent=None):
        state=ledger.new_ledger() if state is None else state
        return ledger.reserve(state,INTENT if intent is None else intent,ledger.digest(state),NOW)

    def authorize(self,state,**changes):
        values=dict(sourceSha='b'*40,runId='12345678901',runAttempt=1,versionCode=3,versionName='0.3.0',recipientSha256='c'*64)
        values.update(changes)
        return ledger.authorize(state,values,'0','true',PIN,NOW)

    def test_reserve_is_durable_and_does_not_mutate_input(self):
        original=ledger.new_ledger();before=copy.deepcopy(original)
        reserved=self.reserve(original)
        self.assertEqual(original,before)
        self.assertEqual(reserved['lastReservedVersionCode'],3)
        self.assertEqual(reserved['lastSignedVersionCode'],0)
        self.assertEqual(reserved['reservation']['state'],'reserved')
        self.assertEqual(reserved['parentDigest'],ledger.digest(original))

    def test_active_reservation_and_stale_revision_cannot_be_overwritten(self):
        state=self.reserve()
        with self.assertRaises(ValueError):self.reserve(state,{**INTENT,'versionCode':4})
        with self.assertRaises(ValueError):ledger.reserve(ledger.new_ledger(),INTENT,'d'*64,NOW)

    def test_authorization_binds_exact_run_attempt_source_version_and_recipient(self):
        state=self.reserve();self.assertEqual(self.authorize(state)['versionCode'],3)
        for change in [{'runId':'12345678902'},{'runAttempt':2},{'sourceSha':'d'*40},{'versionCode':4},{'versionName':'0.4.0'},{'recipientSha256':'d'*64}]:
            with self.subTest(change=change),self.assertRaises(ValueError):self.authorize(state,**change)

    def test_expiry_pin_baseline_and_first_use_fail_closed(self):
        state=self.reserve()
        for last,first,pin,now in [('1','false',PIN,NOW),('0','false',PIN,NOW),('0','true','',NOW),('0','true','e'*64,NOW),('0','true',PIN,NOW+3600)]:
            with self.subTest(last=last,first=first),self.assertRaises(ValueError):ledger.authorize(state,INTENT,last,first,pin,now)

    def test_failed_reservation_burns_version_and_denies_replay(self):
        state=self.reserve()
        failed=ledger.finish(state,'failed',{'runId':INTENT['runId'],'stage':'build'},ledger.digest(state))
        self.assertEqual(failed['lastReservedVersionCode'],3)
        with self.assertRaises(ValueError):self.authorize(failed)
        with self.assertRaises(ValueError):self.reserve(failed)
        next_state=self.reserve(failed,{**INTENT,'versionCode':4,'versionName':'0.4.0','runId':'12345678902'})
        self.assertEqual(next_state['lastReservedVersionCode'],4)

    def test_signed_then_delivered_requires_bound_receipts(self):
        state=self.reserve()
        receipt={**INTENT,'apkSha256':'e'*64,'capsuleSha256':'f'*64}
        with self.assertRaises(ValueError):ledger.finish(state,'delivered',receipt,ledger.digest(state))
        signed=ledger.finish(state,'signed',receipt,ledger.digest(state))
        self.assertEqual(signed['lastSignedVersionCode'],3)
        with self.assertRaises(ValueError):ledger.finish(signed,'delivered',{**receipt,'apkSha256':'1'*64,'deliveryEvidenceSha256':'2'*64},ledger.digest(signed))
        delivered=ledger.finish(signed,'delivered',{**receipt,'deliveryEvidenceSha256':'2'*64},ledger.digest(signed))
        self.assertEqual(delivered['reservation']['state'],'delivered')
        with self.assertRaises(ValueError):self.authorize(delivered)

    def test_failure_after_signing_preserves_the_signed_high_water_mark(self):
        state=self.reserve();receipt={**INTENT,'apkSha256':'e'*64,'capsuleSha256':'f'*64}
        signed=ledger.finish(state,'signed',receipt,ledger.digest(state))
        failed=ledger.finish(signed,'failed',{'runId':INTENT['runId'],'stage':'delivery'},ledger.digest(signed))
        self.assertEqual(failed['lastSignedVersionCode'],3)
        next_state=self.reserve(failed,{**INTENT,'versionCode':4,'runId':'12345678902'})
        context={**INTENT,'versionCode':4,'runId':'12345678902'}
        self.assertEqual(ledger.authorize(next_state,context,'3','false',PIN,NOW)['versionCode'],4)

    def test_duplicate_json_and_init_reset_are_rejected(self):
        import os,subprocess,sys
        from pathlib import Path
        with self.assertRaises(ValueError):ledger.load_json('{"generation":1,"generation":0}')
        env=os.environ.copy();env['FORMA_RELEASE_LEDGER']=json.dumps(self.reserve())
        result=subprocess.run([sys.executable,str(Path(ledger.__file__)),'init'],env=env,capture_output=True,text=True)
        self.assertNotEqual(result.returncode,0);self.assertEqual(result.stdout,'')

    def test_provider_ref_blob_and_transition_are_checked(self):
        state=self.reserve();commit,genesis,mapping,get=provider_fixture(state)
        self.assertEqual(ledger.read_reserved_state(commit,genesis,get,NOW),state)
        for change in ['wrong-head','wrong-blob','wrong-history','reset']:
            _,_,entries,lookup=provider_fixture(state)
            if change=='wrong-head':entries[ledger.REF_URL]['object']['sha']='e'*40
            elif change=='wrong-blob':entries[ledger.API+'/contents/release/ledger.json?ref='+commit]['sha']='0'*40
            elif change=='wrong-history':entries[ledger.API+'/compare/'+genesis+'...'+commit+'?per_page=1']['ahead_by']=8
            else:_,_,entries,lookup=provider_fixture(ledger.new_ledger())
            with self.subTest(change=change),self.assertRaises(ValueError):ledger.read_reserved_state(commit,genesis,lookup,NOW)

    def test_provider_head_change_during_read_is_rejected(self):
        commit,genesis,_,get=provider_fixture(self.reserve());calls=0
        def racing(url):
            nonlocal calls
            result=get(url)
            if url==ledger.REF_URL:
                calls+=1
                if calls==2:result['object']['sha']='e'*40
            return result
        with self.assertRaises(ValueError):ledger.read_reserved_state(commit,genesis,racing,NOW)

    def test_running_or_unverified_original_attempt_cannot_be_finalized(self):
        state=self.reserve()
        good={'id':12345678901,'head_sha':'b'*40,'run_attempt':1,'status':'completed','conclusion':'success','path':'.github/workflows/signed-release.yml','event':'workflow_dispatch','head_branch':'main','repository':{'full_name':'spenskoj90-sudo/forma-fitness-android'}}
        def provider(original=good,latest=None,signing='success'):
            def get(url):
                if '/jobs?' in url:return {'jobs':[{'name':ledger.SIGNING_JOB,'status':'completed','conclusion':signing}]}
                return original if url.endswith('/attempts/1') else (original if latest is None else latest)
            return get
        ledger.require_terminal_run(state,'signed',provider())
        for changed in [{**good,'status':'in_progress'},{**good,'run_attempt':2},{**good,'head_sha':'e'*40},{**good,'conclusion':'failure'},{**good,'path':'.github/workflows/android.yml'}]:
            with self.subTest(changed=changed),self.assertRaises(ValueError):ledger.require_terminal_run(state,'signed',provider(changed))
        with self.assertRaises(ValueError):ledger.require_terminal_run(state,'failed',provider())
        ledger.require_terminal_run(state,'signed',provider({**good,'conclusion':'failure'},{**good,'run_attempt':2}))
        with self.assertRaises(ValueError):ledger.require_terminal_run(state,'signed',provider(signing='failure'))

    def test_invalid_or_oversized_state_is_rejected(self):
        for version in [True,0,-1,2100000001,'3',18446744073709551619]:
            with self.subTest(version=version),self.assertRaises(ValueError):self.reserve(intent={**INTENT,'versionCode':version})
        for change in [{'runId':'01'},{'runAttempt':2},{'expiresAt':NOW-1},{'expiresAt':NOW+86400},{'recipientSha256':'x'*64}]:
            with self.subTest(change=change),self.assertRaises(ValueError):self.reserve(intent={**INTENT,**change})
        state=ledger.new_ledger();state['lastSignedVersionCode']=4
        with self.assertRaises(ValueError):self.reserve(state)


if __name__=='__main__':unittest.main()
