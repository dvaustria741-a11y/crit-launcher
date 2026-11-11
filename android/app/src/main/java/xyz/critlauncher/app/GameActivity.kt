package xyz.critlauncher.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.movtery.zalithlauncher.bridge.ZLBridge
import com.movtery.zalithlauncher.bridge.ZLNativeInvoker
import org.lwjgl.glfw.CallbackBridge
import xyz.critlauncher.app.engine.EngineLauncher
import xyz.critlauncher.app.engine.LaunchSpec
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.concurrent.thread

/**
 * Runs the game in its own process (":game", see the manifest) so that the JVM can exit, or be killed,
 * without taking the launcher down. Shows a SurfaceView the engine renders into, plus on-screen controls.
 */
class GameActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private var started = false
    private val main = Handler(Looper.getMainLooper())

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            finishGame(143)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        ZLNativeInvoker.activity = this
        ZLNativeInvoker.onExit = { code, _ -> finishGame(code) }

        surfaceView = SurfaceView(this).apply {
            holder.setFormat(PixelFormat.RGBA_8888)
            holder.addCallback(this@GameActivity)
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            textSize = 13f
            text = "Preparing game engine..."
        }
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(status, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        setContentView(root)
        buildControls()
        enterImmersive()

        val filter = IntentFilter(ACTION_STOP)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stopReceiver, filter)
        }

        CallbackBridge.setListener(object : CallbackBridge.GameStateListener {
            override fun onGrabChanged(grabbing: Boolean) {
                main.post { grabbingCursor = grabbing }
            }

            override fun onFirstFrame() {
                main.post { status.visibility = View.GONE }
            }
        })
    }

    private fun enterImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    // ---- surface -> engine ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        ZLBridge.setupBridgeWindow(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (started) {
            CallbackBridge.sendUpdateWindowSize(width, height)
            return
        }
        started = true
        val specPath = intent.getStringExtra(EXTRA_SPEC)
        if (specPath == null) {
            showError("No launch information was passed to the game screen.")
            return
        }
        thread(name = "crit-jvm") {
            try {
                val spec = LaunchSpec.read(specPath)
                runOnUiThread { status.text = "Starting Java ${spec.major}..." }
                val code = EngineLauncher.run(applicationContext, spec, width, height)
                finishGame(code)
            } catch (t: Throwable) {
                val sw = StringWriter()
                t.printStackTrace(PrintWriter(sw))
                try {
                    java.io.File(intent.getStringExtra(EXTRA_LOG) ?: "").appendText("\nEngine error:\n$sw")
                } catch (_: Throwable) {
                }
                showError("Could not start the game engine:\n$t")
                finishGame(1, delayMs = 6000)
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        try {
            ZLBridge.releaseBridgeWindow()
        } catch (_: Throwable) {
        }
    }

    private fun showError(msg: String) {
        runOnUiThread {
            status.visibility = View.VISIBLE
            status.text = msg
        }
    }

    /** Tell the launcher the game ended, then end this process (the JVM cannot be restarted in it). */
    private fun finishGame(code: Int, delayMs: Long = 0) {
        val tell = {
            try {
                sendBroadcast(Intent(ACTION_EXIT).setPackage(packageName).putExtra(EXTRA_CODE, code))
            } catch (_: Throwable) {
            }
            Process.killProcess(Process.myPid())
        }
        if (delayMs > 0) main.postDelayed(tell, delayMs) else tell()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back = Escape in game
        CallbackBridge.sendKey(GLFW_KEY_ESCAPE, true)
        CallbackBridge.sendKey(GLFW_KEY_ESCAPE, false)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(stopReceiver)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    // ---- touch: look / click ----

    private var grabbingCursor = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var moved = false
    private var holdingLeft = false
    private val holdRunnable = Runnable {
        if (!moved && grabbingCursor) {
            holdingLeft = true
            CallbackBridge.sendMouseButton(CallbackBridge.BUTTON_LEFT, true)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installTouch() {
        surfaceView.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y; downTime = e.eventTime; moved = false
                    if (!grabbingCursor) {
                        cursorX = e.x; cursorY = e.y
                        CallbackBridge.sendCursorPos(cursorX, cursorY)
                        CallbackBridge.sendMouseButton(CallbackBridge.BUTTON_LEFT, true)
                    } else {
                        main.postDelayed(holdRunnable, 350)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    if (Math.abs(e.x - lastX) + Math.abs(e.y - lastY) > dp(2)) moved = true
                    lastX = e.x; lastY = e.y
                    if (grabbingCursor) {
                        cursorX += dx; cursorY += dy
                    } else {
                        cursorX = e.x; cursorY = e.y
                    }
                    CallbackBridge.sendCursorPos(cursorX, cursorY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(holdRunnable)
                    if (!grabbingCursor) {
                        CallbackBridge.sendMouseButton(CallbackBridge.BUTTON_LEFT, false)
                    } else if (holdingLeft) {
                        holdingLeft = false
                        CallbackBridge.sendMouseButton(CallbackBridge.BUTTON_LEFT, false)
                    } else if (!moved && e.eventTime - downTime < 250) {
                        CallbackBridge.sendMouseButton(CallbackBridge.BUTTON_LEFT, true)
                        CallbackBridge.sendMouseButton(CallbackBridge.BUTTON_LEFT, false)
                    }
                }
            }
            true
        }
    }

    // ---- on-screen controls ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun keyButton(label: String, key: Int, toggle: Boolean = false): TextView {
        var on = false
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 13f
            background = GradientDrawable().apply {
                setColor(0x66000000)
                setStroke(dp(1), 0x88FFFFFF.toInt())
                cornerRadius = dp(10).toFloat()
            }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> if (toggle) {
                        on = !on
                        CallbackBridge.sendKey(key, on)
                        v.alpha = if (on) 1f else 0.7f
                    } else {
                        CallbackBridge.sendKey(key, true)
                        v.alpha = 1f
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!toggle) {
                        CallbackBridge.sendKey(key, false)
                        v.alpha = 0.7f
                    }
                }
                true
            }
            alpha = 0.7f
        }
    }

    private fun mouseButton(label: String, button: Int): TextView =
        keyButton(label, 0).apply {
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { CallbackBridge.sendMouseButton(button, true); v.alpha = 1f }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { CallbackBridge.sendMouseButton(button, false); v.alpha = 0.7f }
                }
                true
            }
        }

    private fun place(v: View, size: Int, gravity: Int, marginStart: Int, marginTop: Int, marginEnd: Int, marginBottom: Int) {
        root.addView(v, FrameLayout.LayoutParams(dp(size), dp(size), gravity).apply {
            setMargins(dp(marginStart), dp(marginTop), dp(marginEnd), dp(marginBottom))
        })
    }

    private fun buildControls() {
        installTouch()
        val s = 52
        val bl = Gravity.BOTTOM or Gravity.START
        val br = Gravity.BOTTOM or Gravity.END
        // movement cluster (bottom-left)
        place(keyButton("W", GLFW_KEY_W), s, bl, 16 + s, 0, 0, 16 + s * 2)
        place(keyButton("A", GLFW_KEY_A), s, bl, 16, 0, 0, 16 + s)
        place(keyButton("S", GLFW_KEY_S), s, bl, 16 + s, 0, 0, 16 + s)
        place(keyButton("D", GLFW_KEY_D), s, bl, 16 + s * 2, 0, 0, 16 + s)
        place(keyButton("SNK", GLFW_KEY_LEFT_SHIFT, toggle = true), s, bl, 16, 0, 0, 16 + s * 2)
        // actions (bottom-right)
        place(keyButton("JUMP", GLFW_KEY_SPACE), s, br, 0, 0, 16 + s, 16 + s)
        place(mouseButton("USE", CallbackBridge.BUTTON_RIGHT), s, br, 0, 0, 16, 16 + s)
        place(keyButton("INV", GLFW_KEY_E), s, br, 0, 0, 16 + s, 16)
        place(keyButton("DROP", GLFW_KEY_Q), s, br, 0, 0, 16, 16)
        // top bar
        val tl = Gravity.TOP or Gravity.START
        place(keyButton("ESC", GLFW_KEY_ESCAPE), 44, tl, 16, 12, 0, 0)
        place(keyButton("CHAT", GLFW_KEY_T), 44, tl, 16 + 50, 12, 0, 0)
        place(keyButton("F5", GLFW_KEY_F5), 44, tl, 16 + 100, 12, 0, 0)
        place(keyButton("F3", GLFW_KEY_F3), 44, tl, 16 + 150, 12, 0, 0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val k = hardwareKey(keyCode)
        val ch = event.unicodeChar
        if (k == 0 && ch <= 0) return super.onKeyDown(keyCode, event)
        if (k != 0) CallbackBridge.sendKey(k, true)
        if (ch >= 32) CallbackBridge.sendChar(ch.toChar())
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val k = hardwareKey(keyCode)
        if (k != 0) {
            CallbackBridge.sendKey(k, false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun hardwareKey(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_ENTER -> 257
        KeyEvent.KEYCODE_DEL -> 259
        KeyEvent.KEYCODE_TAB -> 258
        KeyEvent.KEYCODE_SPACE -> GLFW_KEY_SPACE
        KeyEvent.KEYCODE_DPAD_UP -> 265
        KeyEvent.KEYCODE_DPAD_DOWN -> 264
        KeyEvent.KEYCODE_DPAD_LEFT -> 263
        KeyEvent.KEYCODE_DPAD_RIGHT -> 262
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> GLFW_KEY_A + (code - KeyEvent.KEYCODE_A)
        else -> 0
    }

    companion object {
        const val EXTRA_SPEC = "spec"
        const val EXTRA_LOG = "log"
        const val EXTRA_CODE = "code"
        const val ACTION_EXIT = "xyz.critlauncher.app.GAME_EXIT"
        const val ACTION_STOP = "xyz.critlauncher.app.GAME_STOP"

        // GLFW key codes
        private const val GLFW_KEY_SPACE = 32
        private const val GLFW_KEY_A = 65
        private const val GLFW_KEY_D = 68
        private const val GLFW_KEY_E = 69
        private const val GLFW_KEY_Q = 81
        private const val GLFW_KEY_S = 83
        private const val GLFW_KEY_T = 84
        private const val GLFW_KEY_W = 87
        private const val GLFW_KEY_ESCAPE = 256
        private const val GLFW_KEY_F3 = 292
        private const val GLFW_KEY_F5 = 294
        private const val GLFW_KEY_LEFT_SHIFT = 340
    }
}
