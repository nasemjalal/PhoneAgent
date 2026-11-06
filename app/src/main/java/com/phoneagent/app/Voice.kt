package com.phoneagent.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

object Voice {
    @Volatile var always = false
    @Volatile var speaking = false
    @Volatile var since = 0L
    @Volatile private var gen = 0
    @Volatile private var lastWarn = 0L
    private var app: Context? = null
    private var tts: TextToSpeech? = null
    private var ready = false

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) {
                for (loc in listOf(Locale("ar", "IQ"), Locale("ar"), Locale("ar", "SA"), Locale("ar", "EG"))) {
                    val r = tts?.setLanguage(loc) ?: TextToSpeech.ERROR
                    if (r == TextToSpeech.LANG_AVAILABLE || r == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                        r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) {
                        ready = true
                        break
                    }
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { speaking = true }
                    override fun onDone(utteranceId: String?) { speaking = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { speaking = false }
                })
            }
        }
    }

    private fun warn(m: String) {
        val now = System.currentTimeMillis()
        if (now - lastWarn > 60000) {
            lastWarn = now
            Agent.say(m)
        }
    }

    fun speak(t: String): Boolean {
        val c = app ?: return false
        val my = ++gen
        GeminiVoice.stop()
        tts?.stop()
        speaking = true
        since = System.currentTimeMillis()
        Thread {
            var played = false
            try {
                played = GeminiVoice.play(c, t) { my == gen }
            } catch (e: Exception) {
                warn("⚠️ صوت Gemini ما اشتغل (" + (e.message ?: "").take(60) + ")، أستخدم صوت الجوال")
            }
            if (my != gen) return@Thread
            if (played) {
                speaking = false
            } else if (ready) {
                val r = tts?.speak(t.take(3500), TextToSpeech.QUEUE_FLUSH, null, "agent")
                if (r != TextToSpeech.SUCCESS) speaking = false
            } else {
                speaking = false
                warn("⚠️ ما فيه صوت عربي بالجهاز. ثبّته من إعدادات الجوال، النطق (Google)")
            }
        }.start()
        return true
    }

    fun stopSpeaking() {
        gen++
        speaking = false
        GeminiVoice.stop()
        tts?.stop()
    }
}
