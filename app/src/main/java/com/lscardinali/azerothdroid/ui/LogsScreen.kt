package com.lscardinali.azerothdroid.ui

import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_UP
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Live tail of the servers' log files, so problems can be read without adb.
 * [primaryFocus] goes on the selected log's chip.
 */
@Composable
fun LogsScreen(modifier: Modifier, primaryFocus: FocusRequester) {
    val files = LocalContext.current.filesDir
    var selected by rememberSaveable { mutableStateOf(0) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var text by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

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
        // Entering the chips from below lands on the selected log.
        Row(
            Modifier
                .focusProperties { onEnter = { primaryFocus.requestFocus() } }
                .focusGroup()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LOGS.forEachIndexed { index, (name, _) ->
                FilterChip(
                    selected = index == selected,
                    onClick = { selected = index },
                    label = { Text(name) },
                    modifier = Modifier
                        .then(if (index == selected) Modifier.focusRequester(primaryFocus) else Modifier)
                        .focusRing(FilterChipDefaults.shape),
                )
            }
        }
        Panel(modifier = Modifier.weight(1f), contentPadding = RowPanelPadding) {
            SwitchRow("Follow new lines", null, follow) { follow = it }
            val shape = RoundedCornerShape(8.dp)
            // A focus stop of its own, so a controller can page through the text with up and
            // down; at either end those keys move focus on as usual. While following, the text
            // is pinned to its end anyway, so the keys just pass through.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .focusRing(shape, outset = 0.dp)
                    .onPreviewKeyEvent { event ->
                        if (follow) return@onPreviewKeyEvent false
                        val direction = when (event.key.nativeKeyCode) {
                            KEYCODE_DPAD_UP -> -1
                            KEYCODE_DPAD_DOWN -> 1
                            else -> return@onPreviewKeyEvent false
                        }
                        val canScroll = if (direction < 0) scroll.canScrollBackward else scroll.canScrollForward
                        if (!canScroll) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown) {
                            val step = direction * scroll.viewportSize * 0.6f
                            // A held key repeats faster than the animation runs: jump instead.
                            scope.launch {
                                if (event.nativeKeyEvent.repeatCount > 0) scroll.scrollBy(step) else scroll.animateScrollBy(step)
                            }
                        }
                        true
                    }
                    .focusable()
                    .verticalScroll(scroll)
                    .padding(8.dp),
            ) {
                SelectionContainer(Modifier.focusProperties { canFocus = false }) {
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
}

/** Name and path under filesDir. */
private val LOGS = listOf(
    "World" to "azerothcore/logs/Server.log",
    "Errors" to "azerothcore/logs/Errors.log",
    "Playerbots" to "azerothcore/logs/Playerbots.log",
    "Login" to "azerothcore/logs/Auth.log",
    "Database" to "mariadb/mariadb.err",
)

/** How much of each log the tab shows; the tab bar's subtitle quotes it. */
const val LOG_TAIL_BYTES = 24 * 1024

private fun tail(file: File): String {
    if (!file.isFile) return "(no log yet)"
    return RandomAccessFile(file, "r").use { raf ->
        val start = (raf.length() - LOG_TAIL_BYTES).coerceAtLeast(0)
        raf.seek(start)
        val bytes = ByteArray((raf.length() - start).toInt())
        raf.readFully(bytes)
        // Drop the partial first line when starting mid-file.
        String(bytes).let { if (start > 0) it.substringAfter('\n') else it }
    }
}
