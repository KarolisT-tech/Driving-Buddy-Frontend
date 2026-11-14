package com.example.drivingbuddy

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/** Small wrapper around Android's text-to-speech. */
class Speaker(context: Context) {

    // Voice language. Default = phone language. For Lithuanian use:
    // Locale.forLanguageTag("lt-LT")   (the voice must be installed on the phone)
    private val locale: Locale = Locale.getDefault()

    private var ready = false
    private val waiting = mutableListOf<String>()   // texts requested before TTS was ready

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(locale)
            ready = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ready) {
                Log.w("Speaker", "Language $locale not available. Install the voice in Settings > Text-to-speech.")
            }
            waiting.forEach { say(it) }
            waiting.clear()
        } else {
            Log.e("Speaker", "TextToSpeech init failed: $status")
        }
    }

    /** Speaks the text after anything already being spoken. */
    fun speak(text: String) {
        if (ready) say(text) else waiting.add(text)
    }

    /** Stops speaking immediately. */
    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private fun say(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "msg_${System.nanoTime()}")
    }
}
