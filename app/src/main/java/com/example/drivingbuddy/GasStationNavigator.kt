package com.example.drivingbuddy

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single blink -> find the closest gas station -> start Google Maps
 * turn-by-turn navigation to it immediately. The user never picks anything.
 *
 * Speed:
 *  - Location: uses the last known position instantly, waits max 3 s otherwise.
 *  - Stations: asks several OpenStreetMap (Overpass) servers AT THE SAME TIME
 *    and uses the first answer. Hard limit of [LOOKUP_TIMEOUT_MS].
 *
 * Navigation is started with google.navigation:q=<lat>,<lon>&mode=d, which goes
 * straight into driving navigation from the current location (no list, no Start button).
 */
object GasStationNavigator {

    private const val TAG = "GasStationNavigator"
    private const val COOLDOWN_MS = 30_000L
    private const val LOCATION_TIMEOUT_MS = 3_000L
    private const val LOOKUP_TIMEOUT_MS = 6_000L
    private const val SEARCH_RADIUS_METERS = 15_000

    // Public Overpass servers; all are queried in parallel, first good answer wins.
    private val OVERPASS_SERVERS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    data class Station(val name: String, val lat: Double, val lon: Double, val distanceMeters: Float)

    private var lastLaunch = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Starts the lookup. [onResult] runs on the main thread with the station Maps
     * is navigating to, or null if no station could be found (Maps is NOT opened then).
     * Returns false if skipped because of the cooldown.
     */
    fun navigate(context: Context, onResult: (Station?) -> Unit = {}): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (lastLaunch != 0L && now - lastLaunch < COOLDOWN_MS) {
            Log.d(TAG, "Skipped: cooldown active")
            return false
        }
        lastLaunch = now

        val appContext = context.applicationContext
        val t0 = SystemClock.elapsedRealtime()

        if (!hasLocationPermission(appContext)) {
            Log.w(TAG, "No location permission")
            lastLaunch = 0L
            onResult(null)
            return true
        }

        getLocation(appContext) { location ->
            Log.d(TAG, "Location after ${SystemClock.elapsedRealtime() - t0} ms: $location")
            if (location == null) {
                lastLaunch = 0L
                onResult(null)
                return@getLocation
            }

            findNearestStation(location) { station ->
                Log.d(TAG, "Lookup done after ${SystemClock.elapsedRealtime() - t0} ms: $station")
                if (station != null && startNavigation(appContext, station)) {
                    onResult(station)
                } else {
                    lastLaunch = 0L  // allow an immediate retry
                    onResult(null)
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // Location (fast)
    // ------------------------------------------------------------------

    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun getLocation(context: Context, callback: (Location?) -> Unit) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        val cached = providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

        // Anything from the last 10 minutes is good enough to find the nearest station.
        if (cached != null && System.currentTimeMillis() - cached.time < 10 * 60_000) {
            callback(cached)
            return
        }

        val provider = providers.firstOrNull { it != LocationManager.PASSIVE_PROVIDER }
        if (provider == null) {
            callback(cached)
            return
        }

        val done = AtomicBoolean(false)
        val timeout = Runnable { if (done.compareAndSet(false, true)) callback(cached) }
        mainHandler.postDelayed(timeout, LOCATION_TIMEOUT_MS)

        lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(context)) { loc ->
            if (done.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(timeout)
                callback(loc ?: cached)
            }
        }
    }

    // ------------------------------------------------------------------
    // Station lookup: all servers in parallel, first good answer wins
    // ------------------------------------------------------------------

    private fun findNearestStation(here: Location, callback: (Station?) -> Unit) {
        val query = """
            [out:json][timeout:5];
            nwr["amenity"="fuel"](around:$SEARCH_RADIUS_METERS,${here.latitude},${here.longitude});
            out center;
        """.trimIndent()

        val finished = AtomicBoolean(false)
        val pending = AtomicInteger(OVERPASS_SERVERS.size)
        val calls = mutableListOf<Call>()

        fun finish(station: Station?) {
            if (!finished.compareAndSet(false, true)) return
            calls.forEach { it.cancel() }          // stop the slower servers
            mainHandler.post { callback(station) }
        }

        // Hard overall limit.
        mainHandler.postDelayed({ finish(null) }, LOOKUP_TIMEOUT_MS + 500)

        for (url in OVERPASS_SERVERS) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "DrivingBuddy/1.0 (Android)")
                .post(FormBody.Builder().add("data", query).build())
                .build()

            val call = http.newCall(request)
            calls += call
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val nearest = try {
                            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                            parseStations(it.body!!.string(), here).minByOrNull { s -> s.distanceMeters }
                        } catch (e: Exception) {
                            Log.w(TAG, "$url failed: ${e.message}")
                            null
                        }
                        Log.d(TAG, "$url answered: $nearest")
                        if (nearest != null) finish(nearest)
                        else if (pending.decrementAndGet() == 0) finish(null)
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (!call.isCanceled()) Log.w(TAG, "$url failed: ${e.message}")
                    if (pending.decrementAndGet() == 0) finish(null)
                }
            })
        }
    }

    private fun parseStations(json: String, here: Location): List<Station> {
        val elements = JSONObject(json).getJSONArray("elements")
        val result = mutableListOf<Station>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            // Nodes have lat/lon, ways/relations have a "center" object.
            val point = if (el.has("lat")) el else el.optJSONObject("center") ?: continue
            val lat = point.getDouble("lat")
            val lon = point.getDouble("lon")

            val tags = el.optJSONObject("tags")
            val name = tags?.optString("name")?.takeIf { it.isNotBlank() }
                ?: tags?.optString("brand")?.takeIf { it.isNotBlank() }
                ?: "gas station"

            val dist = FloatArray(1)
            Location.distanceBetween(here.latitude, here.longitude, lat, lon, dist)
            result += Station(name, lat, lon, dist[0])
        }
        return result
    }

    // ------------------------------------------------------------------
    // Start navigation (no user input)
    // ------------------------------------------------------------------

    private fun startNavigation(context: Context, station: Station): Boolean {
        val navUri = Uri.parse("google.navigation:q=${station.lat},${station.lon}&mode=d")
        Log.d(TAG, "Starting navigation: $navUri (${station.name})")

        val mapsIntent = Intent(Intent.ACTION_VIEW, navUri)
            .setPackage("com.google.android.apps.maps")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(mapsIntent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Google Maps is not installed")
            false
        }
    }
}
