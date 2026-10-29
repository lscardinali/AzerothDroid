package com.lscardinali.pocketwow.realm

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The data the servers read besides their own binaries:
 *
 *  - SQL ([sourceDirectory]): bundled in the APK as assets/azerothcore/sql.zip and unpacked
 *    into app storage once per version. It is AzerothCore's SourceDirectory, read by its
 *    DB updater to create and update the databases.
 *  - Client data ([clientData]): dbc, maps, vmaps, mmaps. Derived from Blizzard's files, so
 *    it is never bundled; [ClientDataInstaller] downloads it during setup (or
 *    tools/push_data.py streams it in over adb). It lives in internal app storage: files
 *    adb writes into Android/data belong to the shell user and the app cannot read them.
 */
class DataPack(private val context: Context) {

    private val manifest by lazy {
        JSONObject(context.assets.open("azerothcore/manifest.json").bufferedReader().readText())
    }

    /** AzerothCore's SourceDirectory: data/sql and modules/<name>/data/sql. */
    val sourceDirectory = File(context.filesDir, "azerothcore-source")

    /** AzerothCore's DataDir: dbc, maps, vmaps, mmaps. */
    val clientData = File(context.filesDir, "client-data")

    /** The wowgaming/client-data release matching the bundled AzerothCore. */
    val clientDataVersion: String get() = manifest.getJSONObject("clientData").getString("version")
    val clientDataUrl: String get() = manifest.getJSONObject("clientData").getString("url")

    val clientDataInstalled: Boolean
        get() = installedVersion(clientData) == clientDataVersion

    private val sqlIdentity: String get() = manifest.getString("sqlIdentity")

    /** Blocking: unpacks the bundled SQL if this version is not unpacked yet. */
    fun installSql() {
        if (installedVersion(sourceDirectory) == sqlIdentity) return
        val staging = File(sourceDirectory.path + ".tmp")
        staging.deleteRecursively()
        ZipInputStream(context.assets.open("azerothcore/sql.zip").buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(staging, entry.name)
                check(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                    "unsafe path in sql.zip: ${entry.name}"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        File(staging, VERSION_FILE).writeText(sqlIdentity)
        sourceDirectory.deleteRecursively()
        check(staging.renameTo(sourceDirectory)) { "could not move SQL into place" }
    }

    /** Throws with a user-facing message unless everything the servers need is present. */
    fun check() {
        check(installedVersion(sourceDirectory) == sqlIdentity) { "SQL not unpacked yet" }
        check(clientDataInstalled) { "Client data $clientDataVersion missing: run setup first" }
    }

    fun status(): JSONObject = JSONObject()
        .put("sql", installedVersion(sourceDirectory)?.take(10))
        .put("clientData", installedVersion(clientData))
        .put("clientDataExpected", clientDataVersion)

    companion object {
        const val VERSION_FILE = ".version"

        fun installedVersion(dir: File): String? =
            File(dir, VERSION_FILE).takeIf { it.isFile }?.readText()?.trim()
    }
}
