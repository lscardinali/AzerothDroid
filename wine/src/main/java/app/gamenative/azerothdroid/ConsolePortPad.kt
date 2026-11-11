package app.gamenative.azerothdroid

import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import app.gamenative.PluviaApp
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Drives the ConsolePortLK addon from a gamepad, the way WoWpadX does on Windows: WoW 3.3.5a has no
 * gamepad support, so every button becomes a keyboard key that ConsolePortLK has calibrated to a
 * controller button, the left stick becomes W/A/S/D (+ H/V for 16-way movement) and the right stick
 * moves the mouse. A port of WoWpadX 1.0.0's InputMapper with its default bindings and settings
 * (KeybindDefaults.h, AppSettings.h); [ConsolePortLayout] holds the same table for the addon's side.
 *
 * Keys go straight into GameNative's X server, so Wine sees an ordinary keyboard and mouse.
 * Events from other devices (touch, a real keyboard) are left alone.
 */
class ConsolePortPad {

    private val pressed = HashSet<XKeycode>()
    private val mouseDown = HashSet<Pointer.Button>()

    @Volatile private var leftX = 0f
    @Volatile private var leftY = 0f
    @Volatile private var rightX = 0f
    @Volatile private var rightY = 0f

    // Triggers and the d-pad come as buttons and as axes, often both at once: the Retroid Pocket 6
    // reports BTN_TL2 with ABS_BRAKE, and BTN_DPAD_* with ABS_HAT0X/Y. A key is held while either
    // source holds it. Following only the latest report released it whenever the other disagreed,
    // e.g. L2's Ctrl (ConsolePort's second modifier) dropped every time a stick moved.
    private val fromButton = HashSet<XKeycode>()
    private val fromAxis = HashSet<XKeycode>()

    private var ticker: Thread? = null

    private val xServer: XServer? get() = PluviaApp.xServerView?.getxServer()

    /** Returns true for events it consumed. */
    fun onEvent(event: InputEvent): Boolean {
        if (!fromGamepad(event)) return false
        val server = xServer ?: return false
        return when (event) {
            is KeyEvent -> onKey(server, event)
            is MotionEvent -> onMotion(server, event)
            else -> false
        }
    }

