package com.example.drivingbuddy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/** Receives incoming SMS and stores them. It does NOT speak: speaking waits for the double blink. */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages[0].originatingAddress ?: "unknown number"
        // long SMS arrive in several parts, so join them
        val body = messages.joinToString("") { it.messageBody ?: "" }

        MessageStore.add("Message from $sender: $body")
        Log.d("SmsReceiver", "Stored message, ${MessageStore.count()} waiting")
    }
}
