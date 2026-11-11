package com.lscardinali.azerothdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lscardinali.azerothdroid.realm.AuthServer
import com.lscardinali.azerothdroid.realm.SystemStats
import com.lscardinali.azerothdroid.realm.WorldServer
import com.lscardinali.azerothdroid.setup.ClientDataInstaller
import org.json.JSONObject

/**
 * The home tab: one control for the whole realm, then how each server and the device are
 * doing. [status] is RealmService's status JSON, null while the service is not running.
 * [primaryFocus] goes on setup until the client data is in, then on Start/Stop.
 */
@Composable
fun RealmScreen(
    modifier: Modifier,
    primaryFocus: FocusRequester,
    status: JSONObject?,
    device: SystemStats.DeviceMemory,
    clientDataReady: Boolean,
    clientDataVersion: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSetup: () -> Unit,
) {
    val setup by ClientDataInstaller.progress.collectAsState()
    // Setup's button goes away once the data is in; hand focus on to Start.
    LaunchedEffect(clientDataReady) { if (clientDataReady) primaryFocus.requestFocus() }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!clientDataReady) {
            SetupPanel(clientDataVersion, setup, primaryFocus, onSetup)
            // Before the download, so the 4 GB can go straight to the SD card.
            DataLocationPanel(realmRunning = status != null)
        }
        RealmPanel(status, clientDataReady, if (clientDataReady) primaryFocus else null, onStart, onStop)
        if (clientDataReady) PlayPanel(realmUp = status?.optJSONObject("worldserver")?.optString("state").let { it == "RUNNING" || it == "PAUSED" })
        if (status != null) ServersPanel(status)
        MemoryPanel(status, device)
        ConnectPanel(status)
    }
}

@Composable
private fun RealmPanel(
    status: JSONObject?,
    clientDataReady: Boolean,
    focus: FocusRequester?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val servers = SERVERS.map { (key, _) -> status?.optJSONObject(key) }
    val states = servers.map { it?.optString("state") }
    val health = when {
        status == null -> Health.Down
        "FAILED" in states -> Health.Broken
        "STOPPING" in states -> Health.Stopping
        states.last() == "RUNNING" -> Health.Up
        states.last() == "PAUSED" -> Health.Sleeping
        else -> Health.Busy
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Realm", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (health) {
                        Health.Up -> "Up ${uptime(status?.optLong("uptimeSec") ?: 0)}. Log in with your client."
                        Health.Busy -> "Starting ${starting(states)}: a minute or two, longer on the very first start."
                        Health.Broken -> "A server failed; see below and the Logs tab."
                        Health.Stopping -> "Saving players and shutting down."
                        Health.Sleeping -> "Sleeping: nobody has played for a while. Logging in wakes it."
                        Health.Down -> if (clientDataReady) "Stopped." else "Install the client data first."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HealthBadge(health)
        }
        if (health == Health.Busy || health == Health.Stopping) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        // One button for both, so it keeps controller focus when the realm starts or stops.
        val running = status != null
        Button(
            onClick = if (running) onStop else onStart,
            enabled = running || clientDataReady,
            colors = if (running) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                .focusRing(ButtonDefaults.shape),
        ) { Text(if (running) "Stop realm" else "Start realm") }
    }
}

@Composable
private fun ServersPanel(status: JSONObject) {
    Panel("Servers", contentPadding = PaddingValues(top = 16.dp), readable = true) {
        SERVERS.forEach { (key, name) ->
            val server = status.optJSONObject(key) ?: return@forEach
            ListItem(
                headlineContent = { Text(name) },
                supportingContent = {
                    Column {
                        Text(detail(key, server))
                        if (!server.isNull("error")) Text(server.optString("error"), color = MaterialTheme.colorScheme.error)
                    }
                },
                trailingContent = { HealthBadge(Health.of(server.optString("state"))) },
                colors = cardListItemColors(),
            )
        }
    }
}

