package com.lscardinali.pocketwow.setup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lscardinali.pocketwow.MainActivity
import com.lscardinali.pocketwow.R
import com.lscardinali.pocketwow.realm.DataPack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs the one-time setup work (client data download and extraction) as a foreground
 * service, so it keeps going with the screen off or the app in the background.
 * Unlike the realm, this is finite work, so it holds wake and Wi-Fi locks while it runs.
 */
class SetupService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        running = true
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("Preparing…", null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketWoW:setup").apply { acquire(LOCK_TIMEOUT_MS) }
        @Suppress("DEPRECATION")
        val wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PocketWoW:setup").apply { acquire() }

        val installer = ClientDataInstaller(DataPack(this))
        val progressUpdates = scope.launch {
            ClientDataInstaller.progress.collect { updateNotification(it) }
        }
        scope.launch {
            try {
                installer.run()
            } finally {
                progressUpdates.cancel()
                if (wakeLock.isHeld) wakeLock.release()
                if (wifiLock.isHeld) wifiLock.release()
                ServiceCompat.stopForeground(this@SetupService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun updateNotification(progress: ClientDataInstaller.Progress) {
        val (text, percent) = when (progress) {
            is ClientDataInstaller.Progress.Downloading ->
                "Downloading client data: ${progress.bytes / MB} / ${progress.total / MB} MB" to
                    (progress.bytes * 100 / progress.total.coerceAtLeast(1)).toInt()
            is ClientDataInstaller.Progress.Extracting ->
                "Extracting client data: ${progress.files} / ${progress.total} files" to
                    (progress.files * 100 / progress.total.coerceAtLeast(1))
            else -> return
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, percent))
    }

    private fun notification(text: String, percent: Int?): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.setup_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("PocketWoW setup")
            .setContentText(text)
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "setup"
        private const val NOTIFICATION_ID = 2
        private const val MB = 1024L * 1024
        /** Safety net; the download plus extraction takes minutes, not hours. */
        private const val LOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, SetupService::class.java))
    }
}
