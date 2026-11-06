package com.phoneagent.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

object Voice {
    @Volatile var always = false
    private var tts: TextToSpeech? = null
    private var ready = false

    fun init(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("ar")) ?: TextToSpeech.ERROR
                ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED && r != TextToSpeech.ERROR
            }
        }
    }

    fun speak(t: String): Boolean {
        if (!ready) return false
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "agent")
        return true
    }

    fun stopSpeaking() { tts?.stop() }
}
