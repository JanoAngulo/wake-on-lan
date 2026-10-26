package dev.jano.wake.agent

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class SecretStoreTest {
    @Test
    fun saveLoadClearRoundTrip() {
        val dir = Files.createTempDirectory("wake-store")
        val store = SecretStore(dir)
        assertNull(store.load())

        val secret = ByteArray(32) { it.toByte() }
        store.save(secret)
        assertContentEquals(secret, store.load())
        assertContentEquals(secret, SecretStore(dir).load())

        store.clear()
        assertNull(store.load())
        assertNull(SecretStore(dir).load())
    }
}
