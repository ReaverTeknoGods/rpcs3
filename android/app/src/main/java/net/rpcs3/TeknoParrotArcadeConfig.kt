package net.rpcs3

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/** Installs the exact per-title VFS and patch data from the TeknoParrot RPCS3 release. */
object TeknoParrotArcadeConfig {
    private data class TaikoVersion(
        val seriesVersion: Byte,
        val year: Byte,
        val archiveVersion: Byte
    )

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

    // hardware ID (2 bytes), hardware revision, configuration checksum.
    // This is the exact table used by old TeknoParrot CheckRpcs3().
    private val boardStorage = mapOf(
        "ttt2u" to byteArrayOf(0x43, 0x50, 0x24, 0x68),
        "ttt2" to byteArrayOf(0x43, 0x50, 0x24, 0x68),
        "AKB48" to byteArrayOf(0x43, 0x50, 0x54, 0x4E),
        "DarkEscape4D" to byteArrayOf(0x31, 0x4C, 0xB0.toByte(), 0xE9.toByte()),
        "taikogreen" to byteArrayOf(0x43, 0x50, 0xA7.toByte(), 0x9B.toByte()),
        "taikoyellow" to byteArrayOf(0x43, 0x50, 0xA7.toByte(), 0x9B.toByte())
    )

    // Exact SetupTaikoVersionFiles values from the original TeknoParrot
    // RPCS3 launcher. Both emulated USB devices must expose the version file
    // or the cabinet software cannot complete its version checks.
    private val taikoVersions = mapOf(
        "taikogreen" to TaikoVersion(0x0B, 0x19, 0x0A),
        "taikoyellow" to TaikoVersion(0x09, 0x19, 0x0A)
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

        if (profileName == "DarkEscape4D") {
            val result = runCatching {
                DarkEscape4DPatches.applySensorMessagePatch(root, File(appRoot, "config"))
            }.getOrElse {
                Log.w(TAG, "Could not apply the Dark Escape sensor-message patch", it)
                DarkEscape4DPatches.Result.Unsupported
            }
            Log.i(TAG, "Dark Escape sensor-message patch: $result")
        }

        listOf("dev_hdd0", "dev_hdd1", "dev_bdvd", "games/shortcuts", "dev_usb000")
            .forEach { File(root, it).mkdirs() }
        val userDirectory = File(root, "dev_hdd0/home/00000001")
        userDirectory.mkdirs()
        val localUsername = File(userDirectory, "localusername")
        if (!localUsername.isFile) localUsername.writeText("User")

        // Match desktop TeknoParrot's ApplyHddFixRPCS3Settings behavior.
        // These files are shipped with the arcade dump, but their contents
        // are tied to the console PSID. Keeping the copied 40-byte blob after
        // an RPCS3 PSID change produces the Namco 19-1 system error. Desktop
        // TeknoParrot recreates the existing file as an empty file on every
        // launch so the game can initialize it against RPCS3's static PSID.
        val securityFileName = securityFileName(profileName)
        val securityFile = File(root, "dev_hdd0/game/SCEEXE000/USRDIR/$securityFileName")
        if (securityFile.isFile) {
            securityFile.outputStream().use { }
            Log.i(TAG, "Reset $securityFileName for $profileName")
        }

        boardStorageData(profileName)?.let { data ->
            val boardStorageFile = File(root, "dev_hdd1/caches/board_storage.bin")
            boardStorageFile.parentFile?.mkdirs()
            boardStorageFile.writeBytes(data)
            Log.i(TAG, "Created board_storage.bin for $profileName")
        }

        taikoVersionData(profileName)?.let { data ->
            listOf("dev_usb000", "dev_usb001").forEach { usbDevice ->
                val versionFile = File(root, "$usbDevice/VERSIONUP/DATA00000.BIN")
                versionFile.parentFile?.mkdirs()
                versionFile.writeBytes(data)
            }
            Log.i(TAG, "Created Taiko version files for $profileName")
        }

        // Mirror the RPCS3Config entries used by the desktop TeknoParrot
        // profiles. Reset title-specific values on every launch because all
        // arcade games intentionally share the SCEEXE000 title id.
        check(RPCS3.instance.settingsSet("Core@@PPU Threads", "2"))
        // RPCS3's old Android fork supported class-based CPU affinity, but the
        // support disappeared during later upstream merges. SM8850 has six
        // 3.63 GHz cores and two 4.74 GHz cores. Keep PPU work on the two fast
        // cores, separate RSX and SPU work, and retain CPU5 as a shared escape
        // hatch for every class. Unknown SoCs remain fully unrestricted.
        // Tekken 6's PS3A USB-memory LDD is timing-sensitive during its early
        // board handshake. Pinning its PPU and USB worker threads makes the
        // original 09000000.BIN key consistently fail even though the dump is
        // intact. Leave that one title on Android's scheduler; the other
        // arcade profiles retain the measured affinity benefit.
        if (Build.SOC_MODEL.equals("SM8850", ignoreCase = true) && profileName != "Tekken6") {
            val affinity = listOf("SPU", "SPU", "SPU", "SPU", "RSX", "General", "PPU", "PPU")
            affinity.forEachIndexed { index, threadClass ->
                check(RPCS3.instance.settingsSet("Core@@Affinity@@CPU$index", "\"$threadClass\""))
            }
            check(RPCS3.instance.settingsSet("Core@@Thread Scheduler Mode", "\"RPCS3 Alternative Scheduler\""))
            Log.i(TAG, "Applied SM8850 PPU/SPU/RSX affinity map")
        } else {
            check(RPCS3.instance.settingsSet("Core@@Thread Scheduler Mode", "\"Operating System\""))
        }
        // LLVM's automatic setting uses every S26 CPU core. Large arcade
        // executables can then run several memory-hungry module compilers at
        // once and exhaust Android's native address space near completion.
        // Even two simultaneous Zenkai game.self modules exceed the S26's
        // commit limit, so keep the conservative default. Tekken 6 and BR
        // stay near 1.25 GB RSS with four workers, but TTT2 reached 1.8 GB and
        // aborted inside LLVM's allocator at four. TTT2 remains safe at two;
        // Unlimited still hit Scudo's secondary-map limit at 1.5 GB with two,
        // so compile that larger revision serially.
        val tekken6Profiles = setOf("Tekken6", "Tekken6BR")
        val tagProfiles = setOf("ttt2", "ttt2u")
        val tekkenProfiles = tekken6Profiles + tagProfiles
        val onDemandPpuProfiles = tekkenProfiles + setOf(
            "taikogreen", "taikoyellow", "DSPS", "RazingStorm")
        check(RPCS3.instance.settingsSet(
            "Core@@Max LLVM Compile Threads",
            when (profileName) {
                in tekken6Profiles -> "4"
                "ttt2" -> "2"
                "ttt2u" -> "1"
                else -> "1"
            }
        ))
        // Tekken's dump contains dozens of regional, test, and live SELF
        // variants. Precompiling all of them took 16-69 minutes on the S26,
        // even though the selected cabinet boots only one. Taiko's 110-module
        // executable similarly reaches a single LLVM module large enough to
        // exhaust Scudo even with one worker. Compile reachable modules on
        // demand instead; warm boots remain cache-backed. DSPS and Razing
        // Storm can exceed the phone's practical memory ceiling while applying
        // their full precompiled trees, before the main PPU thread is started.
        check(RPCS3.instance.settingsSet(
            "Core@@LLVM Precompilation",
            (profileName !in onDemandPpuProfiles).toString()
        ))
        // The desktop-oriented default emits every failed /dev_usb000-127
        // probe to logcat. Tekken repeats that scan continuously, producing
        // roughly 4,900 lines per second and starving the guest. Silencing
        // runtime diagnostics restored a measured, evenly paced 60 FPS while
        // leaving Android lifecycle/configuration logs available.
        check(RPCS3.instance.settingsSet("Miscellaneous@@Silence All Logs", "true"))
        // Zenkai's SPU block at 0xcfc cannot be allocated by LLVM on ARM64,
        // including RPCS3's retry without TBL2/TBX2. AKB48 completes its LLVM
        // cache but then leaves all five CellSpurs kernels and RSX spinning on
        // a frozen frame. Razing Storm requires the interrupt-capable dynamic
        // interpreter: LLVM stalls its CellSpurs kernels, while the static
        // interpreter cannot feed the movie/ATRAC pipelines in real time.
        // Reset this shared setting on every arcade launch.
        check(RPCS3.instance.settingsSet(
            "Core@@SPU Decoder",
            "\"${spuDecoder(profileName)}\""
        ))
        check(RPCS3.instance.settingsSet(
            "Video@@Write Color Buffers",
            (profileName == "DSPS" || profileName == "RazingStorm").toString()
        ))
        target
    }.getOrNull()

