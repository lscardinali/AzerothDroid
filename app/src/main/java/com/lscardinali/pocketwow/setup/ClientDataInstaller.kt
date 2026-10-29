package com.lscardinali.pocketwow.setup

import android.os.StatFs
import android.util.Log
import com.lscardinali.pocketwow.realm.DataPack
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
class ClientDataInstaller(private val dataPack: DataPack) {

    sealed interface Progress {
        data object Idle : Progress
        data class Downloading(val bytes: Long, val total: Long) : Progress
        data class Extracting(val files: Int, val total: Int) : Progress
        data object Done : Progress
        data class Failed(val message: String) : Progress
    }

    private val target = dataPack.clientData
    private val work = File(target.parentFile, "client-data-setup")
    private val archive = File(work, "data.zip")
    private val partial = File(work, "data.zip.part")
    private val staging = File(work, "extracted")

    /** Blocking. Reports through [progress]; never throws. */
    fun run() {
        try {
            if (dataPack.clientDataInstalled) {
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
