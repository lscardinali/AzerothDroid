package app.gamenative.azerothdroid

import app.gamenative.PluviaApp
import com.winlator.xserver.XKeycode
import timber.log.Timber
import java.io.File

/**
 * Background work for a running game:
 *  - Keeps every thread of wow.exe off the small cores. WoW's main thread limits the frame rate, and
 *    the scheduler can park it on a slow core between bursts. (GameNative has code to set this
 *    through WinHandler, but nothing calls it.)
 *  - For measuring: `adb shell setprop debug.azerothdroid.spin 1` holds the turn-left key, so the
 *    character turns in place at a steady rate, the same scene load as turning the camera.
 */
class GameTuning {

    @Volatile private var thread: Thread? = null
    private val pinned = HashSet<Int>()
    private var spinning = false

    fun start() {
        if (thread != null) return
        thread = Thread(::run, "GameTuning").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                pinGame()
                spin()
                Thread.sleep(POLL_MS)
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun pinGame() {
        if (WowContainer.debugProperty("nopin") == "1") return
        val mask = bigCoreMask ?: return
        val pids = File("/proc").listFiles()?.mapNotNull { it.name.toIntOrNull() }?.filter { pid ->
            runCatching { File("/proc/$pid/comm").readText().trim().equals("wow.exe", ignoreCase = true) }
                .getOrDefault(false)
        }.orEmpty()
        for (pid in pids - pinned) {
            val ok = runCatching {
                ProcessBuilder(TASKSET, "-a", "-p", mask, pid.toString())
                    .redirectErrorStream(true).start().waitFor() == 0
            }.getOrDefault(false)
            if (ok) {
                pinned += pid
                Timber.i("wow.exe (%d) pinned to CPU mask %s", pid, mask)
            }
        }
    }

    private fun spin() {
        val want = WowContainer.debugProperty("spin") == "1"
        if (want == spinning) return
        val server = PluviaApp.xServerView?.getxServer() ?: return
        if (want) server.injectKeyPress(XKeycode.KEY_LEFT) else server.injectKeyRelease(XKeycode.KEY_LEFT)
        spinning = want
    }

    private companion object {
        const val POLL_MS = 1_000L
        const val TASKSET = "/system/bin/taskset"

        /**
         * Every core except the slowest cluster (the Retroid Pocket 6's three A510s), in hex for
         * taskset. Null when all cores are alike or taskset is missing.
         */
        val bigCoreMask: String? by lazy {
            runCatching {
                if (!File(TASKSET).canExecute()) return@runCatching null
                val freqs = (0 until Runtime.getRuntime().availableProcessors()).map { cpu ->
                    File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                }
                val slowest = freqs.min()
                val big = freqs.indices.filter { freqs[it] > slowest }
                if (big.isEmpty()) return@runCatching null
                big.fold(0L) { acc, cpu -> acc or (1L shl cpu) }.toString(16)
            }.getOrNull()
        }
    }
}
