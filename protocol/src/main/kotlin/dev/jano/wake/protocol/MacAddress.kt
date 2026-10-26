package dev.jano.wake.protocol

/** A MAC address, always stored as `AA:BB:CC:DD:EE:FF` in uppercase. */
data class MacAddress(val value: String) {
    fun bytes(): ByteArray =
        value.split(':').map { it.toInt(16).toByte() }.toByteArray()
}

private val HEX12 = Regex("^[0-9A-Fa-f]{12}$")

/** Accepts `:`, `-` or no separators. Returns null for anything else. */
fun parseMac(input: String): MacAddress? {
    val trimmed = input.trim()
    val separators = trimmed.count { it == ':' || it == '-' }
    if (separators != 0 && separators != 5) return null
    val hex = trimmed.replace(":", "").replace("-", "")
    if (!HEX12.matches(hex)) return null
    return MacAddress(hex.uppercase().chunked(2).joinToString(":"))
}
