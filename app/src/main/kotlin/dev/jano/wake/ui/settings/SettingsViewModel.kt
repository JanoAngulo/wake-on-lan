package dev.jano.wake.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jano.wake.data.Authenticator
import dev.jano.wake.data.BackupCodec
import dev.jano.wake.data.Folder
import dev.jano.wake.data.Store
import dev.jano.wake.main.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.util.UUID

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    val store: StateFlow<Store> = graph.repository.store.stateIn(viewModelScope, SharingStarted.Eagerly, Store())
    val message = MutableStateFlow<String?>(null)

    // saved() reads the Keystore, which the store flow doesn't know about; bump this after every change.
    private val unlockChanged = MutableStateFlow(0)

    /** Device ids with a fingerprint-unlock password saved on this phone. */
    val unlockSaved: StateFlow<Set<String>> = combine(graph.repository.store, unlockChanged) { s, _ ->
        s.devices.map { it.id }.filter(graph.unlockPasswords::saved).toSet()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** One-line result shown on the Fingerprint unlock screen. */
    val unlockInfo = MutableStateFlow<String?>(null)

    suspend fun exportJson(): String = BackupCodec.export(graph.repository.store.first())

    fun importJson(json: String) {
        viewModelScope.launch {
            BackupCodec.import(json)
                .onSuccess { (devices, folders) ->
                    val before = graph.repository.store.first().devices.size
                    graph.repository.update { BackupCodec.merge(it, devices, folders) }
                    val added = graph.repository.store.first().devices.size - before
                    message.value = if (added == 1) "Imported 1 device" else "Imported $added devices"
                }
                .onFailure { message.value = "Couldn't import: ${it.message}" }
        }
    }

    fun exported() {
        message.value = "Devices exported"
    }

    /** Fingerprint first (it authorises the new key), then save. [password] is zeroed either way. */
    fun saveUnlockPassword(deviceId: String, password: CharArray, auth: Authenticator) = viewModelScope.launch {
        var bytes: ByteArray? = null
        try {
            val cipher = auth.authenticate(graph.unlockPasswords.sealCipher(deviceId)) ?: return@launch
            bytes = utf8(password)
            graph.unlockPasswords.save(deviceId, cipher, bytes)
            unlockInfo.value = "Saved. Lock your PC and tap Unlock to try it."
        } catch (e: GeneralSecurityException) {
            // Typically no screen lock or no strong biometric enrolled, so the Keystore can't make the key.
            unlockInfo.value = "Couldn't save. Make sure this phone has a screen lock and a fingerprint set up."
        } finally {
            password.fill('\u0000')
            bytes?.fill(0)
            unlockChanged.value++
        }
    }

    fun removeUnlockPassword(deviceId: String) = viewModelScope.launch {
        graph.unlockPasswords.remove(deviceId)
        unlockInfo.value = null
        unlockChanged.value++
    }

    fun clearUnlockInfo() {
        unlockInfo.value = null
    }

    /** The main screen can remove a password (wrong password, new fingerprints); re-read before showing setup. */
    fun refreshUnlockSaved() {
        unlockChanged.value++
    }

    fun deleteDevice(id: String) = viewModelScope.launch {
        graph.secrets.remove(id)
        graph.unlockPasswords.remove(id)
        graph.repository.deleteDevice(id)
    }

    fun unpair(id: String) = viewModelScope.launch {
        graph.secrets.remove(id)
        graph.unlockPasswords.remove(id)
        unlockChanged.value++
        graph.repository.update { s -> s.copy(devices = s.devices.map { if (it.id == id) it.copy(paired = false) else it }) }
    }

    fun assignFolder(deviceId: String, folderId: String?) = viewModelScope.launch {
        graph.repository.update { s -> s.copy(devices = s.devices.map { if (it.id == deviceId) it.copy(folderId = folderId) else it }) }
    }

    fun saveFolder(id: String?, name: String) = viewModelScope.launch {
        graph.repository.upsertFolder(Folder(id ?: UUID.randomUUID().toString(), name.trim()))
    }

    fun deleteFolder(id: String) = viewModelScope.launch { graph.repository.deleteFolder(id) }

    fun setPollMs(ms: Long) = viewModelScope.launch { graph.repository.updateSettings { it.copy(pollMs = ms) } }

    fun setHaptics(on: Boolean) = viewModelScope.launch { graph.repository.updateSettings { it.copy(haptics = on) } }
}

/** UTF-8 without going through a String; the intermediate buffer is wiped. */
private fun utf8(chars: CharArray): ByteArray {
    val buffer = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars))
    return try {
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } finally {
        if (buffer.hasArray()) buffer.array().fill(0)
    }
}
