#!/usr/bin/env node
import { createHash, createPublicKey, verify } from 'node:crypto';
import { readFileSync, statSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';

const maxApkBytes = 200 * 1024 * 1024;
const maxEnvelopeBytes = 16 * 1024;
const maxVersionCode = 2_100_000_000;
const fingerprint = /^[a-f0-9]{64}$/;

function httpsUrl(value) {
  const authority = /^https:\/\/([^/?#]+)(?:[/?#]|$)/.exec(value)?.[1];
  if (!authority || authority.includes('@') || /[\s\\]/u.test(value)) {
    throw new Error('Invalid update URL');
  }
  const url = new URL(value);
  if (url.protocol !== 'https:' || !url.hostname || url.port || value.includes('#')) {
    throw new Error('Invalid update URL');
  }
  return url;
}

// The identity is the output of release_guard.py apk for the authenticated
// delivery. Bind those checked attributes to the exact bytes being offered.
export function prepareUpdatePayload(identity, apk, options) {
  const { previousVersion, expectedSigner, feedUrl, apkUrl, notes } = options;
  const releaseNotes = notes.trim();
  if (!fingerprint.test(expectedSigner) || identity.package !== 'ru.forma.fitness' ||
      identity.v2 !== true || identity.signerSha256 !== expectedSigner) {
    throw new Error('APK identity mismatch');
  }
  if (!Number.isInteger(previousVersion) || previousVersion < 0 ||
      !Number.isInteger(identity.versionCode) || identity.versionCode <= previousVersion ||
      identity.versionCode > maxVersionCode || typeof identity.versionName !== 'string' ||
      !/^[0-9]+\.[0-9]+\.[0-9]+(?:[-.][0-9A-Za-z.-]+)?$/.test(identity.versionName) ||
      identity.versionName.length > 80) {
    throw new Error('Invalid update version');
  }
  if (!Buffer.isBuffer(apk) || apk.length < 1 || apk.length > maxApkBytes ||
      apk.length !== identity.sizeBytes ||
      createHash('sha256').update(apk).digest('hex') !== identity.apkSha256) {
    throw new Error('APK bytes mismatch');
  }
  if (httpsUrl(feedUrl).hostname !== httpsUrl(apkUrl).hostname ||
      releaseNotes.length < 1 || releaseNotes.length > 500) {
    throw new Error('Invalid update source or release notes');
  }
  return Buffer.from(JSON.stringify({
    channel: 'stable', sourceUrl: feedUrl, applicationId: identity.package,
    versionCode: identity.versionCode, versionName: identity.versionName, apkUrl,
    sha256: identity.apkSha256, certificateSha256: identity.signerSha256,
    sizeBytes: identity.sizeBytes, releaseNotes,
  }));
}

export function packUpdateEnvelope(payload, signature, publicKeySpkiB64) {
  const spki = Buffer.from(publicKeySpkiB64, 'base64');
  if (spki.toString('base64') !== publicKeySpkiB64) throw new Error('Invalid public key encoding');
  const publicKey = createPublicKey({ key: spki, format: 'der', type: 'spki' });
  const bits = publicKey.asymmetricKeyDetails?.modulusLength;
  if (publicKey.asymmetricKeyType !== 'rsa' || !bits || bits < 3072 || bits > 4096 ||
      payload.length < 1 || payload.length > maxEnvelopeBytes ||
      signature.length !== Math.ceil(bits / 8) || !verify('sha256', payload, publicKey, signature)) {
    throw new Error('Invalid update signature');
  }
  const envelope = Buffer.from(JSON.stringify({ payload: payload.toString('base64'),
    signature: signature.toString('base64') }) + '\n');
  if (envelope.length > maxEnvelopeBytes) throw new Error('Update envelope too large');
  return envelope;
}

function main([command, ...args]) {
  const names = command === 'payload'
    ? ['identity', 'apk', 'previous-version', 'expected-signer', 'feed', 'apk-url', 'notes', 'out']
    : ['payload', 'signature', 'public-key', 'out'];
  if (command !== 'payload' && command !== 'envelope') throw new Error('Choose payload or envelope');
  const options = Object.fromEntries(names.map(name => [name, { type: 'string' }]));
  const { values } = parseArgs({ args, options });
  if (names.some(name => !values[name])) throw new Error('Missing update manifest argument');

  let output;
  if (command === 'payload') {
    if (statSync(values.apk).size > maxApkBytes || !/^\d+$/.test(values['previous-version'])) {
      throw new Error('Invalid APK or version');
    }
    const apk = readFileSync(values.apk);
    try {
      output = prepareUpdatePayload(JSON.parse(readFileSync(values.identity, 'utf8')), apk, {
        previousVersion: Number(values['previous-version']), expectedSigner: values['expected-signer'],
        feedUrl: values.feed, apkUrl: values['apk-url'], notes: readFileSync(values.notes, 'utf8'),
      });
    } finally { apk.fill(0); }
  } else {
    output = packUpdateEnvelope(readFileSync(values.payload), readFileSync(values.signature),
      readFileSync(values['public-key'], 'utf8').trim());
  }
  writeFileSync(values.out, output, { flag: 'wx', mode: 0o600 });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try { main(process.argv.slice(2)); }
  catch { console.error('FORMA update manifest validation failed'); process.exitCode = 1; }
}
