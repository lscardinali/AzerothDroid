package com.lscardinali.pocketwow.realm

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the MariaDB server staged by tools/stage_mariadb.py as a child process
 * (see [NativeRuntime] for how executables and libraries are packaged).
 * Data lives in filesDir/mariadb/data.
 *
 * Listens on a unix socket and on 127.0.0.1:3306 (loopback only, not LAN).
 */
class MariaDbServer(context: Context, private val native: NativeRuntime) {

    enum class State { STOPPED, INSTALLING, INITIALIZING, STARTING, RUNNING, STOPPING, FAILED }

    @Volatile var state = State.STOPPED
        private set
    @Volatile var error: String? = null
        private set

    private val root = File(context.filesDir, "mariadb")
    private val runtime = File(root, "runtime")
    private val dataDir = File(root, "data")
    private val runDir = File(root, "run")
    private val tmpDir = File(root, "tmp")
    private val configFile = File(root, "my.cnf")
    private val socket = File(runDir, "mariadb.sock")
    private val pidFile = File(runDir, "mariadb.pid")
    val logFile = File(root, "mariadb.err")
    private val initializedMarker = File(dataDir, ".pocketwow-initialized")
    private val usersMarker = File(dataDir, ".pocketwow-users")

    private var process: Process? = null
    private var version: String? = null

    /** Blocking: install runtime, initialize datadir on first run, start and wait until ready. */
    fun start() {
        if (state == State.RUNNING || state == State.STARTING) return
        error = null
        try {
            state = State.INSTALLING
            install()
            killStaleServer()
            writeConfig()
            if (!initializedMarker.exists()) {
                state = State.INITIALIZING
                bootstrap()
            }
            state = State.STARTING
            launch()
            awaitReady()
            if (!usersMarker.exists()) createUsers()
            state = State.RUNNING
            Log.i(TAG, "MariaDB $version ready on ${socket.path} and 127.0.0.1:$PORT")
        } catch (e: Exception) {
            Log.e(TAG, "MariaDB failed to start", e)
            error = e.message
            state = State.FAILED
            terminate(timeoutSec = 10)
        }
    }

    /** Blocking: SIGTERM lets InnoDB flush and close cleanly; SIGKILL only after a timeout. */
    fun stop() {
        if (state == State.STOPPED) return
        state = State.STOPPING
        terminate(timeoutSec = 60)
        state = State.STOPPED
    }

    fun status(): JSONObject {
        val pid = NativeRuntime.readPid(pidFile)
        val memory = pid?.let { SystemStats.processMemory(it) }
        return JSONObject()
            .put("state", state.name)
            .put("version", version)
            .put("error", error)
            .put("pid", pid)
            .put("rssKb", memory?.rssKb)
            .put("peakRssKb", memory?.peakRssKb)
            .put("port", PORT)
            .put("socket", socket.path)
    }

    // -- install ------------------------------------------------------------

    /** Copies assets/mariadb (share data, bootstrap SQL) into filesDir and links libraries. */
    private fun install() {
        val manifest = native.manifest(COMPONENT)
        version = manifest.getString("version")
        native.linkLibraries(COMPONENT)
        val stamp = File(runtime, ".installed")
        val identity = "${manifest.getString("bootstrapSha256")} ${native.installIdentity}"
        if (stamp.isFile && stamp.readText() == identity) return

        runtime.deleteRecursively()
        native.copyAssets(COMPONENT, runtime)
        File(runtime, "plugin").mkdirs()
        stamp.writeText(identity)
    }

    private fun writeConfig() {
        listOf(dataDir, runDir, tmpDir).forEach { it.mkdirs() }
        val share = File(runtime, "share")
        configFile.writeText(
            """
            [mariadbd]
            basedir=${runtime.path}
            datadir=${dataDir.path}
            tmpdir=${tmpDir.path}
            socket=${socket.path}
            pid-file=${pidFile.path}
            log-error=${logFile.path}
            plugin-dir=${File(runtime, "plugin").path}
            lc-messages-dir=${share.path}
            lc-messages=en_US
            character-sets-dir=${File(share, "charsets").path}/
            bind-address=127.0.0.1
            port=$PORT
            skip-name-resolve
            skip-log-bin
            character-set-server=utf8mb4
            collation-server=utf8mb4_unicode_ci
            max-connections=50
            performance-schema=OFF
            innodb-buffer-pool-size=128M
            innodb-log-file-size=48M
            innodb-use-native-aio=0
            innodb-flush-neighbors=0
            host-cache-size=0

            [client]
            socket=${socket.path}
            """.trimIndent() + "\n",
        )
    }

