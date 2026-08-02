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
    private data class Button(val label: String, val mask: Long = 0, val special: String = "")
    private data class Layout(
        val buttons: List<Button>,
        val gun: Boolean = false,
        val triggerMask: Long = 0,
        val dpad: Map<String, Long> = emptyMap()
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val heldPointers = mutableMapOf<Int, Button>()
    private val controllerHeld = mutableSetOf<Button>()
    private var profileName = ""
    private var layout = Layout(emptyList())
    private var aimX = 128
    private var aimY = 128

    fun configure(profile: String) {
        profileName = profile
        layout = layouts[profile] ?: Layout(emptyList())
        visibility = if (layout.buttons.isEmpty()) GONE else VISIBLE
        publish()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (layout.gun) {
            paint.color = Color.argb(26, 255, 255, 255)
            canvas.drawRect(width * .18f, height * .08f, width * .82f, height * .9f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.argb(120, 255, 255, 255)
            canvas.drawCircle(width * .18f + aimX / 255f * width * .64f,
                height * .08f + aimY / 255f * height * .82f, 16f, paint)
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
                    heldPointers[pointer] = Button("TRIGGER", layout.triggerMask)
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
        publish()
        invalidate()
        return true
    }

    fun onControllerKey(keyCode: Int, down: Boolean): Boolean {
        val button = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> semantic("UP")
            KeyEvent.KEYCODE_DPAD_DOWN -> semantic("DOWN")
            KeyEvent.KEYCODE_DPAD_LEFT -> semantic("LEFT")
            KeyEvent.KEYCODE_DPAD_RIGHT -> semantic("RIGHT")
            KeyEvent.KEYCODE_BUTTON_X -> action(0)
            KeyEvent.KEYCODE_BUTTON_Y -> action(1)
            KeyEvent.KEYCODE_BUTTON_A -> action(2)
            KeyEvent.KEYCODE_BUTTON_B -> action(3)
            KeyEvent.KEYCODE_BUTTON_L1 -> action(4) ?: semantic("CARD")
            KeyEvent.KEYCODE_BUTTON_R1 -> action(5) ?: semantic("CARD")
            KeyEvent.KEYCODE_BUTTON_L2 -> if (layout.gun) action(1) else action(4)
            KeyEvent.KEYCODE_BUTTON_R2 -> if (layout.gun) action(0) else action(5)
            KeyEvent.KEYCODE_BUTTON_START -> semantic("START")
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_THUMBL -> Button("COIN", special = "coin")
            KeyEvent.KEYCODE_BUTTON_THUMBR -> semantic("SERVICE")
            KeyEvent.KEYCODE_BUTTON_MODE -> semantic("TEST")
            else -> null
        } ?: return false
        if (down) controllerHeld += button else controllerHeld -= button
        publish()
        invalidate()
        return true
    }

    fun onControllerMotion(event: MotionEvent): Boolean {
        if (layout.buttons.isEmpty()) return false
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        setController(semantic("LEFT"), hatX < -.35f)
        setController(semantic("RIGHT"), hatX > .35f)
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
        publish()
        invalidate()
        return true
    }

    private fun setController(button: Button?, down: Boolean) {
        if (button == null) return
        if (down) controllerHeld += button else controllerHeld -= button
    }

    private fun action(index: Int): Button? {
        val actions = layout.buttons
            .filter { it.label !in systemLabels && it.special.isEmpty() }
            .toMutableList()
        if (layout.gun) actions.add(0, Button("TRIGGER", layout.triggerMask))
        return actions.getOrNull(index)
    }

    private fun semantic(label: String): Button? = layout.buttons.firstOrNull { it.label == label }
        ?: layout.dpad[label]?.let { Button(label, it) }

    private fun publish() {
        val held = heldPointers.values + controllerHeld
        val mask = held.fold(0L) { value, button -> value or button.mask }
        RPCS3.instance.arcadeInput(mask, aimX, aimY, aimY, 128, 128, 128, 128,
            held.any { it.special == "coin" },
            held.any { it.special == "test" },
            held.any { it.special == "card" })
    }

    private fun updateAim(x: Float, y: Float) {
        aimX = (((x / width - .18f) / .64f) * 255).toInt().coerceIn(0, 255)
        aimY = (((y / height - .08f) / .82f) * 255).toInt().coerceIn(0, 255)
    }

    private fun inAimArea(x: Float, y: Float) =
        x in width * .18f..width * .82f && y in height * .08f..height * .9f

    private fun buttonRects(): List<Pair<Button, RectF>> {
        val buttons = layout.buttons
        if (buttons.isEmpty()) return emptyList()
        val size = min(width, height) * .14f
        val left = buttons.filter { it.label in setOf("UP", "DOWN", "LEFT", "RIGHT") }
        val others = buttons - left.toSet()
        val result = mutableListOf<Pair<Button, RectF>>()
        left.forEach { button ->
            val cx = when (button.label) { "LEFT" -> size; "RIGHT" -> size * 3; else -> size * 2 }
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

    companion object {
        private val systemLabels = setOf("UP", "DOWN", "LEFT", "RIGHT", "START", "COIN", "SERVICE", "TEST", "CARD")
        private fun b(label: String, mask: Long) = Button(label, mask)
        private val coin = Button("COIN", special = "coin")
        private val test = Button("TEST", special = "test")
        private val card = Button("CARD", special = "card")
        private val layouts = mapOf(
            "DarkEscape4D" to gun(0x800000, listOf(b("ALT", 0x400000), b("START", 0x200000), b("UP", 0x2000), b("DOWN", 0x1000), b("ENTER", 0x200), b("TOGGLE", 0x20000))),
            "AKB48" to gun(0x800000, listOf(b("ALT", 0x400000), b("START", 0x200000), b("UP", 0x2000), b("DOWN", 0x1000), b("ENTER", 0x200))),
            "DSPS" to gun(0x800000, listOf(b("ALT", 0x400000), b("START", 0x200000), b("UP", 0x2000), b("DOWN", 0x1000), b("ENTER", 0x200))),
            "RazingStorm" to gun(0x200000, listOf(b("PEDAL", 0x80000), b("START", 0x800000), b("UP", 0x2000), b("DOWN", 0x1000), b("ENTER", 0x200))),
            "Tekken6" to fighter(false), "Tekken6BR" to fighter(false),
            "ttt2" to fighter(true), "ttt2u" to fighter(true),
            "taikogreen" to taiko(), "taikoyellow" to taiko(),
            "dbzenkai" to Layout(listOf(
                b("UP", 0x200000), b("DOWN", 0x100000), b("LEFT", 0x80000), b("RIGHT", 0x40000),
                b("A1", 0x20000), b("A2", 0x10000), b("A3", 0x80000000L), b("A4", 0x40000000), b("A5", 0x20000000),
                b("START", 0x800000), coin, b("SERVICE", 0x400000), test))
        )
        private fun gun(trigger: Long, actions: List<Button>) = Layout(
            actions + coin + b("SERVICE", 0x4000) + test, true, trigger)
        private fun fighter(tag: Boolean): Layout = if (tag) Layout(listOf(
            b("UP", 0x200000), b("DOWN", 0x100000), b("LEFT", 0x80000), b("RIGHT", 0x40000),
            b("LP", 0x20000), b("RP", 0x10000), b("LK", 0x40000000), b("RK", 0x20000000), b("TAG", 0x80000000L),
            b("START", 0x800000), coin, b("SERVICE", 0x4000), test, card)) else Layout(listOf(
            b("UP", 0x2000), b("DOWN", 0x1000), b("LEFT", 0x800), b("RIGHT", 0x400),
            b("LP", 0x200), b("RP", 0x100), b("LK", 0x400000), b("RK", 0x200000),
            b("START", 0x8000), coin, b("SERVICE", 0x4000), test))
        private fun taiko() = Layout(listOf(
            b("KA L", 0x200), b("DON L", 0x4), b("DON R", 0x20), b("KA R", 0x80000),
            b("START", 0x2), coin, b("SERVICE", 0x40), b("TEST", 0x1)))
    }
}
