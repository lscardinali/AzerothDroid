package com.lscardinali.azerothdroid.realm

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

/**
 * Where the game data ([DataPack]: client data and the unpacked SQL) lives. The database,
 * native libraries and configs always stay in internal storage: MariaDB needs a real Linux
 * file system for its data files and socket, and a card pulled mid-write would corrupt it.
 *
 * The SD card location is the app's own folder on the card (Android/data/<package>/files),
 * which needs no storage permission and is removed with the app.
 */
enum class DataLocation(val label: String) {
    INTERNAL("Internal storage"),
    SD_CARD("SD card"),
    ;

    /** The folder holding this location's game data, or null when the card is not mounted. */
    fun root(context: Context): File? = when (this) {
        INTERNAL -> context.filesDir
        SD_CARD -> sdCardRoot(context)
    }

    companion object {
        private const val PREFS = "data_location"
        private const val KEY = "location"

        /** The chosen location; read synchronously, since services build a [DataPack] on start. */
        fun current(context: Context): DataLocation {
            val name = prefs(context).getString(KEY, null)
            return entries.firstOrNull { it.name == name } ?: INTERNAL
        }

        fun set(context: Context, location: DataLocation) {
            prefs(context).edit().putString(KEY, location.name).commit()
        }

        /** The app's folder on the first mounted removable volume (the SD card), if any. */
        fun sdCardRoot(context: Context): File? =
            context.getExternalFilesDirs(null).filterNotNull().firstOrNull { dir ->
                runCatching {
                    Environment.isExternalStorageRemovable(dir) &&
                        Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED
                }.getOrDefault(false)
            }?.also { it.mkdirs() }

        fun freeBytes(dir: File): Long = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L)

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