    // -- lifecycle ----------------------------------------------------------

    /** Creates the mysql system schema. Equivalent of mariadb-install-db without Perl. */
    private fun bootstrap() {
        dataDir.deleteRecursively()
        dataDir.mkdirs()
        val bootstrapSql = File(runtime, "bootstrap.sql")
        val result = command(MARIADBD, "--defaults-file=${configFile.path}", "--bootstrap")
            .redirectInput(bootstrapSql)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
        check(result.waitFor(5, TimeUnit.MINUTES)) { result.destroyForcibly(); "bootstrap timed out" }
        check(result.exitValue() == 0) { "bootstrap failed (exit ${result.exitValue()}), see mariadb.err" }
        initializedMarker.writeText(version.orEmpty())
    }

    private fun launch() {
        pidFile.delete()
        socket.delete()
        process = command(MARIADBD, "--defaults-file=${configFile.path}")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
    }

    private fun awaitReady() {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val proc = checkNotNull(process)
            check(proc.isAlive) { "mariadbd exited (code ${proc.exitValue()}), see mariadb.err" }
            if (socket.exists() && sql("SELECT 1").first == 0) return
            Thread.sleep(500)
        }
        error("mariadbd not ready after 120 s")
    }

    /**
     * AzerothCore's default credentials. Only reachable over the unix socket and
     * loopback, so they never leave the device (adb forward aside).
     */
    private fun createUsers() {
        val (code, output) = sql(
            """
            CREATE USER IF NOT EXISTS 'acore'@'localhost' IDENTIFIED BY 'acore';
            CREATE USER IF NOT EXISTS 'acore'@'127.0.0.1' IDENTIFIED BY 'acore';
            GRANT ALL PRIVILEGES ON *.* TO 'acore'@'localhost' WITH GRANT OPTION;
            GRANT ALL PRIVILEGES ON *.* TO 'acore'@'127.0.0.1' WITH GRANT OPTION;
            FLUSH PRIVILEGES;
            """.trimIndent(),
        )
        check(code == 0) { "creating users failed: $output" }
        usersMarker.writeText("acore")
    }

    private fun terminate(timeoutSec: Long) {
        NativeRuntime.readPid(pidFile)?.let { NativeRuntime.terminate(it, MARIADBD, timeoutSec) }
        process?.let { if (!it.waitFor(5, TimeUnit.SECONDS)) it.destroyForcibly() }
        process = null
    }

    /** A server left running by a previous, killed instance of the realm process. */
    private fun killStaleServer() {
        val pid = NativeRuntime.readPid(pidFile) ?: return
        if (NativeRuntime.isRunning(pid, MARIADBD)) {
            Log.w(TAG, "Stopping stale mariadbd $pid")
            terminate(timeoutSec = 60)
        }
    }

    // -- helpers ------------------------------------------------------------

    /** Runs SQL as root; throws with the client's output if it fails. Returns the output. */
    fun execute(statements: String): String {
        check(state == State.RUNNING) { "database not running" }
        val (code, output) = sql(statements)
        check(code == 0) { "SQL failed: $output" }
        return output
    }

    /** Runs SQL as root through the bundled client. Returns exit code and output. */
    private fun sql(statements: String): Pair<Int, String> {
        val proc = command(CLIENT, "--defaults-file=${configFile.path}", "--user=root", "--batch")
            .redirectErrorStream(true)
            .start()
        proc.outputStream.bufferedWriter().use { it.write(statements) }
        val output = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(30, TimeUnit.SECONDS)) proc.destroyForcibly()
        return (if (proc.isAlive) -1 else proc.exitValue()) to output
    }

    private fun command(executable: String, vararg args: String) =
        native.command(executable, *args, home = root)

    companion object {
        private const val TAG = "MariaDbServer"
        private const val COMPONENT = "mariadb"
        private const val MARIADBD = "libmariadbd.so"
        const val CLIENT = "libmariadb_client.so"
        const val PORT = 3306
    }
}
