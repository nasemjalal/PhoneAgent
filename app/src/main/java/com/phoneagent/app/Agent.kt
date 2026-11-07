package com.phoneagent.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
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
        if (speak || Voice.always) Voice.speak(txt)
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
        if (voice) Thread.sleep(200)
        for (step in 1..30) {
            if (!running) return
            val (w, h) = svc.screenSize()
            val first = step == 1
            val obs = if (first)
                "Step 1. Screen " + w + "x" + h + ". (No screenshot yet. If your task needs the screen, reply with the action look. Otherwise just answer.)"
            else
                "Step " + step + ". Screen " + w + "x" + h + ".\n" + svc.dumpScreen()
            val turns = ArrayList(history)
            turns.add(Gemini.turn("user", obs, if (first) null else svc.screenshotB64()))
            val raw = Gemini.call(key, model, turns)
            if (!running) return
            history.add(Gemini.turn("user", obs.take(200)))
            history.add(Gemini.turn("model", raw))
            val a = try { parse(raw) } catch (e: Exception) {
                say("⚠️ رد غير مفهوم، أعيد المحاولة")
                history.add(Gemini.turn("user", "Invalid JSON. Reply with ONE JSON object only."))
                continue
            }
            val th = a.optString("thought")
            if (th.isNotBlank()) say("💭 " + th)

            var list = a.optJSONArray("actions")
            if (list == null) {
                list = JSONArray()
                if (a.has("action")) list.put(a)
            }
            val results = StringBuilder()
            var n = 0
            for (i in 0 until minOf(list.length(), 6)) {
                if (!running) return
                val act = list.getJSONObject(i)
                val kind = act.optString("action")
                if (kind == "done") { reply(act.optString("msg"), voice); return }
                say("▶ " + act.toString().take(120))
                val res = Tools.exec(svc, act)
                n++
                results.append(n).append(") ").append(res.take(300)).append("\n")
                say("   → " + res.take(100))
                // a failed action cancels the rest of the batch
                if (res.startsWith("ERROR") || res.startsWith("missing") || res.contains("failed") ||
                    res.contains("not found") || res.contains("no focused")) break
                if (kind in setOf("tap", "long_press", "swipe", "type", "back", "home", "recents")) Thread.sleep(250)
            }
            if (n == 0) results.append("no actions were sent")
            history.add(Gemini.turn("user", "Results:\n" + results))
            while (history.size > 30) history.removeAt(1)
            Thread.sleep(300)
        }
        say("⏹ وصلت الحد الأقصى (30 خطوة)")
    }
}
