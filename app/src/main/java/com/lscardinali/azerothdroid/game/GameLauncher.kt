package com.lscardinali.azerothdroid.game

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * Starts the player's own Windows WoW 3.3.5a client (Wow.exe) in the :wine module's GameActivity,
 * pointed at this phone's realm.
 *
 * Wine opens the client folder by path and writes WTF, Cache and Logs inside it, so on Android 11+
 * the app needs all-files access ([hasStorageAccess]). The Wine engine needs Android 10.
 */
object GameLauncher {

    private const val TAG = "GameLauncher"
    private const val PREFS = "game"
    private const val KEY_ROOT = "clientFolder"
    private const val CLIENT_DIR = "Games/World of Warcraft"
    private const val BACKUP_SUFFIX = ".before-azerothdroid"
    private const val KEY_OLD_CAP_DROPPED = "oldFpsCapDropped"
    private val OLD_FPS_CAP = Regex("""SET\s+maxFPS\s+"60"""", RegexOption.IGNORE_CASE)
    private const val REALMLIST_BACKUP = "realmlist.wtf$BACKUP_SUFFIX"
    const val LOCAL_REALM = "127.0.0.1"

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun hasStorageAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun storageAccessIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    /** The saved client folder if it still holds Wow.exe, else the first `Games/World of Warcraft` on any volume. */
    fun findClient(context: Context): File? {
        val saved = prefs(context).getString(KEY_ROOT, null)?.let(::File)
        if (saved != null && isClient(saved)) return saved
        return volumeRoots(context).map { File(it, CLIENT_DIR) }.firstOrNull(::isClient)
            ?.also { prefs(context).edit().putString(KEY_ROOT, it.path).apply() }
    }

    fun isClient(dir: File): Boolean =
        dir.listFiles()?.any { it.isFile && it.name.equals("Wow.exe", ignoreCase = true) } == true

    /**
     * Every storage volume's root (/storage/emulated/0, /storage/XXXX-XXXX). Derived from the app's
     * own external folders, which sit at <root>/Android/data/<package>/files on each volume.
     */
    private fun volumeRoots(context: Context): List<File> =
        context.getExternalFilesDirs(null).filterNotNull().mapNotNull { dir ->
            generateSequence(dir) { it.parentFile }.firstOrNull { it.name == "Android" }?.parentFile
        }.distinct()

    /**
     * Points the client at this phone's realm: every Data/<locale>/realmlist.wtf says
     * `set realmlist 127.0.0.1`. The first time, the old file is kept beside it.
     */
    fun pointAtLocalRealm(client: File) {
        val data = client.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("Data", ignoreCase = true) } ?: return
        data.listFiles()?.filter { it.isDirectory }?.forEach { locale ->
            val realmlist = locale.listFiles()?.firstOrNull { it.name.equals("realmlist.wtf", ignoreCase = true) }
                ?: return@forEach
            val wanted = "set realmlist $LOCAL_REALM\n"
            if (realmlist.readText().trim().equals(wanted.trim(), ignoreCase = true)) return@forEach
            val backup = File(locale, REALMLIST_BACKUP)
            if (!backup.exists()) realmlist.copyTo(backup)
            realmlist.writeText(wanted)
            Log.i(TAG, "Pointed ${realmlist.path} at $LOCAL_REALM")
        }
    }

