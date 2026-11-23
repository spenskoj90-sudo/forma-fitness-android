package ru.forma.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.forma.domain.Measurement
import ru.forma.domain.Profile

@RunWith(AndroidJUnit4::class)
class FirebaseSyncEmulatorTest {
    @get:Rule val compose = createEmptyComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test fun accountWorkoutScopeSurvivesReentryAndRejectsOldCommands() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val forma = context.applicationContext as FormaApp
        check(FirebaseApp.getApps(context).none { it.name == FirebaseApp.DEFAULT_APP_NAME })
        val firebase = checkNotNull(FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setProjectId(projectId)
            .setApplicationId("1:123456789012:android:1234567890123456")
            .setApiKey("demo-forma-emulator-key")
            .build()))
        val auth = FirebaseAuth.getInstance(firebase).apply { useEmulator(emulatorHost, 9099) }
        val account = checkNotNull(forma.firebaseAccount)
        val identity = account.identity
        val password = UUID.randomUUID().toString()
        val firstEmail = "${UUID.randomUUID()}@example.test"
        val secondEmail = "${UUID.randomUUID()}@example.test"
        val a = Profile(name = "Профиль первого аккаунта")
        val b = Profile(name = "Профиль второго аккаунта")
        val guest = Profile(name = "Локальный профиль")

        try {
            listOf(a, b, guest).forEach { forma.repository.saveProfile(it) }
            forma.settings.select(a.id)
            suspend fun bind(email: String, profile: Profile) {
                account.register(email, password)
                verifyEmail(auth, email)
                account.refreshVerification()
                forma.repository.bindAndQueueInitialSnapshotForVerifiedConsent(profile.id, identity, 1L)
                account.signOut()
            }
            account.register(firstEmail, password)
            verifyEmail(auth, firstEmail)
            account.refreshVerification()
            account.signOut()
            bind(secondEmail, b)
            account.signIn(firstEmail, password)

            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                compose.waitUntilExactlyOneExists(hasText("Профиль"), 15_000)
                compose.onNodeWithText("Профиль").performClick()
                compose.onNodeWithText("Аккаунт FORMA").performScrollTo().performClick()
                compose.onNodeWithText("Связать выбранный профиль").performScrollTo().performClick()
                compose.onNodeWithText(firstEmail, substring = true).assertExists()
                compose.onNodeWithText("Отмена").performClick()
                assertNotNull(forma.repository.load(a.id))
                assertNull(forma.database.sync().binding(a.id))
                compose.onNodeWithText("Связать выбранный профиль").performScrollTo().performClick()
                compose.onNodeWithText("Согласен и связываю профиль").performClick()
                compose.waitUntil(15_000) { runBlocking { forma.database.sync().binding(a.id) != null } }
                assertNull(forma.repository.load(a.id))

                fun enterScope(): AccountWorkoutScope {
                    compose.waitUntilExactlyOneExists(hasText("Профили аккаунта") and hasClickAction(), 15_000)
                    compose.onNodeWithText("Профили аккаунта").performScrollTo().performClick()
                    compose.waitUntil(15_000) { forma.accountWorkouts.value != null }
                    return checkNotNull(forma.accountWorkouts.value)
                }

                val old = enterScope()
                compose.waitUntilExactlyOneExists(hasText("✓ ${a.name}"), 15_000)
                compose.onNodeWithText(b.name, substring = true).assertDoesNotExist()
                compose.onNodeWithText(guest.name).assertDoesNotExist()
                compose.onNodeWithText("Тренировки").performScrollTo().performClick()
                compose.onAllNodesWithText("Начать тренировку").onFirst().performScrollTo().performClick()
                compose.waitUntil(15_000) { forma.serviceRunning }
                val session = checkNotNull(old.repository.load(a.id)?.active)
                val oldRequest = Intent(forma, WorkoutService::class.java).setAction("STOP")
                    .putExtra("profile", a.id).putExtra("session", session.id)
                    .putExtra("workoutScope", old.id)

                account.signOut()
                compose.waitUntil(15_000) { forma.accountWorkouts.value == null && !forma.serviceRunning }
                compose.onNodeWithText(a.name, substring = true).assertDoesNotExist()
                account.signIn(secondEmail, password)
                val second = enterScope()
                compose.waitUntilExactlyOneExists(hasText("✓ ${b.name}"), 15_000)
                assertEquals(listOf(b), second.repository.profiles.first())
                assertNull(second.repository.load(a.id))
                assertNull(forma.resolveWorkoutRepository(old.id))
                assertTrue(runCatching { old.repository.load(a.id) }.exceptionOrNull() is LocalWorkoutAccessRevoked)
                activity.onActivity { ContextCompat.startForegroundService(it, oldRequest) }
                compose.waitForIdle()
                compose.onNodeWithText(a.name, substring = true).assertDoesNotExist()

                account.signOut()
                compose.waitUntil(15_000) { forma.accountWorkouts.value == null }
                account.signIn(firstEmail, password)
                val reopened = enterScope()
                assertNotEquals(old.id, reopened.id)
                assertNull(forma.resolveWorkoutRepository(old.id))
                compose.waitUntilExactlyOneExists(hasText("Продолжить сопровождение"), 15_000)
                val paused = checkNotNull(reopened.repository.load(a.id)?.active)
                assertEquals(session.id, paused.id)
                assertTrue(paused.paused)
                compose.onNodeWithText("Остановить тренировку").performScrollTo().performClick()
                compose.onNodeWithText("Подтвердить остановку").performClick()
                compose.waitUntilExactlyOneExists(hasText("Сохранить и открыть историю"), 15_000)
                compose.onNodeWithText("Сохранить и открыть историю").performScrollTo().performClick()
                compose.waitUntil(15_000) {
                    runBlocking { reopened.repository.load(a.id)?.history?.singleOrNull()?.id == session.id }
                }
                assertNull(forma.repository.load(a.id))
            }
        } finally {
            account.signOut()
            forma.accountWorkouts.value?.let { forma.closeAccountWorkouts(it.id) }
            identity.close()
            firebase.delete()
        }
    }

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
            val account = FirebaseAccount(app)
            val identity = account.identity
            val remote = FirestoreSyncRemote(app, identity)
            val db = Room.inMemoryDatabaseBuilder(context, FormaDatabase::class.java).build()
            try {
                val coordinator = SyncDeliveryCoordinator(db, identity, remote)
                val repo = ProfileRepository(db)
                val firstEmail = "${UUID.randomUUID()}@example.test"
                val password = UUID.randomUUID().toString()
                val measurement = Measurement(dateMs = 0L, values = mapOf("weight" to 75.0))
                val profile = Profile(name = "Synthetic profile", recovery = 4, measurements = listOf(measurement))
                repo.saveProfile(profile)
                assertNull(repo.profilesForVerifiedAccount(identity))
                assertEquals(SyncDeliveryStep.NO_AUTH, coordinator.deliverOne())

                account.register(firstEmail, password)
                assertEquals(AccountSession.EMAIL_UNVERIFIED, account.currentSession)
                assertNull(identity.currentLease()) // The user has not verified their email yet.
                verifyEmail(auth, firstEmail)
                account.refreshVerification()
                assertEquals(AccountSession.EMAIL_VERIFIED, account.currentSession)
                val first = checkNotNull(identity.currentLease())
                val firstDocument = firestore.document("accounts/${first.uid}/profiles/${profile.id}")
                assertEquals(VerifiedAccountProfiles(first, emptyList()), repo.profilesForVerifiedAccount(identity))
                assertEquals(SyncDeliveryStep.EMPTY, coordinator.deliverOne())
                assertFalse(firstDocument.get(Source.SERVER).await().exists())

                repo.bindAndQueueInitialSnapshotForVerifiedConsent(profile.id, identity, 1L)
                val accountProfiles = checkNotNull(repo.profilesForVerifiedAccount(identity))
                assertEquals(VerifiedAccountProfiles(first, listOf(profile)), accountProfiles)
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

                repo.mutateForVerifiedAccount(profile.id, accountProfiles.lease, identity) {
                    it.copy(recovery = 3, measurements = emptyList())
                }
                val parentUpdate = checkNotNull(db.sync().nextPendingForAccount(first.uid))
                assertEquals("profile", parentUpdate.kind)
                assertEquals(2, parentUpdate.revision)
                assertEquals(SyncDeliveryStep.ACKED, coordinator.deliverOne())
                val tombstone = checkNotNull(db.sync().nextPendingForAccount(first.uid))
                assertTrue(tombstone.tombstone)
                val predecessor = checkNotNull(remote.read(first, measurementRow))
                assertFalse(remote.compareAndCommit(first, tombstone, predecessor, firstEcho))
                assertEquals(SyncDeliveryStep.ACKED, coordinator.deliverOne())
                assertTrue(checkNotNull(remote.read(first, tombstone)).tombstone)

                account.signOut()
                assertEquals(AccountSession.SIGNED_OUT, account.currentSession)
                assertNull(repo.profilesForVerifiedAccount(identity))
                assertEquals(SyncDeliveryStep.NO_AUTH, coordinator.deliverOne())
                assertNull(ProfileRepository.forGuest(db).load(profile.id))
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                expectFirestoreError(FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    firstDocument.get(Source.SERVER).await()
                }
                val secondEmail = "${UUID.randomUUID()}@example.test"
                account.register(secondEmail, password)
                verifyEmail(auth, secondEmail)
                account.refreshVerification()
                val second = checkNotNull(identity.currentLease())
                assertNotEquals(first.uid, second.uid)
                assertEquals(VerifiedAccountProfiles(second, emptyList()), repo.profilesForVerifiedAccount(identity))
                try {
                    repo.mutateForVerifiedAccount(profile.id, second, identity) {
                        throw AssertionError("A foreign profile reached the edit callback")
                    }
                    fail("Expected the foreign profile edit to be denied")
                } catch (error: IllegalStateException) {
                    assertEquals("Профиль не найден", error.message)
                }
                assertEquals(SyncDeliveryStep.EMPTY, coordinator.deliverOne())
                expectFirestoreError(FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    firstDocument.get(Source.SERVER).await()
                }
                expectFirestoreError(FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    firstDocument.update(mapOf("deleted" to true, "revision" to 3L,
                        "opId" to UUID.randomUUID().toString(),
                        "updatedAt" to FieldValue.serverTimestamp())).await()
                }

                account.signOut()
                account.signIn(firstEmail, password)
                assertEquals(AccountSession.EMAIL_VERIFIED, account.currentSession)
                val restored = checkNotNull(identity.currentLease())
                assertEquals(first.uid, restored.uid)
                assertNotEquals(first.generation, restored.generation)
                assertEquals(
                    VerifiedAccountProfiles(restored, listOf(profile.copy(recovery = 3, measurements = emptyList()))),
                    repo.profilesForVerifiedAccount(identity)
                )
                assertEquals(SyncDeliveryStep.EMPTY, coordinator.deliverOne())
                assertTrue(checkNotNull(remote.read(restored, tombstone)).tombstone)
                account.sendPasswordReset(firstEmail)
            } finally {
                db.close()
                account.signOut()
                identity.close()
                firestore.terminate().await()
                app.delete()
            }
        }
    }

    private suspend fun verifyEmail(auth: FirebaseAuth, email: String) {
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
