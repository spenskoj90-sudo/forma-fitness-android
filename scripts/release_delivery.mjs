#!/usr/bin/env node
// Recipient-only APK transport. This module never receives Android signing secrets.
import {createHash,createPublicKey,createPrivateKey,publicEncrypt,privateDecrypt,
 createCipheriv,createDecipheriv,randomBytes,constants,generateKeyPairSync} from 'node:crypto';
import {readFileSync,writeFileSync,realpathSync,statfsSync,statSync,openSync,fstatSync,closeSync,unlinkSync,constants as fsConstants} from 'node:fs';
import {dirname,resolve,join,basename} from 'node:path';
import {pathToFileURL} from 'node:url';

const MAX=150*1024*1024;
const hex=/^[a-f0-9]{64}$/;
const fields=['schema','ledgerGeneration','ledgerCommit','signerSha256','sourceSha','runId','runAttempt','versionCode','versionName','recipientSha256','expiresAt'];
const fail=()=>{throw Error('FORMA private delivery FAIL');};
const sha=data=>createHash('sha256').update(data).digest('hex');
const sorted=value=>value&&typeof value==='object'&&!Array.isArray(value)?Object.fromEntries(Object.keys(value).sort().map(k=>[k,sorted(value[k])])):value;
const canonical=value=>Buffer.from(JSON.stringify(sorted(value)));
const exact=(value,keys)=>value&&Object.keys(value).sort().join('|')===[...keys].sort().join('|');

export function recipientFingerprint(pem){
 if(typeof pem!=='string'||pem.length>10000||!(/^-----BEGIN PUBLIC KEY-----\r?\n[A-Za-z0-9+/=\r\n]+-----END PUBLIC KEY-----$/).test(pem.trim()))fail();
 const key=createPublicKey(pem),details=key.asymmetricKeyDetails;
 if(key.asymmetricKeyType!=='rsa'||!details||details.modulusLength<3072||details.modulusLength>8192||details.publicExponent!==65537n)fail();
 return sha(key.export({type:'spki',format:'der'}));
}

function validateBinding(binding){
 if(!exact(binding,fields)||binding.schema!=='forma.release-binding.v1'||!Number.isInteger(binding.ledgerGeneration)||binding.ledgerGeneration<1||
 !hex.test(binding.signerSha256)||!hex.test(binding.recipientSha256)||!(/^[0-9a-f]{40}$/).test(binding.sourceSha)||!(/^[0-9a-f]{40}$/).test(binding.ledgerCommit)||
 typeof binding.runId!=='string'||!(/^[1-9][0-9]{0,19}$/).test(binding.runId)||binding.runAttempt!==1||
 !Number.isInteger(binding.versionCode)||binding.versionCode<1||binding.versionCode>2100000000||
 typeof binding.versionName!=='string'||!(/^[0-9]+\.[0-9]+\.[0-9]+(?:[-.][0-9A-Za-z.-]+)?$/).test(binding.versionName)||
 !Number.isSafeInteger(binding.expiresAt)||binding.expiresAt<1)fail();
}

export function seal(apk,identity,pem,binding,now=Math.floor(Date.now()/1000)){
 validateBinding(binding);
 if(!Buffer.isBuffer(apk)||apk.length<1||apk.length>MAX||now>=binding.expiresAt||binding.expiresAt>now+21600||
 recipientFingerprint(pem)!==binding.recipientSha256||identity.package!=='ru.forma.fitness'||identity.v2!==true||
 identity.versionCode!==binding.versionCode||identity.versionName!==binding.versionName||identity.signerSha256!==binding.signerSha256||
 identity.apkSha256!==sha(apk)||identity.sizeBytes!==apk.length)fail();
 const header={protocol:'forma.private-apk.v1',algorithm:'RSA-OAEP-SHA256+A256GCM',
  ...binding,package:'ru.forma.fitness',apkSha256:identity.apkSha256,sizeBytes:apk.length};
 const key=randomBytes(32),nonce=randomBytes(12);
 try{
  const cipher=createCipheriv('aes-256-gcm',key,nonce);cipher.setAAD(canonical(header));
  const ciphertext=Buffer.concat([cipher.update(apk),cipher.final()]);
  const wrapped=publicEncrypt({key:pem,padding:constants.RSA_PKCS1_OAEP_PADDING,oaepHash:'sha256'},key);
  return {header,nonceBase64:nonce.toString('base64'),wrappedKeyBase64:wrapped.toString('base64'),
   ciphertextBase64:ciphertext.toString('base64'),tagBase64:cipher.getAuthTag().toString('base64')};
 }finally{key.fill(0);}
}

export function encodeCapsule(capsule){return Buffer.concat([canonical(capsule),Buffer.from('\n')]);}

function b64(text,length){
 if(typeof text!=='string')fail();
 const bytes=Buffer.from(text,'base64');
 if(bytes.toString('base64')!==text||(length!==undefined&&bytes.length!==length))fail();
 return bytes;
}

