package net.rpcs3

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Profile-specific System 357/369 controls backed by RPCS3's USIO bridge. */
class TeknoParrotArcadeControlsOverlay(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val heldPointers = mutableMapOf<Int, ArcadeControlButton>()
    private val controllerHeld = mutableSetOf<ArcadeControlButton>()
    private var profileName = ""
    private var layout = ArcadeControlLayout(emptyList())
    private var aimX = 128
    private var aimY = 128
    private var rotaryEncoder = 0
    private var rotaryRepeatRunning = false

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
        rotaryRepeatRunning = false
        heldPointers.clear()
        controllerHeld.clear()
        profileName = profile
        layout = TeknoParrotArcadeControlProfiles.forProfile(profile)
        aimX = 128
        aimY = 128
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
            canvas.drawCircle(aimX / 255f * width, aimY / 255f * height, 16f, paint)
            paint.style = Paint.Style.FILL
        }

        buttonRects().forEach { (button, rect) ->
            val down = heldPointers.containsValue(button) || controllerHeld.contains(button)
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
                    heldPointers[pointer] = ArcadeControlButton("TRIGGER", layout.triggerMask)
                    updateAim(x, y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    if (heldPointers[id]?.label == "TRIGGER") updateAim(event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) heldPointers.clear()
                else heldPointers.remove(pointer)
            }
        }
        updateRotaryRepeat()
        publish()
        invalidate()
        return true
    }

    fun onControllerKey(keyCode: Int, down: Boolean): Boolean {
        val button = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> semantic("UP")
            KeyEvent.KEYCODE_DPAD_DOWN -> semantic("DOWN")
            KeyEvent.KEYCODE_DPAD_LEFT -> semantic("LEFT") ?: semantic("WHEEL L")
            KeyEvent.KEYCODE_DPAD_RIGHT -> semantic("RIGHT") ?: semantic("WHEEL R")
            KeyEvent.KEYCODE_BUTTON_X -> action(0)
            KeyEvent.KEYCODE_BUTTON_Y -> action(1)
            KeyEvent.KEYCODE_BUTTON_A -> action(2)
            KeyEvent.KEYCODE_BUTTON_B -> action(3)
            KeyEvent.KEYCODE_BUTTON_L1 -> action(4) ?: semantic("CARD")
            KeyEvent.KEYCODE_BUTTON_R1 -> action(5) ?: semantic("CARD")
            KeyEvent.KEYCODE_BUTTON_L2 -> if (layout.gun) action(1) else action(4)
            KeyEvent.KEYCODE_BUTTON_R2 -> if (layout.gun) action(0) else action(5)
            KeyEvent.KEYCODE_BUTTON_START -> semantic("START")
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_THUMBL ->
                ArcadeControlButton("COIN", special = "coin")
            KeyEvent.KEYCODE_BUTTON_THUMBR -> semantic("SERVICE")
            KeyEvent.KEYCODE_BUTTON_MODE -> semantic("TEST")
            else -> null
        } ?: return false
        if (down) controllerHeld += button else controllerHeld -= button
        updateRotaryRepeat()
        publish()
        invalidate()
        return true
    }

    fun onControllerMotion(event: MotionEvent): Boolean {
        if (layout.buttons.isEmpty()) return false
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        setController(semantic("LEFT") ?: semantic("WHEEL L"), hatX < -.35f)
        setController(semantic("RIGHT") ?: semantic("WHEEL R"), hatX > .35f)
        setController(semantic("UP"), hatY < -.35f)
        setController(semantic("DOWN"), hatY > .35f)
        val leftTrigger = maxOf(
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rightTrigger = maxOf(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_GAS))
        setController(if (layout.gun) action(1) else action(4), leftTrigger > .2f)
        setController(if (layout.gun) action(0) else action(5), rightTrigger > .2f)
        if (layout.gun) {
            aimX = (event.getAxisValue(MotionEvent.AXIS_X).coerceIn(-1f, 1f) * 127f + 128f).toInt()
            aimY = (event.getAxisValue(MotionEvent.AXIS_Y).coerceIn(-1f, 1f) * 127f + 128f).toInt()
        }
        updateRotaryRepeat()
        publish()
        invalidate()
        return true
    }

    private fun setController(button: ArcadeControlButton?, down: Boolean) {
        if (button == null) return
        if (down) controllerHeld += button else controllerHeld -= button
    }

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
        val held = heldPointers.values + controllerHeld
        val mask = held.fold(0L) { value, button -> value or button.mask }
        val vitalSensor = if (profileName == "DarkEscape4D") 60 else 128
        RPCS3.instance.arcadeInput(mask, aimX, aimY, 128, 128, vitalSensor, vitalSensor, 128,
            rotaryEncoder, 0, 0, 0,
            held.any { it.special == "coin" },
            held.any { it.special == "test" },
            held.any { it.special == "card" })
    }

    private fun updateAim(x: Float, y: Float) {
        aimX = (x / width * 255).toInt().coerceIn(0, 255)
        aimY = (y / height * 255).toInt().coerceIn(0, 255)
    }

    private fun inAimArea(x: Float, y: Float) =
        x in 0f..width.toFloat() && y in 0f..height.toFloat()

    private fun rotaryDirection(): Int {
        val held = heldPointers.values + controllerHeld
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
        rotaryRepeatRunning = false
        super.onDetachedFromWindow()
    }

    companion object {
        private const val ROTARY_INTERVAL_MS = 16L
        private const val ROTARY_STEP = 8
    }
}