    /**
     * Sets the client's display to fill the game screen: a maximized window at [screenSize] (the
     * X screen Wine draws on, e.g. "1280x720"). Also points Config.wtf at this realm and skips
     * the hardware detection that would reset graphics. Everything else stays as the player set it.
     */
    fun configureClient(client: File, screenSize: String, account: String? = null, dropOldFpsCap: Boolean = false) {
        val wtf = client.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("WTF", ignoreCase = true) }
            ?: File(client, "WTF").apply { mkdirs() }
        val config = wtf.listFiles()?.firstOrNull { it.name.equals("Config.wtf", ignoreCase = true) } ?: File(wtf, "Config.wtf")
        val forced = linkedMapOf(
            "gxWindow" to "1",
            "gxMaximize" to "1",
            "gxResolution" to screenSize,
            "gxRefresh" to "60",
            "hwDetect" to "0",
            "realmList" to LOCAL_REALM,
        )
        // With the name saved, the login screen opens on the password field (see AutoLogin).
        if (account != null) forced["accountName"] = account
        val lines = if (config.exists()) config.readLines().filter { it.isNotBlank() } else emptyList()
        val keyOf = { line: String -> Regex("""^\s*SET\s+(\S+)""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1) }
        val kept = lines.filter { line -> forced.keys.none { it.equals(keyOf(line), ignoreCase = true) } }
        // A low cap for the moments before the app freezes the game in the background, unless the
        // player chose one. No foreground cap: a second limiter at 60 on top of the Quick Menu's
        // (and the 60 Hz screen) makes frames miss and drop into the 50s.
        val defaults = mapOf("maxFPSBk" to "10")
            .filterKeys { key -> lines.none { key.equals(keyOf(it), ignoreCase = true) } }
        val withoutOldCap = if (dropOldFpsCap) kept.filterNot { OLD_FPS_CAP.matches(it.trim()) } else kept
        val text = (withoutOldCap + (forced + defaults).map { (key, value) -> "SET $key \"$value\"" })
            .joinToString("\n", postfix = "\n")
        if (!config.exists() || config.readText() != text) config.writeText(text)
    }

    /**
     * Moves a DXVK d3d9.dll out of the client folder (to d3d9.dll.before-azerothdroid). Wine loads a
     * DLL beside the game before the prefix's, so it would replace the app's DXVK (gplasync). Only
     * DXVK builds move: another d3d9.dll, e.g. a ReShade wrapper, is the player's choice and stays.
     * Never deletes anything but an exact copy of the backup (e.g. put back by another launcher).
     */
    fun moveClientDxvkAside(client: File) {
        val dll = client.listFiles()?.firstOrNull { it.isFile && it.name.equals("d3d9.dll", ignoreCase = true) } ?: return
        val bytes = dll.readBytes()
        if (!bytes.contains("DXVK".toByteArray())) return
        val backup = File(client, "d3d9.dll$BACKUP_SUFFIX")
        when {
            !backup.exists() -> if (dll.renameTo(backup)) Log.i(TAG, "Moved the client's DXVK d3d9.dll aside")
            backup.readBytes().contentEquals(bytes) -> dll.delete()
            else -> Log.w(TAG, "Leaving ${dll.path}: a different backup is already there")
        }
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        (0..size - needle.size).any { i -> needle.indices.all { this[i + it] == needle[it] } }

    fun launch(context: Context, client: File) {
        // Already running (frozen in the background): just bring it back, untouched.
        if (app.gamenative.azerothdroid.GameActivity.isRunning) {
            context.startActivity(app.gamenative.azerothdroid.GameActivity.intent(context, client))
            return
        }
        pointAtLocalRealm(client)
        val login = AutoLoginStore(context).get()
        // Once: the foreground cap an earlier version added (see configureClient).
        val dropOldCap = !prefs(context).getBoolean(KEY_OLD_CAP_DROPPED, false)
        configureClient(client, app.gamenative.azerothdroid.WowContainer.screenSize(context), login?.account, dropOldCap)
        if (dropOldCap) prefs(context).edit().putBoolean(KEY_OLD_CAP_DROPPED, true).apply()
        ConsolePortSetup.apply(client)
        moveClientDxvkAside(client)
        // The client can sit on its login screen while the realm comes up.
        if (com.lscardinali.azerothdroid.realm.RealmService.currentStatus() == null) {
            com.lscardinali.azerothdroid.realm.RealmService.start(context)
        }
        app.gamenative.azerothdroid.GameActivity.autoLogin = login?.let {
            app.gamenative.azerothdroid.AutoLogin(it.password) { worldUp() }
        }
        context.startActivity(app.gamenative.azerothdroid.GameActivity.intent(context, client))
    }

    /** Whether logging in will work: the world server runs (a sleeping one wakes on login). */
    private fun worldUp(): Boolean =
        com.lscardinali.azerothdroid.realm.RealmService.currentStatus()
            ?.optJSONObject("worldserver")?.optString("state")
            .let { it == "RUNNING" || it == "PAUSED" }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
