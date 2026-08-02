package net.rpcs3

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FirmwareRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readyRequiresSuccessfulStatusAndInstalledPayload() {
        val root = temporaryFolder.newFolder("firmware")
        installPayload(root)

        writeStatus(root, "None")
        assertFalse(FirmwareRepository.isReady(root))

        writeStatus(root, "Installed")
        assertTrue(FirmwareRepository.isReady(root))

        writeStatus(root, "Compiled")
        assertTrue(FirmwareRepository.isReady(root))
    }

    @Test
    fun staleStatusCannotAuthorizeMissingOrEmptyFirmwareFiles() {
        val root = temporaryFolder.newFolder("stale")
        writeStatus(root, "Installed")
        assertFalse(FirmwareRepository.isReady(root))

        installPayload(root)
        File(root, "config/dev_flash/vsh/module/vsh.self").writeBytes(byteArrayOf())
        assertFalse(FirmwareRepository.isReady(root))

        File(root, "config/dev_flash/vsh/module/vsh.self").writeBytes(byteArrayOf(1))
        File(root, "config/dev_flash/vsh/etc/version.txt").delete()
        assertFalse(FirmwareRepository.isReady(root))
    }

    @Test
    fun malformedOrMissingStatusCannotAuthorizeFirmware() {
        val root = temporaryFolder.newFolder("metadata")
        installPayload(root)
        assertFalse(FirmwareRepository.isReady(root))

        File(root, "fw.json").writeText("not-json")
        assertFalse(FirmwareRepository.isReady(root))
    }

    private fun installPayload(root: File) {
        File(root, "config/dev_flash/vsh/module/vsh.self").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        File(root, "config/dev_flash/vsh/etc/version.txt").apply {
            parentFile!!.mkdirs()
            writeText("release:04.9200")
        }
    }

    private fun writeStatus(root: File, status: String) {
        File(root, "fw.json").writeText(
            """{"version":"4.92","status":"$status"}"""
        )
    }
}
