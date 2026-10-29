package com.lscardinali.pocketwow.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/** Live tail of the servers' log files, so problems can be read without adb. */
@Composable
fun LogsScreen(modifier: Modifier) {
    val files = LocalContext.current.filesDir
    var selected by rememberSaveable { mutableStateOf(0) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var text by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    LaunchedEffect(selected, follow) {
        while (true) {
            text = withContext(Dispatchers.IO) { tail(File(files, LOGS[selected].second)) }
            if (follow) {
                // Let the new text lay out before jumping to its end.
                delay(50)
                scroll.scrollTo(scroll.maxValue)
            }
            if (!follow) break
            delay(2_000)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Logs", "The last ${TAIL_BYTES / 1024} KB of each server's log")
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LOGS.forEachIndexed { index, (name, _) ->
                FilterChip(selected = index == selected, onClick = { selected = index }, label = { Text(name) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Follow new lines", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(follow, { follow = it })
        }
        Panel(modifier = Modifier.weight(1f)) {
            SelectionContainer(Modifier.fillMaxWidth().verticalScroll(scroll)) {
                Text(
                    text.ifEmpty { "(empty)" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Name and path under filesDir. */
private val LOGS = listOf(
    "World" to "azerothcore/logs/Server.log",
    "Errors" to "azerothcore/logs/Errors.log",
    "Playerbots" to "azerothcore/logs/Playerbots.log",
    "Login" to "azerothcore/logs/Auth.log",
    "Database" to "mariadb/mariadb.err",
)

private const val TAIL_BYTES = 24 * 1024

private fun tail(file: File): String {
    if (!file.isFile) return "(no log yet)"
    return RandomAccessFile(file, "r").use { raf ->
        val start = (raf.length() - TAIL_BYTES).coerceAtLeast(0)
        raf.seek(start)
        val bytes = ByteArray((raf.length() - start).toInt())
        raf.readFully(bytes)
        // Drop the partial first line when starting mid-file.
        String(bytes).let { if (start > 0) it.substringAfter('\n') else it }
    }
}
