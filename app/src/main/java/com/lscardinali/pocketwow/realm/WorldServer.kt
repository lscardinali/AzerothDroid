package com.lscardinali.pocketwow.realm

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Runs AzerothCore's worldserver with mod-playerbots, built by tools/build_azerothcore.py.
 *
 * Reads client data (DataDir) and SQL (SourceDirectory) from the [DataPack]. On first
 * start AzerothCore's updater
 * creates and imports acore_world and acore_characters, and mod-playerbots creates
 * acore_playerbots; the world import alone can take a long time on a phone.
 *
 * Listens on 0.0.0.0:8085. SOAP (127.0.0.1:7878) is enabled for app-side commands.
 */
class WorldServer(context: Context, private val native: NativeRuntime, private val dataPack: DataPack) {

    /** PAUSED: the process is stopped with SIGSTOP (no CPU at all) until [resume]. */
    enum class State { STOPPED, STARTING, RUNNING, PAUSED, STOPPING, FAILED }

    @Volatile var state = State.STOPPED
        private set
    @Volatile var error: String? = null
        private set

    private val root = File(context.filesDir, "azerothcore")
    private val etcDir = File(root, "etc")
    private val runDir = File(root, "run")
    private val logsDir = File(root, "logs")
    private val configFile = File(etcDir, "worldserver.conf")
    private val pidFile = File(runDir, "worldserver.pid")
    /** AzerothCore's own log (Appender.Server), rewritten on each start. */
    val logFile = File(logsDir, "Server.log")
    /** Raw stdout/stderr; block-buffered, so mostly useful after a crash. */
    val stdoutFile = File(logsDir, "worldserver.out")

    private var process: Process? = null

