package com.phoneagent.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Natural human voice from the Gemini speech model.
// Throws on any error so Voice.kt can fall back to the phone's own voice.
object GeminiVoice {

    @Volatile private var track: AudioTrack? = null

    fun stop() {
        try {
            track?.stop()
            track?.release()
        } catch (e: Exception) {
        }
        track = null
    }

    // returns true when the text was played (or cancelled by a newer sentence)
    fun play(c: Context, text: String, alive: () -> Boolean): Boolean {
        val sp = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val key = (sp.getString("key", "") ?: "").trim()
        if (key.isBlank()) return false
        val model = (sp.getString("ttsmodel", "gemini-3.8-flash-tts") ?: "gemini-3.8-flash-tts").trim()
        val voice = (sp.getString("voice_name", "Kore") ?: "Kore").trim()

        val body = JSONObject()
        body.put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(
            JSONObject().put("text", "Say in a warm, natural, friendly tone: " + text.take(1500))))))
        body.put("generationConfig", JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig",
                JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))

        val cn = URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
            .openConnection() as HttpURLConnection
        cn.requestMethod = "POST"
        cn.setRequestProperty("Content-Type", "application/json")
        cn.setRequestProperty("x-goog-api-key", key)
        cn.doOutput = true
        cn.connectTimeout = 15000
        cn.readTimeout = 60000
        cn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = cn.responseCode
        val txt = (if (code in 200..299) cn.inputStream else cn.errorStream).bufferedReader().readText()
        if (code !in 200..299) throw Exception("HTTP " + code)

        val parts = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        var data = ""
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            val inl = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data")
            if (inl != null) data = inl.optString("data")
        }
        if (data.isBlank()) throw Exception("no audio")
        val pcm = Base64.decode(data, Base64.DEFAULT)
        if (!alive()) return true

        // the model returns raw 16-bit mono PCM at 24 kHz
        val at = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(24000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track = at
        at.write(pcm, 0, pcm.size)
        at.play()
        val end = System.currentTimeMillis() + pcm.size / 2 * 1000L / 24000 + 400
        while (System.currentTimeMillis() < end && alive()) Thread.sleep(80)
        if (track === at) stop()
        return true
    }
}
