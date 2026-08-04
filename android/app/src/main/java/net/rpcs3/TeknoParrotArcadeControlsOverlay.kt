package net.rpcs3

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Profile-specific System 357/369 controls backed by RPCS3's USIO bridge. */
class TeknoParrotArcadeControlsOverlay(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val heldPointers = mutableMapOf<Int, ArcadeControlButton>()
    private val pointerDownTimes = mutableMapOf<Int, Long>()
    private val pulsedButtons = mutableMapOf<ArcadeControlButton, Long>()
    private val controllerHeld = mutableMapOf<Int, MutableSet<ArcadeControlButton>>()
    private val controllerPlayers = mutableMapOf<Int, Int>()
    private var profileName = ""
    private var layout = ArcadeControlLayout(emptyList())
    private val aimX = intArrayOf(128, 128)
    private val aimY = intArrayOf(128, 128)
    private var secondaryAimActive = false
    private var rotaryEncoder = 0
    private var rotaryRepeatRunning = false

    private val pulseRelease = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            pulsedButtons.entries.removeAll { it.value <= now }
            publish()
            invalidate()
            pulsedButtons.values.minOrNull()?.let { postDelayed(this, (it - now).coerceAtLeast(1)) }
        }
    }

    private val rotaryRepeat = object : Runnable {
        override fun run() {
            val direction = rotaryDirection()
            if (direction == 0 || !layout.rotaryEncoder) {
                rotaryRepeatRunning = false
                return
            }
            rotaryEncoder = (rotaryEncoder + direction * ROTARY_STEP) and 0xff
            publish()
            invalidate()
            postDelayed(this, ROTARY_INTERVAL_MS)
        }
    }

    fun configure(profile: String) {
        removeCallbacks(rotaryRepeat)
        removeCallbacks(pulseRelease)
        rotaryRepeatRunning = false
        heldPointers.clear()
        pointerDownTimes.clear()
        pulsedButtons.clear()
        controllerHeld.clear()
        controllerPlayers.clear()
        profileName = profile
        layout = TeknoParrotArcadeControlProfiles.forProfile(profile)
        aimX.fill(128)
        aimY.fill(128)
        secondaryAimActive = false
        rotaryEncoder = 0
        visibility = if (layout.buttons.isEmpty()) GONE else VISIBLE
        publish()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (layout.gun) {
            paint.color = Color.argb(26, 255, 255, 255)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.argb(120, 255, 255, 255)
            canvas.drawCircle(aimX[0] / 255f * width, aimY[0] / 255f * height, 16f, paint)
            if (secondaryAimActive) {
                paint.color = Color.argb(150, 64, 220, 255)
                canvas.drawCircle(aimX[1] / 255f * width, aimY[1] / 255f * height, 20f, paint)
            }
            paint.style = Paint.Style.FILL
        }

        buttonRects().forEach { (button, rect) ->
            val down = heldPointers.containsValue(button) || controllerButtons().contains(button) ||
                pulsedButtons.containsKey(button)
            paint.color = if (down) Color.argb(210, 255, 116, 32)
                else Color.argb(145, 22, 22, 26)
            canvas.drawRoundRect(rect, 18f, 18f, paint)
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = min(rect.width(), rect.height()) * .25f
            canvas.drawText(button.label, rect.centerX(), rect.centerY() -
                (paint.ascent() + paint.descent()) / 2f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val pointer = event.getPointerId(index)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(index)
                val y = event.getY(index)
                val button = buttonRects().firstOrNull { it.second.contains(x, y) }?.first
                if (button != null) heldPointers[pointer] = button
                else if (layout.gun && inAimArea(x, y)) {
                    heldPointers[pointer] = fireButton(0)
                    updateAim(0, x, y)
                }
                if (heldPointers.containsKey(pointer)) {
                    pointerDownTimes[pointer] = SystemClock.uptimeMillis()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    if (heldPointers[id]?.let(::isFireButton) == true) {
                        updateAim(0, event.getX(i), event.getY(i))
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    heldPointers.clear()
                    pointerDownTimes.clear()
                } else {
                    val released = heldPointers.remove(pointer)
                    val pressedAt = pointerDownTimes.remove(pointer)
                    if (released != null && pressedAt != null && shouldPulse(released)) {
                        val until = pressedAt + minimumPulseMs(released)
                        if (until > SystemClock.uptimeMillis()) {
                            pulsedButtons[released] = maxOf(pulsedButtons[released] ?: 0, until)
                            schedulePulseRelease()
                        }
                    }
                }
            }
        }
        updateRotaryRepeat()
        publish()
        invalidate()
        return true
    }

    fun onControllerKey(deviceId: Int, keyCode: Int, down: Boolean): Boolean {
        val player = controllerPlayer(deviceId)
        val button = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> semantic("UP")
            KeyEvent.KEYCODE_DPAD_DOWN -> semantic("DOWN")
            KeyEvent.KEYCODE_DPAD_LEFT -> semantic("LEFT") ?: semantic("WHEEL L")
            KeyEvent.KEYCODE_DPAD_RIGHT -> semantic("RIGHT") ?: semantic("WHEEL R")
            KeyEvent.KEYCODE_BUTTON_X -> if (layout.gun) fireButton(player) else action(0)
            KeyEvent.KEYCODE_BUTTON_Y -> if (layout.gun) altTriggerButton(player) else action(1)
            KeyEvent.KEYCODE_BUTTON_A -> action(2)
            KeyEvent.KEYCODE_BUTTON_B -> action(3)
            KeyEvent.KEYCODE_BUTTON_L1 -> if (layout.gun) altTriggerButton(player)
                else action(4) ?: semantic("CARD")
            KeyEvent.KEYCODE_BUTTON_R1 -> if (layout.gun) fireButton(player)
                else action(5) ?: semantic("CARD")
            KeyEvent.KEYCODE_BUTTON_L2 -> if (layout.gun) {
                if (player == 0 && !hasDedicatedSecondController()) fireButton(1)
                else altTriggerButton(player)
            } else action(4)
            KeyEvent.KEYCODE_BUTTON_R2 -> if (layout.gun) fireButton(player) else action(5)
            KeyEvent.KEYCODE_BUTTON_START -> startButton(player)
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_THUMBL ->
                ArcadeControlButton("COIN", special = "coin")
            KeyEvent.KEYCODE_BUTTON_THUMBR -> semantic("SERVICE")
            KeyEvent.KEYCODE_BUTTON_MODE -> semantic("TEST")
            else -> null
        } ?: return false
        setController(deviceId, button, down)
        updateRotaryRepeat()
        publish()
        invalidate()
        return true
    }

    fun onControllerMotion(event: MotionEvent): Boolean {
        if (layout.buttons.isEmpty()) return false
        val player = controllerPlayer(event.deviceId)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        setController(event.deviceId, semantic("LEFT") ?: semantic("WHEEL L"), hatX < -.35f)
        setController(event.deviceId, semantic("RIGHT") ?: semantic("WHEEL R"), hatX > .35f)
        setController(event.deviceId, semantic("UP"), hatY < -.35f)
        setController(event.deviceId, semantic("DOWN"), hatY > .35f)
        val leftTrigger = maxOf(
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rightTrigger = maxOf(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_GAS))
        if (layout.gun) {
            if (player == 0 && !hasDedicatedSecondController()) {
                setController(event.deviceId, altTriggerButton(0), false)
                setController(event.deviceId, fireButton(1), leftTrigger > .2f)
            } else {
                if (player == 0) setController(event.deviceId, fireButton(1), false)
                setController(event.deviceId, altTriggerButton(player), leftTrigger > .2f)
            }
            setController(event.deviceId, fireButton(player), rightTrigger > .2f)
            updateControllerAim(player,
                event.getAxisValue(MotionEvent.AXIS_X),
                event.getAxisValue(MotionEvent.AXIS_Y))
            if (player == 0 && !hasDedicatedSecondController()) {
                val secondX = event.getAxisValue(MotionEvent.AXIS_Z)
                val secondY = event.getAxisValue(MotionEvent.AXIS_RZ)
                if (secondaryAimActive || kotlin.math.abs(secondX) > .05f ||
                    kotlin.math.abs(secondY) > .05f) {
                    secondaryAimActive = true
                    updateControllerAim(1, secondX, secondY)
                }
            } else if (player == 1) {
                secondaryAimActive = true
            }
        } else {
            setController(event.deviceId, action(4), leftTrigger > .2f)
            setController(event.deviceId, action(5), rightTrigger > .2f)
        }
        updateRotaryRepeat()
        publish()
        invalidate()
        return true
    }

    private fun setController(deviceId: Int, button: ArcadeControlButton?, down: Boolean) {
        if (button == null) return
        val held = controllerHeld.getOrPut(deviceId) { mutableSetOf() }
        if (down) held += button else held -= button
        if (held.isEmpty()) controllerHeld.remove(deviceId)
    }

    private fun controllerPlayer(deviceId: Int): Int =
        controllerPlayers.getOrPut(deviceId) {
            when {
                0 !in controllerPlayers.values -> 0
                1 !in controllerPlayers.values -> 1
                else -> 1
            }
        }

    private fun hasDedicatedSecondController() =
        controllerPlayers.values.any { it == 1 }

    private fun controllerButtons(): List<ArcadeControlButton> =
        controllerHeld.values.flatMap { it }

    private fun fireButton(player: Int): ArcadeControlButton {
        val mask = if (player == 0) {
            layout.triggerMask or layout.fireCompanionMask
        } else {
            layout.secondaryTriggerMask or layout.secondaryFireCompanionMask
        }
        return ArcadeControlButton(if (player == 0) "TRIGGER" else "P2 TRIGGER", mask)
    }

    private fun altTriggerButton(player: Int): ArcadeControlButton? =
        if (player == 0) action(1)
        else layout.secondaryAltTriggerMask.takeIf { it != 0L }
            ?.let { ArcadeControlButton("P2 ALT", it) }

    private fun startButton(player: Int): ArcadeControlButton? =
        if (player == 0) semantic("START")
        else layout.secondaryStartMask.takeIf { it != 0L }
            ?.let { ArcadeControlButton("P2 START", it) }

    private fun action(index: Int): ArcadeControlButton? {
        val actions = layout.buttons
            .filter {
                it.label !in TeknoParrotArcadeControlProfiles.systemLabels && it.special.isEmpty()
            }
            .toMutableList()
        if (layout.gun) actions.add(0, ArcadeControlButton("TRIGGER", layout.triggerMask))
        return actions.getOrNull(index)
    }

    private fun semantic(label: String): ArcadeControlButton? =
        layout.buttons.firstOrNull { it.label == label }
            ?: layout.dpad[label]?.let { ArcadeControlButton(label, it) }

    private fun publish() {
        val held = heldPointers.values + controllerButtons() + pulsedButtons.keys
        val mask = held.fold(0L) { value, button -> value or button.mask }
        val vitalSensor = if (profileName == "DarkEscape4D") 60 else 128
        val secondAimX = if (!secondaryAimActive && layout.mirrorGunAim) aimX[0] else aimX[1]
        val secondAimY = if (!secondaryAimActive && layout.mirrorGunAim) aimY[0] else aimY[1]
        RPCS3.instance.arcadeInput(mask, aimX[0], aimY[0], secondAimX, secondAimY,
            vitalSensor, vitalSensor, 128,
            rotaryEncoder, 0, 0, 0,
            held.any { it.special == "coin" },
            held.any { it.special == "test" },
            held.any { it.special == "card" })
    }

    private fun shouldPulse(button: ArcadeControlButton) =
        button.label.endsWith("START") || isFireButton(button) || button.special == "coin"

    private fun minimumPulseMs(button: ArcadeControlButton) =
        if (isFireButton(button)) TRIGGER_PULSE_MS else BUTTON_PULSE_MS

    private fun schedulePulseRelease() {
        removeCallbacks(pulseRelease)
        val now = SystemClock.uptimeMillis()
        pulsedButtons.values.minOrNull()?.let { postDelayed(pulseRelease, (it - now).coerceAtLeast(1)) }
    }

    private fun updateAim(player: Int, x: Float, y: Float) {
        aimX[player] = (x / width * 255).toInt().coerceIn(0, 255)
        aimY[player] = (y / height * 255).toInt().coerceIn(0, 255)
    }

    private fun updateControllerAim(player: Int, x: Float, y: Float) {
        aimX[player] = (x.coerceIn(-1f, 1f) * 127f + 128f).toInt()
        aimY[player] = (y.coerceIn(-1f, 1f) * 127f + 128f).toInt()
    }

    private fun isFireButton(button: ArcadeControlButton) =
        button.label.endsWith("TRIGGER")

    private fun inAimArea(x: Float, y: Float) =
        x in 0f..width.toFloat() && y in 0f..height.toFloat()

    private fun rotaryDirection(): Int {
        val held = heldPointers.values + controllerButtons()
        val left = held.any { it.special == "rotary-left" }
        val right = held.any { it.special == "rotary-right" }
        return when {
            left == right -> 0
            left -> -1
            else -> 1
        }
    }

    private fun updateRotaryRepeat() {
        val active = layout.rotaryEncoder && rotaryDirection() != 0
        if (active && !rotaryRepeatRunning) {
            rotaryRepeatRunning = true
            rotaryRepeat.run()
        } else if (!active && rotaryRepeatRunning) {
            removeCallbacks(rotaryRepeat)
            rotaryRepeatRunning = false
        }
    }

    private fun buttonRects(): List<Pair<ArcadeControlButton, RectF>> {
        val buttons = layout.buttons
        if (buttons.isEmpty()) return emptyList()
        val size = min(width, height) * .14f
        val left = buttons.filter {
            it.label in setOf("UP", "DOWN", "LEFT", "RIGHT", "WHEEL L", "WHEEL R")
        }
        val others = buttons - left.toSet()
        val result = mutableListOf<Pair<ArcadeControlButton, RectF>>()
        left.forEach { button ->
            val cx = when (button.label) {
                "LEFT", "WHEEL L" -> size
                "RIGHT", "WHEEL R" -> size * 3
                else -> size * 2
            }
            val cy = when (button.label) { "UP" -> height - size * 3; "DOWN" -> height - size; else -> height - size * 2 }
            result += button to RectF(cx - size * .48f, cy - size * .48f, cx + size * .48f, cy + size * .48f)
        }
        others.forEachIndexed { i, button ->
            val columns = if (layout.gun) 2 else 3
            val col = i % columns
            val row = i / columns
            val cx = width - size * (.65f + col * 1.08f)
            val cy = height - size * (.65f + row * 1.08f)
            result += button to RectF(cx - size * .48f, cy - size * .48f, cx + size * .48f, cy + size * .48f)
        }
        return result
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(rotaryRepeat)
        removeCallbacks(pulseRelease)
        rotaryRepeatRunning = false
        super.onDetachedFromWindow()
    }

    companion object {
        private const val ROTARY_INTERVAL_MS = 16L
        private const val ROTARY_STEP = 8
        private const val BUTTON_PULSE_MS = 350L
        private const val TRIGGER_PULSE_MS = 120L
    }
}
