package ru.forma.app

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await

internal enum class AccountSession { SIGNED_OUT, EMAIL_UNVERIFIED, EMAIL_VERIFIED }

/** The backend is the Firebase app supplied by build configuration. */
internal class FirebaseAccount(private val app: FirebaseApp) {
    private val auth = FirebaseAuth.getInstance(app)
    internal val identity = FirebaseSyncIdentity(app)
    fun firestoreTransport() = FirestoreSyncRemote(app, identity)

    val currentSession: AccountSession get() = auth.currentUser.toAccountSession()

    val sessions = callbackFlow {
        val listener = FirebaseAuth.IdTokenListener { trySend(currentSession) }
        auth.addIdTokenListener(listener)
        awaitClose { auth.removeIdTokenListener(listener) }
    }.distinctUntilChanged()

    val localLeases = callbackFlow {
        val listener = FirebaseAuth.IdTokenListener { trySend(identity.localLease()) }
        auth.addIdTokenListener(listener)
        awaitClose { auth.removeIdTokenListener(listener) }
    }.distinctUntilChanged()

    fun verifiedEmail(lease: VerifiedSyncLease): String? {
        if (identity.localLease() != lease) return null
        return auth.currentUser?.takeIf { it.uid == lease.uid }?.email
    }

    suspend fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
    }

    suspend fun register(email: String, password: String) {
        val user = checkNotNull(auth.createUserWithEmailAndPassword(email.trim(), password).await().user)
        user.sendEmailVerification().await()
    }

    suspend fun refreshVerification() {
        val user = checkNotNull(auth.currentUser)
        user.reload().await()
        if (auth.currentUser?.uid == user.uid) user.getIdToken(true).await()
    }

    suspend fun verifySession(): Boolean {
        val lease = identity.localLease() ?: return false
        return identity.verifyOnlineLease(lease) == lease
    }

    suspend fun resendVerification() {
        val user = checkNotNull(auth.currentUser)
        if (!user.isEmailVerified) user.sendEmailVerification().await()
    }

    suspend fun sendPasswordReset(email: String) {
        auth.sendPasswordResetEmail(email.trim()).await()
    }

    fun signOut() = identity.signOut()
}

private fun FirebaseUser?.toAccountSession(): AccountSession = when {
    this == null || isAnonymous -> AccountSession.SIGNED_OUT
    isEmailVerified -> AccountSession.EMAIL_VERIFIED
    else -> AccountSession.EMAIL_UNVERIFIED
}
