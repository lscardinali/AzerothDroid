package com.lscardinali.azerothdroid.realm

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * Shared plumbing for the native server processes staged by tools/stage_*.py and
 * tools/build_azerothcore.py.
 *
 * Executables and libraries are packaged as lib*.so in nativeLibraryDir (the only place an
 * app may exec from). Each component's assets/<component>/manifest.json lists the
 * libraries' real sonames; [libDir] holds <soname> -> nativeLibraryDir symlinks and is
 * passed to every process as LD_LIBRARY_PATH.
 */
class NativeRuntime(private val context: Context) {

    val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    val libDir = File(context.filesDir, "native/lib")

    /** Bumped on every app update, so per-install work is redone after an upgrade. */
    val installIdentity: String =
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()

    fun manifest(component: String): JSONObject =
        JSONObject(context.assets.open("$component/manifest.json").bufferedReader().readText())

    /** Ensures every soname a component needs links to its packaged library. */
    fun linkLibraries(component: String) {
        libDir.mkdirs()
        val links = manifest(component).getJSONObject("links")
        for (soname in links.keys()) {
            val target = File(nativeDir, links.getString(soname))
            check(target.isFile) { "missing native library ${target.name}" }
            val link = File(libDir, soname)
            val current = runCatching { Files.readSymbolicLink(link.toPath()) }.getOrNull()
            if (current != target.toPath()) {
                link.delete()
                Files.createSymbolicLink(link.toPath(), target.toPath())
            }
        }
    }

    /** Copies an asset directory tree into [target]. */
    fun copyAssets(path: String, target: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        } else {
            target.mkdirs()
            children.forEach { copyAssets("$path/$it", File(target, it)) }
        }
    }

    fun command(
        executable: String,
        vararg args: String,
        home: File,
        extraEnv: Map<String, String> = emptyMap(),
    ): ProcessBuilder =
        ProcessBuilder(affinityPrefix + listOf(File(nativeDir, executable).path) + args).apply {
            environment()["LD_LIBRARY_PATH"] = libDir.path
            // OpenSSL providers (legacy.so, for RC4) are linked into libDir too.
            environment()["OPENSSL_MODULES"] = libDir.path
            environment()["HOME"] = home.path
            environment()["TMPDIR"] = File(home, "tmp").apply { mkdirs() }.path
            // No terminal: AzerothCore must not wait on stdin prompts (e.g. "create database?").
            environment()["AC_DISABLE_INTERACTIVE"] = "1"
            environment().putAll(extraEnv)
            directory(home)
        }

    companion object {
        private const val TAG = "NativeRuntime"
        private const val TASKSET = "/system/bin/taskset"

        /**
         * Keeps the servers off the phone's prime core(s), so the game client's main thread, which
         * limits its frame rate, has one to itself. Through toybox taskset, which execs the server
         * (same pid) with a CPU mask that all its threads inherit. Only when the fastest cores are
         * a separate cluster of one or two out of six or more: on other chips there is no spare
         * fast core, and taking big cores from the servers would only slow the realm.
         */
        private val affinityPrefix: List<String> by lazy {
            runCatching {
                val freqs = (0 until Runtime.getRuntime().availableProcessors()).map { cpu ->
                    File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                }
                val top = freqs.max()
                val prime = freqs.indices.filter { freqs[it] == top }
                if (freqs.size < 6 || prime.size > 2 || !File(TASKSET).canExecute()) return@runCatching emptyList()
                val mask = freqs.indices.filter { it !in prime }.fold(0L) { acc, cpu -> acc or (1L shl cpu) }
                Log.i(TAG, "Servers on CPUs ${freqs.indices - prime.toSet()}, leaving $prime to the game")
                listOf(TASKSET, mask.toString(16))
            }.getOrElse {
                Log.w(TAG, "No CPU affinity for the servers", it)
                emptyList()
            }
        }
        private const val SIGTERM = 15

        fun readPid(pidFile: File): Int? = pidFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()

        /** True if [pid] is alive and is the packaged [executable] (not a recycled pid). */
        fun isRunning(pid: Int, executable: String): Boolean =
            runCatching { File("/proc/$pid/cmdline").readText().contains(executable) }.getOrDefault(false)

        /**
         * Keeps the last [keepBytes] of an append-only log once it passes [maxBytes], so
         * stdout logs kept across restarts do not grow without bound.
         */
        fun trimLog(file: File, maxBytes: Long = 4L shl 20, keepBytes: Int = 512 shl 10) {
            if (!file.isFile || file.length() <= maxBytes) return
            runCatching {
                val tail = java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(raf.length() - keepBytes)
                    ByteArray(keepBytes).also { raf.readFully(it) }
                }
                file.writeBytes(tail)
            }.onFailure { Log.w(TAG, "Could not trim ${file.name}", it) }
        }

        /** SIGTERM for a clean shutdown, SIGKILL if it is still alive after [timeoutSec]. */
        fun terminate(pid: Int, executable: String, timeoutSec: Long) {
            if (!isRunning(pid, executable)) return
            android.os.Process.sendSignal(pid, SIGTERM)
            val deadline = System.currentTimeMillis() + timeoutSec * 1000
            while (isRunning(pid, executable) && System.currentTimeMillis() < deadline) Thread.sleep(200)
            if (isRunning(pid, executable)) {
                Log.w(TAG, "$executable ($pid) ignored SIGTERM, killing")
                android.os.Process.killProcess(pid)
            }
        }
    }
}
