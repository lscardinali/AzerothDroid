package com.lscardinali.pocketwow

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.lscardinali.pocketwow.realm.DataPack
import com.lscardinali.pocketwow.realm.RealmService
import com.lscardinali.pocketwow.realm.SystemStats
import com.lscardinali.pocketwow.setup.SetupService
import com.lscardinali.pocketwow.ui.AccountsScreen
import com.lscardinali.pocketwow.ui.LogsScreen
import com.lscardinali.pocketwow.ui.RealmScreen
import com.lscardinali.pocketwow.ui.SettingsScreen
import com.lscardinali.pocketwow.ui.theme.PocketWowTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            PocketWowTheme {
                App(
                    onStart = { RealmService.start(this) },
                    onStop = { RealmService.stop(this) },
                    onSetup = { SetupService.start(this) },
                )
            }
        }
    }
}

/** Four tabs; few enough that a navigation library would only add weight. */
private enum class Tab(val label: String, val icon: ImageVector) {
    Realm("Realm", Icons.Filled.Home),
    Accounts("Accounts", Icons.Filled.Person),
    Settings("Settings", Icons.Filled.Settings),
    @Suppress("DEPRECATION")
    Logs("Logs", Icons.Filled.List),
}

@Composable
private fun App(onStart: () -> Unit, onStop: () -> Unit, onSetup: () -> Unit) {
    val dataPack = DataPack(LocalContext.current)
    var tab by rememberSaveable { mutableStateOf(Tab.Realm) }
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var device by remember { mutableStateOf(SystemStats.deviceMemory()) }
    // Re-read from disk on every poll: covers setup finishing and data pushed over adb.
    var clientDataReady by remember { mutableStateOf(dataPack.clientDataInstalled) }

    LaunchedEffect(Unit) {
        while (true) {
            status = withContext(Dispatchers.IO) { RealmService.currentStatus() }
            device = SystemStats.deviceMemory()
            clientDataReady = dataPack.clientDataInstalled
            delay(2_000)
        }
    }
    BackHandler(enabled = tab != Tab.Realm) { tab = Tab.Realm }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = null) },
                        label = { Text(entry.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.Realm -> RealmScreen(
                modifier, status, device, clientDataReady, dataPack.clientDataVersion, onStart, onStop, onSetup,
            )
            Tab.Accounts -> AccountsScreen(
                modifier,
                databaseRunning = status?.optJSONObject("database")?.optString("state") == "RUNNING",
            )
            Tab.Settings -> SettingsScreen(modifier, realmRunning = status != null)
            Tab.Logs -> LogsScreen(modifier)
        }
    }
}
