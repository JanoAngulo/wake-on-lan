package dev.jano.wake

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dev.jano.wake.net.discoverAgents
import dev.jano.wake.net.probeHost
import dev.jano.wake.net.readPhoneNet
import dev.jano.wake.ui.WakeNavHost
import dev.jano.wake.ui.main.DemoStates
import dev.jano.wake.ui.main.MainActions
import dev.jano.wake.ui.main.MainScreen
import dev.jano.wake.ui.theme.WakeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
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

    /** Debug-only: proves this phone may reach the LAN (Android 17 local-network rules). */
    private fun runNetworkCheck() = lifecycleScope.launch {
        Log.i(DEBUG_TAG, "phone net: ${readPhoneNet(this@MainActivity)}")
        Log.i(DEBUG_TAG, "discovery: ${runCatching { discoverAgents("192.168.1.255", 3_000) }}")
        Log.i(DEBUG_TAG, "probe: ${runCatching { probeHost("192.168.1.14", listOf(47613, 135)) }}")
    }

    private companion object {
        const val DEBUG_TAG = "WakeDebug"
        val noActions = MainActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}
