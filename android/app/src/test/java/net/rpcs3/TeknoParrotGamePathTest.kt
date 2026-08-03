package net.rpcs3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeknoParrotGamePathTest {
    @Test
    fun acceptsDirectSystem357EbootAndFindsItsRoot() {
        val path = "/storage/emulated/0/arcade/rpcs3/DSPS" +
            "/dev_hdd0/game/SCEEXE000/USRDIR/EBOOT.BIN"

        assertTrue(TeknoParrotGamePath.isConfigured(path))
        assertEquals(
            "/storage/emulated/0/arcade/rpcs3/DSPS",
            TeknoParrotGamePath.arcadeRoot(path)?.path?.replace('\\', '/')
        )
    }

    @Test
    fun rejectsFoldersAndUnrelatedExecutables() {
        assertFalse(TeknoParrotGamePath.isConfigured(
            "/storage/emulated/0/arcade/rpcs3/DSPS"
        ))
        assertFalse(TeknoParrotGamePath.isConfigured(
            "/storage/emulated/0/arcade/rpcs3/DSPS/dev_hdd0/game/OTHER/USRDIR/EBOOT.BIN"
        ))
        assertFalse(TeknoParrotGamePath.isConfigured(
            "/data/local/tmp/dev_hdd0/game/SCEEXE000/USRDIR/EBOOT.BIN"
        ))
        assertNull(TeknoParrotGamePath.arcadeRoot("EBOOT.BIN"))
    }
}
