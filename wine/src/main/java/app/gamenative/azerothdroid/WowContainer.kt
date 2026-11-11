package app.gamenative.azerothdroid

import android.content.Context
import android.os.Build
import android.view.WindowManager
import app.gamenative.PluviaApp
import app.gamenative.utils.Net
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contents.ContentsManager
import com.winlator.core.FileUtils
import com.winlator.core.TarCompressorUtils
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.ImageFsInstaller
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Prepares the Wine container that runs a Windows WoW 3.3.5a client (32-bit x86, Direct3D 9).
 *
 * Built on WoWForeverywhere's setup for WoW Forever, with what differs for 3.3.5a:
 *  - Wow.exe is i386, so it runs through wow64 with FEXCore (x86 DLLs) rather than natively.
 *  - Direct3D 9 goes through x86 DXVK 2.4.1 gplasync. A d3d9.dll in the game folder would load
 *    first, so the host app moves a DXVK one aside before launching (GameLauncher).
 *  - No Battle.net, no client download: the client is the player's own folder.
 */
object WowContainer {

    const val CONTAINER_ID = "azerothdroid_wow"
    private const val PROTON_VERSION = "proton-11.0-90624-arm64ec"
    /** x86 DXVK with async pipeline compiling (GameNative's build): new effects compile without hitching. */
    private const val DXVK_VERSION = "2.4.1-gplasync"
    private const val FEXCORE_VERSION = "2605"
    /** The Turnip build WoWForeverywhere uses on Adreno 7xx (the Retroid Pocket 6 has an Adreno 740). */
    private const val ADRENO_7XX_DRIVER = "Turnip-WoW-scheduler-test"
    private const val ADRENO_8XX_DRIVER = "Turnip-V32-RP6sched"
    private const val IMAGEFS_ARCHIVE = "imagefs_bionic.txz"
    private const val EMULATOR = "FEXCore"
    private const val FEX_PRESET = "INTERMEDIATE"

    /**
     * Render size before reshaping to the window: native 1080p on Adreno 7xx (WoWForeverywhere runs the
     * far heavier modern client there at 1080p), 720p on Adreno 8xx, as WoWForeverywhere does for its driver.
     */
    private fun baseScreenSize(): String = if (isAdreno8xx()) "1280x720" else "1920x1080"

