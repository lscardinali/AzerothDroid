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
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Foreground service hosting the realm stack: status endpoint and MariaDB for now;
 * authserver and worldserver get layered on here one at a time.
 *
 * No wake lock is held: when the device sleeps the CPU suspends and every process
 * freezes, then continues where it left off on wake. The foreground state is what keeps
 * Android from killing the stack while the WoW client is in front.
 */
class RealmService : Service() {

    private var statusServer: StatusServer? = null
    private lateinit var database: MariaDbServer
    /** Start/stop steps block for seconds, so they run off the main thread, in order. */
    private val lifecycle = Executors.newSingleThreadExecutor()
    private var startedAt = 0L

    override fun onCreate() {
        super.onCreate()
        database = MariaDbServer(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            lifecycle.execute {
                database.stop()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (statusServer == null) startRealm()
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
        statusServer = StatusServer(STATUS_PORT, routes()).also {
            try {
                it.start()
            } catch (e: Exception) {
                Log.e(TAG, "Status server failed to start", e)
            }
        }
        lifecycle.execute { database.start() }
    }

    override fun onDestroy() {
        statusServer?.stop()
        statusServer = null
        // Normally already stopped via ACTION_STOP. If the system destroys us instead,
        // still ask MariaDB to shut down cleanly; a leftover server is reaped on next start.
        lifecycle.execute { database.stop() }
        lifecycle.shutdown()
        super.onDestroy()
    }

    private fun routes(): Map<String, () -> Pair<String, String>> = mapOf(
        "/" to { StatusServer.JSON to status().toString(2) },
        "/status" to { StatusServer.JSON to status().toString(2) },
        "/logs/mariadb" to { StatusServer.TEXT to tail(database.logFile) },
    )

    private fun status(): JSONObject {
        val device = SystemStats.deviceMemory()
        val self = SystemStats.processMemory()
        return JSONObject()
            .put("service", "running")
            .put("uptimeSec", (SystemClock.elapsedRealtime() - startedAt) / 1000)
            .put("addresses", StatusServer.addressesJson())
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
    }

    private fun tail(file: java.io.File, maxBytes: Int = 16 * 1024): String {
        if (!file.isFile) return "(no log yet)\n"
        val bytes = file.readBytes()
        return String(bytes, maxOf(0, bytes.size - maxBytes), minOf(bytes.size, maxBytes))
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
            .setContentText("Status on port $STATUS_PORT")
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
        const val STATUS_PORT = 8080

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, RealmService::class.java))

        fun stop(context: Context) {
            context.startService(stopIntent(context))
        }

        private fun stopIntent(context: Context) =
            Intent(context, RealmService::class.java).setAction(ACTION_STOP)
    }
}
