package com.lscardinali.pocketwow.realm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lscardinali.pocketwow.MainActivity
import com.lscardinali.pocketwow.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Foreground service hosting the realm stack: MariaDB, authserver and worldserver (with
 * mod-playerbots), started in that order. It runs in the app's own process, so the UI reads
 * its state directly ([currentStatus]) and the device pays for one Android runtime, not two.
 *
 * No wake lock is held: when the device sleeps the CPU suspends and every process
 * freezes, then continues where it left off on wake. The foreground state is what keeps
 * Android from killing the stack while the WoW client is in front.
 */
class RealmService : Service() {

    private var running = false
    private lateinit var database: MariaDbServer
    private lateinit var auth: AuthServer
    private lateinit var world: WorldServer
    private lateinit var dataPack: DataPack
    /** Start/stop steps block for seconds, so they run off the main thread, in order. */
    private val lifecycle = Executors.newSingleThreadExecutor()
    private var startedAt = 0L
    @Volatile private var realmAddress: String? = null

    override fun onCreate() {
        super.onCreate()
        val native = NativeRuntime(this)
        database = MariaDbServer(this, native)
        dataPack = DataPack(this)
        auth = AuthServer(this, native, dataPack)
        world = WorldServer(this, native, dataPack)
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
        lifecycle.execute {
            database.start()
            if (database.state != MariaDbServer.State.RUNNING) return@execute
            runCatching { dataPack.installSql() }.onFailure { Log.e(TAG, "Unpacking SQL failed", it) }
            // Before auth too: it exits when no realm is listable, e.g. flagged offline after
            // the world server was killed. On first start the table does not exist yet.
            publishRealmAddress()
            auth.start()
            if (auth.state != AuthServer.State.RUNNING) return@execute
            publishRealmAddress()
            world.start(RealmSettingsStore(this).load())
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

    /** Reverse start order: servers first, so their last writes reach the database. */
    private fun stopStack() {
        world.stop()
        auth.stop()
        database.stop()
    }

    override fun onDestroy() {
        running = false
        instance = null
        // Normally already stopped via ACTION_STOP. If the system destroys us instead,
        // still shut the stack down cleanly; leftover processes are reaped on next start.
        lifecycle.execute { stopStack() }
        lifecycle.shutdown()
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
            .setContentTitle("PocketWoW realm running")
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
        private const val ACTION_STOP = "com.lscardinali.pocketwow.STOP"
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
