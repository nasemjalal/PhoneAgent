package com.phoneagent.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

object Voice {
    @Volatile var always = false
    @Volatile var speaking = false
    @Volatile var since = 0L
    private var tts: TextToSpeech? = null
    private var ready = false

    fun init(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("ar")) ?: TextToSpeech.ERROR
                ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED && r != TextToSpeech.ERROR
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { speaking = true }
                    override fun onDone(utteranceId: String?) { speaking = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { speaking = false }
                })
            }
        }
    }

    fun speak(t: String): Boolean {
        if (!ready) return false
        speaking = true
        since = System.currentTimeMillis()
        val r = tts?.speak(t.take(3500), TextToSpeech.QUEUE_FLUSH, null, "agent")
        if (r != TextToSpeech.SUCCESS) {
            speaking = false
            return false
        }
        return true
    }

    fun stopSpeaking() {
        speaking = false
        tts?.stop()
    }
}
