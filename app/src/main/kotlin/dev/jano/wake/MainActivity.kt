package dev.jano.wake

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dev.jano.wake.net.broadcastOf
import dev.jano.wake.net.discoverAgents
import dev.jano.wake.net.probeHost
import dev.jano.wake.net.readPhoneNet
import dev.jano.wake.ui.WakeNavHost
import dev.jano.wake.ui.main.DemoStates
import dev.jano.wake.ui.main.MainActions
import dev.jano.wake.ui.main.MainScreen
import dev.jano.wake.ui.theme.WakeTheme
import kotlinx.coroutines.launch

// FragmentActivity (still a ComponentActivity) because BiometricPrompt hosts its dialog in a fragment.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is dark-only, so the status and navigation bar icons must be light even when the phone is in
        // light mode; the default (auto) would draw dark icons on the dark background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG && intent.getStringExtra("debugAction") == "discover") runNetworkCheck()
        val demo = if (BuildConfig.DEBUG) intent.getStringExtra("demoState") else null
        setContent {
            WakeTheme {
                val demoState = demo?.let { DemoStates.of(it, System.currentTimeMillis()) }
                if (demoState != null) MainScreen(demoState, noActions) else WakeNavHost()
            }
        }
    }

    /** Debug-only: proves this phone may reach the LAN (Android local-network rules). */
    private fun runNetworkCheck() = lifecycleScope.launch {
        val net = readPhoneNet(this@MainActivity)
        Log.i(DEBUG_TAG, "phone net: $net")
        val broadcast = if (net.ip != null && net.prefixLength != null) broadcastOf(net.ip, net.prefixLength) else "255.255.255.255"
        val replies = runCatching { discoverAgents(broadcast, 3_000) }
        Log.i(DEBUG_TAG, "discovery: $replies")
        replies.getOrNull()?.forEach { reply ->
            Log.i(DEBUG_TAG, "probe ${reply.ip}: ${runCatching { probeHost(reply.ip, listOf(reply.httpPort, 135)) }}")
        }
    }

    private companion object {
        const val DEBUG_TAG = "WakeDebug"
        val noActions = MainActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}
