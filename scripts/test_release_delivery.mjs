import {test} from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync,createHash} from 'node:crypto';
import {mkdtempSync,writeFileSync,readFileSync,symlinkSync,existsSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';

let delivery;
try { delivery=await import('./release_delivery.mjs'); } catch(error) { if(error.code==='ERR_MODULE_NOT_FOUND')delivery=null;else throw error; }
const pair=generateKeyPairSync('rsa',{modulusLength:3072});
const pub=pair.publicKey.export({type:'spki',format:'pem'});
const priv=pair.privateKey.export({type:'pkcs8',format:'pem'});
const fp=createHash('sha256').update(pair.publicKey.export({type:'spki',format:'der'})).digest('hex');
const NOW=1800000000;
const data=Buffer.from('synthetic APK bytes, never a real signing key');
const meta={package:'ru.forma.fitness',versionCode:3,versionName:'0.3.0',signerSha256:'a'.repeat(64),v2:true,apkSha256:createHash('sha256').update(data).digest('hex'),sizeBytes:data.length};
const binding={schema:'forma.release-binding.v1',ledgerGeneration:1,ledgerCommit:'d'.repeat(40),signerSha256:'a'.repeat(64),sourceSha:'b'.repeat(40),runId:'12345678901',runAttempt:1,versionCode:3,versionName:'0.3.0',recipientSha256:fp,expiresAt:NOW+3600};
const requireImpl=()=>assert.ok(delivery,'delivery implementation missing');
const expected=buffer=>({...binding,...meta,capsuleSha256:createHash('sha256').update(buffer).digest('hex')});

test('RSA recipient canonical fingerprint and private-key rejection',()=>{
 requireImpl();assert.equal(delivery.recipientFingerprint(pub),fp);
 assert.throws(()=>delivery.recipientFingerprint(priv));
 assert.throws(()=>delivery.recipientFingerprint(pub+priv));
 const weak=generateKeyPairSync('rsa',{modulusLength:2048});
 assert.throws(()=>delivery.recipientFingerprint(weak.publicKey.export({type:'spki',format:'pem'})));
});
test('real AES-GCM/RSA roundtrip binds exact artifact identity',()=>{
 requireImpl();const buffer=delivery.encodeCapsule(delivery.seal(data,meta,pub,binding,NOW));
 assert.deepEqual(delivery.openCapsule(buffer,priv,expected(buffer)),data);
 assert.ok(!buffer.includes(data));assert.ok(!buffer.includes(Buffer.from('PRIVATE KEY')));
});
test('fresh random keys/nonces produce distinct ciphertexts',()=>{
 requireImpl();assert.notDeepEqual(delivery.encodeCapsule(delivery.seal(data,meta,pub,binding,NOW)),delivery.encodeCapsule(delivery.seal(data,meta,pub,binding,NOW)));
});
test('wrong digest, code, source, recipient, expiry and v2=false reject before encryption',()=>{
 requireImpl();for(const changed of [{...meta,apkSha256:'d'.repeat(64)},{...meta,versionCode:4},{...meta,v2:false}]) assert.throws(()=>delivery.seal(data,changed,pub,binding,NOW));
 assert.throws(()=>delivery.seal(data,meta,pub,{...binding,recipientSha256:'d'.repeat(64)},NOW));
 assert.throws(()=>delivery.seal(data,meta,pub,binding,NOW+3600));
 assert.throws(()=>delivery.seal(data,meta,pub,{...binding,sourceSha:'bad'},NOW));
 assert.throws(()=>delivery.seal(data,meta,pub,{...binding,sourceSha:binding.sourceSha+'\n'},NOW));
 assert.throws(()=>delivery.seal(data,meta,pub,{...binding,versionName:binding.versionName+'\n'},NOW));
});
test('tampered ciphertext, authentication tag, wrapped key and header deny',()=>{
 requireImpl();const original=delivery.seal(data,meta,pub,binding,NOW);
 for(const field of ['ciphertextBase64','tagBase64','wrappedKeyBase64']){
  const changed=structuredClone(original);const bytes=Buffer.from(changed[field],'base64');bytes[0]^=1;changed[field]=bytes.toString('base64');
  const buffer=delivery.encodeCapsule(changed);assert.throws(()=>delivery.openCapsule(buffer,priv,expected(buffer)));
 }
 const changed=structuredClone(original);changed.header.versionCode=4;
 const buffer=delivery.encodeCapsule(changed);assert.throws(()=>delivery.openCapsule(buffer,priv,{...expected(buffer),versionCode:4}));
});
test('a fresh recipient and wrong authenticated download receipt cannot decrypt',()=>{
 requireImpl();const buffer=delivery.encodeCapsule(delivery.seal(data,meta,pub,binding,NOW));
 const other=generateKeyPairSync('rsa',{modulusLength:3072});
 assert.throws(()=>delivery.openCapsule(buffer,other.privateKey.export({type:'pkcs8',format:'pem'}),expected(buffer)));
 assert.throws(()=>delivery.openCapsule(buffer,priv,{...expected(buffer),capsuleSha256:'d'.repeat(64)}));
 for(const altered of [{sourceSha:'d'.repeat(40)},{runId:'12345678902'},{signerSha256:'d'.repeat(64)},{apkSha256:'d'.repeat(64)}]) assert.throws(()=>delivery.openCapsule(buffer,priv,{...expected(buffer),...altered}));
});
test('malformed encoding and extra fields are rejected',()=>{
 requireImpl();const capsule=delivery.seal(data,meta,pub,binding,NOW);
 for(const altered of [{...capsule,nonceBase64:'bad!'},{...capsule,extra:'unexpected'},{...capsule,tagBase64:''}]){
  const buffer=delivery.encodeCapsule(altered);assert.throws(()=>delivery.openCapsule(buffer,priv,expected(buffer)));
 }
});
test('receiver CLI refuses a tmpfs symlink to a disk private key',()=>{
 requireImpl();const disk=mkdtempSync(join(tmpdir(),'forma-disk-key-test-')),ram=mkdtempSync('/dev/shm/forma-key-test-');
 try{
  const bytes=delivery.encodeCapsule(delivery.seal(data,meta,pub,binding,NOW));
  writeFileSync(join(disk,'capsule.json'),bytes);writeFileSync(join(disk,'expected.json'),JSON.stringify(expected(bytes)));
  writeFileSync(join(disk,'private.pem'),priv,{mode:0o600});symlinkSync(join(disk,'private.pem'),join(ram,'linked.pem'));
  const args=[process.execPath,fileURLToPath(new URL('./release_delivery.mjs',import.meta.url)),'open','--capsule',join(disk,'capsule.json'),'--private-key',join(ram,'linked.pem'),'--expected',join(disk,'expected.json'),'--out',join(disk,'restored.apk')];
  const result=spawnSync('bash',['-c','ulimit -c 0\nexec "$@"','forma-test',...args],{encoding:'utf8'});
  assert.equal(result.status,1,result.stderr);assert.equal(existsSync(join(disk,'restored.apk')),false);
 }finally{rmSync(disk,{recursive:true,force:true});rmSync(ram,{recursive:true,force:true});}
});
test('recipient CLI refuses enabled core dumps before creating private material',(t)=>{
 requireImpl();const ram=mkdtempSync('/dev/shm/forma-core-test-');
 try{
  const args=[process.execPath,fileURLToPath(new URL('./release_delivery.mjs',import.meta.url)),'create-recipient','--private-out',join(ram,'private.pem'),'--public-out',join(ram,'public.pem')];
  const result=spawnSync('bash',['-c','if ! ulimit -c unlimited; then exit 77; fi\nexec "$@"','forma-test',...args],{encoding:'utf8'});
  if(result.status===77)t.diagnostic('Host hard core limit already prevents the unsafe mode; application unsafe-mode branch cannot be exercised here.');
  else assert.equal(result.status,1,result.stderr);
  assert.equal(existsSync(join(ram,'private.pem')),false);
 }finally{rmSync(ram,{recursive:true,force:true});}
});
test('CLI sealing produces the exact receipt and recovery enforces actual kernel policy',()=>{
 requireImpl();const disk=mkdtempSync(join(tmpdir(),'forma-cli-test-')),ram=mkdtempSync('/dev/shm/forma-cli-test-');
 try{
  const script=fileURLToPath(new URL('./release_delivery.mjs',import.meta.url));
  writeFileSync(join(disk,'input.apk'),data);writeFileSync(join(disk,'identity.json'),JSON.stringify(meta));writeFileSync(join(disk,'binding.json'),JSON.stringify({...binding,expiresAt:Math.floor(Date.now()/1000)+3600}));
  const result=spawnSync(process.execPath,[script,'seal','--apk',join(disk,'input.apk'),'--identity',join(disk,'identity.json'),'--binding',join(disk,'binding.json'),'--out',join(disk,'capsule.json')],{env:{...process.env,FORMA_DELIVERY_RECIPIENT_PEM:pub,FORMA_EXPECTED_SIGNER_SHA256:meta.signerSha256},encoding:'utf8'});
  assert.equal(result.status,0,result.stderr);
  const receipt=JSON.parse(result.stdout),bytes=readFileSync(join(disk,'capsule.json'));
  assert.equal(receipt.capsuleSha256,createHash('sha256').update(bytes).digest('hex'));
  writeFileSync(join(disk,'expected.json'),JSON.stringify(receipt));writeFileSync(join(ram,'private.pem'),priv,{mode:0o600});
  const recovered=spawnSync('bash',['-c','ulimit -c 0\nexec "$@"','forma-test',process.execPath,script,'open','--capsule',join(disk,'capsule.json'),'--private-key',join(ram,'private.pem'),'--expected',join(disk,'expected.json'),'--out',join(disk,'output.apk')],{encoding:'utf8'});
  if(readFileSync('/proc/swaps','utf8').trim().split('\n').length===1){assert.equal(recovered.status,0,recovered.stderr);assert.deepEqual(readFileSync(join(disk,'output.apk')),data);}
  else {assert.notEqual(recovered.status,0);assert.equal(existsSync(join(disk,'output.apk')),false);}
 }finally{rmSync(disk,{recursive:true,force:true});rmSync(ram,{recursive:true,force:true});}
});
