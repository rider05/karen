package com.karen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import com.karen.ui.AppUpdate
import com.karen.ui.KarenApp
import com.karen.ui.UserPrefs
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    private var wakeStartScreen = "Home"

    // Shake → Karen Settings. Three deliberate shakes in a row open Settings;
    // a single bump does nothing. The counter is Compose state so KarenApp can
    // react without the activity having to reach into its navigation state.
    private var shakeSignal by mutableIntStateOf(0)
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lastShakeAt = 0L
    private var shakeCount = 0
    private val gravity = FloatArray(3)

    private object Shake {
        /** Linear impulse that counts as a shake (~1.1 g). */
        const val THRESHOLD = 11f
        /** Rebound impulses closer than this belong to the same shake. */
        const val MIN_GAP_MS = 400L
        /** A longer pause starts a fresh triple-shake sequence. */
        const val SEQUENCE_GAP_MS = 1500L
        /** Deliberate shakes required before Settings opens. */
        const val COUNT_TO_OPEN = 3
    }

    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            // High-pass the accelerometer to drop gravity, then measure the
            // remaining linear impulse. A firm shake clears ~11 m/s².
            var linearSq = 0f
            for (i in 0..2) {
                gravity[i] = 0.8f * gravity[i] + 0.2f * event.values[i]
                val linear = event.values[i] - gravity[i]
                linearSq += linear * linear
            }
            if (sqrt(linearSq) <= Shake.THRESHOLD) return
            val now = SystemClock.elapsedRealtime()
            val gap = now - lastShakeAt
            if (gap < Shake.MIN_GAP_MS) return
            lastShakeAt = now
            shakeCount = if (gap > Shake.SEQUENCE_GAP_MS) 1 else shakeCount + 1
            if (shakeCount >= Shake.COUNT_TO_OPEN) {
                shakeCount = 0
                shakeSignal++
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        wakeStartScreen = startScreenFor(intent)
        if (wakeStartScreen == "Voice") WakeWordService.notifyAppOpened(this)
        setContent { KarenApp(startScreen = wakeStartScreen, shakeSignal = shakeSignal) }
        requestEssentialPermissions()
        // Cold start via wake trigger: keep the listener alive while in use.
        maybeRestartWakeListener()
        // Birthday: wish today if it's the birthday, and keep the alarm armed.
        com.karen.BirthdayNotifier.checkToday(this)
        // Post-install: clean up pending APK if an update was just installed.
        if (AppUpdate.checkPostInstall(this)) {
            android.widget.Toast.makeText(this, "Update installed successfully", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val accel = accelerometer
        if (accel != null) {
            sensorManager?.registerListener(shakeListener, accel, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        sensorManager?.unregisterListener(shakeListener)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isWakeIntent(intent)) {
            WakeWordService.notifyAppOpened(this)
            WakeWordNavigator.requestOpen()
        }
    }

    private fun isWakeIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.action == WakeWord.ACTION_WAKE) return true
        return intent.getBooleanExtra(WakeWord.EXTRA_OPEN_VOICE, false)
    }

    private fun startScreenFor(intent: Intent?): String {
        if (isWakeIntent(intent)) return "Voice"
        // Service died with the process: a fresh trigger (< 60s) still opens Voice.
        return try {
            val fresh = System.currentTimeMillis() - UserPrefs.wakeTriggerAt(this) < 60_000L
            if (fresh && UserPrefs.wakeTriggerAt(this) > 0L) "Voice" else "Home"
        } catch (_: Exception) {
            "Home"
        }
    }

    private fun maybeRestartWakeListener() {
        try {
            if (UserPrefs.wakeEnabled(this) &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                !WakeWordService.running
            ) {
                WakeWordService.start(this)
            }
        } catch (_: Exception) {
        }
    }

    private fun requestEssentialPermissions() {
        val needed = mutableListOf<String>()
        fun addIfNeeded(perm: String) {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) needed.add(perm)
        }
        addIfNeeded(Manifest.permission.CAMERA)
        addIfNeeded(Manifest.permission.READ_CONTACTS)
        addIfNeeded(Manifest.permission.CALL_PHONE)
        addIfNeeded(Manifest.permission.SEND_SMS)
        addIfNeeded(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfNeeded(Manifest.permission.POST_NOTIFICATIONS)
            addIfNeeded(Manifest.permission.READ_MEDIA_IMAGES)
            addIfNeeded(Manifest.permission.READ_MEDIA_VIDEO)
            addIfNeeded(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            addIfNeeded(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
        }
    }
}
