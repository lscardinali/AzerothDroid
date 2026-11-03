package com.lscardinali.azerothdroid.realm

import android.content.Context
import android.net.wifi.WifiManager
import android.os.FileObserver
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Spends battery on the realm only while someone plays.
 *
 * - While a real player is connected, holds a partial wake lock and a Wi-Fi lock, so play
 *   from another device continues with this phone's screen off.
 * - With nobody connected for [IDLE_MS] (bots log out after 5 minutes without a player),
 *   saves and freezes the world server with SIGSTOP: no CPU at all, even with the device awake.
 *   The auth server and database stay up; they cost a few percent of a core.
 * - A successful login in Auth.log resumes the world before the client reaches it; the
 *   kernel queues the client's world connection meanwhile.
 *
 * Pausing is off when [pauseWhenIdle] is false (bots should live on without players).
 */
class IdleGuard(
    context: Context,
    private val world: WorldServer,
    private val console: WorldConsole,
    private val authLog: File,
    private val pauseWhenIdle: Boolean,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var lastActiveAt = SystemClock.elapsedRealtime()
    private var authLogOffset = authLog.length()

    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AzerothDroid:players")
        .apply { setReferenceCounted(false) }

    @Suppress("DEPRECATION")
    private val wifiLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AzerothDroid:players")
        .apply { setReferenceCounted(false) }

    // Auth.log is recreated on every auth start, so watch its directory, not the file.
    @Suppress("DEPRECATION")
    private val loginObserver = object : FileObserver(authLog.parent, MODIFY or CREATE) {
        override fun onEvent(event: Int, path: String?) {
            if (path == authLog.name) scheduler.execute { checkLogins() }
        }
    }

    fun start() {
        loginObserver.startWatching()
        scheduler.scheduleWithFixedDelay(::poll, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS)
    }

    /** Before stopping the stack; leaves the world running (or resumed) for its own stop. */
    fun stop() {
        loginObserver.stopWatching()
        scheduler.shutdownNow()
        world.resume()
        releaseLocks()
    }

    private fun poll() {
        if (world.state != WorldServer.State.RUNNING) {
            releaseLocks()
            return
        }
        val players = runCatching { connectedPlayers(console.execute("server info")) }
            .onFailure { Log.w(TAG, "server info failed", it) }
            .getOrNull() ?: return
        val now = SystemClock.elapsedRealtime()
        if (players > 0) {
            lastActiveAt = now
            wakeLock.acquire(LOCK_TIMEOUT_MS)
            if (!wifiLock.isHeld) wifiLock.acquire()
            return
        }
        releaseLocks()
        if (pauseWhenIdle && now - lastActiveAt >= IDLE_MS) {
            runCatching { console.execute("saveall") }.onFailure { Log.w(TAG, "saveall before pause failed", it) }
            world.pause()
        }
    }

    /** Reads what Auth.log gained since last time; a login wakes the world. */
    private fun checkLogins() {
        val added = runCatching {
            RandomAccessFile(authLog, "r").use { file ->
                // A new auth start truncates the log.
                if (file.length() < authLogOffset) authLogOffset = 0
                file.seek(authLogOffset)
                val bytes = ByteArray((file.length() - authLogOffset).toInt())
                file.readFully(bytes)
                authLogOffset += bytes.size
                String(bytes)
            }
        }.getOrNull() ?: return
        if (AuthServer.LOGIN_MARKER in added) {
            lastActiveAt = SystemClock.elapsedRealtime()
            if (world.state == WorldServer.State.PAUSED) {
                Log.i(TAG, "Login: resuming the world server")
                world.resume()
            }
        }
    }

    private fun releaseLocks() {
        if (wakeLock.isHeld) wakeLock.release()
        if (wifiLock.isHeld) wifiLock.release()
    }

    companion object {
        private const val TAG = "IdleGuard"
        private const val POLL_SECONDS = 30L
        /** Longer than playerbots' 300 s logout delay, so bots are gone and saved first. */
        private val IDLE_MS = TimeUnit.MINUTES.toMillis(6)
        /** Renewed on every poll while players are on; lapses if the app stops polling. */
        private val LOCK_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(2)
        private val CONNECTED = Regex("""Connected players: (\d+)""")

        fun connectedPlayers(serverInfo: String): Int? =
            CONNECTED.find(serverInfo)?.groupValues?.get(1)?.toIntOrNull()
    }
}
