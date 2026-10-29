package com.lscardinali.pocketwow.realm

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Runs AzerothCore's authserver (login + realm list) built by tools/build_azerothcore.py.
 *
 * On first start AzerothCore's own updater (Updates.AutoSetup) creates the acore_auth
 * database and imports data/sql/base/db_auth (from the [DataPack]) through the bundled
 * mariadb client.
 * Listens on 0.0.0.0:3724 so clients on the device and on the LAN can log in.
 */
class AuthServer(context: Context, private val native: NativeRuntime, private val dataPack: DataPack) {

    enum class State { STOPPED, STARTING, RUNNING, STOPPING, FAILED }

    @Volatile var state = State.STOPPED
        private set
    @Volatile var error: String? = null
        private set

    private val root = File(context.filesDir, "azerothcore")
    private val runDir = File(root, "run")
    private val logsDir = File(root, "logs")
    private val configFile = File(root, "authserver.conf")
    private val pidFile = File(runDir, "authserver.pid")
    /** AzerothCore's own log (Appender.Auth), rewritten on each start. */
    val logFile = File(logsDir, "Auth.log")
    /** Raw stdout/stderr; block-buffered, so mostly useful after a crash. */
    val stdoutFile = File(logsDir, "authserver.out")

    private var process: Process? = null
    private var commit: String? = null

    /** Blocking: requires MariaDB to be running. Returns once port 3724 accepts connections. */
    fun start() {
        if (state == State.RUNNING || state == State.STARTING) return
        error = null
        state = State.STARTING
        try {
            dataPack.check()
            install()
            NativeRuntime.readPid(pidFile)?.let {
                if (NativeRuntime.isRunning(it, EXECUTABLE)) Log.w(TAG, "Stopping stale authserver $it")
                NativeRuntime.terminate(it, EXECUTABLE, timeoutSec = 20)
            }
            writeConfig()
            pidFile.delete()
            process = native.command(EXECUTABLE, "-c", configFile.path, home = root)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(stdoutFile))
                .start()
            awaitPort()
            state = State.RUNNING
            Log.i(TAG, "authserver ready on port $PORT")
        } catch (e: Exception) {
            Log.e(TAG, "authserver failed to start", e)
            error = e.message
            state = State.FAILED
            terminate()
        }
    }

    fun stop() {
        if (state == State.STOPPED) return
        state = State.STOPPING
        terminate()
        state = State.STOPPED
    }

    fun status(): JSONObject {
        val pid = NativeRuntime.readPid(pidFile)?.takeIf { NativeRuntime.isRunning(it, EXECUTABLE) }
        val memory = pid?.let { SystemStats.processMemory(it) }
        return JSONObject()
            .put("state", state.name)
            .put("commit", commit?.take(10))
            .put("error", error)
            .put("pid", pid)
            .put("rssKb", memory?.rssKb)
            .put("peakRssKb", memory?.peakRssKb)
            .put("port", PORT)
    }

    /** Links libraries and copies the config template. */
    private fun install() {
        commit = native.manifest(COMPONENT).getString("commit")
        native.linkLibraries(COMPONENT)
        native.copyAssets("$COMPONENT/$COMPONENT.conf.dist", File(root, "$COMPONENT.conf.dist"))
    }

    private fun writeConfig() {
        listOf(runDir, logsDir, File(root, "tmp")).forEach { it.mkdirs() }
        val overrides = mapOf(
            "LogsDir" to logsDir.path,
            "PidFile" to pidFile.path,
            "SourceDirectory" to dataPack.sourceDirectory.path,
            "MySQLExecutable" to File(native.nativeDir, MariaDbServer.CLIENT).path,
            "TempDir" to File(root, "tmp").path + "/",
            "BindIP" to "0.0.0.0",
            "RealmServerPort" to PORT.toString(),
            "LoginDatabaseInfo" to "127.0.0.1;${MariaDbServer.PORT};acore;acore;acore_auth",
            // data/sql/archive is not bundled; drop its rows from the updates table.
            "Updates.CleanDeadRefMaxCount" to "-1",
        )
        configFile.writeText(applyOverrides(File(root, "$COMPONENT.conf.dist").readText(), overrides))
    }

    private fun awaitPort() {
        // First start also creates and imports acore_auth, so allow a few minutes.
        val deadline = System.currentTimeMillis() + 300_000
        while (System.currentTimeMillis() < deadline) {
            val proc = checkNotNull(process)
            check(proc.isAlive) { "authserver exited (code ${proc.exitValue()}), see authserver.out" }
            val open = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 500) }
            }.isSuccess
            if (open) return
            Thread.sleep(500)
        }
        error("authserver not listening after 300 s")
    }

    private fun terminate() {
        NativeRuntime.readPid(pidFile)?.let { NativeRuntime.terminate(it, EXECUTABLE, timeoutSec = 20) }
        process?.let { if (!it.waitFor(5, TimeUnit.SECONDS)) it.destroyForcibly() }
        process = null
    }

    companion object {
        private const val TAG = "AuthServer"
        private const val COMPONENT = "authserver"
        private const val EXECUTABLE = "libauthserver.so"
        const val PORT = 3724

        /** Replaces `Key = value` lines of an AzerothCore .conf.dist; quotes string values. */
        fun applyOverrides(conf: String, overrides: Map<String, String>): String =
            overrides.entries.fold(conf) { text, (key, value) ->
                val line = Regex("""^${Regex.escape(key)}\s*=.*$""", RegexOption.MULTILINE)
                check(line.containsMatchIn(text)) { "config key $key not found" }
                val quoted = if (value.toLongOrNull() != null) value else "\"$value\""
                text.replace(line, Regex.escapeReplacement("$key = $quoted"))
            }
    }
}