    internal fun securityFileName(profileName: String): String =
        if (profileName == "DSPS" || profileName == "RazingStorm")
            "s357secr.bin"
        else
            "s357security.bin"

    internal fun spuDecoder(profileName: String): String =
        if (profileName == "dbzenkai" || profileName == "AKB48" || profileName == "RazingStorm")
            "Interpreter (dynamic)"
        else
            "Recompiler (LLVM)"

    internal fun boardStorageData(profileName: String): ByteArray? =
        boardStorage[profileName]?.let { identity ->
            (byteArrayOf(0x01, 0xFC.toByte()) + identity +
                ByteArray(10) { 0xFF.toByte() }).also { check(it.size == 16) }
        }

    internal fun taikoVersionData(profileName: String): ByteArray? =
        taikoVersions[profileName]?.let { version ->
            buildList<Byte> {
                addAll(listOf(0x00, 0x00, 0x00, 0x16).map(Int::toByte))
                addAll("serialization::archive".encodeToByteArray().toList())
                add(0x00)
                add(version.archiveVersion)
                addAll(listOf(0x04, 0x04, 0x04, 0x08).map(Int::toByte))
                addAll(listOf(0x00, 0x00, 0x00, 0x01).map(Int::toByte))
                addAll(ByteArray(8).toList())
                add(version.seriesVersion)
                addAll(listOf(0x00, 0x20).map(Int::toByte))
                add(version.year)
                add(0x03)
            }.toByteArray()
        }

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

    private const val TAG = "RPCS3X6 Arcade Config"
}
