package net.rpcs3

import java.io.File

object TeknoParrotGamePath {
    const val EBOOT_SUFFIX = "/dev_hdd0/game/SCEEXE000/USRDIR/EBOOT.BIN"

    fun isConfigured(path: String?): Boolean {
        val normalized = path?.trim()?.replace('\\', '/') ?: return false
        return normalized.startsWith("/storage/") &&
            normalized.endsWith(EBOOT_SUFFIX, ignoreCase = true)
    }

    fun arcadeRoot(path: String?): File? {
        if (!isConfigured(path)) return null
        val normalized = path!!.trim().replace('\\', '/')
        return File(normalized.dropLast(EBOOT_SUFFIX.length))
    }
}
