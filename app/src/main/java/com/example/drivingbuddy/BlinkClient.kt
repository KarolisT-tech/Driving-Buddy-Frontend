package com.example.drivingbuddy

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Connects to the Python server (main.py) over a WebSocket and calls
 * [onDoubleBlink] on the main thread whenever the server sends "double_blink".
 * Reconnects automatically if the connection drops.
 */
class BlinkClient(
    private val url: String,
    private val onDoubleBlink: () -> Unit
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)      // notices a dead connection
        .build()
    private val handler = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var running = false
    private var retryScheduled = false

    fun start() {
        running = true
        connect()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        socket?.close(1000, null)
        socket = null
    }

    private fun connect() {
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("BlinkClient", "Connected to $url")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "double_blink") handler.post { onDoubleBlink() }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("BlinkClient", "Connection failed: ${t.message}")
                retry()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("BlinkClient", "Closed")
                retry()
            }
        })
    }

    private fun retry() {
        if (!running || retryScheduled) return
        retryScheduled = true
        handler.postDelayed({
            retryScheduled = false
            if (running) connect()
        }, 3000)
    }
}
