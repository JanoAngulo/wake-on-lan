package dev.jano.wake.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

data class PhoneNet(val onWifi: Boolean, val ip: String?, val prefixLength: Int?)

fun readPhoneNet(context: Context): PhoneNet {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val network = cm.activeNetwork ?: return PhoneNet(false, null, null)
    val caps = cm.getNetworkCapabilities(network)
    val onLan = caps != null && (
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    )
    val v4 = cm.getLinkProperties(network)?.linkAddresses?.firstOrNull { it.address is Inet4Address }
    return PhoneNet(onLan, v4?.address?.hostAddress, v4?.prefixLength)
}

private fun toInt(ip: String): Int = ip.split('.').fold(0) { acc, part -> (acc shl 8) or part.toInt() }

private fun toIp(n: Int): String = listOf(24, 16, 8, 0).joinToString(".") { ((n ushr it) and 0xFF).toString() }

private fun mask(prefixLength: Int): Int = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)

fun subnetOf(ip: String, prefixLength: Int): String = "${toIp(toInt(ip) and mask(prefixLength))}/$prefixLength"

fun broadcastOf(ip: String, prefixLength: Int): String = toIp(toInt(ip) or mask(prefixLength).inv())

/** Home = on Wi-Fi and, when the device's subnet is known, inside it. */
fun isHome(phone: PhoneNet, deviceSubnet: String?): Boolean {
    if (!phone.onWifi || phone.ip == null || phone.prefixLength == null) return false
    return deviceSubnet == null || subnetOf(phone.ip, phone.prefixLength) == deviceSubnet
}
