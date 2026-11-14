package com.example.drivingbuddy

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Plays a looping alarm sound (using Android's Alarm stream) and vibrates
 * until [stop] is called. Falls back to ToneGenerator if no alarm ringtone exists.
 */
class DrowsinessAlarm(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var beepRunnable: Runnable? = null

    var isPlaying: Boolean = false
        private set

    fun start() {
        if (isPlaying) return
        isPlaying = true
        startAudio()
        startVibration()
    }

    fun stop() {
        if (!isPlaying) return
        isPlaying = false

        beepRunnable?.let { handler.removeCallbacks(it) }
        beepRunnable = null

        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null

        try {
            toneGenerator?.stopTone()
            toneGenerator?.release()
        } catch (_: Exception) {
        }
        toneGenerator = null

        try {
            getVibrator()?.cancel()
        } catch (_: Exception) {
        }
    }

    private fun startAudio() {
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        if (alarmUri != null) {
            try {
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(context.applicationContext, alarmUri)
                    isLooping = true
                    prepare()
                    start()
                }
                return
            } catch (e: Exception) {
                Log.w("DrowsinessAlarm", "MediaPlayer failed, using ToneGenerator fallback", e)
                try {
                    mediaPlayer?.release()
                } catch (_: Exception) {
                }
                mediaPlayer = null
            }
        }

        // Fallback: works even on emulators with no alarm sound file
        try {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            toneGenerator = tg
            val runnable = object : Runnable {
                override fun run() {
                    if (!isPlaying) return
                    tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 450)
                    handler.postDelayed(this, 650)
                }
            }
            beepRunnable = runnable
            handler.post(runnable)
        } catch (e: Exception) {
            Log.e("DrowsinessAlarm", "Fallback ToneGenerator failed", e)
        }
    }

    private fun startVibration() {
        try {
            val vibrator = getVibrator() ?: return
            val pattern = longArrayOf(0, 500, 300) // vibrate 500ms, pause 300ms, repeat
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } catch (e: Exception) {
            Log.w("DrowsinessAlarm", "Vibration failed", e)
        }
    }

    private fun getVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}