@Composable
private fun MemoryPanel(status: JSONObject?, device: SystemStats.DeviceMemory) {
    Panel("Memory", readable = true) {
        Meter(
            "Device RAM", device.totalKb - device.availableKb, device.totalKb,
            "${mb(device.availableKb)} free of ${mb(device.totalKb)}",
        )
        if (device.swapTotalKb > 0) {
            Meter(
                "Compressed swap", device.swapTotalKb - device.swapFreeKb, device.swapTotalKb,
                "${mb(device.swapFreeKb)} free of ${mb(device.swapTotalKb)}",
            )
        }
        if (status != null) {
            Caption("Realm processes (in RAM + swapped out)")
            SERVERS.forEach { (key, name) ->
                val server = status.optJSONObject(key) ?: return@forEach
                if (server.isNull("pid")) return@forEach
                ProcessRow(name, server.optLong("rssKb"), server.optLong("swapKb"))
            }
            status.optJSONObject("processes")?.optJSONObject("realmService")?.let {
                ProcessRow("App", it.optLong("rssKb"), null)
            }
        }
        Text(
            "Android stops the realm when memory runs out. Players are saved every two minutes " +
                "and again when memory gets critical.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProcessRow(name: String, rssKb: Long, swapKb: Long?) {
    Row {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            if (swapKb == null) mb(rssKb) else "${mb(rssKb)} + ${mb(swapKb)}",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ConnectPanel(status: JSONObject?) {
    Panel("Connect a client", readable = true) {
        Text(
            "Set realmlist.wtf in your 3.3.5a client (Data/enUS or enGB) to:",
            style = MaterialTheme.typography.bodyMedium,
        )
        val realm = status?.optJSONObject("worldserver")?.optString("realmAddress")?.takeIf { it.isNotEmpty() }
        val addresses = SystemStats.lanAddresses()
        // Selectable by touch, but not a focus stop: a controller has nothing to do there.
        SelectionContainer(Modifier.focusProperties { canFocus = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RealmlistLine("set realmlist 127.0.0.1", "this phone")
                addresses.forEach { (nic, ip) -> RealmlistLine("set realmlist $ip", "other devices, $nic") }
            }
        }
        if (addresses.isEmpty()) Text("No network: only a client on this phone can connect.", style = MaterialTheme.typography.bodySmall)
        realm?.let {
            Text(
                "The realm advertises $it; if this phone's address changes, restart the realm.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RealmlistLine(line: String, where: String) {
    Column {
        Text(where, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            line,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun SetupPanel(version: String, progress: ClientDataInstaller.Progress, focus: FocusRequester, onSetup: () -> Unit) {
    val buttonModifier = Modifier
        .fillMaxWidth()
        .focusRequester(focus)
        .focusRing(ButtonDefaults.shape)
    Panel("First-time setup") {
        Text(
            "The world server needs WotLK 3.3.5a client data (maps, DBC, pathfinding), $version, " +
                "from AzerothCore's client-data release: a 1.2 GB download, 3.5 GB once extracted. " +
                "The download is deleted afterwards. Wi-Fi recommended.",
            style = MaterialTheme.typography.bodyMedium,
        )
        when (progress) {
            is ClientDataInstaller.Progress.Downloading -> {
                Meter("Downloading", progress.bytes / 1024, progress.total / 1024, "${mb(progress.bytes / 1024)} of ${mb(progress.total / 1024)}")
            }
            is ClientDataInstaller.Progress.Extracting -> {
                Meter("Extracting", progress.files.toLong(), progress.total.toLong(), "${progress.files} of ${progress.total} files")
            }
            is ClientDataInstaller.Progress.Failed -> {
                Text(progress.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = onSetup, modifier = buttonModifier) { Text("Retry") }
            }
            ClientDataInstaller.Progress.Idle ->
                Button(onClick = onSetup, modifier = buttonModifier) { Text("Download and install") }
            ClientDataInstaller.Progress.Done -> Text("Installed")
            // Shown by DataLocationPanel.
            is ClientDataInstaller.Progress.Moving -> Unit
        }
    }
}

/** Status JSON key and display name, in start order. */
private val SERVERS = listOf("database" to "Database", "authserver" to "Login server", "worldserver" to "World server")

private fun detail(key: String, server: JSONObject): String = buildList {
    when (key) {
        "database" -> server.optString("version").substringAfter(':').takeIf { it.isNotEmpty() }?.let { add("MariaDB $it") }
        "authserver" -> add("port ${AuthServer.PORT}")
        "worldserver" -> add("port ${WorldServer.PORT}")
    }
    if (!server.isNull("pid")) add(mb(server.optLong("rssKb")))
}.joinToString(" · ")

private fun starting(states: List<String?>): String =
    when (states.indexOfFirst { it != "RUNNING" }) {
        0 -> "the database"
        1 -> "the login server"
        else -> "the world server"
    }

private fun uptime(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
}
