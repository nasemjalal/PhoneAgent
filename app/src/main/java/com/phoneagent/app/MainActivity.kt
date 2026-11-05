package com.phoneagent.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sp = getSharedPreferences("cfg", MODE_PRIVATE)
        val d = resources.displayMetrics.density
        fun dp(n: Int) = (n * d).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(40), dp(12), dp(12))

        fun btn(t: String, f: () -> Unit): Button {
            val b = Button(this)
            b.text = t
            b.setOnClickListener { f() }
            return b
        }

        val status = TextView(this)
        val key = EditText(this)
        key.hint = "Gemini API Key"
        key.setText(sp.getString("key", ""))
        key.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val model = EditText(this)
        model.hint = "Model"
        model.setText(sp.getString("model", "gemini-2.5-flash"))
        val goal = EditText(this)
        goal.hint = "اكتب أمرك هنا"
        goal.minLines = 2
        val log = TextView(this)
        log.textSize = 13f
        log.setTextIsSelectable(true)
        val scroll = ScrollView(this)
        scroll.addView(log)

        val row1 = LinearLayout(this)
        row1.addView(btn("1) تفعيل الخدمة") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row1.addView(btn("2) صلاحية الملفات") {
            if (Build.VERSION.SDK_INT >= 30) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + packageName)))
            } else {
                requestPermissions(arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))

        val row2 = LinearLayout(this)
        row2.addView(btn("ابدأ") {
            sp.edit().putString("key", key.text.toString().trim())
                .putString("model", model.text.toString().trim()).apply()
            Agent.start(key.text.toString().trim(), model.text.toString().trim(), goal.text.toString())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row2.addView(btn("إيقاف") { Agent.stop() }, LinearLayout.LayoutParams(0, -2, 1f))

        root.addView(status)
        root.addView(key)
        root.addView(model)
        root.addView(row1)
        root.addView(goal)
        root.addView(row2)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val tick = object : Runnable {
            override fun run() {
                status.text = if (AgentService.instance != null)
                    "الخدمة: مفعّلة ✅  |  للإيقاف الطارئ اضغط خفض الصوت"
                else "الخدمة: غير مفعّلة ❌"
                val s = Agent.log.toString()
                if (s != log.text.toString()) {
                    log.text = s
                    scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }
                ui.postDelayed(this, 600)
            }
        }
        ui.post(tick)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
