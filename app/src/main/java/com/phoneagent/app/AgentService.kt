package com.phoneagent.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class AgentService : AccessibilityService() {

    companion object {
        @Volatile var instance: AgentService? = null
    }

    private var bubble: TextView? = null

    override fun onServiceConnected() {
        instance = this
        Voice.init(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        try {
            bubble?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        } catch (e: Exception) {
        }
        bubble = null
        return super.onUnbind(intent)
    }

    // Volume Down = emergency stop while the agent is running
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (Agent.running && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) Agent.stop()
            return true
        }
        return false
    }

    // ---------- floating mic button (works over any app) ----------
    private fun showBubble() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val v = TextView(this)
        v.text = "🎤"
        v.textSize = 24f
        v.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(0xCC2F6FED.toInt())
        v.background = bg
        val size = (56 * resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 16
        lp.y = 500
        v.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f
            var sy = 0f
            var ox = 0
            var oy = 0
            var moved = false
            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - sx).toInt()
                        val dy = (e.rawY - sy).toInt()
                        if (abs(dx) > 12 || abs(dy) > 12) moved = true
                        if (moved) {
                            lp.x = ox + dx
                            lp.y = oy + dy
                            wm.updateViewLayout(view, lp)
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) onBubbleTap()
                }
                return true
            }
        })
        wm.addView(v, lp)
        bubble = v
    }

    private fun onBubbleTap() {
        if (Agent.running) {
            Agent.stop()
        } else {
            if (VoiceService.live) {
                VoiceService.instance?.stopLive()
                return
            }
            val i = Intent(this, VoiceActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
    }

    fun refreshBubble() {
        bubble?.post { bubble?.text = if (Agent.running) "⏹" else if (VoiceService.live) "🔴" else "🎤" }
    }

    // ---------- screen control ----------
    fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            Pair(m.widthPixels, m.heightPixels)
        }
    }

    fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val p = Path()
        p.moveTo(x1, y1)
        if (x1 != x2 || y1 != y2) p.lineTo(x2, y2)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms.coerceAtLeast(1)))
            .build()
        val latch = CountDownLatch(1)
        var ok = false
        val cb = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                ok = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }
        dispatchGesture(g, cb, null)
        latch.await(6, TimeUnit.SECONDS)
        return ok
    }

    fun typeText(t: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    fun dumpScreen(): String {
        val root = rootInActiveWindow ?: return "(no accessibility tree - use the screenshot)"
        val (w, h) = screenSize()
        val sb = StringBuilder()
        sb.append("App: ").append(root.packageName).append("\n")
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || sb.length > 4000) return
            val t = (n.text ?: n.contentDescription)?.toString()
            if (!t.isNullOrBlank() || n.isClickable || n.isEditable) {
                val r = Rect()
                n.getBoundsInScreen(r)
                sb.append("[").append(r.centerX() * 1000 / w).append(",")
                    .append(r.centerY() * 1000 / h).append("] ")
                    .append(t ?: "")
                if (n.isClickable) sb.append(" (click)")
                if (n.isEditable) sb.append(" (edit)")
                sb.append("\n")
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return sb.toString()
    }

    fun screenshotB64(): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = CountDownLatch(1)
        var result: String? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()
                    if (bmp != null) {
                        val w = 640
                        val h = bmp.height * w / bmp.width
                        val sm = Bitmap.createScaledBitmap(bmp, w, h, true)
                        val bo = ByteArrayOutputStream()
                        sm.compress(Bitmap.CompressFormat.JPEG, 60, bo)
                        result = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
                    }
                } catch (e: Exception) {
                }
                latch.countDown()
            }
            override fun onFailure(errorCode: Int) {
                latch.countDown()
            }
        })
        latch.await(6, TimeUnit.SECONDS)
        return result
    }
}
