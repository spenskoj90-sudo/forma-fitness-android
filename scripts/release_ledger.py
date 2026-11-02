#!/usr/bin/env python3
"""Owner-controlled durable release reservations; no provider write credential in CI.

The owner commits each transition with an expected-head atomic ref update.
A protected variable pins the approved commit. CI cannot write or reset the ledger.
"""
import argparse
import base64
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import time
from urllib.request import Request, HTTPRedirectHandler, build_opener

from release_guard import validate_release

HEX=re.compile(r'[0-9a-f]{64}')
SOURCE=re.compile(r'[0-9a-f]{40}')
RUN=re.compile(r'[1-9][0-9]{0,19}')
VERSION=re.compile(r'[0-9]+\.[0-9]+\.[0-9]+(?:[-.][0-9A-Za-z.-]+)?')
FIELDS={'sourceSha','runId','runAttempt','versionCode','versionName','recipientSha256','expiresAt'}
API='https://api.github.com/repos/spenskoj90-sudo/forma-fitness-android'
REF_URL=API+'/git/ref/heads/ops/forma-release-ledger'
ADOPTED_SIGNER='a13104307fbd01a38a4e1fbdf7aafdf3f2e76be7cd18a1ac986a9b775d35cf7b'
SIGNING_JOB='Build verify and encrypt FORMA candidate'
LEDGER_GENESIS='b00b8b1d928507db6dcc5cf7d4dcd7dafea82ce7'


def number(value, maximum=2100000000):
    if type(value) is not int or not 0<=value<=maximum:raise ValueError('Invalid ledger integer')
    return value


def canonical(value):return json.dumps(value,sort_keys=True,separators=(',',':')).encode()


def digest(value):return hashlib.sha256(canonical(value)).hexdigest()


def new_ledger():
    return {'schema':'forma.release-ledger.v1','generation':0,'parentDigest':None,'signerSha256':ADOPTED_SIGNER,
            'lastReservedVersionCode':0,'lastSignedVersionCode':0,'reservation':None}


def intent_valid(intent):
    if not isinstance(intent,dict) or set(intent)!=FIELDS:raise ValueError('Invalid release intent')
    if any(type(intent[k]) is not str for k in ('sourceSha','runId','versionName','recipientSha256')):raise ValueError('Invalid intent text field')
    if not SOURCE.fullmatch(str(intent['sourceSha'])) or not RUN.fullmatch(str(intent['runId'])):raise ValueError('Invalid run identity')
    if type(intent['runId']) is not str or type(intent['runAttempt']) is not int or intent['runAttempt']!=1:raise ValueError('First attempt only')
    if not HEX.fullmatch(str(intent['recipientSha256'])) or not VERSION.fullmatch(str(intent['versionName'])):raise ValueError('Invalid recipient or version')
    if number(intent['versionCode'])==0:raise ValueError('Positive version required')
    number(intent['expiresAt'],9999999999)


def validate(state):
    if not isinstance(state,dict) or set(state)!=set(new_ledger()) or state['schema']!='forma.release-ledger.v1':raise ValueError('Invalid ledger schema')
    if state['signerSha256']!=ADOPTED_SIGNER:raise ValueError('Signing lineage cannot be replaced')
    if len(canonical(state))>16000:raise ValueError('Oversized ledger')
    number(state['generation']);number(state['lastReservedVersionCode']);number(state['lastSignedVersionCode'])
    if state['lastSignedVersionCode']>state['lastReservedVersionCode']:raise ValueError('Ledger regression')
    if state['parentDigest'] is not None and not HEX.fullmatch(str(state['parentDigest'])):raise ValueError('Invalid parent digest')
    reservation=state['reservation']
    if reservation is None:
        if state!=new_ledger():raise ValueError('Nonempty ledger missing reservation')
        return
    extras=set(reservation)-FIELDS
    allowed={'reserved':[{'state'}],'signed':[{'state','receipt'}],
             'delivered':[{'state','receipt','deliveryEvidenceSha256'}],
             'failed':[{'state','failureStage'},{'state','failureStage','receipt'}]}
    if extras not in allowed.get(reservation.get('state'),[]):raise ValueError('Invalid reservation fields')
    intent_valid({k:reservation[k] for k in FIELDS})
    if reservation['versionCode']!=state['lastReservedVersionCode'] or reservation['state'] not in {'reserved','signed','delivered','failed'}:raise ValueError('Invalid reservation state')
    if reservation['state'] in {'signed','delivered'} and state['lastSignedVersionCode']!=reservation['versionCode']:raise ValueError('Invalid signing ledger')
    receipt=reservation.get('receipt')
    if receipt is not None and (set(receipt)!={'apkSha256','capsuleSha256'} or not all(HEX.fullmatch(str(v)) for v in receipt.values())):raise ValueError('Invalid receipt')
    if reservation['state'] in {'signed','delivered'} and receipt is None:raise ValueError('Missing signing receipt')
    if reservation['state']=='delivered' and not HEX.fullmatch(str(reservation.get('deliveryEvidenceSha256',''))):raise ValueError('Missing delivery proof')


