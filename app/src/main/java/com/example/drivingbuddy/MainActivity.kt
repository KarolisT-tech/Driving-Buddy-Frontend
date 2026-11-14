package com.example.drivingbuddy

import android.Manifest
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var speaker: Speaker
    private lateinit var blinkClient: BlinkClient
    private lateinit var drowsinessAlarm: DrowsinessAlarm
    private var alarmDialog: AlertDialog? = null

    private val smsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* granted or not */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        smsPermission.launch(Manifest.permission.RECEIVE_SMS)

        speaker = Speaker(this)
        drowsinessAlarm = DrowsinessAlarm(this)
        speaker.speak("Hello, this is a test.")

        // Emulator: "ws://10.0.2.2:8765"
        // Real phone over USB (adb reverse tcp:8765 tcp:8765): "ws://127.0.0.1:8765"
        // Real phone over Wi-Fi: "ws://<PC_IP>:8765"
        blinkClient = BlinkClient(
            url = "ws://10.18.91.234:8765",
            onDoubleBlink = { readMessages() },
            onDrowsy = { triggerDrowsinessAlarm() }
        )
        blinkClient.start()

        // Keep tap as manual backup while testing
        findViewById<View>(R.id.main).setOnClickListener { readMessages() }
    }

    private fun triggerDrowsinessAlarm() {
        if (isFinishing || isDestroyed) return
        if (alarmDialog?.isShowing == true) return

        speaker.stop()
        drowsinessAlarm.start()
        speaker.speak("Wake up! Drowsiness detected.")

        alarmDialog = AlertDialog.Builder(this)
            .setTitle("DROWSINESS DETECTED!")
            .setMessage("You appear to be falling asleep. Stay focused on the road!")
            .setCancelable(false)
            .setPositiveButton("I'M AWAKE - STOP ALARM") { dialog, _ ->
                drowsinessAlarm.stop()
                dialog.dismiss()
            }
            .show()
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
        alarmDialog?.dismiss()
        drowsinessAlarm.stop()
        blinkClient.stop()
        speaker.shutdown()
        super.onDestroy()
    }
}