package com.phoneagent.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class Att(val name: String, val mime: String, val b64: String?, val text: String?, val path: String?)

object Gemini {

    const val SYSTEM = """You are a friendly personal assistant living inside the user's Android phone. Talk like a natural Arabic speaker (Iraqi dialect is fine). Your "msg" is always read aloud, so write it exactly as you would speak it: plain text, no markdown, no lists, no emojis, no symbols.
When the user just wants to talk, be a real conversation partner: warm, natural, curious, with personality and a little humor. Answer the actual question fully but conversationally (usually 2 to 5 spoken sentences, longer only if asked), react to what they said, give your honest opinion when asked, and sometimes ask a short follow-up question. Never sound like a robot or a command log. Remember everything said earlier in the conversation. Answer chat messages immediately with the done action. After finishing a phone task, say in a friendly way what you did. If the task needs the phone, act step by step.
Each turn you get the current screen (screenshot + list of UI elements) and must reply with ONE JSON object only.
Coordinates x,y are 0-1000 (0,0 = top-left, 1000,1000 = bottom-right). UI lines look like [x,y] text.
Actions:
{"thought":"..","action":"tap","x":500,"y":500}
{"action":"long_press","x":..,"y":..}
{"action":"swipe","x":..,"y":..,"x2":..,"y2":..,"ms":400}
{"action":"type","text":".."}   types into the focused input field (tap the field first)
{"action":"back"} {"action":"home"} {"action":"recents"}
{"action":"find_app","name":".."}   lists installed apps matching a name, with package ids
{"action":"open_app","pkg":".."}
{"action":"wait","ms":1500}
{"action":"look"}   shows you the current screen (the first step has no screenshot)
{"action":"list_dir","path":".."} {"action":"read_file","path":".."}
{"action":"write_file","path":"..","content":".."} {"action":"edit_file","path":"..","old":"..","new":".."}
{"action":"done","msg":".."}   final answer to the user in Arabic; use it also when finished or impossible
The PhoneAgent app itself (its Live or chat screen) is your own interface: never tap inside it, use open_app or home to reach other apps.
Files live under /storage/emulated/0/ . Files the user attaches are saved in /storage/emulated/0/Download/PhoneAgent/ and described in the message. For code tasks (Minecraft mods, KubeJS, plugins, scripts) write files directly instead of typing inside games. Look at a file before editing it. Never delete or overwrite things the user did not ask about. Never enter passwords, payment or banking details."""

    private fun inline(m: String, d: String): JSONObject =
        JSONObject().put("inline_data", JSONObject().put("mime_type", m).put("data", d))

    fun turn(role: String, text: String, img: String? = null, atts: List<Att> = emptyList()): JSONObject {
        val parts = JSONArray().put(JSONObject().put("text", text))
        if (img != null) parts.put(inline("image/jpeg", img))
        for (a in atts) if (a.b64 != null) parts.put(inline(a.mime, a.b64))
        return JSONObject().put("role", role).put("parts", parts)
    }

    private fun schema(): JSONObject {
        val p = JSONObject()
        for (k in listOf("thought", "text", "pkg", "name", "path", "content", "old", "new", "msg"))
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
            .put("propertyOrdering", JSONArray(listOf("thought", "action", "x", "y", "x2", "y2", "ms",
                "text", "pkg", "name", "path", "content", "old", "new", "msg")))
            .put("required", JSONArray().put("action"))
    }

    fun call(key: String, model: String, turns: List<JSONObject>): String {
        val body = JSONObject()
        body.put("system_instruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
        body.put("contents", JSONArray(turns))
        body.put("generationConfig", JSONObject()
            .put("responseMimeType", "application/json")
            .put("responseSchema", schema())
            .put("maxOutputTokens", 4096))

        var lastErr = ""
        for (attempt in 1..6) {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
                .openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.doOutput = true
            c.connectTimeout = 20000
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
                lastErr = "رد فارغ من Gemini: " + (cand?.optString("finishReason") ?: "") + " " + (j.optJSONObject("promptFeedback")?.toString() ?: "")
                Thread.sleep(2000)
                continue
            }
            lastErr = "HTTP " + code + ": " + txt.take(300)
            if (code != 429 && code != 503) break
            Thread.sleep(5000)
        }
        throw Exception(lastErr)
    }
}
