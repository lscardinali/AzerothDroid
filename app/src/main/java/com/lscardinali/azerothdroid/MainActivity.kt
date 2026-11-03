package com.lscardinali.azerothdroid

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.lscardinali.azerothdroid.realm.DataPack
import com.lscardinali.azerothdroid.realm.RealmService
import com.lscardinali.azerothdroid.realm.SystemStats
import com.lscardinali.azerothdroid.setup.SetupService
import com.lscardinali.azerothdroid.ui.AccountsScreen
import com.lscardinali.azerothdroid.ui.LOG_TAIL_BYTES
import com.lscardinali.azerothdroid.ui.LogsScreen
import com.lscardinali.azerothdroid.ui.RealmScreen
import com.lscardinali.azerothdroid.ui.SettingsScreen
import com.lscardinali.azerothdroid.ui.focusRing
import com.lscardinali.azerothdroid.ui.theme.AzerothDroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** -1 or +1 per shoulder-button press: previous or next tab. */
    private val tabSteps = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            AzerothDroidTheme {
                App(
                    tabSteps = tabSteps,
                    onStart = { RealmService.start(this) },
                    onStop = { RealmService.stop(this) },
                    onSetup = { SetupService.start(this) },
                )
            }
        }
    }

    /**
     * Gamepad buttons. Compose clicks only on D-pad centre, Enter and Space, and whether A and
     * B fall back to centre and Back depends on the controller's key character map, so map
     * them here: A activates, B goes back (closing the keyboard first), L1/R1 switch tabs.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> return super.dispatchKeyEvent(
                KeyEvent(
                    event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount,
                    event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
                ),
            )
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    val insets = ViewCompat.getRootWindowInsets(window.decorView)
                    if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
                    } else {
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    tabSteps.tryEmit(if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L1) -1 else 1)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

/** Four tabs; few enough that a navigation library would only add weight. */
private enum class Tab(val label: String, val icon: ImageVector, val subtitle: String) {
    Realm("Realm", Icons.Filled.Home, "A Wrath of the Lich King realm with playerbots, on this phone"),
    Accounts("Accounts", Icons.Filled.Person, "Log in to the realm with these from any 3.3.5a client"),
    Settings("Settings", Icons.Filled.Settings, "Server rates and playerbots; applied when the realm next starts"),
    @Suppress("DEPRECATION")
    Logs("Logs", Icons.Filled.List, "The last ${LOG_TAIL_BYTES / 1024} KB of each server's log"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(tabSteps: Flow<Int>, onStart: () -> Unit, onStop: () -> Unit, onSetup: () -> Unit) {
    val dataPack = DataPack(LocalContext.current)
    var tab by rememberSaveable { mutableStateOf(Tab.Realm) }
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var device by remember { mutableStateOf(SystemStats.deviceMemory()) }
    // Re-read from disk on every poll: covers setup finishing and data pushed over adb.
    var clientDataReady by remember { mutableStateOf(dataPack.clientDataInstalled) }
    // Each tab hangs this on its primary control; tabs without one leave focus on the tab bar.
    val primaryFocus = remember(tab) { FocusRequester() }
    val tabFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        while (true) {
            status = withContext(Dispatchers.IO) { RealmService.currentStatus() }
            device = SystemStats.deviceMemory()
            clientDataReady = dataPack.clientDataInstalled
            delay(2_000)
        }
    }
    LaunchedEffect(Unit) {
        tabSteps.collect { step -> tab = Tab.entries[(tab.ordinal + step).coerceIn(0, Tab.entries.lastIndex)] }
    }
    LaunchedEffect(tab) {
        // Let the tab compose and attach its requester first.
        withFrameNanos {}
        if (!primaryFocus.requestFocus(FocusDirection.Enter)) tabFocus.requestFocus()
    }
    BackHandler(enabled = tab != Tab.Realm) { tab = Tab.Realm }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        if (tab == Tab.Realm) {
                            Text("AzerothDroid", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                        } else {
                            Text(tab.label)
                        }
                        Text(
                            tab.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        },
        bottomBar = {
            // Coming down from a tab's content, land on the open tab, not the nearest one.
            NavigationBar(
                Modifier
                    .focusProperties { onEnter = { tabFocus.requestFocus() } }
                    .focusGroup(),
            ) {
                Tab.entries.forEach { entry ->
                    val selected = tab == entry
                    NavigationBarItem(
                        selected = selected,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = null) },
                        label = { Text(entry.label) },
                        modifier = Modifier
                            .then(if (selected) Modifier.focusRequester(tabFocus) else Modifier)
                            .focusRing(RoundedCornerShape(16.dp), outset = 0.dp),
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.Realm -> RealmScreen(
                modifier, primaryFocus, status, device, clientDataReady, dataPack.clientDataVersion, onStart, onStop, onSetup,
            )
            Tab.Accounts -> AccountsScreen(
                modifier,
                primaryFocus,
                databaseRunning = status?.optJSONObject("database")?.optString("state") == "RUNNING",
            )
            Tab.Settings -> SettingsScreen(modifier, primaryFocus, realmRunning = status != null)
            Tab.Logs -> LogsScreen(modifier, primaryFocus)
        }
    }
}
