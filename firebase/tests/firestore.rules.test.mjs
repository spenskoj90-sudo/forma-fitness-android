import { before, after, beforeEach, test } from "node:test";
import { strict as assert } from "node:assert";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import {
  initializeTestEnvironment, assertSucceeds, assertFails
} from "@firebase/rules-unit-testing";
import {
  Timestamp, serverTimestamp, doc, collection, collectionGroup, getDoc,
  getDocs, setDoc, updateDoc, deleteDoc
} from "firebase/firestore";

const projectId = "demo-forma-rules";
const rulesPath = fileURLToPath(new URL("../firestore.rules", import.meta.url));
let env;

function profileRef(db, uid = "alice", profileId = "p-one") {
  return doc(db, "accounts", uid, "profiles", profileId);
}
function measurementRef(db, uid = "alice", profileId = "p-one", id = "m-one") {
  return doc(db, "accounts", uid, "profiles", profileId, "measurements", id);
}
function workoutRef(db, uid = "alice", profileId = "p-one", id = "w-one") {
  return doc(db, "accounts", uid, "profiles", profileId, "workouts", id);
}
function client(uid) {
  return uid ? env.authenticatedContext(uid).firestore()
             : env.unauthenticatedContext().firestore();
}
function profile(name = "Synthetic athlete") {
  return { name, age: 30, heightCm: 178, weightKg: 75,
           experience: "BEGINNER", equipment: ["BODYWEIGHT"], recovery: 3 };
}
function measurement() {
  return { dateMs: 1700000000000, values: { waist: 80, weight: 75 } };
}
function workout() {
  return { startedMs: 1700000000000, finishedMs: 1700000001000,
           status: "DONE", sets: [], day: { day: 1 },
           activeSeconds: 1, painReported: false };
}
function envelope(uid, profileId, id, payload, patch = {}) {
  return {
    ownerUid: uid, profileId, id, schemaVersion: 1, revision: 1,
    opId: "op-" + id + "-1", createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(), deleted: false, payload, ...patch
  };
}
async function seedProfile(uid = "alice", profileId = "p-one") {
  // Fixture setup only. Production code NEVER uses disabled rules/admin writes.
  await env.withSecurityRulesDisabled(async context => {
    await setDoc(profileRef(context.firestore(), uid, profileId), envelope(
      uid, profileId, profileId, profile(), {
        createdAt: Timestamp.fromMillis(1), updatedAt: Timestamp.fromMillis(1)
      }
    ));
  });
}

before(async () => {
  // Firebase CLI must run a demo-only emulator; tests cannot hit production.
  assert.match(process.env.FIRESTORE_EMULATOR_HOST || "", /^(127\.0\.0\.1|localhost):\d+$/);
  env = await initializeTestEnvironment({
    projectId,
    firestore: { host: "127.0.0.1", port: 8080, rules: readFileSync(rulesPath, "utf8") }
  });
});
beforeEach(async () => { await env.clearFirestore(); });
after(async () => { if (env) await env.cleanup(); });

test("anonymous clients cannot get, list, create or enumerate any account", async () => {
  await seedProfile();
  const db = client(null);
  await assertFails(getDoc(profileRef(db)));
  await assertFails(getDocs(collection(db, "accounts", "alice", "profiles")));
  await assertFails(setDoc(profileRef(db, "anon", "p-two"),
    envelope("anon", "p-two", "p-two", profile())));
  await assertFails(getDoc(doc(db, "accounts", "alice")));
});

test("the verified UID reads and lists only its own profiles", async () => {
  await seedProfile();
  const a = client("alice");
  const b = client("bob");
  const own = await assertSucceeds(getDoc(profileRef(a)));
  assert.equal(own.data().ownerUid, "alice");
  assert.equal((await assertSucceeds(getDocs(collection(
    a, "accounts", "alice", "profiles")))).size, 1);
  await assertFails(getDoc(profileRef(b)));
  await assertFails(getDocs(collection(b, "accounts", "alice", "profiles")));
  await assertFails(getDocs(collectionGroup(b, "profiles")));
  await assertFails(getDoc(doc(a, "accounts", "alice")));
});

test("a client cannot spoof a path UID, document ID, owner field, or admin claim", async () => {
  const a = client("alice");
  const b = env.authenticatedContext("bob", { role: "admin" }).firestore();
  await assertFails(setDoc(profileRef(b, "alice"),
    envelope("alice", "p-one", "p-one", profile())));
  await assertFails(setDoc(profileRef(a), envelope("bob", "p-one", "p-one", profile())));
  await assertFails(setDoc(profileRef(a), envelope("alice", "p-one", "other", profile())));
  await assertFails(setDoc(profileRef(a), envelope("alice", "other", "p-one", profile())));
  await assertFails(setDoc(doc(a, "accounts", "alice"),
    { ownerUid: "alice" }));
  await assertFails(setDoc(doc(a, "accounts", "alice", "settings", "x"),
    { value: "no" }));
});

