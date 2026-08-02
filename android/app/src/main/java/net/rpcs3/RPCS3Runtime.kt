package net.rpcs3

import android.content.Context
import java.io.File
import kotlin.concurrent.thread

/** Process-wide native runtime initialization shared by setup and game activities. */
object RPCS3Runtime {
    @Synchronized
    fun ensureInitialized(context: Context): Boolean {
        if (RPCS3.initialized) return true

        val root = context.applicationContext.getExternalFilesDir(null) ?: return false
        RPCS3.rootDirectory = root.absolutePath.trimEnd('/') + "/"

        if (!TeknoParrotArcadeConfig.installGlobalAssets(context.applicationContext)) return false

        if (!RPCS3.instance.initialize(RPCS3.rootDirectory)) return false

        val nativeLibraryDir =
            context.packageManager.getApplicationInfo(context.packageName, 0).nativeLibraryDir
        val customDriverDir = prepareTurnipDriver(context.applicationContext, nativeLibraryDir)
            ?: return false
        val temporaryDir = File(context.applicationContext.cacheDir, "adrenotools")
        if (!temporaryDir.isDirectory && !temporaryDir.mkdirs()) return false
        if (!RPCS3.instance.configureVulkanDriver(
                nativeLibraryDir,
                customDriverDir.absolutePath.trimEnd('/') + "/",
                temporaryDir.absolutePath
            )
        ) return false

        // A fresh Android configuration currently defaults to the null
        // renderer. The TeknoParrot companion is always an interactive game
        // session, so make Vulkan explicit instead of depending on the user
        // visiting RPCS3's standalone settings UI first.
        if (!RPCS3.instance.settingsSet("Video@@Renderer", "\"Vulkan\"")) return false

        // The shader interpreter precompiles 570 Vulkan pipeline variants at
        // every first boot. Qualcomm's Android driver rejects those pipelines
        // with VK_ERROR_UNKNOWN, terminating every compiler worker and leaving
        // the game permanently stuck at variant 0. Use RPCS3's normal async
        // shader recompiler on Android; shaders still compile and cache as the
        // game encounters them, without the incompatible interpreter startup.
        if (!RPCS3.instance.settingsSet(
                "Video@@Shader Mode",
                "\"Async Recompiler (multi-threaded)\""
            )
        ) return false

        // Zero means RPCS3's platform-aware automatic worker count. Restore
        // this on every launch so a diagnostic/manual override cannot leave
        // Android shader compilation serialized.
        if (!RPCS3.instance.settingsSet("Video@@Shader Compiler Threads", "0")) return false

        RPCS3.initialized = true

        thread(name = "RPCS3X6 main processor") {
            RPCS3.instance.startMainThreadProcessor()
        }
        thread(name = "RPCS3X6 compilation queue") {
            RPCS3.instance.processCompilationQueue()
        }
        return true
    }

    private fun prepareTurnipDriver(context: Context, nativeLibraryDir: String): File? =
        runCatching {
            val source = File(nativeLibraryDir, TURNIP_LIBRARY_NAME)
            check(source.isFile) { "The packaged Turnip library is missing" }

            val directory = File(context.filesDir, "vulkan-driver")
            check(directory.isDirectory || directory.mkdirs()) {
                "Could not create the private Vulkan driver directory"
            }

            val target = File(directory, TURNIP_LIBRARY_NAME)
            val temporary = File(directory, "$TURNIP_LIBRARY_NAME.installing")
            source.inputStream().use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(!target.exists() || target.delete()) { "Could not replace the previous Turnip library" }
            check(temporary.renameTo(target)) { "Could not install the packaged Turnip library" }
            target
        }.getOrNull()

    private const val TURNIP_LIBRARY_NAME = "libvulkan_freedreno.so"
}
