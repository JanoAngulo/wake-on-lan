package dev.jano.wake.protocol

import kotlinx.serialization.Serializable

// Sign-in after a cold boot: the pre-login program on the PC speaks only these two paths, on Ports.SIGNIN.
const val PATH_SIGNIN_STATUS = "/signin/status"
const val PATH_SIGNIN_UNLOCK = "/signin/unlock"

/**
 * The key the pre-login program holds instead of the pairing secret. Requests to it are signed, and
 * passwords sealed, with this key, so a leaked copy can't send commands to the agent.
 */
fun signInKey(secret: ByteArray): ByteArray =
    hkdfSha256(secret, ByteArray(0), "wake-signin-v1".toByteArray(), 32)

/** [signInScreen] is true while the lock or sign-in screen is up and can take a password. */
@Serializable
data class SignInStatus(val mac: String, val serverTimeMs: Long, val signInScreen: Boolean)
