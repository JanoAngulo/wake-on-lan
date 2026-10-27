package dev.jano.wake.agent.unlock

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.AccCtrl
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

private const val PIPE_NAME = """\\.\pipe\wake-unlock"""
private const val PROVIDER_KEY =
    """SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\""" +
        "{8C5D3B0E-6A41-4C7E-9F2B-5D1A7E3C9B04}"
private const val ERROR_SEM_TIMEOUT = 121
private const val PIPE_READMODE_MESSAGE = 0x2
private const val ES_DISPLAY_REQUIRED = 0x2
private const val POLL_MS = 50L

// WriteFile from native memory, so the frame can be wiped after the call (the byte[] overload leaves a copy).
private interface Kernel32Raw : StdCallLibrary {
    fun WriteFile(file: WinNT.HANDLE, buffer: Pointer, length: Int, written: IntByReference, overlapped: Pointer?): Boolean
}

class WindowsPipeHost : PipeHost {
    private val kernel32 = Kernel32.INSTANCE
    private val raw by lazy { Native.load("kernel32", Kernel32Raw::class.java, W32APIOptions.DEFAULT_OPTIONS) }

    override fun installed(): Boolean = Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, PROVIDER_KEY)

    // WaitNamedPipe only asks whether an instance exists; it never takes the provider's one connection.
    override fun pipeExists(): Boolean =
        kernel32.WaitNamedPipe(PIPE_NAME, 1) || Native.getLastError() == ERROR_SEM_TIMEOUT

    override fun connect(): PipeChannel? {
        val handle = kernel32.CreateFile(
            PIPE_NAME,
            WinNT.GENERIC_READ or WinNT.GENERIC_WRITE or WinNT.READ_CONTROL,
            0,
            null,
            WinNT.OPEN_EXISTING,
            0,
            null,
        )
        if (handle == null || handle == WinBase.INVALID_HANDLE_VALUE) return null
        if (!kernel32.SetNamedPipeHandleState(handle, IntByReference(PIPE_READMODE_MESSAGE), null, null)) {
            kernel32.CloseHandle(handle)
            return null
        }
        return Channel(handle)
    }

    override fun wakeDisplay() {
        kernel32.SetThreadExecutionState(ES_DISPLAY_REQUIRED)
    }

    private inner class Channel(private val handle: WinNT.HANDLE) : PipeChannel {
        override val ownerSid: String? by lazy { readOwner() }

        private fun readOwner(): String? {
            val owner = PointerByReference()
            val descriptor = PointerByReference()
            val error = Advapi32.INSTANCE.GetSecurityInfo(
                handle,
                AccCtrl.SE_OBJECT_TYPE.SE_KERNEL_OBJECT,
                WinNT.OWNER_SECURITY_INFORMATION,
                owner,
                null,
                null,
                null,
                descriptor,
            )
            if (error != 0) return null
            return try {
                Advapi32Util.convertSidToStringSid(WinNT.PSID(owner.value))
            } finally {
                kernel32.LocalFree(descriptor.value)
            }
        }

        override fun write(bytes: ByteArray) {
            val buffer = Memory(bytes.size.toLong())
            try {
                buffer.write(0, bytes, 0, bytes.size)
                if (!raw.WriteFile(handle, buffer, bytes.size, IntByReference(), null)) {
                    error("pipe write failed: ${Native.getLastError()}")
                }
            } finally {
                buffer.clear()
            }
        }

        // Polls instead of an overlapped ReadFile: JNA's byte[] marshalling can't outlive a pending read.
        // A broken pipe (provider went away) returns an empty reply, which the relay reports as failed.
        override fun read(timeoutMs: Long): ByteArray? {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            val available = IntByReference()
            while (true) {
                if (!kernel32.PeekNamedPipe(handle, null, 0, null, available, null)) return ByteArray(0)
                if (available.value > 0) {
                    val reply = ByteArray(available.value)
                    val read = IntByReference()
                    if (!kernel32.ReadFile(handle, reply, reply.size, read, null)) return ByteArray(0)
                    return reply.copyOf(read.value)
                }
                if (System.nanoTime() >= deadline) return null
                Thread.sleep(POLL_MS)
            }
        }

        override fun close() {
            kernel32.CloseHandle(handle)
        }
    }
}
