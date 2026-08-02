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
