package com.lscardinali.azerothdroid.realm

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
 *    tools/push_data.py streams it in over adb).
 *
 * Both live under the [DataLocation] the player chose: internal app storage by default, or the
 * app's folder on the SD card. (tools/push_data.py only fills internal storage: files adb writes
 * into Android/data belong to the shell user and the app cannot read them.)
 */
class DataPack(private val context: Context) {

    private val manifest by lazy {
        JSONObject(context.assets.open("azerothcore/manifest.json").bufferedReader().readText())
    }

    /** Read on every use, so a long-lived instance follows a change of location. */
    val location: DataLocation get() = DataLocation.current(context)

    /** The chosen location's folder; null when it is the SD card and no card is mounted. */
    val root: File? get() = location.root(context)

    val storageAvailable: Boolean get() = root != null

    private val requiredRoot: File
        get() = root ?: error("SD card not found: insert it, or choose internal storage in Settings")

    /** AzerothCore's SourceDirectory: data/sql and modules/<name>/data/sql. */
    val sourceDirectory: File get() = sourceDirectoryIn(requiredRoot)

    /** AzerothCore's DataDir: dbc, maps, vmaps, mmaps. */
    val clientData: File get() = clientDataIn(requiredRoot)

    /** The wowgaming/client-data release matching the bundled AzerothCore. */
    val clientDataVersion: String get() = manifest.getJSONObject("clientData").getString("version")
    val clientDataUrl: String get() = manifest.getJSONObject("clientData").getString("url")

    val clientDataInstalled: Boolean
        get() = root?.let { clientDataInstalledIn(it) } ?: false

    fun clientDataInstalledIn(root: File): Boolean = installedVersion(clientDataIn(root)) == clientDataVersion

    private val sqlIdentity: String get() = manifest.getString("sqlIdentity")

    /** Blocking: unpacks the bundled SQL if this version is not unpacked yet. */
    fun installSql() {
        val sourceDirectory = sourceDirectory
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

    /**
     * AzerothCore's updater applies the SQL of every compiled module, so a module whose SQL should
     * wait for the player's choice has it parked outside the path the updater reads. Once applied
     * it stays in place: the updater would otherwise forget it and run it a second time on the
     * next enable, and new versions of the module must keep updating the same database.
     */
    fun setOptionalSql(module: String, enabled: Boolean) {
        val sourceDirectory = sourceDirectory
        val applied = sqlAppliedMarker(module)
        if (enabled) {
            applied.parentFile?.mkdirs()
            applied.createNewFile()
        }
        val active = File(sourceDirectory, "modules/$module/data/sql")
        val parked = File(sourceDirectory, "parked-sql/$module")
        val wanted = applied.exists()
        val (from, to) = if (wanted) parked to active else active to parked
        if (!from.exists()) return
        to.parentFile?.mkdirs()
        to.deleteRecursively()
        check(from.renameTo(to)) { "could not move SQL of $module" }
    }

    /** Whether a realm start has already let [module]'s SQL into the world database. */
    fun optionalSqlApplied(module: String): Boolean = sqlAppliedMarker(module).exists()

    // Internal whatever the data location: it records the state of the database, which stays internal.
    private fun sqlAppliedMarker(module: String) = File(context.filesDir, "azerothcore/$module.sql-applied")

    /** Throws with a user-facing message unless everything the servers need is present. */
    fun check() {
        requiredRoot
        check(installedVersion(sourceDirectory) == sqlIdentity) { "SQL not unpacked yet" }
        check(clientDataInstalled) { "Client data $clientDataVersion missing: run setup first" }
    }

    fun status(): JSONObject = JSONObject()
        .put("location", location.name)
        .put("storageAvailable", storageAvailable)
        .put("sql", root?.let { installedVersion(sourceDirectoryIn(it)) }?.take(10))
        .put("clientData", root?.let { installedVersion(clientDataIn(it)) })
        .put("clientDataExpected", clientDataVersion)

    companion object {
        const val VERSION_FILE = ".version"
        /** Partial download and extraction, next to the client data (see ClientDataInstaller). */
        const val SETUP_DIR = "client-data-setup"

        fun clientDataIn(root: File) = File(root, "client-data")
        fun sourceDirectoryIn(root: File) = File(root, "azerothcore-source")

        fun installedVersion(dir: File): String? =
            File(dir, VERSION_FILE).takeIf { it.isFile }?.readText()?.trim()
    }
}