def transition(state,expected_digest):
    validate(state)
    if digest(state)!=expected_digest:raise ValueError('Stale ledger revision')
    updated=copy.deepcopy(state);updated['generation']+=1;updated['parentDigest']=expected_digest
    return updated


def reserve(state,intent,expected_digest,now=None):
    updated=transition(state,expected_digest);intent_valid(intent)
    now=int(time.time()) if now is None else now
    if not now<intent['expiresAt']<=now+21600:raise ValueError('Invalid reservation expiry')
    if state['reservation'] and state['reservation']['state'] not in {'delivered','failed'}:raise ValueError('Active release reservation')
    if intent['versionCode']<=state['lastReservedVersionCode']:raise ValueError('Reserved version cannot be reused')
    updated['lastReservedVersionCode']=intent['versionCode'];updated['reservation']={**intent,'state':'reserved'}
    validate(updated);return updated


def authorize(state,context,protected_last_signed,first,pin,now=None):
    validate(state);reservation=state['reservation'];now=int(time.time()) if now is None else now
    if not reservation or reservation['state']!='reserved' or now>=reservation['expiresAt']:raise ValueError('No live reservation')
    for key in FIELDS-{'expiresAt'}:
        if type(context.get(key)) is not type(reservation[key]) or context[key]!=reservation[key]:raise ValueError('Reservation identity mismatch')
    if protected_last_signed!=str(state['lastSignedVersionCode']):raise ValueError('Protected ledger mirror mismatch')
    if pin.lower()!=state['signerSha256']:raise ValueError('Protected signer differs from adopted lineage')
    validate_release(str(reservation['versionCode']),protected_last_signed,first,pin)
    return {'schema':'forma.release-binding.v1','ledgerGeneration':state['generation'],'signerSha256':pin.lower(),**{k:reservation[k] for k in FIELDS}}


def finish(state,outcome,receipt,expected_digest):
    updated=transition(state,expected_digest);r=updated['reservation']
    if not r or receipt.get('runId')!=r['runId'] or r['state'] in {'delivered','failed'}:raise ValueError('No matching active reservation')
    if outcome=='failed':
        if receipt.get('stage') not in {'preflight','build','transport','delivery'}:raise ValueError('Invalid failure stage')
        r['state']='failed';r['failureStage']=receipt['stage']
    elif outcome in {'signed','delivered'}:
        for key in FIELDS-{'expiresAt'}:
            if type(receipt.get(key)) is not type(r[key]) or receipt[key]!=r[key]:raise ValueError('Receipt identity mismatch')
        proof={k:receipt.get(k) for k in ('apkSha256','capsuleSha256')}
        if not all(isinstance(v,str) and HEX.fullmatch(v) for v in proof.values()):raise ValueError('Missing exact-byte receipt')
        if outcome=='signed':
            if r['state']!='reserved':raise ValueError('Signing receipt already consumed')
            updated['lastSignedVersionCode']=r['versionCode'];r['receipt']=proof;r['state']='signed'
        else:
            if r['state']!='signed' or r['receipt']!=proof or not HEX.fullmatch(str(receipt.get('deliveryEvidenceSha256',''))):raise ValueError('Delivery receipt mismatch')
            r['state']='delivered';r['deliveryEvidenceSha256']=receipt['deliveryEvidenceSha256']
    else:raise ValueError('Invalid transition')
    validate(updated);return updated


