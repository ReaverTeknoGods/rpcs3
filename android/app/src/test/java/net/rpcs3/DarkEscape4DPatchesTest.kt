package net.rpcs3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class DarkEscape4DPatchesTest {
    private val original = "3-5 VITAL SIGNS CENSOR ERROR".toByteArray(Charsets.UTF_16LE)
    private val hidden = " ".repeat(28).toByteArray(Charsets.UTF_16LE)

    @Test
    fun appliesOnlyExpectedBytesAndKeepsRecoveryData() = withFixture { data, backup, offsets ->
        val before = data.readBytes()
        assertEquals(
            DarkEscape4DPatches.Result.Applied,
            DarkEscape4DPatches.applySensorMessagePatch(data, backup, offsets)
        )
        val after = data.readBytes()

        offsets.forEach { offset ->
            assertArrayEquals(hidden, after.copyOfRange(offset.toInt(), offset.toInt() + hidden.size))
        }
        before.indices.filterNot { index ->
            offsets.any { index.toLong() >= it && index.toLong() < it + hidden.size }
        }.forEach { index -> assertEquals(before[index], after[index]) }
        assertTrue(backup.isFile)
        assertTrue(backup.length() > original.size * offsets.size)
        assertEquals(
            DarkEscape4DPatches.Result.AlreadyApplied,
            DarkEscape4DPatches.applySensorMessagePatch(data, backup, offsets)
        )
    }

    @Test
    fun refusesUnknownDataWithoutChangingTheFile() = withFixture { data, backup, offsets ->
        RandomAccessFile(data, "rw").use {
            it.seek(offsets.last())
            it.write(ByteArray(original.size) { 0x55.toByte() })
        }
        val before = data.readBytes()

        assertEquals(
            DarkEscape4DPatches.Result.Unsupported,
            DarkEscape4DPatches.applySensorMessagePatch(data, backup, offsets)
        )
        assertArrayEquals(before, data.readBytes())
        assertFalse(backup.exists())
    }

    private fun withFixture(
        test: (data: File, backup: File, offsets: LongArray) -> Unit
    ) {
        val directory = Files.createTempDirectory("rpcs3x6-darkescape-test").toFile()
        try {
            val data = File(directory, "Data.npk")
            val backup = File(directory, "backup/original.bin")
            val offsets = longArrayOf(37, 151)
            RandomAccessFile(data, "rw").use {
                it.setLength(256)
                offsets.forEach { offset ->
                    it.seek(offset)
                    it.write(original)
                }
            }
            test(data, backup, offsets)
        } finally {
            directory.deleteRecursively()
        }
    }
}
