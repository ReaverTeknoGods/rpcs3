package net.rpcs3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeknoParrotArcadeConfigTest {
    @Test
    fun securityFileNamesMatchTheDesktopLauncher() {
        assertEquals("s357secr.bin", TeknoParrotArcadeConfig.securityFileName("DSPS"))
        assertEquals("s357secr.bin", TeknoParrotArcadeConfig.securityFileName("RazingStorm"))
        listOf("AKB48", "DarkEscape4D", "dbzenkai", "taikogreen", "taikoyellow", "ttt2", "ttt2u")
            .forEach { profile ->
                assertEquals(
                    "s357security.bin",
                    TeknoParrotArcadeConfig.securityFileName(profile)
                )
            }
    }

    @Test
    fun boardStorageBytesMatchTheDesktopLauncher() {
        val expected = mapOf(
            "ttt2u" to "01fc43502468ffffffffffffffffffff",
            "ttt2" to "01fc43502468ffffffffffffffffffff",
            "AKB48" to "01fc4350544effffffffffffffffffff",
            "DarkEscape4D" to "01fc314cb0e9ffffffffffffffffffff",
            "taikogreen" to "01fc4350a79bffffffffffffffffffff",
            "taikoyellow" to "01fc4350a79bffffffffffffffffffff"
        )
        expected.forEach { (profile, bytes) ->
            assertArrayEquals(hex(bytes), TeknoParrotArcadeConfig.boardStorageData(profile))
        }
        assertNull(TeknoParrotArcadeConfig.boardStorageData("Tekken6"))
    }

    @Test
    fun taikoVersionBytesMatchTheDesktopLauncher() {
        val prefix = "0000001673657269616c697a6174696f6e3a3a61726368697665000a04040408" +
            "000000010000000000000000"
        assertArrayEquals(
            hex(prefix + "0b00201903"),
            TeknoParrotArcadeConfig.taikoVersionData("taikogreen")
        )
        assertArrayEquals(
            hex(prefix + "0900201903"),
            TeknoParrotArcadeConfig.taikoVersionData("taikoyellow")
        )
        assertNull(TeknoParrotArcadeConfig.taikoVersionData("Tekken6"))
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
}
