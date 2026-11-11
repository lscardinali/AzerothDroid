package com.lscardinali.azerothdroid.setup

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.lscardinali.azerothdroid.realm.DataLocation
import com.lscardinali.azerothdroid.realm.DataPack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Setup phase for the client data, the same way AzerothCore's Docker setup does it:
 * download the pinned wowgaming/client-data release zip, extract it into place, then
 * delete the zip. An interrupted download resumes where it stopped (HTTP Range).
 */
class ClientDataInstaller(private val context: Context) {

    private val dataPack = DataPack(context)

    sealed interface Progress {
        data object Idle : Progress
        data class Downloading(val bytes: Long, val total: Long) : Progress
        data class Extracting(val files: Int, val total: Int) : Progress
        data class Moving(val bytes: Long, val total: Long, val to: DataLocation) : Progress
        data object Done : Progress
        data class Failed(val message: String) : Progress
    }

    /** The data location's folder, fixed for the whole run. */
    private lateinit var root: File
    private val target get() = DataPack.clientDataIn(root)
    private val work get() = File(root, DataPack.SETUP_DIR)
    private val archive get() = File(work, "data.zip")
    private val partial get() = File(work, "data.zip.part")
    private val staging get() = File(work, "extracted")

    /** Blocking. Reports through [progress]; never throws. */
    fun run() {
        try {
            root = dataPack.root
                ?: throw IOException("SD card not found: insert it, or choose internal storage in Settings")
            if (dataPack.clientDataInstalledIn(root)) {
                _progress.value = Progress.Done
                return
            }
            work.mkdirs()
            if (!archive.isFile) download()
            extract()
            _progress.value = Progress.Done
        } catch (e: Exception) {
            Log.e(TAG, "Client data setup failed", e)
            _progress.value = Progress.Failed(e.message ?: e.toString())
        }
    }

