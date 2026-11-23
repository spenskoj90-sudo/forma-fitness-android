package ru.forma.app

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import java.io.Closeable
import kotlinx.coroutines.tasks.await

/** A lease comes only from a verified email session in the configured Firebase app. */
internal class FirebaseSyncIdentity(app: FirebaseApp) : VerifiedSyncIdentity, Closeable {
    private val auth = FirebaseAuth.getInstance(app)
    private var lastUid: String? = null
    private var generation = 0L
    private var closed = false
    private val listener = FirebaseAuth.AuthStateListener { observeSession() }

    init { auth.addAuthStateListener(listener) }

    override suspend fun currentLease(): VerifiedSyncLease? {
        val lease = observeSession() ?: return null
        val user = auth.currentUser?.takeIf { it.uid == lease.uid } ?: return null
        val token = try {
            user.getIdToken(false).await()
        } catch (_: FirebaseAuthInvalidUserException) {
            return null
        }
        if (token.token.isNullOrEmpty() || token.claims["email_verified"] != true) return null
        return lease.takeIf { it == observeSession() }
    }

    /** Invalidate the application lease before the SDK can deliver a late callback. */
    @Synchronized fun signOut() {
        generation++
        lastUid = null
        auth.signOut()
    }

    @Synchronized override fun close() {
        closed = true
        auth.removeAuthStateListener(listener)
    }

    @Synchronized private fun observeSession(): VerifiedSyncLease? {
        if (closed) return null
        val uid = auth.currentUser?.takeUnless { it.isAnonymous }?.uid
        if (uid != lastUid) {
            generation++
            lastUid = uid
        }
        return uid?.let { VerifiedSyncLease(it, generation) }
    }
}
