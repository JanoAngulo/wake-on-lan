package dev.jano.wake.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket

sealed interface ProbeResult {
    data class Up(val rttMs: Long) : ProbeResult
    data object Down : ProbeResult
}

/**
 * TCP connect probe. A completed connection or an explicit refusal both prove the host is up;
 * only a timeout or an unreachable network counts as down. Android apps can't send ICMP.
 */
suspend fun probeHost(ip: String, ports: List<Int>, timeoutMs: Int = 800): ProbeResult = coroutineScope {
    val attempts = ports.map { port -> async(Dispatchers.IO) { probePort(ip, port, timeoutMs) } }
    var remaining = attempts
    while (remaining.isNotEmpty()) {
        val (done, result) = select {
            remaining.forEach { attempt -> attempt.onAwait { attempt to it } }
        }
        if (result is ProbeResult.Up) {
            attempts.forEach { it.cancel() }
            return@coroutineScope result
        }
        remaining = remaining - done
    }
    ProbeResult.Down
}

private suspend fun probePort(ip: String, port: Int, timeoutMs: Int): ProbeResult = withContext(Dispatchers.IO) {
    val started = System.nanoTime()
    fun elapsed() = (System.nanoTime() - started) / 1_000_000
    try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
        ProbeResult.Up(elapsed())
    } catch (e: ConnectException) {
        if (e.message?.contains("refused", ignoreCase = true) == true) ProbeResult.Up(elapsed()) else ProbeResult.Down
    } catch (e: java.io.IOException) {
        ProbeResult.Down
    }
}