test("profile create is valid for the owner but extra fields and active sessions are denied", async () => {
  const a = client("alice");
  await assertSucceeds(setDoc(profileRef(a), envelope(
    "alice", "p-one", "p-one", profile())));
  await assertFails(setDoc(profileRef(a, "alice", "p-extra"), envelope(
    "alice", "p-extra", "p-extra", profile(), { injected: true })));
  await assertFails(setDoc(profileRef(a, "alice", "p-active"), envelope(
    "alice", "p-active", "p-active", { ...profile(), active: { phase: "WORK" } })));
  await assertFails(setDoc(profileRef(a, "alice", "p-short"), envelope(
    "alice", "p-short", "p-short", { ...profile(), name: "" })));
  await assertFails(setDoc(profileRef(a, "alice", "p-time"), envelope(
    "alice", "p-time", "p-time", profile(), {
      createdAt: Timestamp.fromMillis(1000)
    })));
});

test("only finalized workout and valid measurement data may be created under an active own profile", async () => {
  await seedProfile();
  const a = client("alice");
  const b = client("bob");
  await assertSucceeds(setDoc(measurementRef(a), envelope(
    "alice", "p-one", "m-one", measurement())));
  await assertSucceeds(setDoc(workoutRef(a), envelope(
    "alice", "p-one", "w-one", workout())));
  assert.equal((await assertSucceeds(getDoc(measurementRef(a)))).data().revision, 1);
  await assertFails(getDoc(measurementRef(b)));
  await assertFails(getDoc(workoutRef(b)));
  await assertFails(getDocs(collection(b, "accounts", "alice", "profiles", "p-one", "workouts")));
  await assertFails(setDoc(measurementRef(b), envelope(
    "alice", "p-one", "m-one", measurement())));
  await assertFails(setDoc(measurementRef(a, "alice", "p-missing", "m-two"), envelope(
    "alice", "p-missing", "m-two", measurement())));
  await assertFails(setDoc(measurementRef(a, "alice", "p-one", "m-three"), envelope(
    "alice", "p-wrong", "m-three", measurement())));
});

test("unknown metrics, nonnumeric measurements and unfinished workouts are rejected", async () => {
  await seedProfile();
  const a = client("alice");
  await assertFails(setDoc(measurementRef(a, "alice", "p-one", "m-bad"), envelope(
    "alice", "p-one", "m-bad", { dateMs: 1700000000000, values: { identity: 1 } })));
  await assertFails(setDoc(measurementRef(a, "alice", "p-one", "m-text"), envelope(
    "alice", "p-one", "m-text", { dateMs: 1700000000000, values: { waist: "secret" } })));
  await assertFails(setDoc(workoutRef(a, "alice", "p-one", "w-active"), envelope(
    "alice", "p-one", "w-active", { ...workout(), status: "WORK" })));
  await assertFails(setDoc(workoutRef(a, "alice", "p-one", "w-reversed"), envelope(
    "alice", "p-one", "w-reversed", { ...workout(), finishedMs: 1 })));
});

test("one revision at a time, exact no-op replay, immutable identity and timestamps", async () => {
  const a = client("alice");
  const ref = profileRef(a);
  await assertSucceeds(setDoc(ref, envelope("alice", "p-one", "p-one", profile())));
  const stored = (await getDoc(ref)).data();
  await assertSucceeds(setDoc(ref, stored)); // identical retry, no duplicate UUID
  await assertFails(updateDoc(ref, {
    revision: 3, opId: "op-3", updatedAt: serverTimestamp()
  }));
  await assertFails(updateDoc(ref, {
    revision: 2, opId: "op-2", ownerUid: "bob", updatedAt: serverTimestamp()
  }));
  await assertFails(updateDoc(ref, {
    revision: 2, opId: "op-2", createdAt: Timestamp.fromMillis(1),
    updatedAt: serverTimestamp()
  }));
  await assertFails(updateDoc(ref, {
    revision: 2, opId: "op-2", updatedAt: Timestamp.fromMillis(1)
  }));
  await assertSucceeds(updateDoc(ref, {
    revision: 2, opId: "op-2", updatedAt: serverTimestamp(),
    payload: { ...stored.payload, weightKg: 77 }
  }));
  await assertFails(updateDoc(ref, {
    revision: 2, opId: "op-stale", updatedAt: serverTimestamp()
  }));
});

test("physical delete and resurrecting tombstones are denied", async () => {
  const a = client("alice");
  const ref = profileRef(a);
  await assertSucceeds(setDoc(ref, envelope("alice", "p-one", "p-one", profile())));
  await assertFails(deleteDoc(ref));
  await assertSucceeds(updateDoc(ref, {
    deleted: true, revision: 2, opId: "op-delete", updatedAt: serverTimestamp()
  }));
  assert.equal((await getDoc(ref)).data().deleted, true);
  await assertFails(updateDoc(ref, {
    deleted: false, revision: 3, opId: "op-resurrect", updatedAt: serverTimestamp()
  }));
  await assertFails(setDoc(measurementRef(a), envelope(
    "alice", "p-one", "m-one", measurement())));
});

test("a batch cannot atomically create one allowed and one cross-account document", async () => {
  // An explicit multi-account request must never partially succeed.
  const { writeBatch } = await import("firebase/firestore");
  const a = client("alice");
  const batch = writeBatch(a);
  batch.set(profileRef(a, "alice", "p-good"), envelope(
    "alice", "p-good", "p-good", profile()));
  batch.set(profileRef(a, "bob", "p-injected"), envelope(
    "bob", "p-injected", "p-injected", profile()));
  await assertFails(batch.commit());
  assert.equal((await getDoc(profileRef(a, "alice", "p-good"))).exists(), false);
});
