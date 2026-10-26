package dev.jano.wake.agent

import java.security.SecureRandom

/** A short window, opened from the tray, during which a phone may pair using the shown code. */
class PairingMode(
    private val clock: () -> Long,
    private val ttlMs: Long = 120_000,
    private val random: SecureRandom = SecureRandom(),
) {
    private var code: String? = null
    private var expiresAt = 0L

    @Synchronized
    fun begin(): String {
        val next = random.nextInt(1_000_000).toString().padStart(6, '0')
        code = next
        expiresAt = clock() + ttlMs
        return next
    }

    @Synchronized
    fun activeCode(): String? {
        if (code != null && clock() >= expiresAt) code = null
        return code
    }

    @Synchronized
    fun end() {
        code = null
    }
}
