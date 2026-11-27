import { execFileSync } from 'node:child_process'
import { createHash, randomBytes, randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'

const mode = process.argv[2]
if (process.argv.length !== 3 || !['--emulator', '--live'].includes(mode)) {
  console.error('Usage: node tests/provider-smoke.mjs --emulator|--live')
  process.exit(2)
}

const live = mode === '--live'
const project = live ? 'forma-ee2e9' : 'demo-forma-rules'
const database = `projects/${project}/databases/(default)`
const authHost = live ? 'https://identitytoolkit.googleapis.com' : 'http://127.0.0.1:9099/identitytoolkit.googleapis.com'
const firestoreHost = live ? 'https://firestore.googleapis.com' : 'http://127.0.0.1:8080'
const run = `forma-smoke-${randomUUID()}`
const attemptedAccounts = []
let adminToken
let apiKey

class ProbeError extends Error {}

function requireCheck(condition, label) {
  if (!condition) throw new ProbeError(label)
}

async function request(label, url, { token, body, status = 200, code } = {}) {
  let response
  let payload
  try {
    response = await fetch(url, {
      method: body === undefined ? 'GET' : 'POST',
      headers: {
        ...(token && { Authorization: `Bearer ${token}` }),
        ...(body !== undefined && { 'Content-Type': 'application/json' })
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(15000),
      redirect: 'error'
    })
    payload = await response.json()
  } catch {
    throw new ProbeError(`${label}: transport or response format failed`)
  }
  requireCheck(response.status === status && (!code || payload.error?.status === code),
    `${label}: unexpected HTTP ${response.status}`)
  return payload
}

function gcloud(args, label) {
  try {
    return execFileSync('gcloud', [...args, `--project=${project}`, '--quiet'],
      {
        encoding: 'utf8', timeout: 30000, stdio: ['ignore', 'pipe', 'pipe'],
        env: { ...process.env, CLOUDSDK_CORE_LOG_HTTP: 'false' }
      }).trim()
  } catch {
    throw new ProbeError(label)
  }
}

function findBrowserKey(keys) {
  const configuredKey = process.env.FORMA_FIREBASE_WEB_KEY_RESOURCE
  const matches = keys.filter(key => configuredKey ? key.name === configuredKey :
    key.displayName === 'Browser key (auto created by Firebase)')
  requireCheck(matches.length === 1 && /^projects\/[A-Za-z0-9-]+\/locations\/global\/keys\/[A-Za-z0-9-]+$/.test(matches[0].name),
    'Expected exactly one existing Firebase browser key')
  return matches[0].name
}

async function readBrowserKeyWithRest(token) {
  const target = await request('Firebase project metadata',
    `https://firebase.googleapis.com/v1beta1/projects/${project}`, { token })
  requireCheck(target.projectId === project && typeof target.projectNumber === 'string' &&
    /^[1-9][0-9]*$/.test(target.projectNumber), 'Firebase project identity mismatch')
  const parent = `projects/${target.projectNumber}/locations/global`
  const keyApi = 'https://apikeys.googleapis.com/v2'
  const keys = []
  const seenPageTokens = new Set()
  const url = new URL(`${keyApi}/${parent}/keys`)
  for (;;) {
    const page = await request('list Firebase browser keys', url, { token })
    keys.push(...(page.keys ?? []))
    if (!page.nextPageToken) break
    requireCheck(!seenPageTokens.has(page.nextPageToken), 'Firebase browser key pagination repeated')
    seenPageTokens.add(page.nextPageToken)
    url.searchParams.set('pageToken', page.nextPageToken)
  }
  const keyResource = findBrowserKey(keys)
  requireCheck(keyResource.startsWith(`${parent}/keys/`), 'Firebase browser key belongs to a different project')
  const key = await request('read Firebase browser key', `${keyApi}/${keyResource}/keyString`, { token })
  return key.keyString
}

async function preflight() {
  if (!live) {
    adminToken = 'owner'
    apiKey = 'emulator'
    return
  }
  const suppliedToken = process.env.FORMA_GOOGLE_ACCESS_TOKEN
  adminToken = suppliedToken === undefined
    ? gcloud(['auth', 'print-access-token'], 'Authorize gcloud for forma-ee2e9 or supply a short-lived FORMA_GOOGLE_ACCESS_TOKEN')
    : suppliedToken
  requireCheck(/^[A-Za-z0-9._~+/-]+=*$/.test(adminToken), 'Google OAuth access token is missing or malformed')
  const config = await request('Firestore database metadata', `${firestoreHost}/v1/${database}`, { token: adminToken })
  requireCheck(config.name === database && config.locationId === 'europe-west3',
    'Firestore database identity or region mismatch')

  const rulesHost = 'https://firebaserules.googleapis.com/v1'
  const release = await request('active Firestore rules release',
    `${rulesHost}/projects/${project}/releases/cloud.firestore`, { token: adminToken })
  requireCheck(new RegExp(`^projects/${project}/rulesets/[A-Za-z0-9_-]+$`).test(release.rulesetName),
    'Active Firestore ruleset does not belong to the requested project')
  const ruleset = await request('published Firestore rules', `${rulesHost}/${release.rulesetName}`, { token: adminToken })
  const source = readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8')
  const files = ruleset.source?.files
  requireCheck(files?.length === 1 && files[0].content === source,
    'Published Firestore rules differ from firebase/firestore.rules')
  console.log(`PASS active rules SHA-256 ${createHash('sha256').update(source).digest('hex')}`)

  if (suppliedToken === undefined) {
    const keys = JSON.parse(gcloud(['services', 'api-keys', 'list', '--format=json(name,displayName)'],
      'Cannot list the existing Firebase browser keys'))
    apiKey = gcloud(['services', 'api-keys', 'get-key-string', findBrowserKey(keys), '--format=value(keyString)'],
      'Cannot read the existing Firebase browser key')
  } else {
    apiKey = await readBrowserKeyWithRest(adminToken)
  }
  requireCheck(/^AIza[A-Za-z0-9_-]{35}$/.test(apiKey), 'Firebase browser key unavailable')
}

function admin(label, method, body) {
  return request(label, `${authHost}/v1/projects/${project}/accounts${method}?key=${apiKey}`,
    { token: adminToken, body })
}

async function createAccount(suffix, verified) {
  const uid = `${run}-${suffix}`
  const account = { uid, email: `${uid}@example.com`, password: `Aa9!${randomBytes(24).toString('hex')}` }
  attemptedAccounts.push(account)
  const result = await admin('create synthetic account', '', {
    localId: uid, email: account.email, password: account.password, emailVerified: verified
  })
  requireCheck(result.localId === uid, 'Provider returned an unexpected synthetic account UID')
  return account
}

async function signIn(account, verified) {
  const { idToken, localId } = await request('password sign-in',
    `${authHost}/v1/accounts:signInWithPassword?key=${apiKey}`,
    { body: { email: account.email, password: account.password, returnSecureToken: true } })
  let claims
  try {
    claims = JSON.parse(Buffer.from(idToken.split('.')[1], 'base64url').toString('utf8'))
  } catch {
    throw new ProbeError('Auth did not return a decodable ID token')
  }
  requireCheck(localId === account.uid && claims.sub === account.uid && claims.aud === project &&
    claims.iss === `https://securetoken.google.com/${project}` &&
    claims.email_verified === verified && claims.firebase?.sign_in_provider === 'password',
  'Firebase ID token claims do not match the expected account and project')
  return idToken
}

function field(value) {
  if (value === null) return { nullValue: null }
  if (typeof value === 'boolean') return { booleanValue: value }
  if (typeof value === 'string') return { stringValue: value }
  if (Number.isInteger(value)) return { integerValue: String(value) }
  if (Array.isArray(value)) return { arrayValue: { values: value.map(field) } }
  return { mapValue: { fields: Object.fromEntries(Object.entries(value).map(([k, v]) => [k, field(v)])) } }
}

const documentsUrl = `${firestoreHost}/v1/${database}/documents`
const profileId = 'provider-smoke'
const collection = account => `/accounts/${account.uid}/profiles`
const profilePath = account => `${collection(account)}/${profileId}`
const measurementPath = account => `${profilePath(account)}/measurements/provider-smoke`

function createDocument(label, target, id, remainder, payload, token, denied = false) {
  const path = profilePath(target) + remainder
  const data = {
    ownerUid: target.uid, profileId, id, schemaVersion: 1, revision: 1,
    opId: randomUUID(), deleted: false, payload
  }
  return request(label, `${documentsUrl}:commit`, {
    token,
    body: { writes: [{
      update: { name: `${database}/documents${path}`, fields: field(data).mapValue.fields },
      currentDocument: { exists: false },
      updateTransforms: ['createdAt', 'updatedAt'].map(fieldPath => ({ fieldPath, setToServerValue: 'REQUEST_TIME' }))
    }] },
    ...(denied && { status: 403, code: 'PERMISSION_DENIED' })
  })
}

async function verifyIsolation() {
  const a = await createAccount('a', false)
  const b = await createAccount('b', true)
  const unverified = await signIn(a, false)
  const tokenB = await signIn(b, true)
  const payload = {
    name: 'FORMA synthetic profile A', age: 40, heightCm: 180, weightKg: 80,
    experience: 'BEGINNER', equipment: ['BODYWEIGHT'], limitations: '',
    primary: 'FITNESS', secondary: null, priorities: [], days: [1], recovery: 3
  }
  await createDocument('unverified profile create', a, profileId, '', payload, unverified, true)
  await admin('verify synthetic account', ':update', { localId: a.uid, emailVerified: true })
  const tokenA = await signIn(a, true)
  console.log('PASS two Email/Password sign-ins with verified claims and unverified write denial')

  const measurement = { dateMs: 0, values: { weight: 80 } }
  await createDocument('A cannot create a profile for B', b, profileId, '', payload, tokenA, true)
  for (const [account, ownToken, otherToken, name] of [
    [a, tokenA, tokenB, payload.name],
    [b, tokenB, tokenA, 'FORMA synthetic profile B']
  ]) {
    await createDocument('owner profile create', account, profileId, '', { ...payload, name }, ownToken)
    const ownProfile = await request('owner profile read', documentsUrl + profilePath(account), { token: ownToken })
    requireCheck(ownProfile.fields?.payload?.mapValue?.fields?.name?.stringValue === name,
      'Owner received unexpected profile payload')
    const ownList = await request('owner profile list', documentsUrl + collection(account), { token: ownToken })
    requireCheck(ownList.documents?.length === 1 && ownList.documents[0].name === ownProfile.name,
      'Owner listing did not contain only the seeded profile')
    const denied = { token: otherToken, status: 403, code: 'PERMISSION_DENIED' }
    await request('cross-account profile read', documentsUrl + profilePath(account), denied)
    await request('cross-account profile list', documentsUrl + collection(account), denied)
    await createDocument('cross-account measurement create', account, 'provider-smoke',
      '/measurements/provider-smoke', measurement, otherToken, true)
    await createDocument('owner measurement create', account, 'provider-smoke',
      '/measurements/provider-smoke', measurement, ownToken)
    const ownMeasure = await request('owner measurement read', documentsUrl + measurementPath(account), { token: ownToken })
    requireCheck(ownMeasure.fields?.ownerUid?.stringValue === account.uid, 'Owner received unexpected measurement')
    await request('cross-account measurement read', documentsUrl + measurementPath(account), denied)
  }
  console.log('PASS isolated profile create, read and list and measurement create and read for both UIDs')
}

async function disableFixtures() {
  let failures = 0
  for (const { uid, email } of attemptedAccounts) {
    try {
      const lookup = () => admin('look up exact synthetic account', ':lookup', { localId: [uid] })
      const { users } = await lookup()
      if (!users?.length) continue
      requireCheck(users.length === 1 && users[0].localId === uid && users[0].email === email,
        'Cleanup account identity mismatch')
      await admin('disable synthetic account', ':update', { localId: uid, disableUser: true })
      requireCheck((await lookup()).users?.[0]?.disabled === true, 'Synthetic disable not confirmed')
    } catch {
      failures++
    }
  }
  requireCheck(failures === 0, `Could not confirm fixture account cleanup for ${run}`)
  if (attemptedAccounts.length) console.log('PASS no synthetic test account left enabled')
}

try {
  await preflight()
  console.log(`START ${live ? 'LIVE' : 'EMULATOR'} ${run}`)
  try {
    await verifyIsolation()
  } finally {
    await disableFixtures()
  }
  console.log(`PASS ${live ? 'LIVE' : 'EMULATOR'} Firebase two-account provider smoke`)
} catch (error) {
  console.error(`FAIL ${error instanceof ProbeError ? error.message : 'unexpected probe error'}`)
  process.exitCode = 1
}
