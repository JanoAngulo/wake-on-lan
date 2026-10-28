package dev.jano.wake.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SignInTest {
    private val secret = ByteArray(32) { it.toByte() }
    private val nonce = "bm9uY2Utb25l"
    private val now = 1_800_000_000_000L

    @Test
    fun signInKeyIsHkdfOfSecret() {
        assertContentEquals(hkdfSha256(secret, ByteArray(0), "wake-signin-v1".toByteArray(), 32), signInKey(secret))
    }

    @Test
    fun signInKeyDiffersFromSecret() {
        assertFalse(signInKey(secret).contentEquals(secret))
    }

    @Test
    fun pairingSecretSignatureFailsUnderSignInKey() {
        val body = "{}".toByteArray()
        val headers = sign(secret, "POST", PATH_SIGNIN_UNLOCK, body, now)
        val verifier = RequestVerifier(secret = { signInKey(secret) }, clock = { now })
        assertEquals(VerifyResult.BAD_SIGNATURE, verifier.verify("POST", PATH_SIGNIN_UNLOCK, body, headers))
    }

    @Test
    fun passwordSealedWithSignInKeyOpensOnlyWithIt() {
        val password = "correct horse".toByteArray()
        val sealed = sealPassword(signInKey(secret), nonce, password)
        assertContentEquals(password, openPassword(signInKey(secret), nonce, sealed))
        assertNull(openPassword(secret, nonce, sealed))
    }

    @Test
    fun signInStatusRoundTrips() {
        val status = SignInStatus(mac = "0A:1B:2C:3D:4E:5F", serverTimeMs = now, signInScreen = true)
        val json = WakeJson.encodeToString(SignInStatus.serializer(), status)
        assertEquals(status, WakeJson.decodeFromString(SignInStatus.serializer(), json))
    }

    @Test
    fun signInPortAndPaths() {
        assertEquals(47615, Ports.SIGNIN)
        assertEquals("/signin/status", PATH_SIGNIN_STATUS)
        assertEquals("/signin/unlock", PATH_SIGNIN_UNLOCK)
    }
}
