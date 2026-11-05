package com.phoneagent.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import org.json.JSONObject
import java.io.File

object Agent {

    @Volatile var running = false
    val log = StringBuffer()
    private val history = ArrayList<JSONObject>()

    fun say(s: String) {
        if (log.length > 20000) log.delete(0, 5000)
        log.append(s).append("\n")
    }

    fun stop() {
        if (running) {
            running = false
            say("⛔ تم الإيقاف")
        }
    }

    fun start(key: String, model: String, goal: String) {
        val svc = AgentService.instance
        if (svc == null) { say("⚠️ فعّل خدمة التحكم أولاً"); return }
        if (key.isBlank() || goal.isBlank()) { say("⚠️ اكتب المفتاح والأمر"); return }
        if (running) return
        running = true
        history.clear()
        say("🎯 " + goal)
        Thread {
            try { loop(svc, key, model, goal) }
            catch (e: Exception) { say("❌ " + e.message) }
            finally { running = false }
        }.start()
    }

    private fun parse(t: String): JSONObject {
        val s = t.indexOf('{')
        val e = t.lastIndexOf('}')
        return JSONObject(t.substring(s, e + 1))
    }

    private fun loop(svc: AgentService, key: String, model: String, goal: String) {
        history.add(Gemini.turn("user", "Task: " + goal))
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        Thread.sleep(1000)
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
            val th = a.optString("thought")
            if (th.isNotBlank()) say("💭 " + th)
            if (a.optString("action") == "done") { say("✅ " + a.optString("msg")); return }
            say("▶ " + a.toString().take(160))
            val res = exec(svc, a)
            say("   → " + res.take(150))
            history.add(Gemini.turn("user", "Result: " + res))
            while (history.size > 40) history.removeAt(1)
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
