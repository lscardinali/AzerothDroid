package com.lscardinali.pocketwow

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lscardinali.pocketwow.realm.RealmService
import com.lscardinali.pocketwow.realm.SystemStats
import com.lscardinali.pocketwow.ui.theme.PocketWowTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
                Scaffold { padding ->
                    HomeScreen(
                        modifier = Modifier.padding(padding),
                        onStart = { RealmService.start(this) },
                        onStop = { RealmService.stop(this) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(modifier: Modifier, onStart: () -> Unit, onStop: () -> Unit) {
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var device by remember { mutableStateOf(SystemStats.deviceMemory()) }
    var ui by remember { mutableStateOf(SystemStats.processMemory()) }

    LaunchedEffect(Unit) {
        while (true) {
            status = withContext(Dispatchers.IO) { fetchStatus() }
            device = SystemStats.deviceMemory()
            ui = SystemStats.processMemory()
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart) { Text("Start realm") }
            OutlinedButton(onClick = onStop) { Text("Stop") }
        }

        InfoCard("Service") {
            val s = status
            if (s == null) {
                Text("Stopped (no answer on 127.0.0.1:${RealmService.STATUS_PORT})")
            } else {
                Text("Running, up ${s.optLong("uptimeSec")} s")
                val realm = s.optJSONObject("processes")?.optJSONObject("realmService")
                Text("Realm process RSS: ${mb(realm?.optLong("rssKb"))} (peak ${mb(realm?.optLong("peakRssKb"))})")
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

        InfoCard("Memory") {
            Text("Device: ${mb(device.availableKb)} free of ${mb(device.totalKb)}")
            Text("UI process RSS: ${mb(ui?.rssKb)}")
        }

        InfoCard("Reach it from your PC") {
            val addresses = SystemStats.lanAddresses()
            if (addresses.isEmpty()) Text("No network connection")
            addresses.forEach { (nic, ip) ->
                Text(
                    "curl http://$ip:${RealmService.STATUS_PORT}/status   ($nic)",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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

private fun mb(kb: Long?): String = if (kb == null) "?" else "%.0f MB".format(kb / 1024.0)

private fun fetchStatus(): JSONObject? = try {
    val conn = URL("http://127.0.0.1:${RealmService.STATUS_PORT}/status")
        .openConnection() as HttpURLConnection
    conn.connectTimeout = 500
    conn.readTimeout = 1_000
    conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
} catch (_: Exception) {
    null
}
