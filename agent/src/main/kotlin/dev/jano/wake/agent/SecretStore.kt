package dev.jano.wake.agent

import java.nio.file.Files
import java.nio.file.Path

/** The pairing secret, in a file only the current Windows user can read. */
class SecretStore(private val dir: Path) {
    private val file: Path = dir.resolve("secret.bin")
    private var cached: ByteArray? = null
    private var loaded = false

    @Synchronized
    fun load(): ByteArray? {
        if (!loaded) {
            cached = if (Files.exists(file)) Files.readAllBytes(file) else null
            loaded = true
        }
        return cached?.copyOf()
    }

    @Synchronized
    fun save(secret: ByteArray) {
        Files.createDirectories(dir)
        Files.write(file, secret)
        restrictToCurrentUser()
        cached = secret.copyOf()
        loaded = true
    }

    @Synchronized
    fun clear() {
        Files.deleteIfExists(file)
        cached = null
        loaded = true
    }

    private fun restrictToCurrentUser() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val user = System.getenv("USERNAME") ?: return
        ProcessBuilder("icacls", file.toString(), "/inheritance:r", "/grant:r", "$user:F")
            .redirectErrorStream(true)
            .start()
            .apply { inputStream.readAllBytes() }
            .waitFor()
    }
}
