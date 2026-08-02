package net.rpcs3

import android.content.Context
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

        val nativeLibraryDir =
            context.packageManager.getApplicationInfo(context.packageName, 0).nativeLibraryDir
        RPCS3.instance.settingsSet(
            "Video@@Vulkan@@Custom Driver@@Hook Directory",
            "\"$nativeLibraryDir\""
        )
        RPCS3.initialized = true

        thread(name = "RPCS3X6 main processor") {
            RPCS3.instance.startMainThreadProcessor()
        }
        thread(name = "RPCS3X6 compilation queue") {
            RPCS3.instance.processCompilationQueue()
        }
        return true
    }
}
