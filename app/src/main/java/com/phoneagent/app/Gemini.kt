package com.phoneagent.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class Att(val name: String, val mime: String, val b64: String?, val text: String?, val path: String?)

object Gemini {

    // 0 = thinkingLevel minimal, 1 = low, 2 = thinkingBudget 0, 3 = no thinking setting.
    // If the model rejects a mode, the next one is tried automatically and remembered.
    @Volatile private var thinkMode = 0

    private fun inline(m: String, d: String): JSONObject =
        JSONObject().put("inline_data", JSONObject().put("mime_type", m).put("data", d))

    fun turn(role: String, text: String, img: String? = null, atts: List<Att> = emptyList()): JSONObject {
        val parts = JSONArray().put(JSONObject().put("text", text))
        if (img != null) parts.put(inline("image/jpeg", img))
        for (a in atts) if (a.b64 != null) parts.put(inline(a.mime, a.b64))
        return JSONObject().put("role", role).put("parts", parts)
    }

    private fun itemSchema(): JSONObject {
        val p = JSONObject()
        for (k in listOf("text", "pkg", "name", "path", "content", "old", "new", "msg"))
            p.put(k, JSONObject().put("type", "STRING"))
        for (k in listOf("x", "y", "x2", "y2"))
            p.put(k, JSONObject().put("type", "NUMBER"))
        p.put("ms", JSONObject().put("type", "INTEGER"))
        val acts = JSONArray()
        for (a in listOf("tap", "long_press", "swipe", "type", "back", "home", "recents", "find_app",
            "open_app", "wait", "look", "list_dir", "read_file", "write_file", "edit_file", "done")) acts.put(a)
        p.put("action", JSONObject().put("type", "STRING").put("enum", acts))
        return JSONObject()
            .put("type", "OBJECT")
            .put("properties", p)
            .put("propertyOrdering", JSONArray(listOf("action", "x", "y", "x2", "y2", "ms",
                "text", "pkg", "name", "path", "content", "old", "new", "msg")))
            .put("required", JSONArray().put("action"))
    }

    private fun schema(): JSONObject {
        val p = JSONObject()
        p.put("thought", JSONObject().put("type", "STRING"))
        p.put("actions", JSONObject().put("type", "ARRAY").put("items", itemSchema()))
        return JSONObject()
            .put("type", "OBJECT")
            .put("properties", p)
            .put("propertyOrdering", JSONArray(listOf("thought", "actions")))
            .put("required", JSONArray().put("actions"))
    }

    fun call(key: String, model: String, turns: List<JSONObject>): String {
        var lastErr = ""
        for (attempt in 1..8) {
            val body = JSONObject()
            body.put("system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Prompt.SYSTEM))))
            body.put("contents", JSONArray(turns))
            val g = JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema())
                .put("maxOutputTokens", 4096)
            when (thinkMode) {
                0 -> g.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
                1 -> g.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
                2 -> g.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            }
            body.put("generationConfig", g)

            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
                .openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.doOutput = true
            c.connectTimeout = 15000
            c.readTimeout = 90000
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()

            if (code in 200..299) {
                val j = JSONObject(txt)
                val cand = j.optJSONArray("candidates")?.optJSONObject(0)
                val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
                val sb = StringBuilder()
                if (parts != null) for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
                if (sb.isNotBlank()) return sb.toString()
                lastErr = "رد فارغ من Gemini: " + (cand?.optString("finishReason") ?: "")
                Thread.sleep(1000)
                continue
            }

            lastErr = "HTTP " + code + ": " + txt.take(300)
            // the model does not accept this thinking setting -> try the next mode right away
            if (code == 400 && txt.contains("think", true) && thinkMode < 3) {
                thinkMode++
                continue
            }
            if (code != 429 && code != 503) break
            Thread.sleep(4000)
        }
        throw Exception(lastErr)
    }
}
