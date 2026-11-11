package com.lscardinali.azerothdroid.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.lscardinali.azerothdroid.game.GameLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private data class ClientState(val access: Boolean, val client: File?, val gameRunning: Boolean = false)

/**
 * Starts the player's own WoW 3.3.5a client on this phone, logged into this realm.
 * [realmUp]: whether the world server is running, so logging in will work.
 */
@Composable
fun PlayPanel(realmUp: Boolean) {
    val context = LocalContext.current
    if (!GameLauncher.supported) return
    // Re-read: access is granted in system settings, and the card may come and go.
    val state by produceState(ClientState(GameLauncher.hasStorageAccess(), null)) {
        while (true) {
            val access = GameLauncher.hasStorageAccess()
            value = ClientState(
                access,
                if (access) withContext(Dispatchers.IO) { GameLauncher.findClient(context) } else null,
                app.gamenative.azerothdroid.GameActivity.isRunning,
            )
            delay(2_000)
        }
    }
    val buttonModifier = Modifier.fillMaxWidth().focusRing(ButtonDefaults.shape)

    Panel("Play") {
        when {
            !state.access -> {
                Text(
                    "To run your WoW client, the app needs access to all files: the game reads and writes " +
                        "its own folder, for example on the SD card.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = { context.startActivity(GameLauncher.storageAccessIntent(context)) },
                    modifier = buttonModifier,
                ) { Text("Allow file access") }
            }
            state.client == null -> Text(
                "No WoW 3.3.5a client found. Put it in Games/World of Warcraft on internal storage or the SD card " +
                    "(the folder with Wow.exe).",
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> {
                val client = state.client!!
                Text(client.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!realmUp && !state.gameRunning) {
                    Text(
                        "Play starts the realm too. Its first start takes a few minutes; the game waits at the login screen.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(onClick = { GameLauncher.launch(context, client) }, modifier = buttonModifier) {
                    Text(if (state.gameRunning) "Return to the game" else "Play World of Warcraft")
                }
                Text(
                    if (state.gameRunning) {
                        "The game is frozen while you are away from it, and the realm keeps you logged in for 15 minutes."
                    } else {
                        "The first start downloads and sets up the Windows runtime: about 180 MB, a few minutes."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
