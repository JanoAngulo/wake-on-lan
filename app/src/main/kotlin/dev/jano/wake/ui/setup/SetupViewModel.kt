package dev.jano.wake.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jano.wake.data.Device
import dev.jano.wake.main.AppGraph
import dev.jano.wake.net.PairingError
import dev.jano.wake.net.PairingFailed
import dev.jano.wake.net.broadcastOf
import dev.jano.wake.net.subnetOf
import dev.jano.wake.protocol.DiscoveryReply
import dev.jano.wake.protocol.parseMac
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class Field { NAME, MAC, BROADCAST, PORT, IP }

data class FieldError(val field: Field, val message: String)

data class SetupUiState(
    val searching: Boolean = false,
    val searched: Boolean = false,
    val found: List<DiscoveryReply> = emptyList(),
    val pairing: Boolean = false,
    val pairError: String? = null,
    val paired: Boolean = false,
)

private val IPV4 = Regex("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$")

fun isIpv4(text: String) = IPV4.matches(text.trim())

class SetupViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    private fun homeBroadcast(): String {
        val net = graph.phoneNet.read()
        return if (net.ip != null && net.prefixLength != null) broadcastOf(net.ip, net.prefixLength) else "192.168.1.255"
    }

    fun find() {
        _state.update { it.copy(searching = true) }
        viewModelScope.launch {
            val found = runCatching { graph.discoverer.discover(homeBroadcast()) }.getOrDefault(emptyList())
            _state.update { it.copy(searching = false, searched = true, found = found) }
        }
    }

    fun pair(reply: DiscoveryReply, code: String) {
        _state.update { it.copy(pairing = true, pairError = null) }
        viewModelScope.launch {
            try {
                val secret = graph.pairer.pair("http://${reply.ip}:${reply.httpPort}", code.trim())
                val net = graph.phoneNet.read()
                val mac = parseMac(reply.mac)?.value ?: reply.mac
                val existing = graph.repository.store.first().devices.firstOrNull { it.mac == mac }
                val device = (existing ?: Device(id = UUID.randomUUID().toString(), name = reply.hostname, mac = mac)).copy(
                    lastIp = reply.ip,
                    agentPort = reply.httpPort,
                    paired = true,
                    broadcast = if (net.ip != null && net.prefixLength != null) broadcastOf(net.ip, net.prefixLength) else existing?.broadcast ?: "192.168.1.255",
                    subnet = if (net.ip != null && net.prefixLength != null) subnetOf(net.ip, net.prefixLength) else existing?.subnet,
                )
                graph.secrets.put(device.id, secret)
                graph.repository.upsertDevice(device)
                graph.repository.select(device.id)
                _state.update { it.copy(pairing = false, paired = true) }
            } catch (e: PairingFailed) {
                _state.update { it.copy(pairing = false, pairError = pairingMessage(e.reason)) }
            }
        }
    }

    fun consumePaired() = _state.update { it.copy(paired = false, pairError = null) }

    /** Validates synchronously so errors show under the fields; saves in the background when valid. */
    fun saveManual(name: String, mac: String, broadcast: String, port: String, ip: String, existingId: String? = null): List<FieldError> {
        val errors = buildList {
            if (name.isBlank()) add(FieldError(Field.NAME, "Enter a name"))
            if (parseMac(mac) == null) add(FieldError(Field.MAC, "Enter a MAC like 0A:1B:2C:3D:4E:5F"))
            if (!isIpv4(broadcast)) add(FieldError(Field.BROADCAST, "Enter an IPv4 address"))
            if (port.trim().toIntOrNull() !in 1..65535) add(FieldError(Field.PORT, "Port must be 1–65535"))
            if (ip.isNotBlank() && !isIpv4(ip)) add(FieldError(Field.IP, "Enter an IPv4 address"))
        }
        if (errors.isNotEmpty()) return errors

        viewModelScope.launch {
            val store = graph.repository.store.first()
            val base = store.devices.firstOrNull { it.id == existingId } ?: Device(id = UUID.randomUUID().toString(), name = name, mac = "")
            val net = graph.phoneNet.read()
            graph.repository.upsertDevice(
                base.copy(
                    name = name.trim(),
                    mac = parseMac(mac)!!.value,
                    broadcast = broadcast.trim(),
                    port = port.trim().toInt(),
                    lastIp = ip.trim().ifBlank { null },
                    subnet = base.subnet ?: if (net.ip != null && net.prefixLength != null) subnetOf(net.ip, net.prefixLength) else null,
                ),
            )
        }
        return emptyList()
    }

    companion object {
        fun pairingMessage(reason: PairingError): String = when (reason) {
            PairingError.NOT_PAIRING -> "Open the Wake tray icon on your PC and choose Pair phone first."
            PairingError.BAD_CODE -> "That code didn't match. Start pairing again on the PC."
            PairingError.AGENT_MISMATCH -> "Couldn't verify the PC. Start pairing again on the PC."
            PairingError.NETWORK -> "Couldn't reach the PC. Check you're on home Wi-Fi."
        }
    }
}
