package dev.jano.wake.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class SigningTest {
    private val secret = ByteArray(32) { it.toByte() }
    private var now = 1_793_000_000_000L
    private val verifier = RequestVerifier(secret = { secret }, clock = { now })
    private val body = """{"action":"shutdown"}""".toByteArray()

    private fun signed(
        method: String = "POST",
        path: String = "/command",
        body: ByteArray = this.body,
        at: Long = now,
        nonce: String = newNonce(),
    ) = sign(secret, method, path, body, at, nonce)

    @Test
    fun validSignatureVerifies() {
        assertEquals(VerifyResult.OK, verifier.verify("POST", "/command", body, signed()))
    }

    @Test
    fun tamperedBodyRejected() {
        val headers = signed()
        assertEquals(
            VerifyResult.BAD_SIGNATURE,
            verifier.verify("POST", "/command", """{"action":"reboot"}""".toByteArray(), headers),
        )
    }

    @Test
    fun tamperedPathRejected() {
        assertEquals(VerifyResult.BAD_SIGNATURE, verifier.verify("POST", "/command/cancel", body, signed()))
    }

    @Test
    fun staleRejected() {
        assertEquals(VerifyResult.STALE, verifier.verify("POST", "/command", body, signed(at = now - 31_000)))
        assertEquals(VerifyResult.OK, verifier.verify("POST", "/command", body, signed(at = now - 29_000)))
    }

    @Test
    fun staleWithBadSignatureIsBadSignature() {
        val forged = signed(at = now - 60_000).copy(signature = "AAAA")
        assertEquals(VerifyResult.BAD_SIGNATURE, verifier.verify("POST", "/command", body, forged))
    }

    @Test
    fun staleProofOnlyMatchesWithSecret() {
        val proof = staleProof(secret, "n9", now)
        assertEquals(proof, staleProof(secret, "n9", now))
        assert(proof != staleProof(ByteArray(32), "n9", now))
        assert(proof != staleProof(secret, "n9", now + 1))
        assert(proof != staleProof(secret, "n8", now))
    }

    @Test
    fun replayRejected() {
        val headers = signed()
        assertEquals(VerifyResult.OK, verifier.verify("POST", "/command", body, headers))
        assertEquals(VerifyResult.REPLAY, verifier.verify("POST", "/command", body, headers))
    }

    @Test
    fun forgedRequestDoesNotBurnNonce() {
        val forged = signed(nonce = "n1").copy(signature = "AAAA")
        assertEquals(VerifyResult.BAD_SIGNATURE, verifier.verify("POST", "/command", body, forged))
        assertEquals(VerifyResult.OK, verifier.verify("POST", "/command", body, signed(nonce = "n1")))
    }

    @Test
    fun notPairedRejected() {
        val unpaired = RequestVerifier(secret = { null }, clock = { now })
        assertEquals(VerifyResult.NOT_PAIRED, unpaired.verify("POST", "/command", body, signed()))
    }

    @Test
    fun noncesEvictedAfterWindow() {
        assertEquals(VerifyResult.OK, verifier.verify("POST", "/command", body, signed(nonce = "n2")))
        now += 61_000
        assertEquals(VerifyResult.OK, verifier.verify("POST", "/command", body, signed(nonce = "n2")))
    }

    @Test
    fun nonceIsUrlSafeAndUnique() {
        val a = newNonce()
        val b = newNonce()
        assertEquals(22, a.length)
        assert(a != b)
        assert(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }
}
