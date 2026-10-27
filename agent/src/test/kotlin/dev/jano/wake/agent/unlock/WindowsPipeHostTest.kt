package dev.jano.wake.agent.unlock

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import dev.jano.wake.protocol.UnlockResult
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the real JNA calls against a pipe this test creates as the current user, i.e. exactly what a
 * squatting user-level program would do. Windows only; skipped elsewhere.
 */
class WindowsPipeHostTest {
    private var server: WinNT.HANDLE? = null

    @BeforeTest
    fun createSquatterPipe() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        server = Kernel32.INSTANCE.CreateNamedPipe(
            """\\.\pipe\wake-unlock""",
            WinBase.PIPE_ACCESS_DUPLEX,
            WinBase.PIPE_TYPE_MESSAGE or WinBase.PIPE_READMODE_MESSAGE or WinBase.PIPE_WAIT,
            1, 64, 64, 0, null,
        )
        assumeTrue(server != WinBase.INVALID_HANDLE_VALUE, "pipe name in use (unlock module running?)")
    }

    @AfterTest
    fun close() {
        server?.takeIf { it != WinBase.INVALID_HANDLE_VALUE }?.let { Kernel32.INSTANCE.CloseHandle(it) }
    }

    @Test
    fun seesThePipeAndReadsItsOwner() {
        val host = WindowsPipeHost()
        assertTrue(host.pipeExists())
        val channel = assertNotNull(host.connect())
        channel.use {
            val me = Advapi32Util.getAccountByName(System.getProperty("user.name")).sidString
            assertEquals(me, it.ownerSid)
        }
    }

    @Test
    fun relayRefusesUserOwnedPipeAndWritesNothing() {
        val logs = mutableListOf<String>()
        // The fake host reports "installed" so the relay goes as far as the owner check on the real pipe.
        val real = WindowsPipeHost()
        val host = object : PipeHost by real {
            override fun installed() = true
        }
        val password = "not-a-real-password".toByteArray()
        val response = PipeUnlockRelay(host, { logs += it }).relay(password)
        assertEquals(UnlockResult.FAILED, response.result)
        assertTrue(logs.single().startsWith("unlock: refused pipe owned by S-1-5-21-"), logs.toString())
        assertTrue(password.all { it == 0.toByte() })
    }
}
