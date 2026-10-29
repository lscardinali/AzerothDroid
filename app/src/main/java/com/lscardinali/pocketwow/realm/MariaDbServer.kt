package com.lscardinali.pocketwow.realm

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Runs the MariaDB server staged by tools/stage_mariadb.py as a child process.
 *
 * Executables and libraries live in nativeLibraryDir (the only place an app may exec
 * from). Their original sonames are recreated as symlinks under runtime/lib, which is
 * passed as LD_LIBRARY_PATH. Data lives in filesDir/mariadb/data.
 *
 * Listens on a unix socket and on 127.0.0.1:3306 (loopback only, not LAN).
 */
class MariaDbServer(private val context: Context) {

    enum class State { STOPPED, INSTALLING, INITIALIZING, STARTING, RUNNING, STOPPING, FAILED }

    @Volatile var state = State.STOPPED
        private set
    @Volatile var error: String? = null
        private set

    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val root = File(context.filesDir, "mariadb")
    private val runtime = File(root, "runtime")
    private val libDir = File(runtime, "lib")
    private val dataDir = File(root, "data")
    private val runDir = File(root, "run")
    private val tmpDir = File(root, "tmp")
    private val configFile = File(root, "my.cnf")
    private val socket = File(runDir, "mariadb.sock")
    private val pidFile = File(runDir, "mariadb.pid")
    val logFile = File(root, "mariadb.err")
    private val initializedMarker = File(dataDir, ".pocketwow-initialized")
    private val usersMarker = File(dataDir, ".pocketwow-users")

    private val mariadbd = File(nativeDir, "libmariadbd.so")
    private val client = File(nativeDir, "libmariadb_client.so")

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
        val pid = readPid()
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

    /** Copies assets/mariadb into filesDir and links sonames to nativeLibraryDir. */
    private fun install() {
        val manifest = JSONObject(context.assets.open("$ASSET_DIR/manifest.json").bufferedReader().readText())
        version = manifest.getString("version")
        val stamp = File(runtime, ".installed")
        val identity = "${manifest.getString("bootstrapSha256")} ${context.packageManager
            .getPackageInfo(context.packageName, 0).lastUpdateTime}"
        if (stamp.isFile && stamp.readText() == identity) return

        runtime.deleteRecursively()
        copyAssets(ASSET_DIR, runtime)
        libDir.mkdirs()
        val links = manifest.getJSONObject("links")
        for (soname in links.keys()) {
            val target = File(nativeDir, links.getString(soname))
            check(target.isFile) { "missing native library ${target.name}" }
            Files.createSymbolicLink(File(libDir, soname).toPath(), target.toPath())
        }
        File(runtime, "plugin").mkdirs()
        stamp.writeText(identity)
    }

    private fun copyAssets(path: String, target: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        } else {
            target.mkdirs()
            children.forEach { copyAssets("$path/$it", File(target, it)) }
        }
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
        val result = command(mariadbd, "--defaults-file=${configFile.path}", "--bootstrap")
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
        process = command(mariadbd, "--defaults-file=${configFile.path}")
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
        val pid = readPid()
        if (pid != null && isMariadbd(pid)) {
            android.os.Process.sendSignal(pid, SIGTERM)
            val deadline = System.currentTimeMillis() + timeoutSec * 1000
            while (isMariadbd(pid) && System.currentTimeMillis() < deadline) Thread.sleep(200)
            if (isMariadbd(pid)) {
                Log.w(TAG, "mariadbd $pid ignored SIGTERM, killing")
                android.os.Process.killProcess(pid)
            }
        }
        process?.let { if (!it.waitFor(5, TimeUnit.SECONDS)) it.destroyForcibly() }
        process = null
    }

    /** A server left running by a previous, killed instance of the realm process. */
    private fun killStaleServer() {
        val pid = readPid() ?: return
        if (isMariadbd(pid)) {
            Log.w(TAG, "Stopping stale mariadbd $pid")
            terminate(timeoutSec = 60)
        }
    }

    // -- helpers ------------------------------------------------------------

    /** Runs SQL as root through the bundled client. Returns exit code and output. */
    private fun sql(statements: String): Pair<Int, String> {
        val proc = command(client, "--defaults-file=${configFile.path}", "--user=root", "--batch")
            .redirectErrorStream(true)
            .start()
        proc.outputStream.bufferedWriter().use { it.write(statements) }
        val output = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(30, TimeUnit.SECONDS)) proc.destroyForcibly()
        return (if (proc.isAlive) -1 else proc.exitValue()) to output
    }

    private fun command(executable: File, vararg args: String) =
        ProcessBuilder(listOf(executable.path) + args).apply {
            environment()["LD_LIBRARY_PATH"] = libDir.path
            environment()["TMPDIR"] = tmpDir.path
            environment()["HOME"] = root.path
        }

    private fun readPid(): Int? = pidFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()

    private fun isMariadbd(pid: Int): Boolean =
        runCatching { File("/proc/$pid/cmdline").readText().contains("libmariadbd.so") }
            .getOrDefault(false)

    companion object {
        private const val TAG = "MariaDbServer"
        private const val ASSET_DIR = "mariadb"
        private const val SIGTERM = 15
        const val PORT = 3306
    }
}
