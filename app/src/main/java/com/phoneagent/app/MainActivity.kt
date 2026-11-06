package com.phoneagent.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val atts = ArrayList<Att>()
    private var rendered = 0
    private lateinit var chip: TextView

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun rounded(c: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(c)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    private fun icon(t: String, f: () -> Unit): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 22f
        v.gravity = Gravity.CENTER
        v.setPadding(dp(10), dp(6), dp(10), dp(6))
        v.setOnClickListener { f() }
        return v
    }

    private fun btn(t: String, f: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.setOnClickListener { f() }
        return b
    }

    private fun bubble(m: Msg): View {
        val tv = TextView(this)
        tv.text = m.text
        tv.setTextIsSelectable(true)
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.setMargins(dp(4), dp(3), dp(4), dp(3))
        when (m.type) {
            0 -> {
                tv.setTextColor(Color.WHITE)
                tv.background = rounded(0xFF2F6FED.toInt(), 16)
                tv.setPadding(dp(12), dp(8), dp(12), dp(8))
                lp.gravity = Gravity.RIGHT
            }
            1 -> {
                tv.setTextColor(Color.BLACK)
                tv.background = rounded(Color.WHITE, 16)
                tv.setPadding(dp(12), dp(8), dp(12), dp(8))
                lp.gravity = Gravity.LEFT
            }
            else -> {
                tv.setTextColor(0xFF888888.toInt())
                tv.textSize = 12f
                lp.gravity = Gravity.LEFT
            }
        }
        tv.layoutParams = lp
        return tv
    }

    private fun readAtt(u: Uri): Att {
        var name = "file"
        contentResolver.query(u, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) name = c.getString(i)
        }
        val mime = contentResolver.getType(u) ?: "application/octet-stream"
        val bytes = contentResolver.openInputStream(u)!!.use { it.readBytes() }
        var path: String? = null
        try {
            val f = File("/storage/emulated/0/Download/PhoneAgent/" + name)
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
            path = f.path
        } catch (e: Exception) {
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        val textExt = setOf("js", "json", "java", "kt", "py", "txt", "md", "yml", "yaml", "xml", "html",
            "css", "toml", "properties", "cfg", "conf", "sh", "gradle", "lua", "csv", "log", "mcmeta", "zs")
        val isText = mime.startsWith("text/") || ext in textExt
        val inline = (mime.startsWith("image/") || mime == "application/pdf") && bytes.size < 8000000
        return Att(
            name, mime,
            if (inline) Base64.encodeToString(bytes, Base64.NO_WRAP) else null,
            if (isText && bytes.size < 200000) String(bytes).take(30000) else null,
            path
        )
    }

    private fun refreshChip() {
        if (atts.isEmpty()) {
            chip.visibility = View.GONE
        } else {
            chip.visibility = View.VISIBLE
            chip.text = "📎 " + atts.joinToString { it.name } + "   ✕"
        }
    }

    private fun showSettings() {
        val sp = getSharedPreferences("cfg", MODE_PRIVATE)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(8), dp(16), dp(8))
        val key = EditText(this)
        key.hint = "Gemini API Key"
        key.setText(sp.getString("key", ""))
        key.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val model = EditText(this)
        model.hint = "Model"
        model.setText(sp.getString("model", "gemini-3.8-flash"))
        box.addView(key)
        box.addView(model)
        box.addView(btn("1) تفعيل خدمة التحكم") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        box.addView(btn("2) صلاحية الملفات") {
            if (Build.VERSION.SDK_INT >= 30) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + packageName)))
            } else {
                requestPermissions(arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            }
        })
        AlertDialog.Builder(this)
            .setTitle("الإعدادات")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                sp.edit().putString("key", key.text.toString().trim())
                    .putString("model", model.text.toString().trim()).apply()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sp = getSharedPreferences("cfg", MODE_PRIVATE)
        Voice.init(this)
        Voice.always = sp.getBoolean("voice", false)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFFF2F2F2.toInt())
        root.setPadding(dp(8), dp(6), dp(8), dp(8))

        // header
        val header = LinearLayout(this)
        header.gravity = Gravity.CENTER_VERTICAL
        val titleBox = LinearLayout(this)
        titleBox.orientation = LinearLayout.VERTICAL
        val title = TextView(this)
        title.text = "PhoneAgent"
        title.textSize = 20f
        title.setTextColor(Color.BLACK)
        val status = TextView(this)
        status.textSize = 11f
        status.setTextColor(0xFF666666.toInt())
        titleBox.addView(title)
        titleBox.addView(status)
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        val voiceBtn = icon(if (Voice.always) "🔊" else "🔇") {}
        voiceBtn.setOnClickListener {
            Voice.always = !Voice.always
            sp.edit().putBoolean("voice", Voice.always).apply()
            voiceBtn.text = if (Voice.always) "🔊" else "🔇"
            if (!Voice.always) Voice.stopSpeaking()
        }
        header.addView(voiceBtn)
        header.addView(icon("🗑") { Agent.newChat() })
        header.addView(icon("⚙️") { showSettings() })
        root.addView(header)

        // messages
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val busy = TextView(this)
        busy.setTextColor(0xFF2F6FED.toInt())
        busy.textSize = 12f
        root.addView(busy)

        chip = TextView(this)
        chip.setTextColor(Color.BLACK)
        chip.background = rounded(0xFFDDE6FF.toInt(), 12)
        chip.setPadding(dp(10), dp(6), dp(10), dp(6))
        chip.visibility = View.GONE
        chip.setOnClickListener { atts.clear(); refreshChip() }
        root.addView(chip)

        // input row
        val input = EditText(this)
        input.hint = "اكتب أو تكلم..."
        input.setTextColor(Color.BLACK)
        input.setHintTextColor(0xFF999999.toInt())
        input.background = rounded(Color.WHITE, 20)
        input.setPadding(dp(14), dp(8), dp(14), dp(8))
        input.maxLines = 4
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(icon("📎") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            startActivityForResult(i, 21)
        })
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val micBtn = icon("🎤") {
            if (VoiceService.live) VoiceService.instance?.stopLive()
            else startActivity(Intent(this, LiveActivity::class.java))
        }
        row.addView(micBtn)
        row.addView(icon("➤") {
            val t = input.text.toString().trim()
            if (t.isNotEmpty() || atts.isNotEmpty()) {
                Agent.send(this, t, ArrayList(atts), false)
                input.setText("")
                atts.clear()
                refreshChip()
            }
        })
        root.addView(row)
        setContentView(root)

        if (Agent.msgs.isEmpty()) {
            Agent.msgs.add(Msg(1, "هلا! اكتب أو اضغط 🎤 وكلمني. أقدر أسوي ملفات وكودات وأتحكم بالجوال. واضغط 🎤 للمحادثة الصوتية مثل Gemini Live."))
        }
        if ((sp.getString("key", "") ?: "").isBlank()) showSettings()

        val tick = object : Runnable {
            override fun run() {
                status.text = if (AgentService.instance != null) "الخدمة مفعّلة ✅" else "الخدمة غير مفعّلة ❌ (من ⚙️)"
                busy.text = if (Agent.running) "⏳ يشتغل... (خفض الصوت للإيقاف)"
                    else if (VoiceService.live) "🔴 أسمعك... تكلم (اضغط 🔴 للإيقاف)" else ""
                micBtn.text = if (VoiceService.live) "🔴" else "🎤"
                val snap = Agent.msgs.toList()
                if (snap.size < rendered) { list.removeAllViews(); rendered = 0 }
                if (snap.size > rendered) {
                    while (rendered < snap.size) { list.addView(bubble(snap[rendered])); rendered++ }
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
                ui.postDelayed(this, 400)
            }
        }
        ui.post(tick)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 21 || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val cd = data.clipData
        if (cd != null) { for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) }
        else data.data?.let { uris.add(it) }
        Thread {
            for (u in uris) {
                try {
                    val a = readAtt(u)
                    runOnUiThread { atts.add(a); refreshChip() }
                } catch (e: Exception) {
                    Agent.say("⚠️ تعذر قراءة الملف: " + e.message)
                }
            }
        }.start()
    }

    override fun onResume() { super.onResume(); Agent.uiVisible = true }
    override fun onPause() { Agent.uiVisible = false; super.onPause() }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
