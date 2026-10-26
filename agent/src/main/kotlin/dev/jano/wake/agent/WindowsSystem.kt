package dev.jano.wake.agent

import com.sun.jna.Library
import com.sun.jna.Native
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

private interface Kernel32Lib : Library {
    fun GetTickCount64(): Long
}

object WindowsSystem {
    private val kernel32 by lazy { Native.load("kernel32", Kernel32Lib::class.java) }

    fun hostname(): String = System.getenv("COMPUTERNAME") ?: InetAddress.getLocalHost().hostName

    fun uptimeSeconds(): Long = kernel32.GetTickCount64() / 1000

    fun defaultGateway(): String? {
        val output = ProcessBuilder("route", "print", "-4", "0.0.0.0")
            .redirectErrorStream(true)
            .start()
            .let { process -> process.inputStream.bufferedReader().readText().also { process.waitFor() } }
        return parseDefaultGateway(output)
    }

    /** The active default route with the lowest metric (VPN adapters add worse-metric defaults). */
    fun parseDefaultGateway(routePrint: String): String? {
        val active = routePrint.substringAfter("Active Routes:", "").substringBefore("Persistent Routes:")
        return active.lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .filter { it.size >= 5 && it[0] == "0.0.0.0" && it[1] == "0.0.0.0" }
            .mapNotNull { cols -> cols[4].toIntOrNull()?.let { metric -> cols[2] to metric } }
            .minByOrNull { it.second }
            ?.first
    }

    fun nics(): List<NicInfo> = NetworkInterface.networkInterfaces().toList().flatMap { nic ->
        val mac = nic.hardwareAddress?.joinToString(":") { "%02X".format(it) }
        nic.interfaceAddresses
            .filter { it.address is Inet4Address }
            .map { addr ->
                NicInfo(
                    name = nic.name,
                    displayName = nic.displayName ?: nic.name,
                    ipv4 = addr.address.hostAddress,
                    prefixLength = addr.networkPrefixLength.toInt(),
                    mac = mac,
                    up = nic.isUp && !nic.isLoopback,
                )
            }
    }
}
