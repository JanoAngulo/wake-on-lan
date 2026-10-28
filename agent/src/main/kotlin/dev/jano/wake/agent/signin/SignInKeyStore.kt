package dev.jano.wake.agent.signin

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.Win32Exception
import dev.jano.wake.protocol.signInKey
import java.nio.file.Files
import java.nio.file.Path
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

/** The sign-in key at rest. [dir] is made by the installer, never here: only the installer sets its ACL. */
class SignInKeyStore(private val dir: Path, private val protector: KeyProtector) {
    private val file: Path = dir.resolve("signin.key")

    @Synchronized
    fun load(): ByteArray? = if (Files.exists(file)) protector.unprotect(Files.readAllBytes(file)) else null

    /** False when sign-in after shutdown isn't installed (no folder). */
    @Synchronized
    fun save(key: ByteArray): Boolean {
        if (!Files.isDirectory(dir)) return false
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
