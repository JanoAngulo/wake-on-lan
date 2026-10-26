package dev.jano.wake.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.jano.wake.protocol.WakeJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Everything the app stores, as one JSON document. Secrets live in [SecretVault], never here. */
class DeviceRepository(private val dataStore: DataStore<Preferences>) {
    private val key = stringPreferencesKey("store")

    val store: Flow<Store> = dataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { WakeJson.decodeFromString(Store.serializer(), it) }.getOrNull() } ?: Store()
    }

    suspend fun update(transform: (Store) -> Store) {
        dataStore.edit { prefs ->
            val current = prefs[key]?.let { runCatching { WakeJson.decodeFromString(Store.serializer(), it) }.getOrNull() } ?: Store()
            prefs[key] = WakeJson.encodeToString(Store.serializer(), transform(current))
        }
    }

    suspend fun upsertDevice(device: Device) = update { store ->
        val others = store.devices.filterNot { it.id == device.id }
        val index = store.devices.indexOfFirst { it.id == device.id }
        val devices = if (index < 0) others + device else others.toMutableList().apply { add(index, device) }
        store.copy(devices = devices, selectedId = store.selectedId ?: device.id)
    }

    suspend fun deleteDevice(id: String) = update { store ->
        store.copy(
            devices = store.devices.filterNot { it.id == id },
            selectedId = store.selectedId.takeIf { it != id },
        )
    }

    suspend fun upsertFolder(folder: Folder) = update { store ->
        store.copy(folders = store.folders.filterNot { it.id == folder.id } + folder)
    }

    suspend fun deleteFolder(id: String) = update { store ->
        store.copy(
            folders = store.folders.filterNot { it.id == id },
            devices = store.devices.map { if (it.folderId == id) it.copy(folderId = null) else it },
        )
    }

    suspend fun select(id: String) = update { it.copy(selectedId = id) }

    suspend fun updateIp(deviceId: String, ip: String) = update { store ->
        store.copy(devices = store.devices.map { if (it.id == deviceId) it.copy(lastIp = ip) else it })
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = update { it.copy(settings = transform(it.settings)) }
}
