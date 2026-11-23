package ru.forma.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.forma.domain.Measurement
import ru.forma.domain.Profile

@RunWith(AndroidJUnit4::class)
class FirebaseSyncEmulatorTest {
    @Test fun verifiedAccountsDeliverWithoutLeakingAcrossSignOutOrAccountSwitch() = runBlocking {
        withTimeout(90_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val app = FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
                .setProjectId(projectId)
                .setApplicationId("1:123456789012:android:1234567890123456")
                .setApiKey("demo-forma-emulator-key")
                .build(), "forma-emulator-${UUID.randomUUID()}")
            val auth = FirebaseAuth.getInstance(app).apply { useEmulator(emulatorHost, 9099) }
            val firestore = FirebaseFirestore.getInstance(app).apply { useEmulator(emulatorHost, 8080) }
            val identity = FirebaseSyncIdentity(app)
            val remote = FirestoreSyncRemote(app, identity)
            val db = Room.inMemoryDatabaseBuilder(context, FormaDatabase::class.java).build()
            try {
                val coordinator = SyncDeliveryCoordinator(db, identity, remote)
                val repo = ProfileRepository(db)
                val firstEmail = "${UUID.randomUUID()}@example.test"
                val password = UUID.randomUUID().toString()
                val measurement = Measurement(dateMs = 0L, values = mapOf("weight" to 75.0))
                val profile = Profile(name = "Synthetic profile", measurements = listOf(measurement))
                repo.saveProfile(profile)
                assertEquals(SyncDeliveryStep.NO_AUTH, coordinator.deliverOne())

                auth.createUserWithEmailAndPassword(firstEmail, password).await()
                assertNull(identity.currentLease()) // The user has not verified their email yet.
                verifyEmail(auth, firstEmail)
                val first = checkNotNull(identity.currentLease())
                val firstDocument = firestore.document("accounts/${first.uid}/profiles/${profile.id}")
                assertEquals(SyncDeliveryStep.EMPTY, coordinator.deliverOne())
                assertFalse(firstDocument.get(Source.SERVER).await().exists())

                repo.bindAndQueueInitialSnapshotForVerifiedConsent(profile.id, identity, 1L)
                val profileRow = checkNotNull(db.sync().nextPendingForAccount(first.uid))
                assertEquals(SyncDeliveryStep.ACKED, coordinator.deliverOne())
                val firstEcho = checkNotNull(remote.read(first, profileRow))
                assertTrue(firstEcho.matches(profileRow))

                val measurementRow = checkNotNull(db.sync().nextPendingForAccount(first.uid))
                firestore.disableNetwork().await()
                try {
                    expectFirestoreError(FirebaseFirestoreException.Code.UNAVAILABLE) { coordinator.deliverOne() }
                    assertEquals(measurementRow, db.sync().nextPendingForAccount(first.uid))
                } finally {
                    firestore.enableNetwork().await()
                }
                assertEquals(SyncDeliveryStep.ACKED, coordinator.deliverOne())
                assertTrue(checkNotNull(remote.read(first, measurementRow)).matches(measurementRow))

                repo.mutate(profile.id) { it.copy(recovery = 3, measurements = emptyList()) }
                assertEquals(SyncDeliveryStep.ACKED, coordinator.deliverOne())
                val tombstone = checkNotNull(db.sync().nextPendingForAccount(first.uid))
                val predecessor = checkNotNull(remote.read(first, measurementRow))
                assertFalse(remote.compareAndCommit(first, tombstone, predecessor, firstEcho))
                assertEquals(SyncDeliveryStep.ACKED, coordinator.deliverOne())
                assertTrue(checkNotNull(remote.read(first, tombstone)).tombstone)

                identity.signOut()
                assertEquals(SyncDeliveryStep.NO_AUTH, coordinator.deliverOne())
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                expectFirestoreError(FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    firstDocument.get(Source.SERVER).await()
                }
                val secondEmail = "${UUID.randomUUID()}@example.test"
                auth.createUserWithEmailAndPassword(secondEmail, password).await()
                verifyEmail(auth, secondEmail)
                val second = checkNotNull(identity.currentLease())
                assertNotEquals(first.uid, second.uid)
                assertEquals(SyncDeliveryStep.EMPTY, coordinator.deliverOne())
                expectFirestoreError(FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    firstDocument.get(Source.SERVER).await()
                }
                expectFirestoreError(FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    firstDocument.update(mapOf("deleted" to true, "revision" to 3L,
                        "opId" to UUID.randomUUID().toString(),
                        "updatedAt" to FieldValue.serverTimestamp())).await()
                }

                identity.signOut()
                auth.signInWithEmailAndPassword(firstEmail, password).await()
                val restored = checkNotNull(identity.currentLease())
                assertEquals(first.uid, restored.uid)
                assertNotEquals(first.generation, restored.generation)
                assertEquals(SyncDeliveryStep.EMPTY, coordinator.deliverOne())
                assertTrue(checkNotNull(remote.read(restored, tombstone)).tombstone)
            } finally {
                db.close()
                identity.signOut()
                identity.close()
                firestore.terminate().await()
                app.delete()
            }
        }
    }

    private suspend fun verifyEmail(auth: FirebaseAuth, email: String) {
        checkNotNull(auth.currentUser).sendEmailVerification().await()
        val code = withContext(Dispatchers.IO) {
            val connection = URL("http://$emulatorHost:9099/emulator/v1/projects/$projectId/oobCodes")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            try {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val codes = JSONObject(body).getJSONArray("oobCodes")
                (0 until codes.length()).map(codes::getJSONObject)
                    .single { it.getString("email") == email }.getString("oobCode")
            } finally { connection.disconnect() }
        }
        auth.applyActionCode(code).await()
        checkNotNull(auth.currentUser).getIdToken(true).await()
    }

    private suspend fun expectFirestoreError(code: FirebaseFirestoreException.Code, operation: suspend () -> Unit) {
        try {
            operation()
            fail("Expected Firestore $code")
        } catch (error: FirebaseFirestoreException) {
            assertEquals(code, error.code)
        }
    }

    private companion object {
        const val projectId = "demo-forma-rules"
        const val emulatorHost = "127.0.0.1"
    }
}
