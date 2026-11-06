package com.phoneagent.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class LiveActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private var wasLive = false
    private var startCount = 0

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun round(t: String, f: () -> Unit): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 20f
        v.gravity = Gravity.CENTER
        v.setTextColor(Color.WHITE)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(0xFF2B2B2B.toInt())
        v.background = bg
        v.setOnClickListener { f() }
        return v
    }

    private fun lp(w: Int, h: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        p.setMargins(dp(6), 0, dp(6), 0)
        return p
    }

    private fun startLive() {
        if (VoiceService.live) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Voice.stopSpeaking()
            startForegroundService(Intent(this, VoiceService::class.java))
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 5)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLive()
        } else {
            Toast.makeText(this, "لازم تسمح بالميكروفون عشان أسمعك", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Voice.init(this)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = 0xFF0B1E6B.toInt()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startCount = Agent.msgs.size

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF000000.toInt(), 0xFF050A24.toInt(), 0xFF0B1E6B.toInt()))
        root.setPadding(dp(16), dp(28), dp(16), dp(24))

        val title = TextView(this)
        title.text = "PhoneAgent  •  Live"
        title.textSize = 14f
        title.gravity = Gravity.CENTER
        title.setTextColor(0xFFBBBBBB.toInt())
        root.addView(title)

        val main = TextView(this)
        main.textSize = 26f
        main.gravity = Gravity.CENTER
        main.setTextColor(Color.WHITE)
        val sub = TextView(this)
        sub.textSize = 14f
        sub.gravity = Gravity.CENTER
        sub.setTextColor(0xFF8A8A8A.toInt())
        sub.setPadding(0, dp(16), 0, 0)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setPadding(dp(8), dp(12), dp(8), dp(12))
        box.addView(main)
        box.addView(sub)
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val status = TextView(this)
        status.textSize = 14f
        status.gravity = Gravity.CENTER
        status.setTextColor(0xFF8AB4FF.toInt())
        status.setPadding(0, dp(8), 0, dp(12))
        root.addView(status)

        val bar = LinearLayout(this)
        bar.gravity = Gravity.CENTER
        val close = round("✕") {
            VoiceService.instance?.stopLive()
            finish()
        }
        val muteBtn = round("🎙") { VoiceService.instance?.toggleMute() }
        val orb = OrbView(this)
        orb.setOnClickListener {
            when {
                !VoiceService.live -> startLive()
                Agent.running -> Agent.stop()
                Voice.speaking -> Voice.stopSpeaking()
            }
        }
        val chat = round("💬") {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
            finish()
        }
        bar.addView(close, lp(dp(48), dp(48)))
        bar.addView(muteBtn, lp(dp(48), dp(48)))
        bar.addView(orb, lp(dp(120), dp(72)))
        bar.addView(chat, lp(dp(48), dp(48)))
        root.addView(bar)
        setContentView(root)

        startLive()

        val tick = object : Runnable {
            override fun run() {
                val live = VoiceService.live
                if (live) wasLive = true
                else if (wasLive) { finish(); return }

                val muted = VoiceService.muted
                orb.state = when {
                    !live || muted -> 0
                    Agent.running -> 2
                    Voice.speaking -> 3
                    VoiceService.listening -> 1
                    else -> 0
                }
                status.text = when {
                    !live -> "اضغط الدائرة للبدء"
                    muted -> "الميكروفون مكتوم"
                    Agent.running -> "أنفّذ... (اضغط الدائرة للإيقاف)"
                    Voice.speaking -> "أتكلم... (اضغط الدائرة لأسكت)"
                    VoiceService.listening -> "جارٍ الاستماع..."
                    else -> "..."
                }
                muteBtn.text = if (muted) "🔇" else "🎙"

                val snap = Agent.msgs.toList().drop(startCount)
                main.text = snap.lastOrNull { it.type == 1 }?.text ?: "مرحباً، ما هي الخطوة التالية؟"
                sub.text = snap.lastOrNull { it.type == 0 }?.text ?: ""
                ui.postDelayed(this, 150)
            }
        }
        ui.post(tick)
    }

    override fun onResume() { super.onResume(); Agent.uiVisible = true }
    override fun onPause() { Agent.uiVisible = false; super.onPause() }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