def load_json(text):
    def unique(pairs):
        result={}
        for k,v in pairs:
            if k in result:raise ValueError('Duplicate JSON key')
            result[k]=v
        return result
    return json.loads(text,object_pairs_hook=unique)


def fetch_json(url):
    if not url.startswith(API+'/'):raise ValueError('Unexpected metadata origin')
    class NoRedirect(HTTPRedirectHandler):
        def redirect_request(self,*args,**kwargs):raise ValueError('Provider redirect rejected')
    headers={'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28','Cache-Control':'no-cache'}
    if os.environ.get('GITHUB_TOKEN'):headers['Authorization']='Bearer '+os.environ['GITHUB_TOKEN']
    with build_opener(NoRedirect()).open(Request(url,headers=headers),timeout=20) as response:
        data=response.read(65537)
    if len(data)>65536:raise ValueError('Oversized provider metadata')
    return load_json(data.decode())


def read_state_file(commit,get):
    if not isinstance(commit,str) or not SOURCE.fullmatch(commit):raise ValueError('Invalid ledger commit')
    content=get(API+'/contents/release/ledger.json?ref='+commit)
    if content.get('type')!='file' or content.get('encoding')!='base64' or content.get('path')!='release/ledger.json':raise ValueError('Invalid provider ledger file')
    if type(content.get('size')) is not int or not 0<content['size']<=16000:raise ValueError('Invalid provider ledger size')
    raw=base64.b64decode(''.join(content['content'].split()),validate=True)
    blob=hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
    if len(raw)!=content['size'] or blob!=content.get('sha'):raise ValueError('Provider ledger blob mismatch')
    state=load_json(raw.decode());validate(state);return state


def read_reserved_state(expected_commit,genesis_commit,get=fetch_json,now=None):
    if any(not isinstance(c,str) or not SOURCE.fullmatch(c) for c in (expected_commit,genesis_commit)):raise ValueError('Missing protected ledger pins')
    ref=get(REF_URL)
    if ref.get('ref')!='refs/heads/ops/forma-release-ledger' or ref.get('object',{}).get('type')!='commit' or ref['object'].get('sha')!=expected_commit:raise ValueError('Ledger branch changed')
    state=read_state_file(expected_commit,get)
    if expected_commit==genesis_commit:
        if state!=new_ledger():raise ValueError('Genesis must be the empty original ledger')
    else:
        ancestry=get(API+'/compare/'+genesis_commit+'...'+expected_commit+'?per_page=1')
        if ancestry.get('status')!='ahead' or ancestry.get('behind_by')!=0 or ancestry.get('ahead_by')!=state['generation']:raise ValueError('Ledger history reset or fork')
        commit=get(API+'/git/commits/'+expected_commit)
        if len(commit.get('parents',[]))!=1:raise ValueError('Ledger history must be linear')
        parent=read_state_file(commit['parents'][0]['sha'],get)
        r=state['reservation']
        if not r:raise ValueError('A ledger reset is forbidden')
        if r['state']=='reserved':replayed=reserve(parent,{k:r[k] for k in FIELDS},digest(parent),now)
        else:
            receipt={k:r[k] for k in FIELDS}
            receipt.update(r.get('receipt',{}));receipt['stage']=r.get('failureStage');receipt['deliveryEvidenceSha256']=r.get('deliveryEvidenceSha256')
            replayed=finish(parent,r['state'],receipt,digest(parent))
        if state!=replayed:raise ValueError('Ledger transition does not match its committed parent')
    if get(REF_URL).get('object',{}).get('sha')!=expected_commit:raise ValueError('Ledger branch advanced during read')
    return state


