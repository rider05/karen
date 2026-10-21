package com.karen

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.karen.ui.UserPrefs
import java.util.Locale

/**
 * Always-on wake-word listener ("Hey Jarvis" by default).
 *
 * Zero new dependencies: loops the platform SpeechRecognizer (with
 * EXTRA_PREFER_OFFLINE) and scans partial + final transcripts for the
 * configured phrase. Runs as a microphone-type foreground service so the
 * mic stays open when the app is backgrounded, killed, or never started
 * (plus a BOOT_COMPLETED receiver re-arms it after reboot).
 *
 * Background-launch limits (Android 10+) forbid startActivity from a
 * background service, so a trigger does both: a best-effort direct launch
 * (works foreground / older OS) and a high-priority full-screen
 * notification the user can tap to open Live mode.
 */
class WakeWordService : Service() {

    companion object {
        private const val CHANNEL_ID = "wake_word"
        private const val CHANNEL_ALERT_ID = "wake_word_alert"
        private const val NOTIF_ID = 1001
        private const val ALERT_NOTIF_ID = 1002

        @Volatile
        var running = false
            private set

        /**
         * Live mode owns the microphone while it is open. Two active
         * SpeechRecognizers in one app fight over the mic session, which the
         * platform reports to the foreground one as ERROR_SERVER_DISCONNECTED
         * (11). Live mode parks this loop on entry and resumes it on exit.
         */
        @Volatile
        private var voicePaused = false

        @Volatile
        private var instance: WakeWordService? = null

        fun pauseForVoice() {
            voicePaused = true
            try {
                instance?.parkForVoice()
            } catch (_: Exception) {
            }
        }

        fun resumeAfterVoice() {
            voicePaused = false
            try {
                instance?.unparkAfterVoice()
            } catch (_: Exception) {
            }
        }

        /**
         * Set by MainActivity when Voice is visibly opened from a wake
         * trigger. Lets the service skip the fallback tap-to-open
         * notification when the direct open already worked.
         */
        @Volatile
        var appOpenedAt = 0L
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, WakeWordService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WakeWordService::class.java))
        }

        fun notifyAppOpened(ctx: Context) {
            appOpenedAt = System.currentTimeMillis()
            try {
                ctx.getSystemService(NotificationManager::class.java)?.cancel(ALERT_NOTIF_ID)
            } catch (_: Exception) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var restartPending = false
    private var lastTriggerAt = 0L
    private var wakePhrase = WakeWord.DEFAULT_PHRASE
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        instance = this
        wakePhrase = UserPrefs.wakePhrase(this).ifBlank { WakeWord.DEFAULT_PHRASE }
        ensureChannels()
        refreshNotification()
        // Partial wakelock keeps recognition alive with the screen off.
        // Released in onDestroy; CPU-only (no screen bright).
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Karen:WakeWord").apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L)
            }
        } catch (_: Exception) {
        }
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        wakePhrase = UserPrefs.wakePhrase(this).ifBlank { WakeWord.DEFAULT_PHRASE }
        ensureChannels()
        refreshNotification()
        if (recognizer == null && !restartPending) startListening()
        return START_STICKY
    }

    override fun onDestroy() {
        restartPending = false
        handler.removeCallbacksAndMessages(null)
        stopRecognizer()
        if (instance === this) instance = null
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        running = false
        super.onDestroy()
    }

    // ---- recognition loop ----

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun recognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            // Prefer the on-device pack when installed; falls back to network.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    private fun startListening() {
        // Live mode holds the mic; the loop resumes when it closes.
        if (voicePaused) return
        if (!hasMicPermission()) {
            stopSelf()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            scheduleRestart(5_000L)
            return
        }
        try {
            stopRecognizer()
            val r = SpeechRecognizer.createSpeechRecognizer(applicationContext)
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    scheduleRestart(300L)
                }

                override fun onError(error: Int) {
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        stopSelf()
                        return
                    }
                    // Busy/client/timeout/no-match: just loop again.
                    scheduleRestart(900L)
                }

                override fun onResults(results: Bundle?) {
                    val heard = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (heard.isNotBlank()) checkPhrase(heard)
                    scheduleRestart(300L)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val heard = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (heard.isNotBlank()) checkPhrase(heard)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            recognizer = r
            r.startListening(recognizerIntent())
        } catch (_: Exception) {
            scheduleRestart(2_000L)
        }
    }

    private val restartRunnable = Runnable {
        restartPending = false
        if (voicePaused) return@Runnable
        try {
            startListening()
        } catch (_: Exception) {
            scheduleRestart(2_000L)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (restartPending || voicePaused) return
        restartPending = true
        handler.postDelayed(restartRunnable, delayMs)
    }

    /** Live mode opened: release the mic immediately and cancel wake restarts. */
    private fun parkForVoice() {
        handler.removeCallbacks(restartRunnable)
        restartPending = false
        stopRecognizer()
        refreshNotification()
    }

    /** Live mode closed: re-arm the wake loop after the mic settles. */
    private fun unparkAfterVoice() {
        if (voicePaused) return
        refreshNotification()
        if (recognizer != null || restartPending) return
        scheduleRestart(700L)
    }

    private fun refreshNotification() {
        try {
            startForeground(
                NOTIF_ID,
                if (voicePaused) {
                    ongoingNotification(
                        title = "Wake word paused",
                        text = "Resumes when Live mode closes"
                    )
                } else {
                    ongoingNotification()
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun stopRecognizer() {
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    private fun checkPhrase(heard: String) {
        if (!WakeWord.matches(heard, wakePhrase)) return
        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < WakeWord.TRIGGER_COOLDOWN_MS) return
        lastTriggerAt = now
        UserPrefs.setWakeTriggerAt(this, now)
        WakeWordNavigator.requestOpen()
        openApp()
    }

    // ---- open the app ----

    private fun openIntent(): Intent =
        Intent(this, MainActivity::class.java).apply {
            action = WakeWord.ACTION_WAKE
            putExtra(WakeWord.EXTRA_OPEN_VOICE, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }

    private fun openApp() {
        // Direct open. This works when Karen is foreground/recent, and —
        // the key part — from background/killed too when the user granted
        // "Display over other apps" (SYSTEM_ALERT_WINDOW is a background-
        // launch exemption on Android 10+). Without that grant the system
        // silently drops the start, so fall back to tap-to-open.
        try {
            startActivity(openIntent())
        } catch (_: Exception) {
        }
        handler.postDelayed(
            {
                try {
                    if (appOpenedAt < lastTriggerAt) showAlertNotification()
                } catch (_: Exception) {
                }
            },
            2_000L
        )
    }

    // ---- notifications ----

    private fun ensureChannels() {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Wake word listener",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Keeps the microphone open for the wake phrase" }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERT_ID,
                    "Wake word detected",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Tap to open Karen Live mode" }
            )
        } catch (_: Exception) {
        }
    }

    private fun openPendingIntent(requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            this,
            requestCode,
            openIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun ongoingNotification(
        title: String = "Listening for \u201c$wakePhrase\u201d",
        text: String = "Say the wake phrase to open Karen Live mode"
    ): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPendingIntent(2001))
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun showAlertNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val fullScreen = openPendingIntent(2002)
            val alert = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
                .setContentTitle("Heard \u201c$wakePhrase\u201d")
                .setContentText("Tap to open Karen Live mode")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(fullScreen)
                .setFullScreenIntent(fullScreen, true)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .build()
            nm.notify(ALERT_NOTIF_ID, alert)
        } catch (_: Exception) {
        }
    }
}
