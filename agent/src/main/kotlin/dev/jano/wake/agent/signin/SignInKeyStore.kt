package dev.jano.wake.agent.signin

import com.sun.jna.platform.win32.AccCtrl
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.PointerByReference
import dev.jano.wake.protocol.signInKey
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.Path

interface KeyProtector {
    fun protect(plain: ByteArray): ByteArray

    /** Null when the blob can't be opened on this PC. */
    fun unprotect(blob: ByteArray): ByteArray?
}

private const val CRYPTPROTECT_UI_FORBIDDEN = 0x1
private const val CRYPTPROTECT_LOCAL_MACHINE = 0x4

/** DPAPI, machine scope: the tray agent (the user) writes it, the pre-login program (another account) reads it. */
object DpapiMachineProtector : KeyProtector {
    override fun protect(plain: ByteArray): ByteArray =
        Crypt32Util.cryptProtectData(plain, null, CRYPTPROTECT_LOCAL_MACHINE or CRYPTPROTECT_UI_FORBIDDEN, "", null)

    override fun unprotect(blob: ByteArray): ByteArray? = try {
        Crypt32Util.cryptUnprotectData(blob, null, CRYPTPROTECT_UI_FORBIDDEN, null)
    } catch (_: Win32Exception) {
        null
    }
}

/** Where the installer puts the sign-in key, with its ACL set. */
fun signInDataDir(): Path = Path(System.getenv("ProgramData") ?: "C:\\ProgramData", "Wake", "signin")

/**
 * True when [dir] is a real folder (not a link or junction) owned by SYSTEM or Administrators, i.e. the one the
 * installer made and locked down. Machine-scope DPAPI opens for any local account, so the folder's ACL is the
 * key's only guard; a folder someone else created first must never receive it.
 */
fun isInstallerFolder(dir: Path): Boolean {
    val attrs = runCatching { Files.readAttributes(dir, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
    if (attrs == null || !attrs.isDirectory || attrs.isSymbolicLink || attrs.isOther) return false
    return ownerSid(dir) in TRUSTED_OWNERS
}

private val TRUSTED_OWNERS = setOf("S-1-5-18", "S-1-5-32-544")

private fun ownerSid(path: Path): String? {
    val owner = PointerByReference()
    val descriptor = PointerByReference()
    val error = Advapi32.INSTANCE.GetNamedSecurityInfo(
        path.toString(), AccCtrl.SE_OBJECT_TYPE.SE_FILE_OBJECT, WinNT.OWNER_SECURITY_INFORMATION, owner, null, null, null, descriptor,
    )
    if (error != 0) return null
    return try {
        Advapi32Util.convertSidToStringSid(WinNT.PSID(owner.value))
    } finally {
        Kernel32.INSTANCE.LocalFree(descriptor.value)
    }
}

/** The sign-in key at rest. [dir] is made by the installer, never here: only the installer sets its ACL. */
class SignInKeyStore(
    private val dir: Path,
    private val protector: KeyProtector,
    private val trusted: (Path) -> Boolean = ::isInstallerFolder,
) {
    private val file: Path = dir.resolve("signin.key")

    @Synchronized
    fun load(): ByteArray? = if (trusted(dir) && Files.exists(file)) protector.unprotect(Files.readAllBytes(file)) else null

    /** False when sign-in after shutdown isn't installed (no folder), or the folder isn't the installer's. */
    @Synchronized
    fun save(key: ByteArray): Boolean {
        if (!Files.isDirectory(dir) || !trusted(dir)) return false
        if (load()?.contentEquals(key) == true) return true
        Files.write(file, protector.protect(key))
        return true
    }

    @Synchronized
    fun clear() {
        Files.deleteIfExists(file)
    }
}

/** Keeps the pre-login program's key in step with the pairing: derived when paired, gone when not. */
fun syncSignInKey(secret: ByteArray?, keys: SignInKeyStore) {
    if (secret == null) keys.clear() else keys.save(signInKey(secret))
}
