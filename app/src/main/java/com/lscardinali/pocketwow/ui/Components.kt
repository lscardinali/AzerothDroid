package com.lscardinali.pocketwow.ui

import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.lscardinali.pocketwow.ui.theme.Blood
import com.lscardinali.pocketwow.ui.theme.Ember
import com.lscardinali.pocketwow.ui.theme.Faded
import com.lscardinali.pocketwow.ui.theme.Fel
import com.lscardinali.pocketwow.ui.theme.Frost
import com.lscardinali.pocketwow.ui.theme.Gold

/**
 * A titled section of a tab. Pass a smaller [contentPadding] for rows that pad themselves
 * (ListItem, [SwitchRow]); the title stays 16 dp in either way. A [readable] panel is a focus
 * stop of its own, so a controller can scroll a card with nothing to press into view.
 */
@Composable
fun Panel(
    title: String? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    readable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focus = if (readable) Modifier.focusRing(CardDefaults.elevatedShape).focusable() else Modifier
    ElevatedCard(modifier.fillMaxWidth().then(focus)) {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            title?.let {
                val inset = (16.dp - contentPadding.calculateStartPadding(LayoutDirection.Ltr)).coerceAtLeast(0.dp)
                Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = inset))
            }
            content()
        }
    }
}

/** Padding for a [Panel] of [SwitchRow]s: rows and [PanelInset] content then line up at 16 dp. */
val RowPanelPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp)

/** Wraps non-row content in a [RowPanelPadding] panel. */
@Composable
fun PanelInset(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** How a component's state reads at a glance. */
enum class Health(val color: Color, val label: String) {
    Up(Fel, "Online"),
    Busy(Gold, "Starting"),
    Down(Faded, "Offline"),
    Broken(Blood, "Failed"),
    Stopping(Ember, "Stopping"),
    /** World server frozen until someone logs in. */
    Sleeping(Frost, "Sleeping"),
    ;

    companion object {
        /** Maps the servers' State enum names (RUNNING, STARTING, ...) to a [Health]. */
        fun of(state: String?): Health = when (state) {
            "RUNNING" -> Up
            "STARTING" -> Busy
            "STOPPING" -> Stopping
            "PAUSED" -> Sleeping
            "FAILED" -> Broken
            else -> Down
        }
    }
}

/** A tinted pill with a status dot; not interactive. */
@Composable
fun HealthBadge(health: Health, label: String = health.label) {
    Surface(shape = CircleShape, color = health.color.copy(alpha = 0.14f), contentColor = health.color) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(8.dp), shape = CircleShape, color = health.color) {}
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
        }
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { fraction },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Heading above a group of rows inside a [Panel]. */
@Composable
fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** List rows sit on the card, not on a surface of their own. */
@Composable
fun cardListItemColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

/**
 * A switch row that is one focus stop: A toggles it, and the whole row shows the focus ring
 * instead of the small thumb. Pads itself by 8 dp, for a [RowPanelPadding] panel.
 */
@Composable
fun SwitchRow(
    label: String,
    note: String?,
    checked: Boolean,
    modifier: Modifier = Modifier,
    extra: (@Composable () -> Unit)? = null,
    onChange: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .focusRing(shape, outset = 0.dp)
            .clip(shape)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            extra?.invoke()
        }
        Switch(checked, onCheckedChange = null, modifier = Modifier.padding(start = 16.dp))
    }
}

/**
 * An [OutlinedTextField] that a controller can pass through: D-pad up/down always leave it,
 * and the keyboard opens only when the field is activated (A, Enter or a tap), not when focus
 * merely lands on it. Compose's own D-pad escape ignores virtual and joystick-synthesised keys.
 */
@Composable
fun ControllerTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    supportingText: (@Composable () -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // Read-only until activated, so focusing does not start an input session.
    var editing by remember { mutableStateOf(false) }
    fun activate(): Boolean {
        editing = true
        keyboard?.show()
        return true
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        readOnly = !editing,
        isError = isError,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        supportingText = supportingText,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = RingColor, focusedLabelColor = RingColor),
        modifier = modifier
            .fillMaxWidth()
            // No ring around label and supporting text: the outline, in ring colour, shows focus.
            .onFocusChanged { if (!it.isFocused) editing = false }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(pass = PointerEventPass.Initial)
                    editing = true
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key.nativeKeyCode) {
                    KEYCODE_DPAD_UP -> focusManager.moveFocus(FocusDirection.Up)
                    KEYCODE_DPAD_DOWN -> focusManager.moveFocus(FocusDirection.Down)
                    // Enter while editing is the IME action; A (centre) reopens a dismissed keyboard.
                    KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER -> if (editing) false else activate()
                    KEYCODE_DPAD_CENTER -> activate()
                    else -> false
                }
            },
    )
}

/**
 * Draws a frost ring around the element while it or a child has focus. Material's own focus
 * state layer is too faint on slate to follow with a controller from arm's length, and frost
 * stands apart from gold, which already means "selected". [outset] moves the ring off the
 * element's edge; use 0 where a parent clips (rows inside cards).
 */
fun Modifier.focusRing(shape: Shape, outset: Dp = 3.dp): Modifier = this then FocusRingElement(shape, outset)

private data class FocusRingElement(val shape: Shape, val outset: Dp) : ModifierNodeElement<FocusRingNode>() {
    override fun create() = FocusRingNode(shape, outset)
    override fun update(node: FocusRingNode) {
        node.shape = shape
        node.outset = outset
        node.invalidateDraw()
    }
}

private class FocusRingNode(var shape: Shape, var outset: Dp) : Modifier.Node(), FocusEventModifierNode, DrawModifierNode {
    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        if (focused != focusState.hasFocus) {
            focused = focusState.hasFocus
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        if (!focused) return drawContent()
        // Inside a clipping parent the ring alone is thin, so also tint the element.
        if (outset == 0.dp) drawOutline(shape.createOutline(size, layoutDirection, this), RingColor.copy(alpha = 0.12f))
        drawContent()
        val o = outset.toPx()
        val ringSize = Size(size.width + 2 * o, size.height + 2 * o)
        translate(-o, -o) {
            drawOutline(shape.createOutline(ringSize, layoutDirection, this), RingColor, style = Stroke(RingWidth.toPx()))
        }
    }
}

private val RingColor = Frost
private val RingWidth = 2.dp

fun mb(kb: Long?): String = if (kb == null) "?" else "%.0f MB".format(kb / 1024.0)
