package dev.jano.wake.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jano.wake.data.BackupCodec
import dev.jano.wake.data.Folder
import dev.jano.wake.data.Store
import dev.jano.wake.main.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    val store: StateFlow<Store> = graph.repository.store.stateIn(viewModelScope, SharingStarted.Eagerly, Store())
    val message = MutableStateFlow<String?>(null)

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

    fun deleteDevice(id: String) = viewModelScope.launch {
        graph.secrets.remove(id)
        graph.repository.deleteDevice(id)
    }

    fun unpair(id: String) = viewModelScope.launch {
        graph.secrets.remove(id)
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
