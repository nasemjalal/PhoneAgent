package com.phoneagent.app

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast

// Hands-free "live" conversation: listen -> agent -> speak -> listen again
class VoiceService : Service() {

    companion object {
        @Volatile var live = false
        @Volatile var muted = false
        @Volatile var listening = false
        @Volatile var instance: VoiceService? = null
    }

    private val h = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> { stopLive(); return START_NOT_STICKY }
            "MUTE" -> { toggleMute(); return START_NOT_STICKY }
        }
        val n = LiveNotif.build(this, muted)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(11, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(11, n)
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "التعرف على الصوت غير متوفر بالجهاز", Toast.LENGTH_LONG).show()
            stopLive()
            return START_NOT_STICKY
        }
        live = true
        muted = false
        instance = this
        Voice.init(this)
        setup()
        listen()
        return START_NOT_STICKY
    }

    fun toggleMute() {
        if (!live) return
        muted = !muted
        if (muted) {
            sr?.cancel()
            listening = false
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(11, LiveNotif.build(this, muted))
    }

    private fun setup() {
        sr?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                listening = false
                if (!live) return
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) Agent.send(this@VoiceService, t, emptyList(), true)
                h.postDelayed({ listen() }, 700)
            }

            override fun onError(error: Int) {
                listening = false
                if (!live) return
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        Toast.makeText(this@VoiceService, "الميكروفون غير مسموح", Toast.LENGTH_LONG).show()
                        stopLive()
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT ->
                        h.postDelayed({ setup(); listen() }, 1200)
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        h.postDelayed({ listen() }, 3000)
                    else -> h.postDelayed({ listen() }, 500)
                }
            }
        })
        sr = r
    }

    private fun listen() {
        if (!live) return
        // do not listen while muted, while the agent works, or while it speaks (it would hear itself)
        val talking = Voice.speaking && System.currentTimeMillis() - Voice.since < 90000
        if (muted || Agent.running || talking) {
            h.postDelayed({ listen() }, 600)
            return
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-IQ")
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        try {
            sr?.startListening(i)
        } catch (e: Exception) {
            h.postDelayed({ setup(); listen() }, 1500)
        }
    }

    fun stopLive() {
        live = false
        muted = false
        listening = false
        instance = null
        h.removeCallbacksAndMessages(null)
        Voice.stopSpeaking()
        sr?.destroy()
        sr = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        live = false
        instance = null
        h.removeCallbacksAndMessages(null)
        sr?.destroy()
        super.onDestroy()
    }
}
