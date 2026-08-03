package net.rpcs3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeknoParrotArcadeControlProfilesTest {
    @Test
    fun everySupportedProfileHasTheDesktopRpcs3ControlMask() {
        val expected = mapOf(
            "DarkEscape4D" to controls(
                "ALT" to 0x400000, "START" to 0x200000, "UP" to 0x2000,
                "DOWN" to 0x1000, "ENTER" to 0x200, "TOGGLE" to 0x20000,
                "SERVICE" to 0x4000
            ),
            "AKB48" to controls(
                "ALT" to 0x400000, "START" to 0x200000, "UP" to 0x2000,
                "DOWN" to 0x1000, "ENTER" to 0x200, "SERVICE" to 0x4000
            ),
            "DSPS" to controls(
                "ALT" to 0x800000, "START" to 0x200000, "UP" to 0x2000,
                "DOWN" to 0x1000, "ENTER" to 0x200, "SERVICE" to 0x4000
            ),
            "RazingStorm" to controls(
                "PEDAL" to 0x80000, "START" to 0x800000, "UP" to 0x2000,
                "DOWN" to 0x1000, "ENTER" to 0x200, "SERVICE" to 0x4000
            ),
            "Tekken6" to controls(
                "UP" to 0x2000, "DOWN" to 0x1000, "LEFT" to 0x800,
                "RIGHT" to 0x400, "LP" to 0x200, "RP" to 0x100,
                "LK" to 0x400000, "RK" to 0x200000, "START" to 0x8000,
                "SERVICE" to 0x4000
            ),
            "Tekken6BR" to controls(
                "UP" to 0x2000, "DOWN" to 0x1000, "LEFT" to 0x800,
                "RIGHT" to 0x400, "LP" to 0x200, "RP" to 0x100,
                "LK" to 0x400000, "RK" to 0x200000, "START" to 0x8000,
                "SERVICE" to 0x4000
            ),
            "ttt2" to controls(
                "UP" to 0x200000, "DOWN" to 0x100000, "LEFT" to 0x80000,
                "RIGHT" to 0x40000, "LP" to 0x20000, "RP" to 0x10000,
                "LK" to 0x40000000, "RK" to 0x20000000,
                "TAG" to 0x80000000L, "START" to 0x800000,
                "SERVICE" to 0x4000
            ),
            "ttt2u" to controls(
                "UP" to 0x200000, "DOWN" to 0x100000, "LEFT" to 0x80000,
                "RIGHT" to 0x40000, "LP" to 0x20000, "RP" to 0x10000,
                "LK" to 0x40000000, "RK" to 0x20000000,
                "TAG" to 0x80000000L, "START" to 0x800000,
                "SERVICE" to 0x4000
            ),
            "taikogreen" to controls(
                "UP" to 0x800, "DOWN" to 0x2000,
                "KA L" to 0x200, "DON L" to 0x4, "DON R" to 0x20,
                "KA R" to 0x80000, "START" to 0x2,
                "SERVICE" to 0x40, "TEST" to 0x1
            ),
            "taikoyellow" to controls(
                "UP" to 0x800, "DOWN" to 0x2000,
                "KA L" to 0x200, "DON L" to 0x4, "DON R" to 0x20,
                "KA R" to 0x80000, "START" to 0x2,
                "SERVICE" to 0x40, "TEST" to 0x1
            ),
            "dbzenkai" to controls(
                "UP" to 0x200000, "DOWN" to 0x100000, "LEFT" to 0x80000,
                "RIGHT" to 0x40000, "A1" to 0x20000, "A2" to 0x10000,
                "A3" to 0x80000000L, "A4" to 0x40000000,
                "A5" to 0x20000000, "START" to 0x800000,
                "SERVICE" to 0x400000
            )
        )

        assertEquals(expected.keys, TeknoParrotArcadeControlProfiles.layouts.keys)
        expected.forEach { (profile, controls) ->
            val actual = TeknoParrotArcadeControlProfiles.forProfile(profile)
                .buttons
                .filter { it.special.isEmpty() }
                .associate { it.label to it.mask }
            assertEquals("Control mismatch for $profile", controls, actual)
        }
    }

    @Test
    fun gunAndCabinetSpecificControlsAreComplete() {
        mapOf(
            "DarkEscape4D" to 0x800000L,
            "AKB48" to 0x800000L,
            "DSPS" to 0x400000L,
            "RazingStorm" to 0x200000L
        ).forEach { (profile, trigger) ->
            val layout = TeknoParrotArcadeControlProfiles.forProfile(profile)
            assertTrue("$profile must use absolute gun aiming", layout.gun)
            assertEquals("$profile trigger", trigger, layout.triggerMask)
            assertTrue(layout.buttons.any { it.special == "coin" })
            assertTrue(layout.buttons.any { it.special == "test" })
        }

        val deadstorm = TeknoParrotArcadeControlProfiles.forProfile("DSPS")
        assertTrue(deadstorm.rotaryEncoder)
        assertTrue(deadstorm.mirrorGunAim)
        assertTrue(deadstorm.buttons.any { it.special == "rotary-left" })
        assertTrue(deadstorm.buttons.any { it.special == "rotary-right" })
        TeknoParrotArcadeControlProfiles.layouts
            .filterKeys { it != "DSPS" }
            .values
            .forEach {
                assertFalse(it.rotaryEncoder)
                assertFalse(it.mirrorGunAim)
            }

        assertTrue(TeknoParrotArcadeControlProfiles.forProfile("ttt2")
            .buttons.any { it.special == "card" })
        assertTrue(TeknoParrotArcadeControlProfiles.forProfile("ttt2u")
            .buttons.any { it.special == "card" })
    }

    private fun controls(vararg values: Pair<String, Number>): Map<String, Long> =
        values.associate { it.first to it.second.toLong() }
}
