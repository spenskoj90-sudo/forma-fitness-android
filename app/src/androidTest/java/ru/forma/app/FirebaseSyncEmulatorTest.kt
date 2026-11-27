package ru.forma.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
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
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
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
import ru.forma.domain.finishSession
import ru.forma.domain.generateProgram
import ru.forma.domain.startSession
import ru.forma.domain.stopSession

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
        val firestore = FirebaseFirestore.getInstance(firebase).apply { useEmulator(emulatorHost, 8080) }
        check(forma.resources.getBoolean(R.bool.forma_cloud_sync_enabled))
        WorkManagerTestInitHelper.initializeTestWorkManager(forma,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
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
                compose.onNodeWithText("Проверить сеанс").performScrollTo().performClick()
                compose.waitUntilExactlyOneExists(hasText("Сеанс подтверждён сервером."), 15_000)
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
                compose.onNodeWithText("Сохранить защищённую копию профиля").assertExists()
                verifyScheduledCloudAccess(forma, firestore, old)
                compose.onNodeWithText("Показать облачные профили").performScrollTo().performClick()
                compose.waitUntilExactlyOneExists(hasText("Восстановить «${a.name}»") and hasClickAction(), 15_000)
                compose.onNodeWithText("Восстановить «${a.name}»").performScrollTo().performClick()
                compose.onNodeWithText("Согласен и восстанавливаю").performClick()
                compose.waitUntilExactlyOneExists(hasText("Восстановлено записей — 0. Требуют разбора — 0."), 15_000)
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

                val queued = checkNotNull(forma.database.sync().nextPendingForAccount(reopened.lease.uid))
                val saved = checkNotNull(reopened.repository.load(a.id))
                disableEmulatorAccount(auth, firstEmail, reopened.lease.uid)
                // Disabling an account does not push a change to the cached local session.
                assertEquals(reopened.lease, identity.localLease())
                assertEquals(DeliveryBatchResult.COMPLETE, checkNotNull(forma.cloudSync).deliver())
                compose.waitUntil(15_000) { forma.accountWorkouts.value == null }
                compose.onNodeWithText(a.name, substring = true).assertDoesNotExist()
                assertNull(identity.localLease())
                assertNull(forma.resolveWorkoutRepository(reopened.id))
                assertTrue(runCatching { reopened.repository.load(a.id) }.exceptionOrNull() is LocalWorkoutAccessRevoked)
                assertEquals(saved, ProfileRepository(forma.database).load(a.id))
                assertEquals(queued, forma.database.sync().nextPendingForAccount(reopened.lease.uid))
                assertNull(forma.repository.load(a.id))
                account.signIn(secondEmail, password)
                assertEquals(listOf(b), enterScope().repository.profiles.first())
            }
        } finally {
            account.signOut()
            forma.accountWorkouts.value?.let { forma.closeAccountWorkouts(it.id) }
            identity.close()
            firebase.delete()
        }
    }

    private suspend fun verifyScheduledCloudAccess(
        forma: FormaApp, firestore: FirebaseFirestore, scope: AccountWorkoutScope,
    ) {
        val manager = WorkManager.getInstance(forma)
        val request = withTimeout(15_000) {
            manager.getWorkInfosForUniqueWorkFlow(CloudDeliveryScheduler.IMMEDIATE)
                .mapNotNull { requests -> requests.firstOrNull { !it.state.isFinished } }.first()
        }
        checkNotNull(WorkManagerTestInitHelper.getTestDriver(forma)).setAllConstraintsMet(request.id)
        val result = withTimeout(30_000) { manager.getWorkInfoByIdFlow(request.id).first { it?.state?.isFinished == true } }
        assertEquals(WorkInfo.State.SUCCEEDED, result?.state)
        val profile = scope.repository.profiles.first().single()
        val uploaded = firestore.document("accounts/${scope.lease.uid}/profiles/${profile.id}").get(Source.SERVER).await()
        assertTrue(uploaded.exists())
        assertNull(forma.database.sync().nextPendingForAccount(scope.lease.uid))

        val measurement = Measurement(dateMs = 2_000L, values = mapOf("weight" to 77.0))
        val payload = FirestoreSyncPayload.document("measurement", restoreJson.encodeToString(measurement.cloudPayload()))
        uploaded.reference.collection("measurements").document(measurement.id).set(mapOf(
            "ownerUid" to scope.lease.uid, "profileId" to profile.id, "id" to measurement.id,
            "schemaVersion" to 1L, "revision" to 1L, "opId" to UUID.randomUUID().toString(),
            "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp(),
            "deleted" to false, "payload" to payload,
        )).await()
        val inbound = withTimeout(15_000) {
            manager.getWorkInfosForUniqueWorkFlow(CloudDeliveryScheduler.RECONCILE_IMMEDIATE)
                .mapNotNull { requests -> requests.firstOrNull { !it.state.isFinished } }.first()
        }
        checkNotNull(WorkManagerTestInitHelper.getTestDriver(forma)).setAllConstraintsMet(inbound.id)
        val fetched = withTimeout(30_000) {
            manager.getWorkInfoByIdFlow(inbound.id).first { it?.state?.isFinished == true }
        }
        assertEquals(WorkInfo.State.SUCCEEDED, fetched?.state)
        assertEquals(listOf(measurement), scope.repository.load(profile.id)?.measurements)
        assertEquals(measurement.id, forma.database.syncReview().cursor(scope.lease.uid, profile.id, "measurement")?.recordId)
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
                val batch = CloudDeliveryBatch(db, identity, remote)
                firestore.disableNetwork().await()
                try {
                    assertEquals(DeliveryBatchResult.RETRY, batch.run())
                    assertEquals(measurementRow, db.sync().nextPendingForAccount(first.uid))
                } finally {
                    firestore.enableNetwork().await()
                }
                assertEquals(DeliveryBatchResult.COMPLETE, batch.run())
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
                val cloudRestore = CloudProfileRestorer(db, identity, remote)
                assertTrue(cloudRestore.list(second).isEmpty())
                assertTrue(runCatching { cloudRestore.restore(first, profile.id) }.exceptionOrNull()
                    is LocalWorkoutAccessRevoked)
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
                verifyCloudRestore(context, restored, profile, db, identity, remote)
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

    private suspend fun verifyCloudRestore(
        context: android.content.Context, lease: VerifiedSyncLease, original: Profile, db: FormaDatabase,
        identity: FirebaseSyncIdentity, remote: FirestoreSyncRemote,
    ) {
        val repo = ProfileRepository(db)
        val measurement = Measurement(dateMs = 2_000L, values = mapOf("weight" to 76.0))
        val session = startSession(original.id, generateProgram(original).first(), 2_000L)
        repo.mutateForVerifiedAccount(original.id, lease, identity) {
            finishSession(it.copy(measurements = listOf(measurement)), stopSession(session, now = 3_000L), 3_000L)
        }
        assertEquals(DeliveryBatchResult.COMPLETE, CloudDeliveryBatch(db, identity, remote).run())
        val fresh = Room.inMemoryDatabaseBuilder(context, FormaDatabase::class.java).build()
        try {
            val restorer = CloudProfileRestorer(fresh, identity, remote)
            assertEquals(listOf(CloudProfileChoice(original.id, original.name)), restorer.list(lease))
            assertEquals(CloudRestoreResult(restored = 3), restorer.restore(lease, original.id))
            val newRepository = ProfileRepository(fresh)
            val downloaded = checkNotNull(newRepository.load(original.id))
            assertEquals(listOf(measurement), downloaded.measurements)
            assertEquals(session.id, downloaded.history.single().id)
            assertNull(ProfileRepository.forGuest(fresh).load(original.id))
            assertNull(fresh.sync().nextPendingForAccount(lease.uid))
            assertEquals(CloudRestoreResult(), restorer.restore(lease, original.id))

            // Two Room stores edit the same record from the restored revision.
            val local = measurement.copy(values = mapOf("weight" to 75.0))
            val otherDevice = measurement.copy(values = mapOf("weight" to 77.0))
            repo.mutateForVerifiedAccount(original.id, lease, identity) { it.copy(measurements = listOf(local)) }
            newRepository.mutateForVerifiedAccount(original.id, lease, identity) {
                it.copy(measurements = listOf(otherDevice))
            }
            assertEquals(DeliveryBatchResult.COMPLETE, CloudDeliveryBatch(fresh, identity, remote).run())
            assertEquals(CloudRestoreResult(conflicts = 1),
                CloudProfileRestorer(db, identity, remote).restore(lease, original.id))
            assertEquals(listOf(local), repo.load(original.id)?.measurements)
            assertEquals("PENDING", db.sync().headForAccount(lease.uid, original.id, "measurement", local.id)?.state)
            // The usual sender also detects divergence and parks the local version durably.
            assertEquals(DeliveryBatchResult.COMPLETE, CloudDeliveryBatch(db, identity, remote).run())
            assertEquals("CONFLICT", db.sync().headForAccount(lease.uid, original.id, "measurement", local.id)?.state)

            val resolver = CloudConflictResolver(db, identity, remote)
            val key = resolver.reviews(lease).single()
            val olderPreview = resolver.preview(lease, key)
            assertTrue(olderPreview.canKeepDevice)
            assertEquals(restoreJson.encodeToString(local.cloudPayload()), olderPreview.localSnapshot)
            val latestOtherDevice = measurement.copy(values = mapOf("weight" to 78.0))
            newRepository.mutateForVerifiedAccount(original.id, lease, identity) {
                it.copy(measurements = listOf(latestOtherDevice))
            }
            assertEquals(DeliveryBatchResult.COMPLETE, CloudDeliveryBatch(fresh, identity, remote).run())
            assertTrue(runCatching { resolver.resolve(lease, olderPreview, CloudConflictChoice.DEVICE) }
                .exceptionOrNull() is CloudRemoteChanged)
            assertTrue(resolver.archives(lease).isEmpty())

            val selected = resolver.preview(lease, key)
            resolver.resolve(lease, selected, CloudConflictChoice.DEVICE)
            val archived = resolver.archives(lease).single()
            assertEquals(selected.localSnapshot, archived.localSnapshot)
            assertEquals(selected.cloud?.snapshot, archived.cloudSnapshot)
            assertEquals(selected.queued, restoreJson.decodeFromString<List<SyncOutboxRow>>(archived.queueSnapshot))
            assertEquals(checkNotNull(selected.cloud).revision + 1, db.sync().nextPendingForAccount(lease.uid)?.revision)
            assertEquals(DeliveryBatchResult.COMPLETE, CloudDeliveryBatch(db, identity, remote).run())
            assertTrue(resolver.reviews(lease).isEmpty())

            val reconcile = CloudInboundReconciler(fresh, identity, remote, restorer)
            val completedBefore = checkNotNull(newRepository.load(original.id)).history
            assertEquals(DeliveryBatchResult.COMPLETE, reconcile.run())
            assertEquals(listOf(local), newRepository.load(original.id)?.measurements)
            val firstCursor = fresh.syncReview().cursor(lease.uid, original.id, "measurement")
            assertNotNull(firstCursor)
            repo.mutateForVerifiedAccount(original.id, lease, identity) { it.copy(measurements = emptyList()) }
            assertEquals(DeliveryBatchResult.COMPLETE, CloudDeliveryBatch(db, identity, remote).run())
            val downloadedResolver = CloudConflictResolver(fresh, identity, remote)
            assertEquals(DeliveryBatchResult.COMPLETE, reconcile.run())
            assertNotEquals(firstCursor, fresh.syncReview().cursor(lease.uid, original.id, "measurement"))
            val deletion = downloadedResolver.preview(lease, downloadedResolver.reviews(lease).single())
            assertFalse(deletion.canKeepDevice)
            assertTrue(deletion.canUseCloud)
            assertEquals(listOf(local), newRepository.load(original.id)?.measurements)
            downloadedResolver.resolve(lease, deletion, CloudConflictChoice.CLOUD)
            assertTrue(checkNotNull(newRepository.load(original.id)).measurements.isEmpty())
            assertEquals(completedBefore, newRepository.load(original.id)?.history)
            assertEquals(restoreJson.encodeToString(local.cloudPayload()), downloadedResolver.archives(lease).single().localSnapshot)
            assertTrue(downloadedResolver.reviews(lease).isEmpty())
        } finally {
            fresh.close()
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

    private suspend fun disableEmulatorAccount(auth: FirebaseAuth, email: String, uid: String) =
        withContext(Dispatchers.IO) {
            check(auth.currentUser?.let { it.uid == uid && it.email == email } == true)
            val base = "http://$emulatorHost:9099/identitytoolkit.googleapis.com/v1/projects/$projectId/accounts"
            val connection = URL("$base:update?key=demo-forma-emulator-key").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer owner")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            try {
                val body = JSONObject().put("localId", uid).put("disableUser", true).toString()
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                assertEquals(200, connection.responseCode)
                val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                assertEquals(uid, result.getString("localId"))
            } finally { connection.disconnect() }
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