    private fun download() {
        val connection = URL(dataPack.clientDataUrl).openConnection() as HttpURLConnection
        val resumeFrom = if (partial.isFile) partial.length() else 0L
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=$resumeFrom-")
        connection.connect()
        val code = connection.responseCode
        val resumed = code == HttpURLConnection.HTTP_PARTIAL
        if (code != HttpURLConnection.HTTP_OK && !resumed) throw IOException("download failed: HTTP $code")
        val offset = if (resumed) resumeFrom else 0L
        val total = offset + connection.contentLengthLong
        // Zip and extracted data exist together until the zip is deleted.
        ensureFreeSpace(total - offset + total * EXTRACTED_RATIO)

        RandomAccessFile(partial, "rw").use { file ->
            file.setLength(offset)
            file.seek(offset)
            connection.inputStream.use { input ->
                val buffer = ByteArray(1 shl 16)
                var done = offset
                var lastReport = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    file.write(buffer, 0, read)
                    done += read
                    if (done - lastReport > REPORT_EVERY) {
                        _progress.value = Progress.Downloading(done, total)
                        lastReport = done
                    }
                }
                if (connection.contentLengthLong > 0 && done != total) throw IOException("download incomplete")
            }
        }
        check(partial.renameTo(archive)) { "could not finish download" }
    }

    private fun extract() {
        staging.deleteRecursively()
        staging.mkdirs()
        ZipFile(archive).use { zip ->
            val entries = zip.entries().toList()
            ensureFreeSpace(entries.sumOf { it.size.coerceAtLeast(0) })
            entries.forEachIndexed { index, entry ->
                val file = File(staging, entry.name)
                check(file.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                    "unsafe path in archive: ${entry.name}"
                }
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                }
                if (index % 200 == 0) _progress.value = Progress.Extracting(index, entries.size)
            }
        }
        val missing = REQUIRED_DIRS.filterNot { File(staging, it).isDirectory }
        check(missing.isEmpty()) { "client data archive is missing $missing" }
        File(staging, DataPack.VERSION_FILE).writeText(dataPack.clientDataVersion)

        target.deleteRecursively()
        check(staging.renameTo(target)) { "could not move client data into place" }
        archive.delete()
        work.deleteRecursively()
    }

    /**
     * Blocking: switches the game data to [to], copying installed client data across first so
     * it needn't be downloaded again. The unpacked SQL is not copied: the next realm start
     * unpacks it from the APK at the new location. Only the copy is ever deleted on failure;
     * the old location is cleared once the new one is in use. The realm must be stopped.
     * Reports through [progress]; never throws.
     */
    fun move(to: DataLocation) {
        try {
            val from = dataPack.location
            if (from == to) {
                _progress.value = Progress.Idle
                return
            }
            val toRoot = to.root(context)
                ?: throw IOException("SD card not found: insert it and try again")
            // No card: nothing can be copied off it, so just switch (its data stays on the card).
            val fromRoot = from.root(context)
            if (fromRoot != null && dataPack.clientDataInstalledIn(fromRoot) && !dataPack.clientDataInstalledIn(toRoot)) {
                copyClientData(DataPack.clientDataIn(fromRoot), DataPack.clientDataIn(toRoot), to)
            }
            DataLocation.set(context, to)
            fromRoot?.let {
                for (dir in listOf(DataPack.clientDataIn(it), DataPack.sourceDirectoryIn(it), File(it, DataPack.SETUP_DIR))) {
                    dir.deleteRecursively()
                }
            }
            _progress.value = if (dataPack.clientDataInstalledIn(toRoot)) Progress.Done else Progress.Idle
        } catch (e: Exception) {
            Log.e(TAG, "Moving game data failed", e)
            _progress.value = Progress.Failed("Moving game data failed: ${e.message ?: e}")
        }
    }

    private fun copyClientData(source: File, target: File, to: DataLocation) {
        val files = source.walkTopDown().filter { it.isFile && it.name != DataPack.VERSION_FILE }.toList()
        val total = files.sumOf { it.length() }
        val copy = File(target.path + ".tmp")
        copy.deleteRecursively()
        copy.mkdirs()
        val free = StatFs(copy.path).availableBytes
        check(free > total + MARGIN) {
            "Not enough space on ${to.label}: need ${(total + MARGIN) / MB} MB, ${free / MB} MB free"
        }
        try {
            var done = 0L
            var lastReport = 0L
            _progress.value = Progress.Moving(0, total, to)
            for (file in files) {
                val dest = File(copy, file.relativeTo(source).path)
                dest.parentFile?.mkdirs()
                file.inputStream().use { input -> dest.outputStream().use { input.copyTo(it, 1 shl 16) } }
                check(dest.length() == file.length()) { "copy of ${file.name} is incomplete" }
                done += file.length()
                if (done - lastReport > REPORT_EVERY) {
                    _progress.value = Progress.Moving(done, total, to)
                    lastReport = done
                }
            }
            // Last, so an interrupted copy never reads as installed.
            File(source, DataPack.VERSION_FILE).copyTo(File(copy, DataPack.VERSION_FILE), overwrite = true)
            target.deleteRecursively()
            check(copy.renameTo(target)) { "could not move the copy into place" }
        } catch (e: Exception) {
            copy.deleteRecursively()
            throw e
        }
    }

    private fun ensureFreeSpace(bytes: Long) {
        val free = StatFs(work.path).availableBytes
        check(free > bytes + MARGIN) {
            "Not enough storage: need ${(bytes + MARGIN) / MB} MB, ${free / MB} MB free"
        }
    }

    companion object {
        private const val TAG = "ClientDataInstaller"
        private const val MB = 1024L * 1024
        private const val MARGIN = 256 * MB
        private const val REPORT_EVERY = 4 * MB
        /** The release unpacks to roughly 3x its zip size. */
        private const val EXTRACTED_RATIO = 3
        private val REQUIRED_DIRS = listOf("dbc", "maps", "vmaps", "mmaps")

        private val _progress = MutableStateFlow<Progress>(Progress.Idle)
        /** Shared with the UI, which runs in the same process as [SetupService]. */
        val progress: StateFlow<Progress> = _progress.asStateFlow()
    }
}
