package com.phoneagent.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

// type: 0 = user, 1 = assistant reply, 2 = small step line
class Msg(val type: Int, val text: String)

object Agent {

    @Volatile var running = false
    @Volatile var uiVisible = false
    val msgs = CopyOnWriteArrayList<Msg>()
    private val history = ArrayList<JSONObject>()

    fun say(s: String) { msgs.add(Msg(2, s)) }

    fun reply(t: String, speak: Boolean) {
        val txt = if (t.isBlank()) "تم" else t
        msgs.add(Msg(1, txt))
        val svc = AgentService.instance
        if (!uiVisible && svc != null) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(svc, txt, Toast.LENGTH_LONG).show()
            }
        }
        if (speak || Voice.always) {
            if (!Voice.speak(txt)) say("⚠️ الصوت العربي غير متوفر بالجهاز")
        }
    }

    fun stop() {
        Voice.stopSpeaking()
        if (running) {
            running = false
            say("⛔ تم الإيقاف")
        }
    }

    fun newChat() {
        stop()
        history.clear()
        msgs.clear()
    }

    // keep old attachments out of the history so requests stay small
    private fun strip() {
        for (t in history) {
            val p = t.getJSONArray("parts")
            val n = JSONArray()
            for (i in 0 until p.length()) {
                if (!p.getJSONObject(i).has("inline_data")) n.put(p.getJSONObject(i))
            }
            t.put("parts", n)
        }
    }

    fun send(ctx: Context, text: String, atts: List<Att> = emptyList(), voice: Boolean = false) {
        if (text.isBlank() && atts.isEmpty()) return
        val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val key = (sp.getString("key", "") ?: "").trim()
        val model = (sp.getString("model", "gemini-3.8-flash") ?: "gemini-3.8-flash").trim()
        val head = if (atts.isNotEmpty()) "📎 " + atts.joinToString { it.name } + "\n" else ""
        msgs.add(Msg(0, head + text))
        val svc = AgentService.instance
        if (key.isBlank()) { reply("حط مفتاح Gemini من الإعدادات ⚙️", false); return }
        if (svc == null) { reply("فعّل خدمة التحكم من الإعدادات ⚙️", false); return }
        if (running) { say("⏳ الوكيل مشغول، اضغط إيقاف أولاً"); return }
        running = true
        svc.refreshBubble()
        strip()
        var content = "User: " + text
        for (a in atts) {
            content += "\n[Attached file: " + a.name + (if (a.path != null) " , saved at " + a.path else "") + "]"
            if (a.text != null) content += "\n----\n" + a.text + "\n----"
        }
        history.add(Gemini.turn("user", content, null, atts))
        Thread {
            try { loop(svc, key, model, voice) }
            catch (e: Exception) { say("❌ " + e.message) }
            finally { running = false; svc.refreshBubble() }
        }.start()
    }

    private fun parse(t: String): JSONObject {
        val s = t.indexOf('{')
        val e = t.lastIndexOf('}')
        return JSONObject(t.substring(s, e + 1))
    }

    private fun loop(svc: AgentService, key: String, model: String, voice: Boolean) {
        if (voice) Thread.sleep(900)
        for (step in 1..30) {
            if (!running) return
            val (w, h) = svc.screenSize()
            val obs = "Step " + step + ". Screen " + w + "x" + h + ".\n" + svc.dumpScreen()
            val turns = ArrayList(history)
            turns.add(Gemini.turn("user", obs, svc.screenshotB64()))
            val raw = Gemini.call(key, model, turns)
            if (!running) return
            history.add(Gemini.turn("user", obs))
            history.add(Gemini.turn("model", raw))
            val a = try { parse(raw) } catch (e: Exception) {
                say("⚠️ رد غير مفهوم، أعيد المحاولة")
                history.add(Gemini.turn("user", "Invalid JSON. Reply with ONE JSON object only."))
                continue
            }
            if (a.optString("action") == "done") { reply(a.optString("msg"), voice); return }
            val th = a.optString("thought")
            if (th.isNotBlank()) say("💭 " + th)
            say("▶ " + a.toString().take(160))
            val res = exec(svc, a)
            say("   → " + res.take(150))
            history.add(Gemini.turn("user", "Result: " + res))
            while (history.size > 60) history.removeAt(1)
            Thread.sleep(700)
        }
        say("⏹ وصلت الحد الأقصى (30 خطوة)")
    }

    private fun path(a: JSONObject): String {
        var p = a.optString("path")
        if (!p.startsWith("/")) p = "/storage/emulated/0/" + p.removePrefix("~/")
        return p
    }

    private fun exec(svc: AgentService, a: JSONObject): String {
        val (w, h) = svc.screenSize()
        fun px(k: String, dim: Int) = (a.optDouble(k, 500.0) / 1000.0 * dim).toFloat()
        return try {
            when (val t = a.optString("action")) {
                "tap" -> if (svc.gesture(px("x", w), px("y", h), px("x", w), px("y", h), 60)) "ok" else "gesture failed"
                "long_press" -> if (svc.gesture(px("x", w), px("y", h), px("x", w), px("y", h), 900)) "ok" else "gesture failed"
                "swipe" -> if (svc.gesture(px("x", w), px("y", h), px("x2", w), px("y2", h), a.optLong("ms", 400))) "ok" else "gesture failed"
                "type" -> if (svc.typeText(a.optString("text"))) "ok" else "no focused input field"
                "back" -> { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); "ok" }
                "home" -> { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); "ok" }
                "recents" -> { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); "ok" }
                "wait" -> { Thread.sleep(a.optLong("ms", 1500)); "ok" }
                "find_app" -> {
                    val q = a.optString("name").lowercase()
                    val pm = svc.packageManager
                    val r = pm.getInstalledApplications(0)
                        .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                        .filter { pm.getApplicationLabel(it).toString().lowercase().contains(q) || it.packageName.contains(q) }
                        .take(10)
                        .joinToString("\n") { pm.getApplicationLabel(it).toString() + " = " + it.packageName }
                    if (r.isBlank()) "none found" else r
                }
                "open_app" -> {
                    val i = svc.packageManager.getLaunchIntentForPackage(a.optString("pkg"))
                    if (i == null) "app not found" else {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        svc.startActivity(i)
                        Thread.sleep(2500)
                        "opened"
                    }
                }
                "list_dir" -> {
                    val l = File(path(a)).listFiles()
                    if (l == null) "cannot list (no permission or not a folder)" else
                        l.sortedBy { it.name }.joinToString("\n") {
                            (if (it.isDirectory) "[dir] " else "") + it.name + (if (it.isFile) " " + it.length() + "B" else "")
                        }.take(4000)
                }
                "read_file" -> File(path(a)).readText().take(8000)
                "write_file" -> {
                    val f = File(path(a))
                    f.parentFile?.mkdirs()
                    if (f.exists()) f.copyTo(File(f.path + ".bak"), true)
                    f.writeText(a.optString("content"))
                    "written " + f.path
                }
                "edit_file" -> {
                    val f = File(path(a))
                    val s = f.readText()
                    val old = a.optString("old")
                    if (!s.contains(old)) "old text not found" else {
                        f.copyTo(File(f.path + ".bak"), true)
                        f.writeText(s.replaceFirst(old, a.optString("new")))
                        "edited " + f.path
                    }
                }
                else -> "unknown action " + t
            }
        } catch (e: Exception) {
            "ERROR: " + e.message
        }
    }
}
