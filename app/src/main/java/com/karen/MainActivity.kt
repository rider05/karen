package com.karen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import com.karen.ui.KarenApp
import com.karen.ui.UserPrefs

class MainActivity : ComponentActivity() {
    private var wakeStartScreen = "Home"

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        wakeStartScreen = startScreenFor(intent)
        setContent { KarenApp(startScreen = wakeStartScreen) }
        requestEssentialPermissions()
        // Cold start via wake trigger: keep the listener alive while in use.
        maybeRestartWakeListener()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isWakeIntent(intent)) {
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