export function openCapsule(bytes,privatePem,expected){
 if(!Buffer.isBuffer(bytes)||bytes.length>MAX*1.4+100000||!hex.test(expected.capsuleSha256)||sha(bytes)!==expected.capsuleSha256)fail();
 const capsule=JSON.parse(bytes.toString('utf8'));
 if(!exact(capsule,['header','nonceBase64','wrappedKeyBase64','ciphertextBase64','tagBase64']))fail();
 const h=capsule.header;
 if(!exact(h,[...fields,'protocol','algorithm','package','apkSha256','sizeBytes']))fail();
 validateBinding(Object.fromEntries(fields.map(k=>[k,h[k]])));
 if(h.protocol!=='forma.private-apk.v1'||h.algorithm!=='RSA-OAEP-SHA256+A256GCM'||h.package!=='ru.forma.fitness'||!hex.test(h.apkSha256)||!Number.isInteger(h.sizeBytes)||h.sizeBytes<1||h.sizeBytes>MAX)fail();
 for(const k of ['sourceSha','runId','runAttempt','versionCode','versionName','signerSha256','recipientSha256','apkSha256','ledgerCommit'])if(expected[k]!==h[k])fail();
 const privateKey=createPrivateKey(privatePem),publicKey=createPublicKey(privateKey);
 if(sha(publicKey.export({type:'spki',format:'der'}))!==h.recipientSha256)fail();
 const wrapped=b64(capsule.wrappedKeyBase64);
 if(wrapped.length<384||wrapped.length>1024)fail();
 const key=privateDecrypt({key:privateKey,padding:constants.RSA_PKCS1_OAEP_PADDING,oaepHash:'sha256'},wrapped);
 try{
  if(key.length!==32)fail();
  const decipher=createDecipheriv('aes-256-gcm',key,b64(capsule.nonceBase64,12));
  decipher.setAAD(canonical(h));decipher.setAuthTag(b64(capsule.tagBase64,16));
  const clear=Buffer.concat([decipher.update(b64(capsule.ciphertextBase64,h.sizeBytes)),decipher.final()]);
  if(clear.length!==h.sizeBytes||sha(clear)!==h.apkSha256){clear.fill(0);fail();}
  return clear;
 }finally{key.fill(0);}
}

function ramOnly(path){
 const parent=realpathSync(dirname(resolve(path)));
 if((parent!=='/dev/shm'&&!parent.startsWith('/dev/shm/'))||statfsSync(parent).type!==0x01021994||readFileSync('/proc/swaps','utf8').trim().split('\n').length!==1)fail();
 if(readFileSync('/proc/self/limits','utf8').match(/^Max core file size\s+(\S+)/m)?.[1]!=='0')fail();
 return join(parent,basename(resolve(path)));
}

function readRamKey(path){
 const canonicalPath=ramOnly(path),fd=openSync(canonicalPath,fsConstants.O_RDONLY|fsConstants.O_NOFOLLOW);
 try{
  const file=fstatSync(fd,{bigint:true});
  if(!file.isFile()||file.size<1n||file.size>10000n||file.uid!==BigInt(process.getuid())||(file.mode&0o077n)!==0n||file.dev!==statSync('/dev/shm',{bigint:true}).dev)fail();
  return readFileSync(fd);
 }finally{closeSync(fd);}
}

function main(){
 const [mode,...args]=process.argv.slice(2),options={};
 if(args.length%2)fail();
 for(let i=0;i<args.length;i+=2){if(!args[i].startsWith('--')||options[args[i]])fail();options[args[i]]=args[i+1];}
 const load=key=>JSON.parse(readFileSync(options[key],'utf8'));
 if(mode==='recipient'){
  console.log(JSON.stringify({recipientSha256:recipientFingerprint(process.env.FORMA_DELIVERY_RECIPIENT_PEM)}));
 }else if(mode==='create-recipient'){
  const privatePath=ramOnly(options['--private-out']);
  const pair=generateKeyPairSync('rsa',{modulusLength:3072});
  const privateBytes=Buffer.from(pair.privateKey.export({type:'pkcs8',format:'pem'}));
  const pub=pair.publicKey.export({type:'spki',format:'pem'});
  let created=false;
  try{
   writeFileSync(privatePath,privateBytes,{mode:0o600,flag:'wx'});created=true;
   writeFileSync(options['--public-out'],pub,{mode:0o600,flag:'wx'});
  }catch(error){if(created)unlinkSync(privatePath);throw error;}
  finally{privateBytes.fill(0);}
  console.log(JSON.stringify({recipientSha256:recipientFingerprint(pub)}));
 }else if(mode==='seal'){
  const binding=load('--binding'),identity=load('--identity'),apk=readFileSync(options['--apk']);
  try{
   if(identity.signerSha256!==process.env.FORMA_EXPECTED_SIGNER_SHA256?.toLowerCase())fail();
   const bytes=encodeCapsule(seal(apk,identity,process.env.FORMA_DELIVERY_RECIPIENT_PEM,binding));
   writeFileSync(options['--out'],bytes,{mode:0o600,flag:'wx'});
   console.log(JSON.stringify({...binding,apkSha256:identity.apkSha256,capsuleSha256:sha(bytes)}));
  }finally{apk.fill(0);}
 }else if(mode==='open'){
  const key=readRamKey(options['--private-key']);
  let clear;
  try{
   clear=openCapsule(readFileSync(options['--capsule']),key,load('--expected'));
   writeFileSync(options['--out'],clear,{mode:0o600,flag:'wx'});
   console.log('FORMA authenticated private APK restore PASS');
  }finally{key.fill(0);clear?.fill(0);}
 }else fail();
}

if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){
 try{main();}catch{console.error('FORMA private delivery FAIL');process.exitCode=1;}
}
