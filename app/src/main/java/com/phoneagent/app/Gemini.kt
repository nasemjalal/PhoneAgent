package com.phoneagent.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Gemini {

    const val SYSTEM = """You are an agent that controls an Android phone. The user writes Arabic; write "thought" and "msg" in Arabic.
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
{"action":"list_dir","path":".."} {"action":"read_file","path":".."}
{"action":"write_file","path":"..","content":".."} {"action":"edit_file","path":"..","old":"..","new":".."}
{"action":"done","msg":".."}   when finished or impossible
Files live under /storage/emulated/0/ . For code tasks (Minecraft mods, KubeJS, plugins, scripts) write files directly instead of typing inside games. Look at a file before editing it. Never delete or overwrite things the user did not ask about. Never enter passwords, payment or banking details."""

    fun turn(role: String, text: String, img: String? = null): JSONObject {
        val parts = JSONArray().put(JSONObject().put("text", text))
        if (img != null) {
            parts.put(JSONObject().put("inline_data",
                JSONObject().put("mime_type", "image/jpeg").put("data", img)))
        }
        return JSONObject().put("role", role).put("parts", parts)
    }

    fun call(key: String, model: String, turns: List<JSONObject>): String {
        val body = JSONObject()
        body.put("system_instruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
        body.put("contents", JSONArray(turns))
        body.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

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
                val parts = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                val sb = StringBuilder()
                for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
                return sb.toString()
            }
            lastErr = "HTTP " + code + ": " + txt.take(300)
            if (code != 429 && code != 503) break
            Thread.sleep(5000)
        }
        throw Exception(lastErr)
    }
}
