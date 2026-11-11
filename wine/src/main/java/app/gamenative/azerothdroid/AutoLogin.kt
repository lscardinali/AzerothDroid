package app.gamenative.azerothdroid

import android.os.SystemClock
import app.gamenative.PluviaApp
import com.winlator.xserver.Window
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer
import timber.log.Timber

/**
 * Logs the 3.3.5a client in by typing, as a player would: WoW 3.3.5a has no auto-login file (the
 * login.txt WoWForeverywhere writes is for the modern client), and its login screen can't be scripted
 * by addons. With the account name saved in Config.wtf (accountName), the client opens on the
 * login screen with the password field focused, so typing the password and Enter logs in.
 *
 * Waits for the wow.exe window, then [SETTLE_MS] for the login screen to load, then for
 * [realmReady] (the client would fail to connect before the world server is up), and types once.
 */
class AutoLogin(private val password: String, private val realmReady: () -> Boolean) {

    @Volatile private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        thread = Thread(::run, "AutoLogin").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        thread?.interrupt()
    }

    private fun run() {
        try {
            val deadline = SystemClock.elapsedRealtime() + GIVE_UP_MS
            var shownAt = 0L
            while (SystemClock.elapsedRealtime() < deadline) {
                val server = PluviaApp.xServerView?.getxServer()
                if (server != null && shownAt == 0L && gameWindowShown(server)) shownAt = SystemClock.elapsedRealtime()
                val settled = shownAt != 0L && SystemClock.elapsedRealtime() - shownAt > SETTLE_MS
                if (server != null && settled && realmReady()) {
                    type(server)
                    return
                }
                Thread.sleep(POLL_MS)
            }
            Timber.w("Auto-login gave up: the game or the realm did not come up")
        } catch (_: InterruptedException) {
        }
    }

    private fun gameWindowShown(server: XServer): Boolean {
        fun search(window: Window): Boolean =
            window.getChildren().any { child ->
                (child.isRenderable() && child.getClassName().equals("wow.exe", ignoreCase = true)) || search(child)
            }
        return runCatching { search(server.windowManager.rootWindow) }.getOrDefault(false)
    }

    private fun type(server: XServer) {
        val keys = password.map { keyFor(it) }
        if (keys.any { it == null }) {
            Timber.w("Auto-login skipped: the password has a character it can't type")
            return
        }
        Timber.i("Auto-login: typing the password")
        keys.forEach { key ->
            val (keycode, shift) = key!!
            if (shift) server.injectKeyPress(XKeycode.KEY_SHIFT_L)
            server.injectKeyPress(keycode)
            Thread.sleep(KEY_MS)
            server.injectKeyRelease(keycode)
            if (shift) server.injectKeyRelease(XKeycode.KEY_SHIFT_L)
            Thread.sleep(KEY_MS)
        }
        server.injectKeyPress(XKeycode.KEY_ENTER)
        Thread.sleep(KEY_MS)
        server.injectKeyRelease(XKeycode.KEY_ENTER)
    }

    private companion object {
        const val POLL_MS = 500L
        /** The login screen loads its art and models a few seconds after the window appears. */
        const val SETTLE_MS = 8_000L
        const val KEY_MS = 30L
        /** The realm's first start imports its databases and can take many minutes. */
        const val GIVE_UP_MS = 30 * 60_000L

        /** US layout, which is what Wine's X keyboard uses here. Null for characters it can't type. */
        fun keyFor(c: Char): Pair<XKeycode, Boolean>? {
            if (c in 'a'..'z' || c in 'A'..'Z') return XKeycode.valueOf("KEY_${c.uppercaseChar()}") to false
            if (c in '0'..'9') return XKeycode.valueOf("KEY_$c") to false
            return when (c) {
                '-' -> XKeycode.KEY_MINUS to false
                '=' -> XKeycode.KEY_EQUAL to false
                '[' -> XKeycode.KEY_BRACKET_LEFT to false
                ']' -> XKeycode.KEY_BRACKET_RIGHT to false
                '\\' -> XKeycode.KEY_BACKSLASH to false
                ';' -> XKeycode.KEY_SEMICOLON to false
                '\'' -> XKeycode.KEY_APOSTROPHE to false
                '`' -> XKeycode.KEY_GRAVE to false
                ',' -> XKeycode.KEY_COMMA to false
                '.' -> XKeycode.KEY_PERIOD to false
                '/' -> XKeycode.KEY_SLASH to false
                '!' -> XKeycode.KEY_1 to true
                '@' -> XKeycode.KEY_2 to true
                '#' -> XKeycode.KEY_3 to true
                '$' -> XKeycode.KEY_4 to true
                '%' -> XKeycode.KEY_5 to true
                '^' -> XKeycode.KEY_6 to true
                '&' -> XKeycode.KEY_7 to true
                '*' -> XKeycode.KEY_8 to true
                '(' -> XKeycode.KEY_9 to true
                ')' -> XKeycode.KEY_0 to true
                '_' -> XKeycode.KEY_MINUS to true
                '+' -> XKeycode.KEY_EQUAL to true
                '{' -> XKeycode.KEY_BRACKET_LEFT to true
                '}' -> XKeycode.KEY_BRACKET_RIGHT to true
                '|' -> XKeycode.KEY_BACKSLASH to true
                ':' -> XKeycode.KEY_SEMICOLON to true
                '"' -> XKeycode.KEY_APOSTROPHE to true
                '~' -> XKeycode.KEY_GRAVE to true
                '<' -> XKeycode.KEY_COMMA to true
                '>' -> XKeycode.KEY_PERIOD to true
                '?' -> XKeycode.KEY_SLASH to true
                else -> null
            }
        }
    }
}
