package net.rpcs3

import android.content.Context
import java.io.File

/** Installs the exact per-title VFS and patch data from the TeknoParrot RPCS3 release. */
object TeknoParrotArcadeConfig {
    private val vfsAssets = mapOf(
        "DarkEscape4D" to "darkescape4d_vfs.yml",
        "DSPS" to "dsps_vfs.yml",
        "dbzenkai" to "dbzenkai_vfs.yml",
        "RazingStorm" to "RazingStorm_vfs.yml",
        "AKB48" to "akb48_vfs.yml",
        "taikogreen" to "taikogreen_vfs.yml",
        "taikoyellow" to "taikoyellow_vfs.yml",
        "Tekken6" to "Tekken6_vfs.yml",
        "Tekken6BR" to "Tekken6BR_vfs.yml",
        "ttt2" to "ttt2_vfs.yml",
        "ttt2u" to "ttt2u_vfs.yml"
    )

    fun installGlobalAssets(context: Context): Boolean = runCatching {
        val root = context.getExternalFilesDir(null) ?: return@runCatching false
        copyAsset(context, "teknoparrot/patch_config.yml", File(root, "config/patch_config.yml"))
        copyAsset(
            context,
            "teknoparrot/patches/imported_patch.yml",
            File(root, "config/patches/imported_patch.yml")
        )
        true
    }.getOrDefault(false)

    fun prepare(context: Context, profileName: String, root: File): File? = runCatching {
        val assetName = vfsAssets[profileName] ?: return@runCatching null
        val appRoot = context.getExternalFilesDir(null) ?: return@runCatching null
        val target = File(appRoot, "config/teknoparrot/$assetName")
        copyAsset(context, "teknoparrot/vfs/$assetName", target)

        listOf("dev_hdd0", "dev_hdd1", "dev_bdvd", "games/shortcuts", "dev_usb000")
            .forEach { File(root, it).mkdirs() }
        val userDirectory = File(root, "dev_hdd0/home/00000001")
        userDirectory.mkdirs()
        val localUsername = File(userDirectory, "localusername")
        if (!localUsername.isFile) localUsername.writeText("User")

        // Mirror the RPCS3Config entries used by the desktop TeknoParrot
        // profiles. Reset title-specific values on every launch because all
        // arcade games intentionally share the SCEEXE000 title id.
        check(RPCS3.instance.settingsSet("Core@@PPU Threads", "2"))
        check(RPCS3.instance.settingsSet(
            "Video@@Write Color Buffers",
            (profileName == "DSPS" || profileName == "RazingStorm").toString()
        ))
        target
    }.getOrNull()

    private fun copyAsset(context: Context, assetPath: String, target: File) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".installing")
        context.assets.open(assetPath).use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        if (!temporary.renameTo(target)) {
            target.delete()
            check(temporary.renameTo(target)) { "Could not install $assetPath" }
        }
    }
}
