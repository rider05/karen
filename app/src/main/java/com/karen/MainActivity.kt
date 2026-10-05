package com.karen

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import com.karen.ui.KarenApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { KarenApp() }
        requestEssentialPermissions()
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
