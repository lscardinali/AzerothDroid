package com.lscardinali.pocketwow

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lscardinali.pocketwow.realm.DataPack
import com.lscardinali.pocketwow.realm.RealmService
import com.lscardinali.pocketwow.realm.SystemStats
import com.lscardinali.pocketwow.setup.ClientDataInstaller
import com.lscardinali.pocketwow.setup.SetupService
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
                // Two screens do not justify a navigation library; back returns home.
                var settings by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = settings) { settings = false }
                Scaffold { padding ->
                    if (settings) {
                        SettingsScreen(
                            modifier = Modifier.padding(padding),
                            realmRunning = RealmService.running,
                            onBack = { settings = false },
                        )
                    } else {
                        HomeScreen(
                            modifier = Modifier.padding(padding),
                            onStart = { RealmService.start(this) },
                            onStop = { RealmService.stop(this) },
                            onSetup = { SetupService.start(this) },
                            onSettings = { settings = true },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSetup: () -> Unit,
    onSettings: () -> Unit,
) {
    val dataPack = DataPack(LocalContext.current)
    val setup by ClientDataInstaller.progress.collectAsState()
    // Re-read from disk when setup finishes; also covers data pushed over adb.
    var clientDataReady by remember { mutableStateOf(dataPack.clientDataInstalled) }
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var device by remember { mutableStateOf(SystemStats.deviceMemory()) }
    var ui by remember { mutableStateOf(SystemStats.processMemory()) }

    LaunchedEffect(Unit) {
        while (true) {
            status = withContext(Dispatchers.IO) { RealmService.currentStatus() }
            device = SystemStats.deviceMemory()
            ui = SystemStats.processMemory()
            clientDataReady = dataPack.clientDataInstalled
            delay(2_000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("PocketWoW", style = MaterialTheme.typography.headlineMedium)

        if (!clientDataReady) {
            SetupCard(dataPack.clientDataVersion, setup, onSetup)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = clientDataReady) { Text("Start realm") }
            OutlinedButton(onClick = onStop) { Text("Stop") }
            OutlinedButton(onClick = onSettings) { Text("Settings") }
        }

        InfoCard("Service") {
            val s = status
            if (s == null) {
                Text("Stopped")
            } else {
                Text("Running, up ${s.optLong("uptimeSec")} s")
                val realm = s.optJSONObject("processes")?.optJSONObject("realmService")
                Text("App process RSS: ${mb(realm?.optLong("rssKb"))} (peak ${mb(realm?.optLong("peakRssKb"))})")
            }
        }

        InfoCard("Database (MariaDB)") {
            val db = status?.optJSONObject("database")
            if (db == null) {
                Text("Not running")
            } else {
                Text("${db.optString("state")}  ${db.optString("version").substringAfter(':')}")
                if (!db.isNull("pid")) {
                    Text("PID ${db.optInt("pid")}, RSS ${mb(db.optLong("rssKb"))} (peak ${mb(db.optLong("peakRssKb"))})")
                }
                if (!db.isNull("error")) {
                    Text(db.optString("error"), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        InfoCard("Auth server") {
            val authserver = status?.optJSONObject("authserver")
            if (authserver == null) {
                Text("Not running")
            } else {
                Text("${authserver.optString("state")}  port ${authserver.optInt("port")}  (AzerothCore ${authserver.optString("commit")})")
                if (!authserver.isNull("pid")) {
                    Text("PID ${authserver.optInt("pid")}, RSS ${mb(authserver.optLong("rssKb"))} (peak ${mb(authserver.optLong("peakRssKb"))})")
                }
                if (!authserver.isNull("error")) {
                    Text(authserver.optString("error"), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (status != null) AccountsCard()

        InfoCard("World server") {
            val worldserver = status?.optJSONObject("worldserver")
            if (worldserver == null) {
                Text("Not running")
            } else {
                Text("${worldserver.optString("state")}  port ${worldserver.optInt("port")}")
                if (!worldserver.isNull("realmAddress")) Text("Realm address ${worldserver.optString("realmAddress")}")
                if (!worldserver.isNull("pid")) {
                    Text("PID ${worldserver.optInt("pid")}, RSS ${mb(worldserver.optLong("rssKb"))} (peak ${mb(worldserver.optLong("peakRssKb"))})")
                }
                if (!worldserver.isNull("error")) {
                    Text(worldserver.optString("error"), color = MaterialTheme.colorScheme.error)
                }
            }
            val pack = status?.optJSONObject("dataPack")
            if (pack != null) {
                Text("Data pack: SQL ${pack.optString("sql", "missing")}, client data ${pack.optString("clientData", "missing")}")
            }
        }

        InfoCard("Memory") {
            Text("Device: ${mb(device.availableKb)} free of ${mb(device.totalKb)}")
            Text("UI process RSS: ${mb(ui?.rssKb)}")
        }

        InfoCard("Connect a client") {
            Text("realmlist.wtf on this device: set realmlist 127.0.0.1")
            val addresses = SystemStats.lanAddresses()
            if (addresses.isEmpty()) Text("No network connection")
            addresses.forEach { (nic, ip) ->
                Text(
                    "From your PC: set realmlist $ip   ($nic)",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AccountsCard() {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var gm by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var existing by remember { mutableStateOf<List<String>>(emptyList()) }
    suspend fun refresh() {
        existing = withContext(Dispatchers.IO) { runCatching { RealmService.accounts()?.list() }.getOrNull() }.orEmpty()
    }
    LaunchedEffect(Unit) { refresh() }

    InfoCard("Accounts") {
        Text(if (existing.isEmpty()) "No accounts yet" else existing.joinToString(", "))
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
        OutlinedTextField(
            password, { password = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(gm, { gm = it })
            Text("Game master (GM commands)")
        }
        Button(onClick = {
            scope.launch {
                message = withContext(Dispatchers.IO) {
                    runCatching {
                        val accounts = checkNotNull(RealmService.accounts()) { "Database not running" }
                        accounts.create(name, password, gm)
                        "Created ${name.uppercase()}"
                    }.getOrElse { it.message ?: it.toString() }
                }
                password = ""
                refresh()
            }
        }) { Text("Create account") }
        message?.let { Text(it) }
    }
}

@Composable
private fun SetupCard(version: String, progress: ClientDataInstaller.Progress, onSetup: () -> Unit) {
    InfoCard("Setup: client data") {
        Text(
            "The world server needs WotLK 3.3.5a client data (maps, DBC, pathfinding), $version. " +
                "It is downloaded from AzerothCore's client-data release: about 1.2 GB, " +
                "3.5 GB once extracted. The download is deleted afterwards.",
        )
        when (progress) {
            is ClientDataInstaller.Progress.Downloading -> {
                Text("Downloading ${progress.bytes / MB} / ${progress.total / MB} MB")
                LinearProgressIndicator(progress = { progress.bytes.toFloat() / progress.total.coerceAtLeast(1) })
            }
            is ClientDataInstaller.Progress.Extracting -> {
                Text("Extracting ${progress.files} / ${progress.total} files")
                LinearProgressIndicator(progress = { progress.files.toFloat() / progress.total.coerceAtLeast(1) })
            }
            is ClientDataInstaller.Progress.Failed -> {
                Text(progress.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = onSetup) { Text("Retry") }
            }
            ClientDataInstaller.Progress.Idle -> Button(onClick = onSetup) { Text("Download and install") }
            ClientDataInstaller.Progress.Done -> Text("Installed")
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private const val MB = 1024L * 1024

private fun mb(kb: Long?): String = if (kb == null) "?" else "%.0f MB".format(kb / 1024.0)

