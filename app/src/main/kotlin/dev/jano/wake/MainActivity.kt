package dev.jano.wake

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.jano.wake.main.AppGraph
import dev.jano.wake.main.MainViewModel
import dev.jano.wake.net.discoverAgents
import dev.jano.wake.net.probeHost
import dev.jano.wake.net.readPhoneNet
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
                if (demoState != null) MainScreen(demoState, noActions) else MainRoute()
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

@Composable
private fun MainRoute() {
    val context = LocalContext.current
    val vm: MainViewModel = viewModel(factory = viewModelFactory { initializer { MainViewModel(AppGraph.get(context)) } })
    val state by vm.ui.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.rememberSubnetIfMissing()
            vm.runPolling()
        }
    }
    MainScreen(
        state,
        MainActions(
            wake = vm::wake, shutdown = vm::shutdown, reboot = vm::reboot, sleep = vm::sleep, lock = vm::lock,
            undo = vm::undo, dismissMessage = vm::clearMessage,
            openSettings = {}, openSetupHelp = {}, switchDevice = {},
        ),
    )
}
