package com.rabie.bmwobd

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rabie.bmwobd.ui.Bmw
import com.rabie.bmwobd.ui.BmwObdTheme
import com.rabie.bmwobd.ui.ConnectScreen
import com.rabie.bmwobd.ui.DiagnosticsScreen
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import com.rabie.bmwobd.ui.SettingsScreen
import com.rabie.bmwobd.ui.LandscapePanel
import com.rabie.bmwobd.ui.LiveScreen
import com.rabie.bmwobd.ui.LogScreen
import com.rabie.bmwobd.ui.SplashScreen
import com.rabie.bmwobd.ui.TripsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            BmwObdTheme {
                var splashDone by rememberSaveable { mutableStateOf(false) }
                val title = remember { (application as BmwObdApp).vehicles.last()?.name ?: "OBD" }
                if (splashDone) MainScreen() else SplashScreen(title, onFinished = { splashDone = true })
            }
        }
    }
}

private const val COMPACT_HEIGHT_DP = 500

private enum class Tab(val title: String) {
    PANEL("Panel"), TRIPS("Trayectos"), FAULTS("Averías"), LOG("Registro"), SETTINGS("Ajustes")
}

@Composable
private fun MainScreen(vm: ObdViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(hasBluetoothPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionGranted = hasBluetoothPermission(context)
    }
    var tab by rememberSaveable { mutableStateOf(Tab.PANEL) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val bubbleEnabled by vm.bubbleEnabled.collectAsStateWithLifecycle()
    val toggleBubble = { if (!vm.toggleBubble()) openOverlaySettings(context) }

    LaunchedEffect(settings.accent) {
        Bmw.Accent = androidx.compose.ui.graphics.Color(settings.accent.argb)
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) vm.refreshDevices() else launcher.launch(runtimePermissions())
    }

    // Apaisado y conectado, el panel pasa a ser el cuadro a pantalla completa. "Menú" vuelve a la
    // app normal sin girar el movil, y tocar otra vez la pestaña Panel regresa al cuadro.
    // Tambien cuenta una ventana baja: la mitad de la pantalla partida con el navegador en la otra.
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
        configuration.screenHeightDp < COMPACT_HEIGHT_DP
    var menuInLandscape by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(landscape) { if (!landscape) menuInLandscape = false }
    if (landscape && !menuInLandscape && tab == Tab.PANEL && state.phase == Phase.LIVE) {
        LandscapePanel(
            state = state,
            keepScreenOn = settings.keepScreenOn,
            onMenu = { menuInLandscape = true },
            modifier = Modifier.safeDrawingPadding(),
        )
        return
    }

    Column(Modifier.fillMaxSize().background(Bmw.Background).safeDrawingPadding()) {
        val content = Modifier.weight(1f)
        when (tab) {
            Tab.PANEL -> if (state.phase == Phase.LIVE) {
                LiveScreen(
                    state = state,
                    keepScreenOn = settings.keepScreenOn,
                    bubbleEnabled = bubbleEnabled,
                    onToggleBubble = toggleBubble,
                    onSaveVehicle = vm::updateVehicle,
                    onDisconnect = vm::disconnect,
                    modifier = content,
                )
            } else {
                ConnectScreen(
                    title = remember(state.phase) { vm.vehicles.last()?.name ?: "OBD" },
                    state = state,
                    devices = devices,
                    permissionGranted = permissionGranted,
                    onRequestPermission = { launcher.launch(runtimePermissions()) },
                    onRefresh = vm::refreshDevices,
                    onConnect = { vm.connect(it.address) },
                    onSimulate = vm::connectSimulator,
                    modifier = content,
                )
            }

            Tab.TRIPS -> TripsScreen(store = vm.trips, vehicles = vm.vehicles, modifier = content)

            Tab.FAULTS -> {
                val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
                DiagnosticsScreen(
                    state = diagnostics,
                    connected = state.phase == Phase.LIVE,
                    vehicleTerms = state.vehicle.searchTerms,
                    onRead = vm::readDiagnostics,
                    onClear = vm::clearDtcs,
                    modifier = content,
                )
            }

            Tab.LOG -> {
                val log by vm.log.collectAsStateWithLifecycle()
                LogScreen(
                    lines = log,
                    canSend = state.phase == Phase.LIVE,
                    onSend = vm::sendCommand,
                    onProbe = vm::probe,
                    modifier = content,
                )
            }

            Tab.SETTINGS -> SettingsScreen(
                settings = settings,
                bubbleEnabled = bubbleEnabled,
                onChange = vm::updateSettings,
                onToggleBubble = toggleBubble,
                modifier = content,
            )
        }
        Tabs(
            selected = tab,
            onSelect = {
                if (it == Tab.PANEL && tab == Tab.PANEL) menuInLandscape = false
                tab = it
            },
        )
    }
}

@Composable
private fun Tabs(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Bmw.Surface)) {
        for (tab in Tab.entries) {
            val active = tab == selected
            Column(
                modifier = Modifier.weight(1f).clickable { onSelect(tab) }.padding(top = 12.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Mas pequeño que el rotulo normal: son cinco pestañas y tienen que caber en una linea.
                Text(
                    tab.title.uppercase(),
                    color = if (active) Bmw.Text else Bmw.TextDim,
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                )
                Box(Modifier.width(28.dp).height(2.dp).background(if (active) Bmw.Accent else Bmw.Surface))
            }
        }
    }
}

/** Bluetooth para hablar con el adaptador y, desde Android 13, el aviso del servicio en segundo plano. */
private fun runtimePermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

/** La pantalla de ajustes donde se concede a la app el permiso de mostrarse sobre otras. */
private fun openOverlaySettings(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
    runCatching { context.startActivity(intent) }
}

private fun hasBluetoothPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
