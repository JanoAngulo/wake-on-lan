package dev.jano.wake.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.jano.wake.main.AppGraph
import dev.jano.wake.main.MainViewModel
import dev.jano.wake.main.UiMessage
import dev.jano.wake.net.broadcastOf
import dev.jano.wake.protocol.DiscoveryReply
import dev.jano.wake.ui.main.BiometricAuthenticator
import dev.jano.wake.ui.main.MainActions
import dev.jano.wake.ui.main.findFragmentActivity
import dev.jano.wake.ui.main.MainScreen
import dev.jano.wake.ui.settings.AdvancedScreen
import dev.jano.wake.ui.settings.DeviceExtras
import dev.jano.wake.ui.settings.DevicePickerSheet
import dev.jano.wake.ui.settings.DevicesScreen
import dev.jano.wake.ui.settings.PcSetupHelpScreen
import dev.jano.wake.ui.settings.SettingsScreen
import dev.jano.wake.ui.settings.SettingsViewModel
import dev.jano.wake.ui.settings.SetupGuideScreen
import dev.jano.wake.ui.settings.UnlockSetupScreen
import dev.jano.wake.ui.setup.FindPcScreen
import dev.jano.wake.ui.setup.ManualDeviceForm
import dev.jano.wake.ui.setup.PairScreen
import dev.jano.wake.ui.setup.SetupViewModel
import dev.jano.wake.ui.theme.WakeColors
import kotlinx.coroutines.launch

private object Routes {
    const val FIND = "setup/find"
    const val PAIR = "setup/pair"
    const val MANUAL = "setup/manual"
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val DEVICES = "settings/devices"
    const val DEVICE = "settings/device/{id}"
    const val ADVANCED = "settings/advanced"
    const val HELP = "settings/help"
    const val GUIDE = "settings/guide"
    const val UNLOCK = "settings/device/{id}/unlock"
    fun device(id: String) = "settings/device/$id"
    fun unlock(id: String) = "settings/device/$id/unlock"
}

private fun NavHostController.goHome() = navigate(Routes.MAIN) { popUpTo(0) { inclusive = true } }

