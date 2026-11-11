package com.lscardinali.azerothdroid.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.lscardinali.azerothdroid.realm.DataLocation
import com.lscardinali.azerothdroid.realm.DataPack
import com.lscardinali.azerothdroid.setup.ClientDataInstaller
import com.lscardinali.azerothdroid.setup.SetupService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the panel shows; re-read every few seconds, since a card can be inserted or removed. */
private data class Storage(
    val current: DataLocation,
    val available: Boolean,
    val installed: Boolean,
    /** Free bytes per location; absent when it cannot be used now (no card). */
    val free: Map<DataLocation, Long>,
)

private fun readStorage(context: Context): Storage {
    val pack = DataPack(context)
    val free = DataLocation.entries.mapNotNull { location ->
        location.root(context)?.let { location to DataLocation.freeBytes(it) }
    }.toMap()
    return Storage(pack.location, pack.storageAvailable, pack.clientDataInstalled, free)
}

/**
 * Chooses where the game data (client data and the unpacked SQL) lives: internal storage or the
 * SD card. Switching moves installed data across in [SetupService], so it needn't be downloaded
 * again. Hidden when there is no card and the data is already internal: there is nothing to choose.
 */
@Composable
fun DataLocationPanel(realmRunning: Boolean) {
    val context = LocalContext.current
    val progress by ClientDataInstaller.progress.collectAsState()
    val storage by produceState(readStorage(context), progress) {
        while (true) {
            value = withContext(Dispatchers.IO) { readStorage(context) }
            delay(2_000)
        }
    }
    val other = DataLocation.entries.first { it != storage.current }
    if (other !in storage.free && storage.current == DataLocation.INTERNAL) return

    val busy = progress is ClientDataInstaller.Progress.Downloading ||
        progress is ClientDataInstaller.Progress.Extracting ||
        progress is ClientDataInstaller.Progress.Moving

    val canMove = !busy && !realmRunning && other in storage.free
    // Readable (a focus stop) only when it has no button to land on.
    Panel("Game data location", readable = !canMove) {
        Text(
            "Client data and the server's SQL: about 4 GB. Characters and the database always stay in " +
                "internal storage.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            DataLocation.entries.joinToString("\n") { location ->
                val marker = if (location == storage.current) "● " else "○ "
                val free = storage.free[location]?.let { "%.1f GB free".format(it / GB) } ?: "not found"
                "$marker${location.label}: $free"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!storage.available) {
            Text(
                "SD card not found. Insert it, or switch to internal storage (the data stays on the card).",
                color = MaterialTheme.colorScheme.error,
            )
        }
        when (val p = progress) {
            is ClientDataInstaller.Progress.Moving ->
                Meter("Moving to ${p.to.label}", p.bytes / 1024, p.total / 1024, "${mb(p.bytes / 1024)} of ${mb(p.total / 1024)}")
            else -> {
                if (realmRunning) {
                    Text("Stop the realm to change where the data lives.", style = MaterialTheme.typography.bodySmall)
                }
                if (other in storage.free) {
                    OutlinedButton(
                        onClick = { SetupService.move(context, other) },
                        enabled = canMove,
                        modifier = Modifier.fillMaxWidth().focusRing(ButtonDefaults.shape),
                    ) {
                        Text(
                            when {
                                storage.installed -> "Move game data to ${other.label}"
                                else -> "Keep game data on ${other.label}"
                            },
                        )
                    }
                    if (storage.installed) {
                        Text(
                            "Copies the data across, then deletes it here. Takes a few minutes; the SD card " +
                                "is slower than internal storage, mostly when the world loads maps.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
private const val GB = 1024.0 * 1024 * 1024
