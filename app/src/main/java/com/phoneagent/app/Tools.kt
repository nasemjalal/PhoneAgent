package com.phoneagent.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import org.json.JSONObject
import java.io.File

object Tools {

    private fun path(a: JSONObject): String {
        var p = a.optString("path")
        if (!p.startsWith("/")) p = "/storage/emulated/0/" + p.removePrefix("~/")
        return p
    }

    fun exec(svc: AgentService, a: JSONObject): String {
        val (w, h) = svc.screenSize()
        fun px(k: String, dim: Int) = (a.optDouble(k, 500.0) / 1000.0 * dim).toFloat()
        val act0 = a.optString("action")
        if ((act0 == "tap" || act0 == "long_press" || act0 == "swipe") && (!a.has("x") || !a.has("y")))
            return "ERROR: missing x or y, send the action again with both"
        if (act0 == "swipe" && (!a.has("x2") || !a.has("y2")))
            return "ERROR: missing x2 or y2, send the action again with both"
        return try {
            when (val t = act0) {
                "tap" -> if (svc.gesture(px("x", w), px("y", h), px("x", w), px("y", h), 50)) "ok" else "gesture failed"
                "long_press" -> if (svc.gesture(px("x", w), px("y", h), px("x", w), px("y", h), 900)) "ok" else "gesture failed"
                "swipe" -> if (svc.gesture(px("x", w), px("y", h), px("x2", w), px("y2", h), a.optLong("ms", 300))) "ok" else "gesture failed"
                "type" -> if (svc.typeText(a.optString("text"))) "ok" else "no focused input field"
                "back" -> { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); "ok" }
                "home" -> { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); "ok" }
                "recents" -> { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); "ok" }
                "wait" -> { Thread.sleep(a.optLong("ms", 1000)); "ok" }
                "look" -> "ok"
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
                        Thread.sleep(1500)
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
