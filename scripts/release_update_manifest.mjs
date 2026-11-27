#!/usr/bin/env node
import { createHash, createPublicKey, verify } from 'node:crypto';
import { readFileSync, statSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';

const maxApkBytes = 200 * 1024 * 1024;
const maxEnvelopeBytes = 16 * 1024;
const maxVersionCode = 2_100_000_000;
const apkContentType = 'application/vnd.android.package-archive';
const downloadDeadlineMs = 120_000;

function isSha256(value) {
  return typeof value === 'string' && value.length === 64 && /^[a-f0-9]+$/.test(value);
}

function isReleaseVersion(code, name) {
  return Number.isInteger(code) && code > 0 && code <= maxVersionCode &&
    typeof name === 'string' && name.length <= 80 && name === name.trim() &&
    /^[0-9]+\.[0-9]+\.[0-9]+(?:[-.][0-9A-Za-z.-]+)?$/.test(name);
}

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
  if (!isSha256(expectedSigner) || identity.package !== 'ru.forma.fitness' ||
      identity.v2 !== true || identity.signerSha256 !== expectedSigner) {
    throw new Error('APK identity mismatch');
  }
  if (!Number.isInteger(previousVersion) || previousVersion < 0 ||
      !isReleaseVersion(identity.versionCode, identity.versionName) || identity.versionCode <= previousVersion) {
    throw new Error('Invalid update version');
  }
  if (!Buffer.isBuffer(apk) || apk.length < 1 || apk.length > maxApkBytes ||
      apk.length !== identity.sizeBytes ||
      createHash('sha256').update(apk).digest('hex') !== identity.apkSha256) {
    throw new Error('APK bytes mismatch');
  }
  const feed = httpsUrl(feedUrl);
  const download = httpsUrl(apkUrl);
  if (feed.origin !== download.origin || feed.href === download.href ||
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

function updatePublicKey(publicKeySpkiB64) {
  const spki = Buffer.from(publicKeySpkiB64, 'base64');
  if (spki.toString('base64') !== publicKeySpkiB64) throw new Error('Invalid public key encoding');
  const publicKey = createPublicKey({ key: spki, format: 'der', type: 'spki' });
  const bits = publicKey.asymmetricKeyDetails?.modulusLength;
  if (publicKey.asymmetricKeyType !== 'rsa' || !bits || bits < 3072 || bits > 4096 ||
      !spki.equals(publicKey.export({ format: 'der', type: 'spki' }))) {
    throw new Error('Invalid update public key');
  }
  return publicKey;
}

export function packUpdateEnvelope(payload, signature, publicKeySpkiB64) {
  const publicKey = updatePublicKey(publicKeySpkiB64);
  const bits = publicKey.asymmetricKeyDetails.modulusLength;
  if (payload.length < 1 || payload.length > maxEnvelopeBytes ||
      signature.length !== Math.ceil(bits / 8) || !verify('sha256', payload, publicKey, signature)) {
    throw new Error('Invalid update signature');
  }
  const envelope = Buffer.from(JSON.stringify({ payload: payload.toString('base64'),
    signature: signature.toString('base64') }) + '\n');
  if (envelope.length > maxEnvelopeBytes) throw new Error('Update envelope too large');
  return envelope;
}

async function verifyHostedBody(fetchResponse, label, url, accept, expectedSize, expectedHash) {
  const response = await fetchResponse(url, {
    headers: { Accept: accept, 'Cache-Control': 'no-cache' },
    redirect: 'error',
    signal: AbortSignal.timeout(downloadDeadlineMs),
  });
  if (response.status !== 200 || !response.body) {
    await response.body?.cancel();
    throw new Error(`${label} is unavailable`);
  }
  const hash = createHash('sha256');
  let size = 0;
  for await (const chunk of response.body) {
    size += chunk.byteLength;
    if (size > expectedSize) throw new Error(`${label} exceeds the approved size`);
    hash.update(chunk);
  }
  if (size !== expectedSize || hash.digest('hex') !== expectedHash) {
    throw new Error(`${label} differs from the approved bytes`);
  }
}

export async function verifyHostedUpdate(envelope, pin, feedUrl, { apkOnly = false, fetchResponse = fetch } = {}) {
  if (!Buffer.isBuffer(envelope) || envelope.length < 1 || envelope.length > maxEnvelopeBytes) {
    throw new Error('Invalid local update envelope size');
  }
  const { payload, signature } = JSON.parse(envelope);
  const signedBytes = Buffer.from(payload, 'base64');
  if (!envelope.equals(packUpdateEnvelope(signedBytes, Buffer.from(signature, 'base64'), pin))) {
    throw new Error('Local update envelope differs from the generated form');
  }
  const release = JSON.parse(signedBytes);
  const feed = httpsUrl(feedUrl);
  const apk = httpsUrl(release.apkUrl);
  const notes = typeof release.releaseNotes === 'string' ? release.releaseNotes.trim() : '';
  if (release.channel !== 'stable' || release.applicationId !== 'ru.forma.fitness' ||
      release.sourceUrl !== feedUrl || apk.origin !== feed.origin || apk.href === feed.href ||
      !isReleaseVersion(release.versionCode, release.versionName) || notes.length < 1 || notes.length > 500 ||
      !Number.isInteger(release.sizeBytes) || release.sizeBytes < 1 || release.sizeBytes > maxApkBytes ||
      !isSha256(release.sha256) || !isSha256(release.certificateSha256)) {
    throw new Error('Signed envelope does not describe an APK at the approved update source');
  }

  if (!apkOnly) {
    await verifyHostedBody(fetchResponse, 'Hosted feed', feed, 'application/json', envelope.length,
      createHash('sha256').update(envelope).digest('hex'));
  }
  await verifyHostedBody(fetchResponse, 'Hosted APK', apk, apkContentType, release.sizeBytes, release.sha256);
}

async function main([command, ...args]) {
  if (command === 'check-config' && args.length === 0) {
    httpsUrl(process.env.FORMA_UPDATE_FEED_URL ?? '');
    updatePublicKey(process.env.FORMA_UPDATE_MANIFEST_PUBKEY ?? '');
    console.log('PASS signed update channel configuration');
    return;
  }
  const commands = {
    payload: ['identity', 'apk', 'previous-version', 'expected-signer', 'feed', 'apk-url', 'notes', 'out'],
    envelope: ['payload', 'signature', 'public-key', 'out'],
    'verify-hosted': ['envelope', 'public-key', 'feed'],
  };
  if (!Object.hasOwn(commands, command)) throw new Error('Choose check-config, payload, envelope or verify-hosted');
  const names = commands[command];
  const options = Object.fromEntries(names.map(name => [name, { type: 'string' }]));
  if (command === 'verify-hosted') options['apk-only'] = { type: 'boolean' };
  const { values } = parseArgs({ args, options });
  if (names.some(name => !values[name])) throw new Error('Missing update manifest argument');

  if (command === 'verify-hosted') {
    if (statSync(values.envelope).size > maxEnvelopeBytes) throw new Error('Update envelope too large');
    await verifyHostedUpdate(readFileSync(values.envelope), readFileSync(values['public-key'], 'utf8').trim(),
      values.feed, { apkOnly: values['apk-only'] });
    console.log(values['apk-only'] ? 'PASS hosted APK' : 'PASS hosted signed feed and APK');
    return;
  }

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
  try { await main(process.argv.slice(2)); }
  catch { console.error('FORMA update manifest validation failed'); process.exitCode = 1; }
}
