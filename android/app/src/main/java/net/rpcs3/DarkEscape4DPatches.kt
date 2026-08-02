package net.rpcs3

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Reversible data fixes required by the original Dark Escape arcade dump. */
internal object DarkEscape4DPatches {
    enum class Result { Missing, Applied, AlreadyApplied, Unsupported }

    private val sensorMessageOffsets = longArrayOf(773_859_094L, 773_860_440L)
    private val originalSensorMessage =
        "3-5 VITAL SIGNS CENSOR ERROR".toByteArray(Charsets.UTF_16LE)
    private val hiddenSensorMessage = " ".repeat(28).toByteArray(Charsets.UTF_16LE)

    fun applySensorMessagePatch(root: File, backupRoot: File): Result {
        val dataFile = File(root, "dev_hdd0/game/SCEEXE000/USRDIR/BootPack/Data.npk")
        val backupFile = File(
            backupRoot,
            "teknoparrot/backups/darkescape4d/Data.npk.sensor-message.original.bin"
        )
        return applySensorMessagePatch(dataFile, backupFile, sensorMessageOffsets)
    }

    internal fun applySensorMessagePatch(
        dataFile: File,
        backupFile: File,
        offsets: LongArray
    ): Result {
        if (!dataFile.isFile) return Result.Missing

        RandomAccessFile(dataFile, "rw").use { data ->
            if (offsets.any { it < 0 || it + originalSensorMessage.size > data.length() }) {
                return Result.Unsupported
            }

            val current = offsets.map { offset ->
                ByteArray(originalSensorMessage.size).also { bytes ->
                    data.seek(offset)
                    data.readFully(bytes)
                }
            }
            if (current.any { !it.contentEquals(originalSensorMessage) &&
                    !it.contentEquals(hiddenSensorMessage) }) {
                return Result.Unsupported
            }
            if (current.all { it.contentEquals(hiddenSensorMessage) }) {
                return Result.AlreadyApplied
            }

            val recovery = recoveryRecord(data.length(), offsets)
            if (backupFile.isFile) {
                if (!backupFile.readBytes().contentEquals(recovery)) return Result.Unsupported
            } else {
                backupFile.parentFile?.mkdirs()
                val temporary = File(backupFile.parentFile, backupFile.name + ".installing")
                FileOutputStream(temporary).use { output ->
                    output.write(recovery)
                    output.fd.sync()
                }
                check(temporary.renameTo(backupFile)) {
                    "Could not save Dark Escape sensor-message recovery data"
                }
            }

            val changed = mutableListOf<Int>()
            try {
                current.forEachIndexed { index, bytes ->
                    if (bytes.contentEquals(originalSensorMessage)) {
                        data.seek(offsets[index])
                        data.write(hiddenSensorMessage)
                        changed += index
                    }
                }
                data.fd.sync()
                offsets.forEach { offset ->
                    val verification = ByteArray(hiddenSensorMessage.size)
                    data.seek(offset)
                    data.readFully(verification)
                    check(verification.contentEquals(hiddenSensorMessage)) {
                        "Dark Escape sensor-message patch verification failed"
                    }
                }
            } catch (error: Throwable) {
                changed.forEach { index ->
                    data.seek(offsets[index])
                    data.write(originalSensorMessage)
                }
                data.fd.sync()
                throw error
            }
        }
        return Result.Applied
    }

    private fun recoveryRecord(dataLength: Long, offsets: LongArray): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeUTF("RPCS3X6-DARKESCAPE-SENSOR-V1")
                output.writeLong(dataLength)
                output.writeInt(offsets.size)
                offsets.forEach { offset ->
                    output.writeLong(offset)
                    output.writeInt(originalSensorMessage.size)
                    output.write(originalSensorMessage)
                }
            }
            bytes.toByteArray()
        }
}