def require_terminal_run(state,outcome,get=fetch_json):
    validate(state)
    reservation=state.get('reservation')
    if not reservation:raise ValueError('No reservation to finalize')
    run=get(API+'/actions/runs/'+reservation['runId']+'/attempts/1')
    if str(run.get('id'))!=reservation['runId'] or run.get('head_sha')!=reservation['sourceSha'] or run.get('run_attempt')!=1 or run.get('status')!='completed':raise ValueError('Original release run is not terminal')
    if run.get('path')!='.github/workflows/signed-release.yml' or run.get('event')!='workflow_dispatch' or run.get('head_branch')!='main' or run.get('repository',{}).get('full_name')!='spenskoj90-sudo/forma-fitness-android':raise ValueError('Unapproved release workflow origin')
    latest=get(API+'/actions/runs/'+reservation['runId'])
    if str(latest.get('id'))!=reservation['runId'] or latest.get('head_sha')!=reservation['sourceSha'] or latest.get('status')!='completed':raise ValueError('Latest release attempt is not terminal')
    if outcome in {'signed','delivered'}:
        jobs=get(API+'/actions/runs/'+reservation['runId']+'/attempts/1/jobs?per_page=100')
        signing=[job for job in jobs.get('jobs',[]) if job.get('name')==SIGNING_JOB]
        if latest.get('conclusion')!='success' or len(signing)!=1 or signing[0].get('status')!='completed' or signing[0].get('conclusion')!='success':raise ValueError('Verified original signing and successful attestation are required')
    if outcome=='failed' and reservation['state']=='reserved' and latest.get('conclusion')=='success':raise ValueError('Record the verified signed receipt before delivery failure')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=['hash','reserve','authorize','signed','delivered','failed'])
    parser.add_argument('--ledger',type=Path);parser.add_argument('--intent',type=Path)
    parser.add_argument('--receipt',type=Path);parser.add_argument('--expected-digest');parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    try:
        if args.mode=='authorize':state=read_reserved_state(os.environ.get('FORMA_RELEASE_LEDGER_COMMIT',''),LEDGER_GENESIS,get=fetch_json)
        else:
            if args.ledger is None:raise ValueError('An explicit current journal snapshot is required')
            state=load_json(args.ledger.read_text())
        if args.mode=='hash':validate(state);print(digest(state));return
        if args.mode=='reserve':result=reserve(state,load_json(args.intent.read_text()),args.expected_digest)
        elif args.mode in {'signed','delivered','failed'}:
            require_terminal_run(state,args.mode,get=fetch_json)
            result=finish(state,args.mode,load_json(args.receipt.read_text()),args.expected_digest)
        elif args.mode=='authorize':
            e=os.environ
            validate_release(e.get('VERSION_CODE',''),e.get('FORMA_LAST_SIGNED_VERSION_CODE',''),e.get('FIRST_RELEASE_APPROVED',''),e.get('FORMA_EXPECTED_SIGNER_SHA256',''))
            if e.get('GITHUB_RUN_ATTEMPT')!='1':raise ValueError('Retry requires a new allocated version')
            context={'sourceSha':e.get('GITHUB_SHA'),'runId':e.get('GITHUB_RUN_ID'),'runAttempt':1,'versionCode':int(e['VERSION_CODE']),'versionName':e.get('VERSION_NAME'),'recipientSha256':e.get('FORMA_DELIVERY_RECIPIENT_SHA256')}
            result=authorize(state,context,e['FORMA_LAST_SIGNED_VERSION_CODE'],e['FIRST_RELEASE_APPROVED'],e['FORMA_EXPECTED_SIGNER_SHA256'])
            result['ledgerCommit']=e['FORMA_RELEASE_LEDGER_COMMIT']
        else:result=state
        encoded=canonical(result)+b'\n'
        if args.output:
            tmp=None
            try:
                with tempfile.NamedTemporaryFile(dir=args.output.parent,delete=False) as handle:
                    tmp=Path(handle.name);handle.write(encoded);handle.flush();os.fsync(handle.fileno())
                os.replace(tmp,args.output)
            finally:
                if tmp and tmp.exists():tmp.unlink()
        else:print(encoded.decode(),end='')
    except (ValueError,OSError,KeyError,TypeError,AttributeError):raise SystemExit('FORMA release ledger FAIL') from None


if __name__=='__main__':main()
