package net.rpcs3

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isInvisible
import androidx.core.view.updateLayoutParams
import net.rpcs3.databinding.ActivityRpcs3Binding
import net.rpcs3.dialogs.AlertDialogQueue
import net.rpcs3.overlay.State
import java.lang.ref.WeakReference
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

class RPCS3Activity : Activity() {
    private lateinit var binding: ActivityRpcs3Binding
    private var unregisterUsbEventListener: () -> Unit = {}
    private var gamePadState: State = State()
    private var usesAxisL2 = false
    private var usesAxisR2 = false
    private var bootThread: Thread? = null
    private var companionSession = false
    private lateinit var arcadeOverlay: TeknoParrotArcadeControlsOverlay
    private var arcadeRootPath: String? = null
    private var arcadeVfsConfigPath: String? = null
    @Volatile private var bootSucceeded = false
    @Volatile private var stopping = false
    @Volatile private var terminalStopComplete = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!RPCS3Runtime.ensureInitialized(applicationContext)) {
            finish()
            return
        }

        activeActivity = WeakReference(this)
        binding = ActivityRpcs3Binding.inflate(layoutInflater)
        setContentView(binding.root)
        arcadeOverlay = TeknoParrotArcadeControlsOverlay(this)
        binding.main.addView(arcadeOverlay, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        arcadeOverlay.visibility = android.view.View.GONE
        binding.oscToggle.bringToFront()

        unregisterUsbEventListener = listenUsbEvents(this)
        enableFullScreenImmersive()

        binding.oscToggle.setOnClickListener {
            val overlay = if (companionSession) arcadeOverlay else binding.padOverlay
            overlay.isInvisible = !overlay.isInvisible
            binding.oscToggle.setImageResource(if (overlay.isInvisible) R.drawable.ic_osc_off else R.drawable.ic_show_osc)
        }

        val gamePath = if (intent.action == TeknoParrotContract.ACTION_LAUNCH_GAME) {
            companionSession = true
            TeknoParrotSession.accept(applicationContext, intent)
        } else {
            intent.getStringExtra(TeknoParrotContract.EXTRA_GAME_PATH)
                ?: intent.getStringExtra(LEGACY_GAME_PATH_EXTRA)
        }

        if (gamePath.isNullOrBlank()) {
            if (companionSession) TeknoParrotSession.update(applicationContext, "failed")
            finish()
            return
        }

        if (companionSession) {
            val firmwareRoot = getExternalFilesDir(null)
            if (firmwareRoot == null || !FirmwareRepository.isReady(firmwareRoot)) {
                Log.e("RPCS3X6 firmware", "Refusing arcade launch without installed PS3 firmware")
                TeknoParrotSession.update(applicationContext, "failed")
                finish()
                return
            }
            binding.padOverlay.isInvisible = true
            positionCompanionOverlayToggle()
            val profileName = intent.getStringExtra(TeknoParrotContract.EXTRA_PROFILE_NAME).orEmpty()
            arcadeOverlay.configure(profileName)
            val arcadeRoot = TeknoParrotGamePath.arcadeRoot(gamePath)
            val vfsConfig = arcadeRoot?.let {
                TeknoParrotArcadeConfig.prepare(applicationContext, profileName, it)
            }
            if (arcadeRoot == null || vfsConfig == null) {
                TeknoParrotSession.update(applicationContext, "failed")
                finish()
                return
            }
            arcadeRootPath = arcadeRoot.absolutePath
            arcadeVfsConfigPath = vfsConfig.absolutePath
        }


        bootThread = thread {
            if (RPCS3.getState() != EmulatorState.Stopped) {
                val state = RPCS3.getState()
                Log.w("RPCS3 State", state.name)

                if (state == EmulatorState.Paused && RPCS3.activeGame.value == gamePath) {
                    RPCS3.instance.resume()
                    bootSucceeded = true
                    if (companionSession) TeknoParrotSession.update(applicationContext, "running")
                    return@thread
                }

                if (RPCS3.getState() != EmulatorState.Stopping && RPCS3.getState() != EmulatorState.Stopped) {
                    RPCS3.instance.kill()

                    while (RPCS3.getState() != EmulatorState.Stopped) {
                        Thread.sleep(300)
                        if (Thread.interrupted()) {
                            return@thread
                        }
                    }
                }
            }

            Log.w("RPCS3 State", RPCS3.getState().name)
            RPCS3.activeGame.value = gamePath

            if (companionSession && !RPCS3.instance.configureArcadeRoot(
                    arcadeRootPath.orEmpty(), arcadeVfsConfigPath.orEmpty())) {
                TeknoParrotSession.update(applicationContext, "failed")
                runOnUiThread { finish() }
                return@thread
            }

            val bootResult = RPCS3.boot(gamePath)
            if (bootResult != BootResult.NoErrors) {
                if (companionSession) TeknoParrotSession.update(applicationContext, "failed")
                AlertDialogQueue.showDialog("Boot Failed", "Error: ${bootResult.name}")
                finish()
            } else {
                bootSucceeded = true
                if (companionSession) TeknoParrotSession.update(applicationContext, "running")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (companionSession && bootSucceeded && !stopping) {
            TeknoParrotSession.update(applicationContext, "running")
        }
    }

    override fun onPause() {
        if (companionSession && bootSucceeded && !stopping) {
            TeknoParrotSession.update(applicationContext, "paused")
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (activeActivity.get() === this) activeActivity.clear()
        unregisterUsbEventListener()
        bootThread?.interrupt()
        bootThread?.join()
        if (::arcadeOverlay.isInitialized) arcadeOverlay.configure("")
        super.onDestroy()

        // Match the PCSX2X6 companion lifecycle: once TPUI has received the
        // terminal session callback and this Activity is genuinely finishing,
        // do not retain RPCS3's large native runtime as an empty cached process.
        // Configuration-driven recreation never sets stopping and must survive.
        if (companionSession && stopping && terminalStopComplete) {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        requestStopAndFinish()
    }

    private fun requestStopAndFinish() {
        if (stopping) return
        stopping = true
        if (companionSession) TeknoParrotSession.update(applicationContext, "stopping")
        thread(name = "RPCS3X6 game stop") {
            terminalStopComplete = stopEmulatorAndWait()
            if (companionSession) TeknoParrotSession.update(applicationContext, "stopped")
            if (terminalStopComplete) {
                // The stopped callback is asynchronous; let Android dispatch
                // it to TPUI before terminal onDestroy kills this process.
                Thread.sleep(250)
                runOnUiThread { finish() }
            } else {
                Log.e("RPCS3X6 lifecycle", "Native stop timed out; terminating the companion process")
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }


    private fun keyCodeToPadBit(keyCode: Int): Pair<Int, Int> {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> return Pair(Digital1Flags.CELL_PAD_CTRL_UP.bit, 0)
            KeyEvent.KEYCODE_DPAD_DOWN -> return Pair(Digital1Flags.CELL_PAD_CTRL_DOWN.bit, 0)
            KeyEvent.KEYCODE_DPAD_LEFT -> return Pair(Digital1Flags.CELL_PAD_CTRL_LEFT.bit, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> return Pair(Digital1Flags.CELL_PAD_CTRL_RIGHT.bit, 0)
            KeyEvent.KEYCODE_BUTTON_A -> return Pair(Digital2Flags.CELL_PAD_CTRL_CROSS.bit, 1)
            KeyEvent.KEYCODE_BUTTON_B -> return Pair(Digital2Flags.CELL_PAD_CTRL_CIRCLE.bit, 1)
            KeyEvent.KEYCODE_BUTTON_X -> return Pair(Digital2Flags.CELL_PAD_CTRL_SQUARE.bit, 1)
            KeyEvent.KEYCODE_BUTTON_Y -> return Pair(Digital2Flags.CELL_PAD_CTRL_TRIANGLE.bit, 1)
            KeyEvent.KEYCODE_BUTTON_L1 -> return Pair(Digital2Flags.CELL_PAD_CTRL_L1.bit, 1)
            KeyEvent.KEYCODE_BUTTON_R1 -> return Pair(Digital2Flags.CELL_PAD_CTRL_R1.bit, 1)
            KeyEvent.KEYCODE_BUTTON_L2 -> return if (usesAxisL2) Pair(
                0,
                0
            ) else Pair(Digital2Flags.CELL_PAD_CTRL_L2.bit, 1)

            KeyEvent.KEYCODE_BUTTON_R2 -> return if (usesAxisR2) Pair(
                0,
                0
            ) else Pair(Digital2Flags.CELL_PAD_CTRL_R2.bit, 1)

            KeyEvent.KEYCODE_BUTTON_START -> return Pair(Digital1Flags.CELL_PAD_CTRL_START.bit, 0)
            KeyEvent.KEYCODE_BUTTON_SELECT -> return Pair(Digital1Flags.CELL_PAD_CTRL_SELECT.bit, 0)
            KeyEvent.KEYCODE_BUTTON_THUMBL -> return Pair(Digital1Flags.CELL_PAD_CTRL_L3.bit, 0)
            KeyEvent.KEYCODE_BUTTON_THUMBR -> return Pair(Digital1Flags.CELL_PAD_CTRL_R3.bit, 0)
            KeyEvent.KEYCODE_BUTTON_MODE -> return Pair(Digital1Flags.CELL_PAD_CTRL_PS.bit, 0)
        }

        return Pair(0, 0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null || (event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD)) == 0 || event.repeatCount != 0) {
            return super.onKeyDown(keyCode, event)
        }
        if (companionSession && arcadeOverlay.onControllerKey(keyCode, true)) return true
        val padBit = keyCodeToPadBit(keyCode)
        if (padBit.first == 0) {
            return super.onKeyDown(keyCode, event)
        }

        gamePadState.digital[padBit.second] = gamePadState.digital[padBit.second] or padBit.first
        sendGamepadData()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null || event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD) == 0) {
            return super.onKeyUp(keyCode, event)
        }

        if (companionSession && arcadeOverlay.onControllerKey(keyCode, false)) return true
        val padBit = keyCodeToPadBit(keyCode)
        if (padBit.first == 0) {
            return super.onKeyUp(keyCode, event)
        }

        gamePadState.digital[padBit.second] =
            gamePadState.digital[padBit.second] and padBit.first.inv()
        sendGamepadData()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event == null || event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || event.action != MotionEvent.ACTION_MOVE) {
            return super.onGenericMotionEvent(event)
        }

        if (companionSession && arcadeOverlay.onControllerMotion(event)) return true

        val leftTrigger = max(
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_BRAKE)
        )
        val rightTrigger = max(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_GAS)
        )

        if (leftTrigger > 0.1f) {
            gamePadState.digital[1] =
                gamePadState.digital[1] or Digital2Flags.CELL_PAD_CTRL_L2.bit
            usesAxisL2 = true
        } else if (usesAxisL2) {
            usesAxisL2 = false
            gamePadState.digital[1] =
                gamePadState.digital[1] and Digital2Flags.CELL_PAD_CTRL_L2.bit.inv()
        }

        if (rightTrigger > 0.1f) {
            gamePadState.digital[1] =
                gamePadState.digital[1] or Digital2Flags.CELL_PAD_CTRL_R2.bit
            usesAxisR2 = true
        } else if (usesAxisR2) {
            usesAxisR2 = false
            gamePadState.digital[1] =
                gamePadState.digital[1] and Digital2Flags.CELL_PAD_CTRL_R2.bit.inv()
        }

        val dpadX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val dpadY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        gamePadState.digital[0] =
            gamePadState.digital[0] and (Digital1Flags.CELL_PAD_CTRL_LEFT.bit or Digital1Flags.CELL_PAD_CTRL_RIGHT.bit or Digital1Flags.CELL_PAD_CTRL_UP.bit or Digital1Flags.CELL_PAD_CTRL_DOWN.bit).inv()
        if (abs(dpadX) > 0.1f) {
            if (dpadX < 0) {
                gamePadState.digital[0] =
                    gamePadState.digital[0] or Digital1Flags.CELL_PAD_CTRL_LEFT.bit
            } else {
                gamePadState.digital[0] =
                    gamePadState.digital[0] or Digital1Flags.CELL_PAD_CTRL_RIGHT.bit
            }
        }

        if (abs(dpadY) > 0.1f) {
            if (dpadY < 0) {
                gamePadState.digital[0] =
                    gamePadState.digital[0] or Digital1Flags.CELL_PAD_CTRL_UP.bit
            } else {
                gamePadState.digital[0] =
                    gamePadState.digital[0] or Digital1Flags.CELL_PAD_CTRL_DOWN.bit
            }
        }

        gamePadState.leftStickX = axisToByte(event, MotionEvent.AXIS_X)
        gamePadState.leftStickY = axisToByte(event, MotionEvent.AXIS_Y)
        gamePadState.rightStickX = axisToByte(
            event,
            preferredAxis(event, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX)
        )
        gamePadState.rightStickY = axisToByte(
            event,
            preferredAxis(event, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY)
        )

        sendGamepadData()
        return true
    }

    private fun preferredAxis(event: MotionEvent, primary: Int, fallback: Int): Int =
        if (event.device?.getMotionRange(primary, event.source) != null) primary else fallback

    private fun axisToByte(event: MotionEvent, axis: Int): Int {
        val range = event.device?.getMotionRange(axis, event.source)
        val raw = event.getAxisValue(axis)
        val value = if (abs(raw) <= (range?.flat ?: 0f)) 0f else raw.coerceIn(-1f, 1f)
        return (value * 127f + 128f).toInt().coerceIn(0, 255)
    }

    private fun sendGamepadData() {
        RPCS3.instance.overlayPadData(
            gamePadState.digital[0],
            gamePadState.digital[1],
            gamePadState.leftStickX,
            gamePadState.leftStickY,
            gamePadState.rightStickX,
            gamePadState.rightStickY
        )
    }

    /** Keep the show/hide affordance clear of every profile's bottom-right action. */
    private fun positionCompanionOverlayToggle() {
        val margin = (10 * resources.displayMetrics.density).toInt()
        binding.oscToggle.updateLayoutParams<ConstraintLayout.LayoutParams> {
            endToEnd = ConstraintSet.UNSET
            bottomToBottom = ConstraintSet.UNSET
            startToStart = ConstraintSet.PARENT_ID
            topToTop = ConstraintSet.PARENT_ID
            marginStart = margin
            topMargin = margin
        }
    }

    private fun enableFullScreenImmersive() {
        with(window) {
            WindowCompat.setDecorFitsSystemWindows(this, false)
            val insetsController = WindowInsetsControllerCompat(this, decorView)
            insetsController.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            attributes.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        applyInsetsToPadOverlay()
    }

    private fun applyInsetsToPadOverlay() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.padOverlay) { view, windowInsets ->
            // I don't think we need `displayCutout` insets here as well
            // Since there is hardly any overlay overlapping with it
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<MarginLayoutParams> {
                leftMargin = insets.left
                rightMargin = insets.right
                topMargin = insets.top
                bottomMargin = insets.bottom
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableFullScreenImmersive()
    }

    companion object {
        private const val LEGACY_GAME_PATH_EXTRA = "path"
        private var activeActivity = WeakReference<RPCS3Activity>(null)

        fun hasActiveActivity(): Boolean = activeActivity.get() != null

        fun finishActiveSession(cleanStopComplete: Boolean) {
            val activity = activeActivity.get()
            if (activity == null) {
                if (cleanStopComplete) android.os.Process.killProcess(android.os.Process.myPid())
                return
            }
            activity.runOnUiThread {
                // Remote TPUI stops arrive through the control receiver rather
                // than onBackPressed(), so mark this as a terminal companion
                // shutdown before onDestroy decides whether to retain the
                // native runtime.
                activity.stopping = true
                activity.terminalStopComplete = cleanStopComplete
                activity.finish()
            }
        }

        fun stopEmulatorAndWait(timeoutMs: Long = 30_000L): Boolean {
            if (!RPCS3.initialized) return true
            if (runCatching { RPCS3.getState() }.getOrDefault(EmulatorState.Stopped) != EmulatorState.Stopped) {
                RPCS3.instance.kill()
            }

            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                if (runCatching { RPCS3.getState() }.getOrDefault(EmulatorState.Stopped) == EmulatorState.Stopped) {
                    return true
                }
                Thread.sleep(50)
            }
            return runCatching { RPCS3.getState() }.getOrDefault(EmulatorState.Stopped) == EmulatorState.Stopped
        }
    }
}