    /**
     * Blocking: requires MariaDB and the data pack. Returns once port 8085 accepts connections.
     * [settings] are written into the .conf files here, so changes apply on the next start.
     */
    fun start(settings: RealmSettings) {
        if (state == State.RUNNING || state == State.STARTING) return
        error = null
        state = State.STARTING
        try {
            dataPack.check()
            native.linkLibraries(COMPONENT)
            NativeRuntime.readPid(pidFile)?.let {
                if (NativeRuntime.isRunning(it, EXECUTABLE)) Log.w(TAG, "Stopping stale worldserver $it")
                NativeRuntime.terminate(it, EXECUTABLE, timeoutSec = 60)
            }
            writeConfig(settings)
            pidFile.delete()
            NativeRuntime.trimLog(stdoutFile)
            process = native.command(
                EXECUTABLE, "-c", configFile.path,
                home = root,
                extraEnv = mapOf("AC_CONFIG_DIR" to etcDir.path),
            )
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(stdoutFile))
                .start()
            awaitPort()
            state = State.RUNNING
            Log.i(TAG, "worldserver ready on port $PORT")
        } catch (e: Exception) {
            Log.e(TAG, "worldserver failed to start", e)
            error = e.message
            state = State.FAILED
            terminate()
        }
    }

    /**
     * For the watchdog: true (once) if the process died while this server was meant to be
     * up, e.g. killed by Android for memory. The server is then FAILED and can be started again.
     */
    fun checkExited(): Boolean {
        if (state != State.RUNNING && state != State.PAUSED) return false
        val pid = NativeRuntime.readPid(pidFile)
        if (pid != null && NativeRuntime.isRunning(pid, EXECUTABLE)) return false
        error = "Exited unexpectedly"
        state = State.FAILED
        return true
    }

    /** Freezes the running server; the kernel still queues new connections for [resume]. */
    @Synchronized
    fun pause() {
        if (state != State.RUNNING) return
        val pid = NativeRuntime.readPid(pidFile)?.takeIf { NativeRuntime.isRunning(it, EXECUTABLE) } ?: return
        android.os.Process.sendSignal(pid, SIGSTOP)
        state = State.PAUSED
        Log.i(TAG, "worldserver paused")
    }

    @Synchronized
    fun resume() {
        if (state != State.PAUSED) return
        NativeRuntime.readPid(pidFile)?.let { android.os.Process.sendSignal(it, SIGCONT) }
        state = State.RUNNING
        Log.i(TAG, "worldserver resumed")
    }

    /** SIGTERM makes worldserver save online players and shut down cleanly. */
    fun stop() {
        if (state == State.STOPPED) return
        // A stopped process holds SIGTERM until it continues.
        resume()
        state = State.STOPPING
        terminate()
        state = State.STOPPED
    }

    fun status(): JSONObject {
        val pid = NativeRuntime.readPid(pidFile)?.takeIf { NativeRuntime.isRunning(it, EXECUTABLE) }
        val memory = pid?.let { SystemStats.processMemory(it) }
        return JSONObject()
            .put("state", state.name)
            .put("error", error)
            .put("pid", pid)
            .put("rssKb", memory?.rssKb)
            .put("swapKb", memory?.swapKb)
            .put("peakRssKb", memory?.peakRssKb)
            .put("port", PORT)
    }

    private fun writeConfig(settings: RealmSettings) {
        listOf(etcDir, File(etcDir, "modules"), runDir, logsDir, File(root, "tmp")).forEach { it.mkdirs() }
        val assets = "$COMPONENT/"
        val worldDist = File(etcDir, "worldserver.conf.dist")
        val botsDist = File(etcDir, "modules/playerbots.conf.dist")
        native.copyAssets(assets + "worldserver.conf.dist", worldDist)
        native.copyAssets(assets + "playerbots.conf.dist", botsDist)

        // Infrastructure last: these are the app's, whatever the settings say.
        val world = settings.world.overrides() + mapOf(
            "DataDir" to dataPack.clientData.path,
            "SourceDirectory" to dataPack.sourceDirectory.path,
            "LogsDir" to logsDir.path,
            "PidFile" to pidFile.path,
            "TempDir" to File(root, "tmp").path + "/",
            "MySQLExecutable" to File(native.nativeDir, MariaDbServer.CLIENT).path,
            "BindIP" to "0.0.0.0",
            "WorldServerPort" to PORT.toString(),
            "LoginDatabaseInfo" to "127.0.0.1;${MariaDbServer.PORT};acore;acore;acore_auth",
            "WorldDatabaseInfo" to "127.0.0.1;${MariaDbServer.PORT};acore;acore;acore_world",
            "CharacterDatabaseInfo" to "127.0.0.1;${MariaDbServer.PORT};acore;acore;acore_characters",
            // No terminal attached; commands go through SOAP instead.
            "Console.Enable" to "0",
            "SOAP.Enabled" to "1",
            "SOAP.IP" to "127.0.0.1",
            "SOAP.Port" to SOAP_PORT.toString(),
            // data/sql/archive is not bundled; drop its rows from the updates table.
            "Updates.CleanDeadRefMaxCount" to "-1",
            // Anti-cheat for public servers; costs CPU and memory per session.
            "Warden.Enabled" to "0",
            // At most 100 world updates a second instead of 1000: plenty for play, far less
            // CPU (and battery) when little is happening.
            "MinWorldUpdateTime" to "10",
            // Android can kill the realm without warning; lose at most two minutes of play.
            "PlayerSaveInterval" to "120000",
        )
        val additions = mapOf(
            // Patch 0008: English clients only, skip a few hundred MB of translations.
            "Locales.Load" to "0",
            // Patch 0011: patrols far from every player would load grid after grid.
            "Creatures.WaypointUpdatesWithoutPlayers" to "0",
        )
        configFile.writeText(AuthServer.applyOverrides(worldDist.readText(), world, additions))

        val botOverrides = settings.bots.overrides() +
            ("PlayerbotsDatabaseInfo" to "127.0.0.1;${MariaDbServer.PORT};acore;acore;acore_playerbots")
        File(etcDir, "modules/playerbots.conf").writeText(AuthServer.applyOverrides(botsDist.readText(), botOverrides))
    }

    private fun awaitPort() {
        // The first start imports the whole world database, which is slow on a phone.
        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(90)
        while (System.currentTimeMillis() < deadline) {
            val proc = checkNotNull(process)
            check(proc.isAlive) { "worldserver exited (code ${proc.exitValue()}), see Server.log" }
            val open = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 500) }
            }.isSuccess
            if (open) return
            Thread.sleep(1_000)
        }
        error("worldserver not listening after 90 min")
    }

    private fun terminate() {
        NativeRuntime.readPid(pidFile)?.let { NativeRuntime.terminate(it, EXECUTABLE, timeoutSec = 90) }
        process?.let { if (!it.waitFor(5, TimeUnit.SECONDS)) it.destroyForcibly() }
        process = null
    }

    companion object {
        private const val TAG = "WorldServer"
        private const val SIGCONT = 18
        private const val SIGSTOP = 19
        private const val COMPONENT = "worldserver"
        private const val EXECUTABLE = "libworldserver.so"
        const val PORT = 8085
        const val SOAP_PORT = 7878
    }
}
