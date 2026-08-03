package net.rpcs3

internal data class ArcadeControlButton(
    val label: String,
    val mask: Long = 0,
    val special: String = ""
)

internal data class ArcadeControlLayout(
    val buttons: List<ArcadeControlButton>,
    val gun: Boolean = false,
    val triggerMask: Long = 0,
    val mirrorGunAim: Boolean = false,
    val rotaryEncoder: Boolean = false,
    val dpad: Map<String, Long> = emptyMap()
)

/**
 * Android control masks mirrored from TeknoParrotUI's authoritative RPCS3
 * profiles and RPCS3Pipe shared-state layout. Keep this pure Kotlin so every
 * supported cabinet can be contract-tested without an Android View runtime.
 */
internal object TeknoParrotArcadeControlProfiles {
    val systemLabels = setOf(
        "UP", "DOWN", "LEFT", "RIGHT", "START", "COIN", "SERVICE", "TEST", "CARD"
    )

    private fun button(label: String, mask: Long) = ArcadeControlButton(label, mask)
    private val coin = ArcadeControlButton("COIN", special = "coin")
    private val test = ArcadeControlButton("TEST", special = "test")
    private val card = ArcadeControlButton("CARD", special = "card")
    private val wheelLeft = ArcadeControlButton("WHEEL L", special = "rotary-left")
    private val wheelRight = ArcadeControlButton("WHEEL R", special = "rotary-right")

    val layouts: Map<String, ArcadeControlLayout> = mapOf(
        "DarkEscape4D" to gun(
            0x800000,
            listOf(
                button("ALT", 0x400000), button("START", 0x200000),
                button("UP", 0x2000), button("DOWN", 0x1000),
                button("ENTER", 0x200), button("TOGGLE", 0x20000)
            )
        ),
        "AKB48" to gun(
            0x800000,
            listOf(
                button("ALT", 0x400000), button("START", 0x200000),
                button("UP", 0x2000), button("DOWN", 0x1000), button("ENTER", 0x200)
            )
        ),
        "DSPS" to gun(
            0x400000,
            listOf(
                button("ALT", 0x800000), button("START", 0x200000),
                button("UP", 0x2000), button("DOWN", 0x1000),
                wheelLeft, wheelRight, button("ENTER", 0x200)
            ),
            mirrorGunAim = true,
            rotaryEncoder = true
        ),
        "RazingStorm" to gun(
            0x200000,
            listOf(
                button("PEDAL", 0x80000), button("START", 0x800000),
                button("UP", 0x2000), button("DOWN", 0x1000), button("ENTER", 0x200)
            )
        ),
        "Tekken6" to fighter(tag = false),
        "Tekken6BR" to fighter(tag = false),
        "ttt2" to fighter(tag = true),
        "ttt2u" to fighter(tag = true),
        "taikogreen" to taiko(),
        "taikoyellow" to taiko(),
        "dbzenkai" to ArcadeControlLayout(
            listOf(
                button("UP", 0x200000), button("DOWN", 0x100000),
                button("LEFT", 0x80000), button("RIGHT", 0x40000),
                button("A1", 0x20000), button("A2", 0x10000),
                button("A3", 0x80000000L), button("A4", 0x40000000),
                button("A5", 0x20000000), button("START", 0x800000),
                coin, button("SERVICE", 0x400000), test
            )
        )
    )

    fun forProfile(profile: String): ArcadeControlLayout =
        layouts[profile] ?: ArcadeControlLayout(emptyList())

    private fun gun(
        trigger: Long,
        actions: List<ArcadeControlButton>,
        mirrorGunAim: Boolean = false,
        rotaryEncoder: Boolean = false
    ) = ArcadeControlLayout(
        actions + coin + button("SERVICE", 0x4000) + test,
        gun = true,
        triggerMask = trigger,
        mirrorGunAim = mirrorGunAim,
        rotaryEncoder = rotaryEncoder
    )

    private fun fighter(tag: Boolean): ArcadeControlLayout =
        if (tag) ArcadeControlLayout(
            listOf(
                button("UP", 0x200000), button("DOWN", 0x100000),
                button("LEFT", 0x80000), button("RIGHT", 0x40000),
                button("LP", 0x20000), button("RP", 0x10000),
                button("LK", 0x40000000), button("RK", 0x20000000),
                button("TAG", 0x80000000L), button("START", 0x800000),
                coin, button("SERVICE", 0x4000), test, card
            )
        ) else ArcadeControlLayout(
            listOf(
                button("UP", 0x2000), button("DOWN", 0x1000),
                button("LEFT", 0x800), button("RIGHT", 0x400),
                button("LP", 0x200), button("RP", 0x100),
                button("LK", 0x400000), button("RK", 0x200000),
                button("START", 0x8000), coin, button("SERVICE", 0x4000), test
            )
        )

    private fun taiko() = ArcadeControlLayout(
        listOf(
            button("UP", 0x800), button("DOWN", 0x2000),
            button("KA L", 0x200), button("DON L", 0x4),
            button("DON R", 0x20), button("KA R", 0x80000),
            button("START", 0x2), coin, button("SERVICE", 0x40), button("TEST", 0x1)
        )
    )
}
