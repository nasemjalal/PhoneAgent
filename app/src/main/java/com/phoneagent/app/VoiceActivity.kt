package com.phoneagent.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

// Transparent helper: asks for the microphone once, then starts the live voice service
class VoiceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            go()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 5)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            go()
        } else {
            Toast.makeText(this, "لازم تسمح بالميكروفون عشان أسمعك", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun go() {
        Voice.stopSpeaking()
        startForegroundService(Intent(this, VoiceService::class.java))
        finish()
    }
}