    /** For measuring on a device: `adb shell setprop debug.azerothdroid.<name> <value>`. */
    internal fun debugProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "debug.azerothdroid.$name") as String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Blocking: installs the Wine runtime (first time only: about 180 MB downloaded and unpacked),
     * then creates or updates the container for [gameRoot]. Returns the container id for [GameActivity].
     */
    suspend fun prepare(context: Context, gameRoot: File, onStatus: (String, Float) -> Unit): String {
        PluviaApp.init(context)
        check(findExe(gameRoot) != null) { "No Wow.exe in $gameRoot" }

        val imageFsArchive = File(context.filesDir, IMAGEFS_ARCHIVE)
        val imageFs = ImageFs.find(context)
        if (!imageFs.isValid && !imageFsArchive.exists()) {
            onStatus("Downloading the Windows runtime", 0f)
            Net.fetchFileWithFallback(IMAGEFS_ARCHIVE, imageFsArchive) { onStatus("Downloading the Windows runtime", it) }
        }

        installBundledComponents(context, onStatus)

        onStatus("Setting up the game container", -1f)
        val manager = ContainerManager(context)
        val config = containerConfig(context, gameRoot)
        var container = manager.getContainerById(CONTAINER_ID)
        if (container == null) {
            onStatus("Creating the Windows prefix (first start only)", -1f)
            container = manager.createContainer(CONTAINER_ID, config)
        } else {
            container.loadData(config)
            container.saveData()
        }
        checkNotNull(container) { "Could not create the game container; check free storage" }

        onStatus("Installing the Windows runtime", 0f)
        val installed = ImageFsInstaller.installIfNeededFuture(context, context.assets, container) { progress ->
            onStatus("Installing the Windows runtime", progress / 100f)
        }.get()
        check(installed) { "Installing the Windows runtime failed" }

        manager.activateContainer(container)
        return CONTAINER_ID
    }

    fun findExe(gameRoot: File): File? =
        gameRoot.listFiles()?.firstOrNull { it.isFile && it.name.equals("Wow.exe", ignoreCase = true) }

    private fun installBundledComponents(context: Context, onStatus: (String, Float) -> Unit) {
        installDriver(context, ADRENO_7XX_DRIVER, "turnip-wow-scheduler-test.zip", onStatus)
        installDriver(context, ADRENO_8XX_DRIVER, "turnip-V32-RP6sched.zip", onStatus)

        val protonDir = File(ImageFs.getSharedProtonDir(context), PROTON_VERSION)
        if (!File(protonDir, "profile.json").exists() || !File(protonDir, "bin/wine").exists()) {
            onStatus("Unpacking Wine (Proton 11)", -1f)
            protonDir.mkdirs()
            check(
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD, context, "bundled_components/$PROTON_VERSION.wcp", protonDir,
                ),
            ) { "Unpacking Wine failed; check free storage" }
            File(protonDir, "bin").listFiles()?.forEach {
                it.setExecutable(true, false)
                it.setReadable(true, false)
            }
        }
        ImageFsInstaller.ensureBionicLib(context, ImageFs.find(context).rootDir)
        ContentsManager(context).syncContents()
    }

    private fun installDriver(context: Context, name: String, assetZip: String, onStatus: (String, Float) -> Unit) {
        val dir = File(context.filesDir, "contents/adrenotools/$name")
        if (File(dir, "meta.json").exists()) return
        onStatus("Installing the graphics driver", -1f)
        dir.mkdirs()
        if (!FileUtils.extractZipFromAssets(context, "bundled_components/$assetZip", dir)) {
            Timber.e("Could not extract driver %s", name)
        }
    }

    private fun containerConfig(context: Context, gameRoot: File): JSONObject {
        val adreno8xx = isAdreno8xx()
        val driver = if (adreno8xx) ADRENO_8XX_DRIVER else ADRENO_7XX_DRIVER
        val tuDebug = if (adreno8xx) "noconform,sysmem" else "noconform"
        val envVars = listOf(
            "WRAPPER_MAX_IMAGE_COUNT=0",
            "MESA_SHADER_CACHE_DISABLE=false",
            "MESA_SHADER_CACHE_MAX_SIZE=512MB",
            "WINEESYNC=0",
            "MESA_VK_WSI_PRESENT_MODE=mailbox",
            "TU_DEBUG=$tuDebug",
            "PULSE_LATENCY_MSEC=144",
            // 3.3.5a is a 2 GB address space game; the HD patches it often carries need the rest.
            "WINE_LARGE_ADDRESS_AWARE=1",
        ).plus(listOfNotNull(debugProperty("env"))).joinToString(" ")
        val exe = findExe(gameRoot)!!.name
        val dxwrapperConfig = Container.DEFAULT_DXWRAPPERCONFIG
            .replace(Regex("""(^|,)version=[^,]*"""), "$1version=$DXVK_VERSION")
            // Async on, and its pipelines cached between sessions (DXVK_ASYNC, DXVK_GPLASYNCCACHE).
            .replace(Regex("""(^|,)async=[^,]*"""), "$1async=1")
            .replace(Regex("""(^|,)asyncCache=[^,]*"""), "$1asyncCache=1")
        return JSONObject().apply {
            put("id", CONTAINER_ID)
            put("name", "World of Warcraft 3.3.5a")
            put("screenSize", screenSize(context))
            put("envVars", envVars)
            put("graphicsDriver", "Wrapper")
            put("graphicsDriverVersion", driver)
            put("graphicsDriverConfig", "version=$driver,adrenotoolsTurnip=1,resourceType=buffer,bcnEmulation=auto,quality=high")
            put("displayRenderer", "vulkan")
            put("dxwrapper", "dxvk")
            put("dxwrapperConfig", dxwrapperConfig)
            put("wineVersion", "$PROTON_VERSION-1")
            put("containerVariant", Container.BIONIC)
            // i386 code under wow64: FEXCore (libwow64fex) or Box64 (wowbox64).
            put("emulator", debugProperty("emulator") ?: EMULATOR)
            put("fexcoreVersion", FEXCORE_VERSION)
            put("fexcorePreset", debugProperty("fexpreset") ?: FEX_PRESET)
            put("wow64Mode", true)
            put("drives", "D:/storage/emulated/0/DownloadE:/data/data/${context.packageName}/storageG:${gameRoot.path}")
            put("executablePath", "G:\\$exe")
            put("execArgs", "")
            put("showFPS", false)
            // Frozen in the background (no CPU, no sound), resumed on return. The realm keeps an idle
            // client connected for SocketTimeOutTime, 15 minutes by default.
            put("suspendPolicy", Container.SUSPEND_POLICY_AUTO)
            put("startupSelection", Container.STARTUP_SELECTION_AGGRESSIVE.toInt())
            put("wincomponents", "direct3d=0,directsound=0,directinput8=0,directinput=0,directmusic=0,directshow=0,directplay=0,vcrun2010=0,wmdecoder=0,opengl=0")
        }
    }

    private fun isAdreno8xx(): Boolean {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
        return listOf("SM8735", "SM8750", "SM8845", "SM8850").any { soc.contains(it, ignoreCase = true) } ||
            Build.HARDWARE.contains("sun", ignoreCase = true)
    }

    /** The X screen the game draws on, so the client can match its window to it. */
    fun screenSize(context: Context): String = fitScreenSize(context, debugProperty("screen") ?: baseScreenSize())

    /** [base]'s pixel count, reshaped to the window's aspect ratio so the game fills the screen. */
    private fun fitScreenSize(context: Context, base: String): String {
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val w = maxOf(bounds.width(), bounds.height())
        val h = minOf(bounds.width(), bounds.height())
        if (w <= 0 || h <= 0) return base
        val (bw, bh) = base.split("x").map { it.toInt() }
        val aspect = w.toFloat() / h
        val height = sqrt(bw * bh / aspect)
        return "${(height * aspect / 2).roundToInt() * 2}x${(height / 2).roundToInt() * 2}"
    }

}
