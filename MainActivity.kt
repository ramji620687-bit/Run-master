package com.runmaster.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.location.*
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var map: MapView
    private lateinit var distanceText: TextView
    private lateinit var timeText: TextView
    private lateinit var speedText: TextView
    private lateinit var paceText: TextView
    private lateinit var lapsText: TextView
    private lateinit var accuracyText: TextView
    private lateinit var statusText: TextView
    private lateinit var tts: TextToSpeech
    private lateinit var trackInput: EditText
    private lateinit var targetInput: EditText
    private lateinit var route: Polyline

    private val points = mutableListOf<GeoPoint>()
    private var running = false
    private var paused = false
    private var startedAt = 0L
    private var pausedAt = 0L
    private var pausedTotal = 0L
    private var distanceMeters = 0f
    private var lastLocation: Location? = null
    private var lapLength = 400f
    private var laps = 0
    private var lastAnnouncedLap = 0
    private var targetAnnounced = false
    private var autoPause = false
    private var lastLapDistance = 0f

    private val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
        .setMinUpdateIntervalMillis(700)
        .setMinUpdateDistanceMeters(1f)
        .build()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            accuracyText.text = "GPS accuracy ±${loc.accuracy.roundToInt()} m"
            if (!running || paused) return

            val previous = lastLocation
            if (previous != null) {
                val delta = previous.distanceTo(loc)
                // Ignore implausible GPS jumps to avoid corrupting distance.
                if (delta in 0.5f..35f && loc.accuracy <= 35f) {
                    distanceMeters += delta
                    points.add(GeoPoint(loc.latitude, loc.longitude))
                    route.setPoints(points)
                    map.invalidate()
                    updateLaps()
                }
            }
            lastLocation = loc
            map.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
            updateStats(loc)
            val target = targetInput.text.toString().toFloatOrNull()
            if (target != null && target > 0 && distanceMeters >= target * 1000 && !targetAnnounced) {
                targetAnnounced = true
                statusText.text = "🎯 लक्ष्य पूरा हुआ!"
                speak("Daily target complete")
            }
            if (autoPause && loc.speed < 0.45f && running && !paused) {
                pauseRun()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        fused = LocationServices.getFusedLocationProviderClient(this)
        tts = TextToSpeech(this, this)
        buildUi()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 100)
        } else centerOnCurrentLocation()
    }

    private fun buildUi() {
        val bg = Color.rgb(9, 11, 10)
        val card = Color.rgb(24, 28, 25)
        val lime = Color.rgb(183, 243, 74)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(16, 18, 16, 10)
        }
        val header = TextView(this).apply {
            text = "RUN MASTER   •   RUNNING"
            textSize = 20f; setTextColor(lime); gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 4, 4, 12)
        }
        root.addView(header)
        map = MapView(this).apply {
            setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(17.0)
            controller.setCenter(GeoPoint(23.0, 85.0))
        }
        root.addView(map, LinearLayout.LayoutParams(-1, 0, 1.15f))

        val stats = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 10, 0, 6) }
        val row1 = LinearLayout(this)
        distanceText = stat("0.00", "KM", lime)
        timeText = stat("00:00", "TIME", Color.WHITE)
        speedText = stat("0.00", "M/S", Color.WHITE)
        row1.addView(distanceText, weightParams()); row1.addView(timeText, weightParams()); row1.addView(speedText, weightParams())
        stats.addView(row1)
        val row2 = LinearLayout(this)
        paceText = stat("--:--", "MIN/KM", Color.WHITE)
        lapsText = stat("0", "LAPS", Color.WHITE)
        accuracyText = stat("GPS waiting", "ACCURACY", Color.LTGRAY)
        row2.addView(paceText, weightParams()); row2.addView(lapsText, weightParams()); row2.addView(accuracyText, weightParams())
        stats.addView(row2)
        root.addView(stats)

        statusText = TextView(this).apply { text = "Ready • GPS चालू करें"; setTextColor(Color.LTGRAY); textSize = 12f }
        root.addView(statusText)

        val inputs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        trackInput = EditText(this).apply {
            hint = "Track meters (400)"; setText("400"); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = 2; setSingleLine()
        }
        targetInput = EditText(this).apply {
            hint = "Target KM"; setText("2"); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = 8194; setSingleLine()
        }
        inputs.addView(trackInput, LinearLayout.LayoutParams(0, 52, 1f))
        inputs.addView(targetInput, LinearLayout.LayoutParams(0, 52, 1f))
        root.addView(inputs)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button("START", lime, Color.BLACK) { startRun() }, weightParams())
        buttons.addView(button("PAUSE", card, Color.WHITE) { pauseRun() }, weightParams())
        buttons.addView(button("RESUME", card, Color.WHITE) { resumeRun() }, weightParams())
        buttons.addView(button("STOP", Color.rgb(110, 38, 38), Color.WHITE) { stopRun() }, weightParams())
        root.addView(buttons)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("LAP", card, lime) { manualLap() }, weightParams())
        actions.addView(button("AUTO PAUSE", card, Color.WHITE) {
            autoPause = !autoPause
            statusText.text = if (autoPause) "Auto pause ON" else "Auto pause OFF"
        }, weightParams())
        actions.addView(button("CALIBRATE", card, Color.WHITE) { calibrate() }, weightParams())
        root.addView(actions)

        val history = button("SAVE / VIEW LAST RUN", card, Color.WHITE) { showLastRun() }
        root.addView(history)
        setContentView(root)
    }

    private fun stat(value: String, label: String, color: Int): TextView =
        TextView(this).apply {
            text = "$value\n$label"; textSize = 17f; setTextColor(color)
            gravity = Gravity.CENTER; setPadding(3, 10, 3, 10)
        }

    private fun weightParams() = LinearLayout.LayoutParams(0, -2, 1f)
    private fun button(label: String, color: Int, textColor: Int, action: () -> Unit) =
        Button(this).apply {
            text = label; textSize = 11f; setTextColor(textColor); setBackgroundColor(color)
            setOnClickListener { action() }
        }

    private fun startRun() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100); return
        }
        resetRun()
        running = true; startedAt = SystemClock.elapsedRealtime()
        statusText.text = "RUNNING • GPS tracking active"
        fused.requestLocationUpdates(request, callback, mainLooper)
    }

    private fun resetRun() {
        distanceMeters = 0f; laps = 0; lastAnnouncedLap = 0; lastLapDistance = 0f; targetAnnounced = false
        points.clear(); route = Polyline().apply { outlinePaint.color = Color.rgb(183,243,74); outlinePaint.strokeWidth = 9f }
        map.overlays.clear(); map.overlays.add(route); map.invalidate()
        lastLocation = null; pausedTotal = 0L; paused = false
        updateLabels(0L, null)
    }

    private fun pauseRun() {
        if (running && !paused) {
            paused = true; pausedAt = SystemClock.elapsedRealtime()
            statusText.text = "PAUSED"
        }
    }

    private fun resumeRun() {
        if (running && paused) {
            pausedTotal += SystemClock.elapsedRealtime() - pausedAt
            paused = false; lastLocation = null
            statusText.text = "RUNNING • resumed"
        }
    }

    private fun stopRun() {
        if (!running) return
        running = false; paused = false
        fused.removeLocationUpdates(callback)
        val elapsed = elapsedMs()
        getPreferences(MODE_PRIVATE).edit()
            .putFloat("last_distance", distanceMeters)
            .putLong("last_time", elapsed)
            .putInt("last_laps", laps).apply()
        statusText.text = "Saved • ${"%.2f".format(distanceMeters / 1000)} KM"
        speak("Run saved")
    }

    private fun elapsedMs(): Long =
        if (!running) 0L else (if (paused) pausedAt else SystemClock.elapsedRealtime()) - startedAt - pausedTotal

    private fun updateStats(loc: Location?) {
        val elapsed = elapsedMs()
        updateLabels(elapsed, loc)
    }

    private fun updateLabels(elapsed: Long, loc: Location?) {
        val km = distanceMeters / 1000.0
        distanceText.text = String.format(Locale.US, "%.2f\nKM", km)
        val sec = elapsed / 1000
        timeText.text = String.format(Locale.US, "%02d:%02d\nTIME", sec / 60, sec % 60)
        val speed = loc?.speed?.toDouble() ?: 0.0
        speedText.text = String.format(Locale.US, "%.2f\nM/S", speed)
        val pace = if (km > 0.02) (elapsed / 60000.0 / km) else 0.0
        paceText.text = if (pace > 0) String.format(Locale.US, "%02d:%02d\nMIN/KM", pace.toInt(), ((pace % 1) * 60).toInt()) else "--:--\nMIN/KM"
        lapsText.text = "$laps\nLAPS"
    }

    private fun updateLaps() {
        val entered = trackInput.text.toString().toFloatOrNull()
        if (entered != null && entered >= 50) lapLength = entered
        val newLaps = (distanceMeters / lapLength).toInt()
        if (newLaps > laps) {
            laps = newLaps
            val lapTime = (elapsedMs() / 1000)
            speak("Lap $laps complete")
            statusText.text = "Lap $laps complete • ${lapLength.roundToInt()} meters per lap"
        }
        lapsText.text = "$laps\nLAPS"
    }

    private fun manualLap() {
        if (!running || paused) return
        laps++
        lastLapDistance = distanceMeters
        lapsText.text = "$laps\nLAPS"
        speak("Lap $laps")
        statusText.text = "Manual lap $laps • total ${distanceMeters.roundToInt()} m"
    }

    private fun calibrate() {
        val entered = trackInput.text.toString().toFloatOrNull()
        if (entered == null || entered < 50) {
            Toast.makeText(this, "Valid track length (minimum 50 m) डालें", Toast.LENGTH_LONG).show()
        } else {
            lapLength = entered
            statusText.text = "Track calibrated: ${lapLength.roundToInt()} m per lap"
            Toast.makeText(this, "Ground lap length set to ${lapLength.roundToInt()} m", Toast.LENGTH_SHORT).show()
        }
    }

    private fun centerOnCurrentLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fused.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) map.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
            }
        }
    }

    private fun showLastRun() {
        val p = getPreferences(MODE_PRIVATE)
        val d = p.getFloat("last_distance", 0f)
        val t = p.getLong("last_time", 0L)
        val l = p.getInt("last_laps", 0)
        AlertDialogBuilder.show(this, "Last Run",
            "Distance: ${"%.2f".format(d / 1000)} km\nTime: ${t / 60000} min ${t / 1000 % 60} sec\nLaps: $l")
    }

    private fun speak(message: String) {
        if (::tts.isInitialized) tts.speak(message, TextToSpeech.QUEUE_ADD, null, "runmaster")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale("hi", "IN")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) centerOnCurrentLocation()
        else statusText.text = "GPS permission जरूरी है"
    }

    override fun onResume() { super.onResume(); if (::map.isInitialized) map.onResume() }
    override fun onPause() { super.onPause(); if (::map.isInitialized) map.onPause() }
    override fun onDestroy() {
        super.onDestroy()
        if (::fused.isInitialized) fused.removeLocationUpdates(callback)
        if (::tts.isInitialized) tts.shutdown()
    }
}

private object AlertDialogBuilder {
    fun show(activity: android.app.Activity, title: String, message: String) {
        android.app.AlertDialog.Builder(activity).setTitle(title).setMessage(message)
            .setPositiveButton("OK", null).show()
    }
}
