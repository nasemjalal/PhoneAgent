package com.phoneagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
        @Volatile var instance: VoiceService? = null
    }

    private val h = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopLive()
            return START_NOT_STICKY
        }
        goForeground()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "التعرف على الصوت غير متوفر بالجهاز", Toast.LENGTH_LONG).show()
            stopLive()
            return START_NOT_STICKY
        }
        live = true
        instance = this
        Voice.init(this)
        AgentService.instance?.refreshBubble()
        setup()
        listen()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("live", "المحادثة الصوتية", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, VoiceService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, "live")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("PhoneAgent يسمعك")
            .setContentText("اضغط إيقاف لإنهاء المحادثة الصوتية")
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "إيقاف", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(11, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(11, n)
        }
    }

    private fun setup() {
        sr?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                if (!live) return
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) Agent.send(this@VoiceService, t, emptyList(), true)
                h.postDelayed({ listen() }, 700)
            }

            override fun onError(error: Int) {
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
        // do not listen while the agent is working or speaking (it would hear itself)
        val talking = Voice.speaking && System.currentTimeMillis() - Voice.since < 90000
        if (Agent.running || talking) {
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
        instance = null
        h.removeCallbacksAndMessages(null)
        Voice.stopSpeaking()
        sr?.destroy()
        sr = null
        AgentService.instance?.refreshBubble()
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
