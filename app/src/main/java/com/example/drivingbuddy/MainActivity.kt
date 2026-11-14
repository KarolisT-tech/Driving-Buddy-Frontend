package com.example.drivingbuddy

import android.Manifest
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * SPEECH-ONLY TEST VERSION (no server, no headset).
 *  - On start it says a test sentence.
 *  - A received SMS is stored by SmsReceiver.
 *  - Tap the screen to hear the stored messages (stands in for the double blink).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var speaker: Speaker
    private lateinit var blinkClient: BlinkClient

    private val smsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* granted or not */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        smsPermission.launch(Manifest.permission.RECEIVE_SMS)

        speaker = Speaker(this)
        speaker.speak("Hello, this is a test.")

        // Change the port/path (e.g. :8765 or :8000/ws) to match your main.py!
        blinkClient = BlinkClient("ws://10.0.2.2:8765") {
            readMessages()
        }
        blinkClient.start()

        // Keep tap as manual backup while testing
        findViewById<View>(R.id.main).setOnClickListener { readMessages() }
    }

    private fun readMessages() {
        val messages = MessageStore.takeAll()
        if (messages.isEmpty()) {
            speaker.speak("No new messages.")
        } else {
            messages.forEach { speaker.speak(it) }
        }
    }

    override fun onDestroy() {
        blinkClient.stop()
        speaker.shutdown()
        super.onDestroy()
    }
}