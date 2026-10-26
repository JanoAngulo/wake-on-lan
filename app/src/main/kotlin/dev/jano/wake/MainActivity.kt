package dev.jano.wake

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import dev.jano.wake.net.discoverAgents
import dev.jano.wake.net.probeHost
import dev.jano.wake.net.readPhoneNet
import dev.jano.wake.ui.theme.WakeColors
import dev.jano.wake.ui.theme.WakeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG && intent.getStringExtra("debugAction") == "discover") runNetworkCheck()
        setContent {
            WakeTheme {
                Box(Modifier.fillMaxSize().background(WakeColors.Background))
            }
        }
    }

    /** Debug-only: proves this phone may reach the LAN (Android 17 local-network rules). */
    private fun runNetworkCheck() = lifecycleScope.launch {
        Log.i(DEBUG_TAG, "phone net: ${readPhoneNet(this@MainActivity)}")
        val replies = runCatching { discoverAgents("192.168.1.255", 3_000) }
        Log.i(DEBUG_TAG, "discovery: $replies")
        val probe = runCatching { probeHost("192.168.1.14", listOf(47613, 135)) }
        Log.i(DEBUG_TAG, "probe: $probe")
    }

    private companion object {
        const val DEBUG_TAG = "WakeDebug"
    }
}
