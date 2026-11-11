package app.gamenative.azerothdroid

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color.TRANSPARENT
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.screen.xserver.XServerScreen
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.LocalSnackbarHostController
import app.gamenative.ui.util.SnackbarHostController
import app.gamenative.wow.ui.BackPressGuard
import com.winlator.core.AppUtils
import com.winlator.inputcontrols.ControllerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Runs the WoW client full screen: prepares the container ([WowContainer]), then hosts GameNative's
 * XServerScreen, which starts Wine and draws the game. Plays the part of GameNative's MainActivity:
 * it routes keys and gamepad motion to the screen through [PluviaApp.events] and passes on pause,
 * resume and system UI requests.
 *
 * Gamepad events go to [ConsolePortPad] (gamepad to ConsolePortLK keys) before anything else;
 * [inputFilter], if the host app sets one, sees them even earlier.
 */
class GameActivity : ComponentActivity() {

    private val onSetSystemUi: (AndroidEvent.SetSystemUIVisibility) -> Unit = { hideSystemBars() }
    private val onEndProcess: (AndroidEvent.EndProcess) -> Unit = { finishAndRemoveTask() }

    /**
     * What Back does in game, registered by XServerScreen: opens GameNative's side menu (quick menu),
     * or closes it. In GameNative its MainActivity calls this on [AndroidEvent.BackPressed].
     */
    @Volatile private var gameBack: () -> Unit = {}
    private val onBackPressed: (AndroidEvent.BackPressed) -> Unit = { runOnUiThread { gameBack() } }

    /** The gamepad, mapped to the keys ConsolePortLK is calibrated to. */
    private val pad = ConsolePortPad()

    /** Types the password at the login screen, when the host app asked for it ([autoLogin]). */
    private var login: AutoLogin? = null

    private val tuning = GameTuning()

    private var inputManager: InputManager? = null
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = ControllerManager.getInstance().onDeviceConnected(deviceId)
        override fun onInputDeviceRemoved(deviceId: Int) = ControllerManager.getInstance().onDeviceDisconnected(deviceId)
        override fun onInputDeviceChanged(deviceId: Int) = ControllerManager.getInstance().onDeviceConnected(deviceId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(TRANSPARENT), SystemBarStyle.dark(TRANSPARENT))
        super.onCreate(savedInstanceState)
        PluviaApp.init(this)
        if (PluviaApp.keepAlive && PluviaApp.xEnvironment == null) PluviaApp.shutdownEnvironment()
        hideSystemBars()

        ControllerManager.getInstance().init(applicationContext)
        inputManager = getSystemService(InputManager::class.java)
        inputManager?.registerInputDeviceListener(deviceListener, null)
        AppUtils.keepScreenOn(this)
        PluviaApp.events.on<AndroidEvent.SetSystemUIVisibility, Unit>(onSetSystemUi)
        PluviaApp.events.on<AndroidEvent.EndProcess, Unit>(onEndProcess)
        PluviaApp.events.on<AndroidEvent.BackPressed, Unit>(onBackPressed)

        val gameRoot = File(requireNotNull(intent.getStringExtra(EXTRA_GAME_ROOT)) { "no game folder" })
        login = autoLogin?.also { autoLogin = null; it.start() }
        tuning.start()
        setContent {
            PluviaTheme {
                CompositionLocalProvider(LocalSnackbarHostController provides remember { SnackbarHostController() }) {
                    Game(gameRoot)
                }
            }
        }
    }

    @Composable
    private fun Game(gameRoot: File) {
        var containerId by remember { mutableStateOf<String?>(null) }
        var status by remember { mutableStateOf("Preparing") }
        var progress by remember { mutableStateOf(-1f) }
        var error by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            try {
                containerId = withContext(Dispatchers.IO) {
                    WowContainer.prepare(this@GameActivity, gameRoot) { text, value ->
                        status = text
                        progress = value
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Preparing the game failed")
                error = e.message ?: e.toString()
            }
        }

        val id = containerId
        if (id == null) {
            Loading(status, progress, error)
            return
        }
        XServerScreen(
            appId = id,
            registerBackAction = { action -> gameBack = action },
            navigateBack = { finish() },
            onExit = { onComplete ->
                onComplete?.invoke()
                finish()
            },
            onGameLaunchError = { message -> error = message },
        )
    }

    @Composable
    private fun Loading(status: String, progress: Float, error: String?) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(420.dp).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (error != null) {
                    Text("Could not start the game", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(error, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { finish() }) { Text("Back") }
                } else {
                    Text(status, color = Color.White)
                    if (progress in 0f..1f) {
                        LinearProgressIndicator(progress = { progress })
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            PluviaApp.events.emit(AndroidEvent.ActivityDestroyed)
            if (PluviaApp.keepAlive) PluviaApp.shutdownEnvironment()
        }
        super.onDestroy()
        inputManager?.unregisterInputDeviceListener(deviceListener)
        pad.stop()
        login?.stop()
        tuning.stop()
        PluviaApp.events.off<AndroidEvent.SetSystemUIVisibility, Unit>(onSetSystemUi)
        PluviaApp.events.off<AndroidEvent.EndProcess, Unit>(onEndProcess)
        PluviaApp.events.off<AndroidEvent.BackPressed, Unit>(onBackPressed)
    }

    private fun gameRunning() =
        PluviaApp.keepAlive && PluviaApp.hasValidSuspendPolicyState() && PluviaApp.xEnvironment != null

    override fun onResume() {
        super.onResume()
        PluviaApp.isActivityInForeground = true
        hideSystemBars()
        if (gameRunning() && !PluviaApp.isNeverSuspendMode()) {
            PluviaApp.xEnvironment?.onResume()
            PluviaApp.isOverlayPaused = false
        }
    }

    override fun onPause() {
        PluviaApp.isActivityInForeground = false
        pad.releaseAll()
        if (gameRunning() && !PluviaApp.isNeverSuspendMode()) PluviaApp.xEnvironment?.onPause()
        super.onPause()
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (inputFilter?.invoke(event) == true || pad.onEvent(event)) return true
        var handled = PluviaApp.events.emit(AndroidEvent.KeyEvent(event)) { results -> results.any { it } } == true
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            BackPressGuard.onKeyUp(event.eventTime)
        }
        if (!handled && event.keyCode == KeyEvent.KEYCODE_BACK && PluviaApp.keepAlive) {
            if (event.action == KeyEvent.ACTION_DOWN && BackPressGuard.onKeyDown(event.eventTime, event.repeatCount)) {
                PluviaApp.events.emit(AndroidEvent.BackPressed)
            }
            handled = true
        }
        return handled || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        if (ev != null && (inputFilter?.invoke(ev) == true || pad.onEvent(ev))) return true
        val handled = PluviaApp.events.emit(AndroidEvent.MotionEvent(ev)) { results -> results.any { it } } == true
        return handled || super.dispatchGenericMotionEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.apply {
            hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val EXTRA_GAME_ROOT = "gameRoot"

        /** Set by the host app; returns true for events it consumed. */
        @JvmStatic
        var inputFilter: ((InputEvent) -> Boolean)? = null

        /** Set by the host app before starting the game; used by the next launch only. */
        @JvmStatic
        var autoLogin: AutoLogin? = null

        /** Whether the game is running (possibly frozen in the background). */
        @JvmStatic
        val isRunning: Boolean get() = PluviaApp.xEnvironment != null

        fun intent(context: Context, gameRoot: File): Intent =
            Intent(context, GameActivity::class.java).putExtra(EXTRA_GAME_ROOT, gameRoot.path)
    }
}
