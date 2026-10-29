package com.lscardinali.pocketwow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.lscardinali.pocketwow.ui.theme.Blood
import com.lscardinali.pocketwow.ui.theme.Ember
import com.lscardinali.pocketwow.ui.theme.Faded
import com.lscardinali.pocketwow.ui.theme.Fel
import com.lscardinali.pocketwow.ui.theme.Gold

/** Title and one line of context at the top of every tab. */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** The app's card: raised slate with a thin gilded rim and an optional serif title. */
@Composable
fun Panel(
    title: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null || trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

/** How a component's state reads at a glance. */
enum class Health(val color: Color, val label: String) {
    Up(Fel, "Online"),
    Busy(Gold, "Starting"),
    Down(Faded, "Offline"),
    Broken(Blood, "Failed"),
    Stopping(Ember, "Stopping"),
    ;

    companion object {
        /** Maps the servers' State enum names (RUNNING, STARTING, ...) to a [Health]. */
        fun of(state: String?): Health = when (state) {
            "RUNNING" -> Up
            "STARTING" -> Busy
            "STOPPING" -> Stopping
            "FAILED" -> Broken
            else -> Down
        }
    }
}

@Composable
fun HealthBadge(health: Health, label: String = health.label) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(health.color),
        )
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = health.color,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** A labelled usage bar; turns ember then blood as it fills. */
@Composable
fun Meter(label: String, usedKb: Long, totalKb: Long, detail: String) {
    val fraction = if (totalKb > 0) (usedKb.toFloat() / totalKb).coerceIn(0f, 1f) else 0f
    val color = when {
        fraction > 0.9f -> Blood
        fraction > 0.75f -> Ember
        else -> MaterialTheme.colorScheme.secondary
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { fraction },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
        )
    }
}

/** Small uppercase caption above a group of rows. */
@Composable
fun Caption(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Gold.copy(alpha = 0.7f))
}

fun mb(kb: Long?): String = if (kb == null) "?" else "%.0f MB".format(kb / 1024.0)
