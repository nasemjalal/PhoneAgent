package com.phoneagent.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast

// Transparent screen that listens to the user, then hands the text to the agent
class VoiceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Voice.stopSpeaking()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-IQ")
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "تكلم...")
        try {
            startActivityForResult(i, 7)
        } catch (e: Exception) {
            Toast.makeText(this, "خدمة التعرف على الصوت غير متوفرة", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (resultCode == RESULT_OK && !t.isNullOrBlank()) {
            Agent.send(this, t, emptyList(), true)
        }
        finish()
    }
}