    private fun onKey(server: XServer, event: KeyEvent): Boolean {
        if (event.repeatCount > 0) return true
        val down = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_THUMBL -> mouse(server, Pointer.Button.BUTTON_LEFT, down)
            KeyEvent.KEYCODE_BUTTON_THUMBR -> mouse(server, Pointer.Button.BUTTON_RIGHT, down)
            else -> held(server, ConsolePortLayout.BUTTON_KEYS[event.keyCode] ?: return false, fromButton, down)
        }
        return true
    }

    /** Records that [source] holds [keycode] or not, and presses it while any source holds it. */
    private fun held(server: XServer, keycode: XKeycode, source: MutableSet<XKeycode>, down: Boolean) {
        val any = synchronized(pressed) {
            if (down) source += keycode else source -= keycode
            keycode in fromButton || keycode in fromAxis
        }
        key(server, keycode, any)
    }

    private fun onMotion(server: XServer, event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_MOVE) return false
        leftX = event.getAxisValue(MotionEvent.AXIS_X)
        leftY = event.getAxisValue(MotionEvent.AXIS_Y)
        rightX = event.getAxisValue(MotionEvent.AXIS_Z)
        rightY = event.getAxisValue(MotionEvent.AXIS_RZ)

        // Triggers: analog on most pads (LTRIGGER/BRAKE), past WoWpadX's 80/250.
        val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        held(server, ConsolePortLayout.L2, fromAxis, lt > TRIGGER_THRESHOLD)
        held(server, ConsolePortLayout.R2, fromAxis, rt > TRIGGER_THRESHOLD)

        // The d-pad arrives as a hat on many pads, instead of or as well as DPAD keys.
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (event.device?.getMotionRange(MotionEvent.AXIS_HAT_X) != null) {
            held(server, ConsolePortLayout.DPAD_LEFT, fromAxis, hatX < -0.5f)
            held(server, ConsolePortLayout.DPAD_RIGHT, fromAxis, hatX > 0.5f)
            held(server, ConsolePortLayout.DPAD_UP, fromAxis, hatY < -0.5f)
            held(server, ConsolePortLayout.DPAD_DOWN, fromAxis, hatY > 0.5f)
        }
        ensureTicker()
        return true
    }

    /** Sticks are polled like WoWpadX's InputWatcher, so a held stick keeps the cursor moving. */
    private fun ensureTicker() {
        if (ticker?.isAlive == true) return
        ticker = Thread({
            while (!Thread.currentThread().isInterrupted) {
                xServer?.let { tick(it) }
                try {
                    Thread.sleep(TICK_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "ConsolePortPad").apply {
            isDaemon = true
            start()
        }
    }

    private fun tick(server: XServer) {
        // Movement (UpdateMovement): W/A/S/D past the threshold; H or V when one axis clearly dominates.
        val x = leftX
        val y = leftY
        val left = -x > MOVE_THRESHOLD
        val right = x > MOVE_THRESHOLD
        val up = -y > MOVE_THRESHOLD
        val down = y > MOVE_THRESHOLD
        val horz = left || right
        val vert = up || down
        key(server, XKeycode.KEY_A, left)
        key(server, XKeycode.KEY_D, right)
        key(server, XKeycode.KEY_W, up)
        key(server, XKeycode.KEY_S, down)
        key(server, XKeycode.KEY_H, horz && vert && abs(x) > abs(y) * 1.5f)
        key(server, XKeycode.KEY_V, horz && vert && abs(y) > abs(x) * 1.5f)

        // Cursor (UpdateCursorMovement): radial deadzone, then WoWpadX's speed curve.
        val rx = rightX
        val ry = rightY
        val mag = sqrt(rx * rx + ry * ry)
        if (mag < CURSOR_DEADZONE) {
            carryX = 0f
            carryY = 0f
            return
        }
        val scale = (mag - CURSOR_DEADZONE) / (1f - CURSOR_DEADZONE) / mag
        // WoWpadX's curve gives pixels per 5 ms tick; this ticks less often, so scale up to the same speed.
        val perTick = cursorScale(server) * TICK_MS / WOWPADX_TICK_MS
        // Keep the fractions, so slow turns move evenly instead of in whole-pixel jumps (or not at all).
        carryX += curve(rx * scale) * perTick
        carryY += curve(ry * scale) * perTick
        val dx = carryX.toInt()
        val dy = carryY.toInt()
        carryX -= dx
        carryY -= dy
        if (dx != 0 || dy != 0) server.injectPointerMoveDelta(dx, dy)
    }

    private var carryX = 0f
    private var carryY = 0f

    private fun curve(v: Float): Float {
        val a = abs(v) * CURSOR_SPEED * CURSOR_CURVE * 0.05f
        val scaled = a.pow(2) + a
        return if (v < 0) -scaled else scaled
    }

    /** WoWpadX's speeds are desktop pixels at about 1080p; the game screen here is smaller. */
    private fun cursorScale(server: XServer): Float = server.screenInfo.height / 1080f

    private fun key(server: XServer, keycode: XKeycode, down: Boolean) {
        synchronized(pressed) {
            if (down == keycode in pressed) return
            if (down) pressed += keycode else pressed -= keycode
        }
        if (down) server.injectKeyPress(keycode) else server.injectKeyRelease(keycode)
    }

    private fun mouse(server: XServer, button: Pointer.Button, down: Boolean) {
        synchronized(mouseDown) {
            if (down == button in mouseDown) return
            if (down) mouseDown += button else mouseDown -= button
        }
        if (down) server.injectPointerButtonPress(button) else server.injectPointerButtonRelease(button)
    }

    /** Lets go of everything, e.g. when the game loses focus, so no key stays stuck down in WoW. */
    fun releaseAll() {
        leftX = 0f; leftY = 0f; rightX = 0f; rightY = 0f
        synchronized(pressed) { fromButton.clear(); fromAxis.clear() }
        val server = xServer
        val keys = synchronized(pressed) { pressed.toList().also { pressed.clear() } }
        val buttons = synchronized(mouseDown) { mouseDown.toList().also { mouseDown.clear() } }
        if (server != null) {
            keys.forEach(server::injectKeyRelease)
            buttons.forEach(server::injectPointerButtonRelease)
        }
    }

    fun stop() {
        releaseAll()
        ticker?.interrupt()
        ticker = null
    }

    private fun fromGamepad(event: InputEvent): Boolean {
        val sources = event.device?.sources ?: event.source
        return sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    private companion object {
        /**
         * 125 updates a second. WoWpadX polls every 5 ms, which costs nothing on Windows; here every
         * pointer move goes through the X server, Wine and wineserver, competing with the game's main
         * thread, and the game only reads the mouse once a frame (60 a second) anyway.
         */
        const val TICK_MS = 8L
        const val WOWPADX_TICK_MS = 5f
        // WoWpadX defaults, rescaled from its units (stick /127, triggers /250) to Android's 0..1.
        const val MOVE_THRESHOLD = 40f / 127f
        const val TRIGGER_THRESHOLD = 80f / 250f
        const val CURSOR_DEADZONE = 20f / 127f
        const val CURSOR_SPEED = 16f
        const val CURSOR_CURVE = 4f
    }
}

/**
 * WoWpadX's default keyboard layout (modifier style 0), shared by [ConsolePortPad] and the
 * ConsolePortLK calibration the host app writes, so both sides agree on every key.
 */
object ConsolePortLayout {
    val DPAD_UP = XKeycode.KEY_F1
    val DPAD_RIGHT = XKeycode.KEY_F2
    val DPAD_DOWN = XKeycode.KEY_F3
    val DPAD_LEFT = XKeycode.KEY_F4
    val L2 = XKeycode.KEY_CTRL_L
    val R2 = XKeycode.KEY_F8

    /** Android key code to X key. */
    val BUTTON_KEYS: Map<Int, XKeycode> = mapOf(
        KeyEvent.KEYCODE_BUTTON_Y to XKeycode.KEY_F9,
        KeyEvent.KEYCODE_BUTTON_B to XKeycode.KEY_F10,
        KeyEvent.KEYCODE_BUTTON_A to XKeycode.KEY_F11,
        KeyEvent.KEYCODE_BUTTON_X to XKeycode.KEY_F12,
        KeyEvent.KEYCODE_DPAD_UP to DPAD_UP,
        KeyEvent.KEYCODE_DPAD_RIGHT to DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_DOWN to DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to DPAD_LEFT,
        KeyEvent.KEYCODE_BUTTON_SELECT to XKeycode.KEY_F5,
        KeyEvent.KEYCODE_BUTTON_START to XKeycode.KEY_F6,
        KeyEvent.KEYCODE_BUTTON_MODE to XKeycode.KEY_KP_MULTIPLY,
        KeyEvent.KEYCODE_BUTTON_L1 to XKeycode.KEY_SHIFT_L,
        KeyEvent.KEYCODE_BUTTON_R1 to XKeycode.KEY_F7,
        KeyEvent.KEYCODE_BUTTON_L2 to L2,
        KeyEvent.KEYCODE_BUTTON_R2 to R2,
    )

    /**
     * ConsolePortLK's calibration: its button names to WoW key names, matching [BUTTON_KEYS].
     * L1 and L2 are its modifiers (Shift and Ctrl), which it needs no calibration for.
     */
    val CALIBRATION: Map<String, String> = linkedMapOf(
        "CP_R_UP" to "F9",
        "CP_R_RIGHT" to "F10",
        "CP_R_DOWN" to "F11",
        "CP_R_LEFT" to "F12",
        "CP_L_UP" to "F1",
        "CP_L_RIGHT" to "F2",
        "CP_L_DOWN" to "F3",
        "CP_L_LEFT" to "F4",
        "CP_X_LEFT" to "F5",
        "CP_X_RIGHT" to "F6",
        "CP_X_CENTER" to "NUMPADMULTIPLY",
        "CP_T1" to "F7",
        "CP_T2" to "F8",
    )
}
