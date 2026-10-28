package dev.jano.wake.agent.signin

import dev.jano.wake.protocol.signInKey
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignInKeyStoreTest {
    // Stand-in for DPAPI: reversible, and never the plain bytes.
    private val xor = object : KeyProtector {
        override fun protect(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }
        override fun unprotect(blob: ByteArray): ByteArray? = protect(blob)
    }
    private val secretA = ByteArray(32) { it.toByte() }
    private val secretB = ByteArray(32) { (it + 7).toByte() }
    private val dir = Files.createTempDirectory("signin")
    private val file = dir.resolve("signin.key")
    private val store = SignInKeyStore(dir, xor, trusted = { true })

    @Test
    fun writesDerivedKeyWhenPaired() {
        syncSignInKey(secretA, store)
        assertContentEquals(signInKey(secretA), store.load())
        assertFalse(Files.readAllBytes(file).contentEquals(signInKey(secretA)))
    }

    @Test
    fun rewritesKeyAfterRepair() {
        syncSignInKey(secretA, store)
        syncSignInKey(secretB, store)
        assertContentEquals(signInKey(secretB), store.load())
    }

    @Test
    fun leavesFileAloneWhenUnchanged() {
        syncSignInKey(secretA, store)
        val old = FileTime.fromMillis(1_000_000L)
        Files.setLastModifiedTime(file, old)
        syncSignInKey(secretA, store)
        assertEquals(old, Files.getLastModifiedTime(file))
    }

    @Test
    fun deletesKeyWhenNotPaired() {
        syncSignInKey(secretA, store)
        syncSignInKey(null, store)
        assertFalse(Files.exists(file))
    }

    @Test
    fun saveDoesNothingWhenFolderMissing() {
        val missing = dir.resolve("not-installed")
        val absent = SignInKeyStore(missing, xor, trusted = { true })
        assertFalse(absent.save(signInKey(secretA)))
        assertFalse(Files.exists(missing))
        syncSignInKey(secretA, absent)
        assertFalse(Files.exists(missing))
    }

    @Test
    fun unreadableBlobLoadsNull() {
        val refusing = object : KeyProtector {
            override fun protect(plain: ByteArray) = plain
            override fun unprotect(blob: ByteArray): ByteArray? = null
        }
        Files.write(file, byteArrayOf(1, 2, 3))
        assertNull(SignInKeyStore(dir, refusing, trusted = { true }).load())
    }

    @Test
    fun untrustedFolderIsNeverWrittenOrRead() {
        // Someone else made the folder (or it's a link): the key must not go there, and nothing there is trusted.
        val planted = SignInKeyStore(dir, xor, trusted = { false })
        assertFalse(planted.save(signInKey(secretA)))
        assertFalse(Files.exists(file))
        Files.write(file, xor.protect(signInKey(secretB)))
        assertNull(planted.load())
    }

    @Test
    fun folderOwnedByAUserIsNotTheInstallers() {
        // A temp folder belongs to whoever runs the tests, like one a standard user planted in ProgramData.
        assertFalse(isInstallerFolder(dir))
        assertFalse(isInstallerFolder(dir.resolve("missing")))
    }

    @Test
    fun saveReportsSuccess() {
        assertTrue(store.save(signInKey(secretA)))
    }
}
