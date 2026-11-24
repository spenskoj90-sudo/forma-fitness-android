// Local Android instrumentation fixture. Keys belong to one emulator run.
import { createHash, generateKeyPairSync, sign } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:https';
import { join } from 'node:path';
import { packUpdateEnvelope, prepareUpdatePayload } from './release_update_manifest.mjs';

const [stage, apkPath, signerSha256] = process.argv.slice(2);
const host = 'https://localhost';
const apk = readFileSync(apkPath);
// The runner verifies debug v2 APK signing; Android checks package and version.
const identity = { package: 'ru.forma.fitness', versionCode: 3, versionName: '0.3.0-update-test',
  signerSha256, v2: true, apkSha256: createHash('sha256').update(apk).digest('hex'), sizeBytes: apk.length };
const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 3072 });
const pin = publicKey.export({ format: 'der', type: 'spki' }).toString('base64');
const payload = prepareUpdatePayload(identity, apk, {
  previousVersion: 2,
  expectedSigner: identity.signerSha256,
  feedUrl: `${host}/stable.json`,
  apkUrl: `${host}/forma.apk`,
  notes: 'Synthetic HTTPS update fixture',
});
const stable = JSON.parse(payload);
const envelope = bytes => packUpdateEnvelope(bytes, sign('sha256', bytes, privateKey), pin);

function signedCase(name, fields) {
  return envelope(Buffer.from(JSON.stringify({ ...stable, sourceUrl: `${host}${name}`, ...fields })));
}

const tampered = JSON.parse(signedCase('/invalid-signature.json', {}));
const signature = Buffer.from(tampered.signature, 'base64');
signature[0] ^= 1;
tampered.signature = signature.toString('base64');
const offers = new Map([
  ['/stable.json', envelope(payload)],
  ['/downgrade.json', signedCase('/downgrade.json', { versionCode: 1 })],
  ['/wrong-source.json', signedCase('/wrong-source.json', { sourceUrl: `${host}/stable.json` })],
  ['/wrong-package.json', signedCase('/wrong-package.json', { applicationId: 'ru.forma.other' })],
  ['/wrong-cert.json', signedCase('/wrong-cert.json', { certificateSha256: '1'.repeat(64) })],
  ['/invalid-signature.json', Buffer.from(JSON.stringify(tampered))],
  ['/apk-redirect.json', signedCase('/apk-redirect.json', { apkUrl: `${host}/redirect.apk` })],
  ['/apk-damaged.json', signedCase('/apk-damaged.json', { apkUrl: `${host}/damaged.apk` })],
  ['/apk-oversize.json', signedCase('/apk-oversize.json', { apkUrl: `${host}/oversize.apk` })],
  ['/apk-wrong-version.json', signedCase('/apk-wrong-version.json', { versionCode: 4 })],
]);

const damaged = Buffer.from(apk);
damaged[damaged.length - 1] ^= 1;
const oversized = Buffer.concat([apk, Buffer.of(0)]);
const binaries = new Map([['/forma.apk', apk], ['/damaged.apk', damaged], ['/oversize.apk', oversized]]);
const redirects = new Map([['/redirect.json', '/stable.json'], ['/redirect.apk', '/forma.apk']]);
const server = createServer({
  cert: readFileSync(join(stage, 'tls.crt')),
  key: readFileSync(join(stage, 'tls.key')),
}, (request, response) => {
  const path = request.url;
  if (request.method === 'GET' && redirects.has(path)) {
    response.writeHead(302, { Location: redirects.get(path) }).end();
    return;
  }
  if (request.method !== 'GET' || (!offers.has(path) && !binaries.has(path))) {
    response.writeHead(404).end();
    return;
  }
  const data = offers.get(path) ?? binaries.get(path);
  response.writeHead(200, { 'Content-Length': data.length,
    'Content-Type': offers.has(path) ? 'application/json' : 'application/vnd.android.package-archive' });
  response.end(data);
});

server.listen(0, '127.0.0.1', () => {
  writeFileSync(join(stage, 'pin'), pin, { flag: 'wx', mode: 0o600 });
  writeFileSync(join(stage, 'port'), String(server.address().port), { flag: 'wx', mode: 0o600 });
});
