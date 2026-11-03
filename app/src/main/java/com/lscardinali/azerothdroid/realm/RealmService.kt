package com.lscardinali.azerothdroid.realm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lscardinali.azerothdroid.MainActivity
import com.lscardinali.azerothdroid.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Foreground service hosting the realm stack: MariaDB, authserver and worldserver (with
 * mod-playerbots), started in that order. It runs in the app's own process, so the UI reads
 * its state directly ([currentStatus]) and the device pays for one Android runtime, not two.
 *
 * Battery: [IdleGuard] holds a wake lock only while a real player is connected, and freezes
 * the world server when nobody has played for a while. Otherwise, when the device sleeps the
 * CPU suspends and every process freezes, then continues where it left off on wake. The
 * foreground state is what keeps Android from killing the stack while the WoW client is in front.
 *
 * Shutdown: Stop, service destruction and device power-off stop the stack in order (the world
 * server saves and kicks every player on SIGTERM). The low-memory killer gives no warning, so
 * the world also saves often (PlayerSaveInterval) and on critical memory pressure ([onTrimMemory]).
 */
class RealmService : Service() {

    private var running = false
    private lateinit var database: MariaDbServer
    private lateinit var auth: AuthServer
    private lateinit var world: WorldServer
    private lateinit var dataPack: DataPack
    private lateinit var console: WorldConsole
    @Volatile private var idleGuard: IdleGuard? = null
    /** `saveall` runs here, so a slow start or stop on [lifecycle] cannot delay it. */
    private val saves = Executors.newSingleThreadExecutor()
    @Volatile private var lastSaveAt = 0L
    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Device shutting down, stopping the realm")
            val pending = goAsync()
            lifecycle.execute {
                try {
                    stopStack()
                } finally {
                    pending.finish()
                }
            }
        }
    }
    /** Start/stop steps block for seconds, so they run off the main thread, in order. */
    private val lifecycle = Executors.newSingleThreadExecutor()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var restarting = false
    private val restartTimes = mutableListOf<Long>()
    private var startedAt = 0L
    @Volatile private var realmAddress: String? = null

    override fun onCreate() {
        super.onCreate()
        val native = NativeRuntime(this)
        database = MariaDbServer(this, native)
        dataPack = DataPack(this)
        auth = AuthServer(this, native, dataPack)
        world = WorldServer(this, native, dataPack)
        console = WorldConsole(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            lifecycle.execute {
                stopStack()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (!running) startRealm()
        return START_STICKY
    }

    private fun startRealm() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        startedAt = SystemClock.elapsedRealtime()
        running = true
        instance = this
        ContextCompat.registerReceiver(
            this, shutdownReceiver,
            IntentFilter(Intent.ACTION_SHUTDOWN).apply { addAction(ACTION_QUICKBOOT_POWEROFF) },
            ContextCompat.RECEIVER_EXPORTED,
        )
        lifecycle.execute(::startStack)
        watchdog.scheduleWithFixedDelay(::checkServers, WATCHDOG_SECONDS, WATCHDOG_SECONDS, TimeUnit.SECONDS)
    }

    /** Blocking, on [lifecycle]: database, then auth, then world; each step needs the last. */
    private fun startStack() {
        run {
            database.start()
            if (database.state != MariaDbServer.State.RUNNING) return@run
            runCatching { dataPack.installSql() }.onFailure { Log.e(TAG, "Unpacking SQL failed", it) }
            // Before auth too: it exits when no realm is listable, e.g. flagged offline after
            // the world server was killed. On first start the table does not exist yet.
            publishRealmAddress()
            auth.start()
            if (auth.state != AuthServer.State.RUNNING) return@run
            publishRealmAddress()
            runCatching { console.prepare(Accounts(database)) }
                .onFailure { Log.w(TAG, "Could not prepare the console account", it) }
            val settings = RealmSettingsStore(this).load()
            world.start(settings)
            if (world.state == WorldServer.State.RUNNING) {
                idleGuard = IdleGuard(
                    this, world, console, auth.logFile,
                    // Without that, bots are meant to live on with nobody playing.
                    pauseWhenIdle = settings.bots.disabledWithoutRealPlayer,
                ).also { it.start() }
            }
        }
    }

    /**
     * Restarts the stack in order when a server died under us (Android's low-memory killer
     * can take a single child process). At most [MAX_RESTARTS] per [RESTART_WINDOW_MS], so
     * a kill-and-restart loop under memory pressure gives up and leaves the failure on screen.
     */
    private fun checkServers() {
        if (!running || restarting) return
        // Check all three: each marks itself FAILED.
        val exited = listOf(database.checkExited(), auth.checkExited(), world.checkExited())
        if (exited.none { it }) return
        val now = SystemClock.elapsedRealtime()
        restartTimes.removeAll { now - it > RESTART_WINDOW_MS }
        if (restartTimes.size >= MAX_RESTARTS) {
            Log.e(TAG, "Server exited again; ${restartTimes.size} restarts recently, giving up")
            return
        }
        restartTimes += now
        restarting = true
        Log.w(TAG, "A server exited unexpectedly (database, auth, world = $exited); restarting the realm")
        lifecycle.execute {
            try {
                stopStack()
                startStack()
            } finally {
                restarting = false
            }
        }
    }

    /**
     * The realm list tells clients where the world server is. Use the current LAN address
     * so both a client on this device and one elsewhere on the network can connect
     * (worldserver binds 0.0.0.0); fall back to loopback when offline.
     */
    private fun publishRealmAddress() {
        val addresses = SystemStats.lanAddresses()
        val ip = (addresses.firstOrNull { it.first == "wlan0" } ?: addresses.firstOrNull())?.second ?: "127.0.0.1"
        runCatching {
            database.execute(
                "UPDATE acore_auth.realmlist SET address = '$ip', localAddress = '$ip', " +
                    "localSubnetMask = '255.255.255.0', port = ${WorldServer.PORT}, flag = flag & ~3 WHERE id = 1;",
            )
        }.onFailure { Log.w(TAG, "Could not update realm address", it) }
        realmAddress = ip
    }

    /**
     * Android is about to kill processes for memory, and we may be next: save every player now.
     * At most once a minute, since the callback can repeat while pressure stays high.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level < TRIM_MEMORY_RUNNING_CRITICAL || world.state != WorldServer.State.RUNNING) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSaveAt < MIN_SAVE_INTERVAL_MS) return
        lastSaveAt = now
        saves.execute {
            runCatching { console.execute("saveall") }
                .onSuccess { Log.i(TAG, "Memory critical (level $level): saved all players") }
                .onFailure { Log.w(TAG, "Memory critical (level $level): saveall failed", it) }
        }
    }

    /** Reverse start order: servers first, so their last writes reach the database. */
    private fun stopStack() {
        idleGuard?.stop()
        idleGuard = null
        world.stop()
        auth.stop()
        database.stop()
    }

    override fun onDestroy() {
        if (running) runCatching { unregisterReceiver(shutdownReceiver) }
        running = false
        instance = null
        // Normally already stopped via ACTION_STOP. If the system destroys us instead,
        // still shut the stack down cleanly; leftover processes are reaped on next start.
        lifecycle.execute { stopStack() }
        watchdog.shutdownNow()
        lifecycle.shutdown()
        saves.shutdown()
        super.onDestroy()
    }

    private fun status(): JSONObject {
        val device = SystemStats.deviceMemory()
        val self = SystemStats.processMemory()
        return JSONObject()
            .put("service", "running")
            .put("uptimeSec", (SystemClock.elapsedRealtime() - startedAt) / 1000)
            .put(
                "addresses",
                JSONArray().apply {
                    SystemStats.lanAddresses().forEach { (nic, ip) ->
                        put(JSONObject().put("interface", nic).put("ip", ip))
                    }
                },
            )
            .put(
                "deviceMemory",
                JSONObject()
                    .put("totalKb", device.totalKb)
                    .put("availableKb", device.availableKb),
            )
            .put(
                "processes",
                JSONObject().put(
                    "realmService",
                    JSONObject()
                        .put("pid", self?.pid)
                        .put("rssKb", self?.rssKb)
                        .put("peakRssKb", self?.peakRssKb),
                ),
            )
            .put("database", database.status())
            .put("authserver", auth.status())
            .put("worldserver", world.status().put("realmAddress", realmAddress))
            .put("dataPack", dataPack.status())
    }


    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.realm_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("AzerothDroid realm running")
            .setContentText("Login on port ${AuthServer.PORT}, world on port ${WorldServer.PORT}")
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val TAG = "RealmService"
        private const val CHANNEL_ID = "realm"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.lscardinali.azerothdroid.STOP"
        /** Power-off broadcast some vendors (HTC, Xiaomi) send instead of ACTION_SHUTDOWN. */
        private const val ACTION_QUICKBOOT_POWEROFF = "android.intent.action.QUICKBOOT_POWEROFF"
        private const val MIN_SAVE_INTERVAL_MS = 60_000L
        private const val WATCHDOG_SECONDS = 15L
        private const val MAX_RESTARTS = 3
        private val RESTART_WINDOW_MS = TimeUnit.MINUTES.toMillis(30)
        @Volatile private var instance: RealmService? = null

        /** Whether the service is up; cheaper than [currentStatus], which samples memory. */
        val running: Boolean get() = instance != null

        /** Status of the running realm, or null when the service is not running. */
        fun currentStatus(): JSONObject? = instance?.status()

        /** Account management, or null unless the service is running with its database up. */
        fun accounts(): Accounts? = instance
            ?.takeIf { it.database.state == MariaDbServer.State.RUNNING }
            ?.let { Accounts(it.database) }

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, RealmService::class.java))

        fun stop(context: Context) {
            context.startService(stopIntent(context))
        }

        private fun stopIntent(context: Context) =
            Intent(context, RealmService::class.java).setAction(ACTION_STOP)
    }
}