@Composable
fun WakeNavHost() {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val mainVm: MainViewModel = viewModel(factory = viewModelFactory { initializer { MainViewModel(graph) } })
    val setupVm: SetupViewModel = viewModel(factory = viewModelFactory { initializer { SetupViewModel(graph) } })
    val settingsVm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(graph) } })

    val loaded by remember { graph.repository.store }.collectAsStateWithLifecycle(initialValue = null)
    val first = loaded ?: return Box(Modifier.fillMaxSize().background(WakeColors.Background))
    val start = remember { if (first.devices.isEmpty()) Routes.FIND else Routes.MAIN }

    val nav = rememberNavController()
    var pairing by remember { mutableStateOf<DiscoveryReply?>(null) }
    val setup by setupVm.state.collectAsStateWithLifecycle()
    val store by settingsVm.store.collectAsStateWithLifecycle()
    val unlockSaved by settingsVm.unlockSaved.collectAsStateWithLifecycle()
    val main by mainVm.ui.collectAsStateWithLifecycle()
    // Why the main screen sent us to unlock setup (wrong password, fingerprints changed).
    var unlockReason by remember { mutableStateOf<String?>(null) }
    // Only known for the selected PC while its agent answers: null lockScreen means no module installed.
    fun unlockModuleMissing(id: String) = main.device?.id == id && main.agentStatus != null && main.agentStatus?.lockScreen == null
    val homeBroadcast = remember {
        graph.phoneNet.read().let { if (it.ip != null && it.prefixLength != null) broadcastOf(it.ip, it.prefixLength) else "255.255.255.255" }
    }

    LaunchedEffect(setup.paired) {
        if (setup.paired) {
            setupVm.consumePaired()
            nav.goHome()
        }
    }

    NavHost(nav, startDestination = start, modifier = Modifier.background(WakeColors.Background)) {
        composable(Routes.FIND) {
            FindPcScreen(
                state = setup,
                onFind = setupVm::find,
                onPick = { reply -> pairing = reply; nav.navigate(Routes.PAIR) },
                onManual = { nav.navigate(Routes.MANUAL) },
                onGuide = { nav.navigate(Routes.GUIDE) },
                onBack = if (nav.previousBackStackEntry != null) ({ nav.popBackStack() }) else null,
            )
        }
        composable(Routes.PAIR) {
            val reply = pairing
            if (reply == null) nav.popBackStack() else PairScreen(reply, setup, { code -> setupVm.pair(reply, code) }, { nav.popBackStack() })
        }
        composable(Routes.MANUAL) {
            ManualDeviceForm(
                title = "Add a PC", initial = null, defaultBroadcast = homeBroadcast,
                onSave = { n, m, b, p, i -> setupVm.saveManual(n, m, b, p, i) },
                onSaved = { nav.goHome() },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.MAIN) {
            MainRoute(
                mainVm, store,
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenHelp = { nav.navigate(Routes.HELP) },
                onOpenUnlockSetup = { id, reason ->
                    unlockReason = reason
                    nav.navigate(Routes.unlock(id))
                },
            )
        }
        composable(Routes.SETTINGS) {
            val scope = rememberCoroutineScope()
            val message by settingsVm.message.collectAsStateWithLifecycle()
            val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) scope.launch {
                    val json = settingsVm.exportJson()
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    settingsVm.exported()
                }
            }
            val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }?.let(settingsVm::importJson)
                }
            }
            SettingsScreen(
                message = message,
                onDevices = { nav.navigate(Routes.DEVICES) },
                onImport = { import.launch(arrayOf("application/json", "text/plain")) },
                onExport = { export.launch("wake-devices.json") },
                onAdvanced = { nav.navigate(Routes.ADVANCED) },
                onHelp = { nav.navigate(Routes.HELP) },
                onGuide = { nav.navigate(Routes.GUIDE) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.DEVICES) {
            DevicesScreen(
                store = store,
                onEdit = { nav.navigate(Routes.device(it)) },
                onAdd = { nav.navigate(Routes.FIND) },
                onSaveFolder = { id, name -> settingsVm.saveFolder(id, name) },
                onDeleteFolder = { settingsVm.deleteFolder(it) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.DEVICE) { entry ->
            val device = store.devices.firstOrNull { it.id == entry.arguments?.getString("id") }
            LaunchedEffect(Unit) { settingsVm.refreshUnlockSaved() }
            if (device == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                ManualDeviceForm(
                    title = device.name, initial = device, defaultBroadcast = device.broadcast,
                    onSave = { n, m, b, p, i -> setupVm.saveManual(n, m, b, p, i, existingId = device.id) },
                    onSaved = { nav.popBackStack() },
                    onBack = { nav.popBackStack() },
                    extra = {
                        DeviceExtras(
                            device, store.folders,
                            onAssignFolder = { settingsVm.assignFolder(device.id, it) },
                            onRepair = { nav.navigate(Routes.FIND) },
                            onUnpair = { settingsVm.unpair(device.id) },
                            onDelete = {
                                settingsVm.deleteDevice(device.id)
                                if (store.devices.size <= 1) nav.navigate(Routes.FIND) { popUpTo(0) { inclusive = true } } else nav.popBackStack()
                            },
                            unlockSaved = device.id in unlockSaved,
                            unlockModuleMissing = unlockModuleMissing(device.id),
                            onUnlockSetup = {
                                unlockReason = null
                                nav.navigate(Routes.unlock(device.id))
                            },
                        )
                    },
                )
            }
        }
        composable(Routes.UNLOCK) { entry ->
            val device = store.devices.firstOrNull { it.id == entry.arguments?.getString("id") }
            val info by settingsVm.unlockInfo.collectAsStateWithLifecycle()
            val activity = context.findFragmentActivity()
            LaunchedEffect(Unit) {
                settingsVm.clearUnlockInfo()
                settingsVm.refreshUnlockSaved()
            }
            if (device == null || activity == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                UnlockSetupScreen(
                    deviceName = device.name,
                    saved = device.id in unlockSaved,
                    moduleMissing = unlockModuleMissing(device.id),
                    reason = unlockReason,
                    info = info,
                    onSave = { password ->
                        unlockReason = null
                        settingsVm.saveUnlockPassword(
                            device.id, password,
                            BiometricAuthenticator(activity, "Save password for ${device.name}", "Your fingerprint will be needed to use it"),
                        )
                    },
                    onRemove = { settingsVm.removeUnlockPassword(device.id) },
                    onBack = { nav.popBackStack() },
                )
            }
        }
        composable(Routes.ADVANCED) { AdvancedScreen(store, { settingsVm.setPollMs(it) }, { settingsVm.setHaptics(it) }, { nav.popBackStack() }) }
        composable(Routes.HELP) { PcSetupHelpScreen { nav.popBackStack() } }
        composable(Routes.GUIDE) {
            SetupGuideScreen(onFindPc = { nav.navigate(Routes.FIND) }, onTroubleshoot = { nav.navigate(Routes.HELP) }, onBack = { nav.popBackStack() })
        }
    }
}

@Composable
private fun MainRoute(
    vm: MainViewModel,
    store: dev.jano.wake.data.Store,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenUnlockSetup: (deviceId: String, reason: String?) -> Unit,
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(state.message) {
        val open = state.message as? UiMessage.OpenUnlockSetup ?: return@LaunchedEffect
        vm.clearMessage()
        onOpenUnlockSetup(open.deviceId, open.reason)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var picker by remember { mutableStateOf(false) }
    val activity = LocalContext.current.findFragmentActivity()
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
            openSettings = onOpenSettings, openSetupHelp = onOpenHelp, switchDevice = { picker = true },
            unlock = {
                if (activity != null) vm.unlock(BiometricAuthenticator(activity, "Unlock ${state.device?.name ?: "PC"}"))
            },
        ),
    )
    if (picker) DevicePickerSheet(store, onSelect = vm::select, onWakeAll = vm::wakeAll, onDismiss = { picker = false })
}
