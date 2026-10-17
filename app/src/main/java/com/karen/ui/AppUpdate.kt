package com.karen.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * In-app update from the tracked APK in git:
 * https://github.com/rider05/karen/blob/main/apks/karen.apk
 *
 * Check compares the latest commit touching that path (GitHub API) with
 * the sha stored at the last updater install. Download pulls the raw file
 * with progress, then fires the system installer (unknown-sources consent
 * is handled by the system, not bypassed).
 */
object AppUpdate {
    const val REPO = "rider05/karen"
    const val APK_PATH = "apks/karen.apk"
    const val RAW_URL = "https://github.com/rider05/karen/raw/main/apks/karen.apk"
    private const val COMMITS_URL =
        "https://api.github.com/repos/rider05/karen/commits?path=apks/karen.apk&per_page=1"

    private const val PREFS = "karen_app_update"
    private const val KEY_SHA = "apk_sha"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val KEY_PENDING_APK = "pending_apk"
    private const val KEY_PENDING_SHA = "pending_sha"
    private const val KEY_JUST_INSTALLED = "just_installed"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun storedSha(ctx: Context): String = prefs(ctx).getString(KEY_SHA, "").orEmpty()
    fun lastCheckedAt(ctx: Context): Long = prefs(ctx).getLong(KEY_CHECKED_AT, 0L)

    /** File of a previously downloaded update, if it is still on disk and valid. */
    fun pendingApk(ctx: Context): java.io.File? {
        val path = prefs(ctx).getString(KEY_PENDING_APK, "").orEmpty()
        if (path.isBlank()) return null
        val file = java.io.File(path)
        if (!file.exists() || file.length() < 1_000_000) return null
        // Verify it still passes validation.
        if (!validateApk(ctx, file).valid) {
            cleanupPendingApk(ctx)
            return null
        }
        return file
    }

    /** Cooperative pause flag read by the download loops below. */
    @Volatile
    var downloadPaused: Boolean = false
        private set

    fun setDownloadPaused(paused: Boolean) {
        downloadPaused = paused
    }

    fun currentVersion(ctx: Context): String {
        return try {
            val pm = ctx.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(ctx.packageName, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName} ($code)"
        } catch (_: Exception) {
            "unknown"
        }
    }

    sealed interface Check {
        data class UpdateAvailable(val sha: String, val date: String, val releaseNotes: String = "") : Check
        data object UpToDate : Check
        data class Failed(val reason: String) : Check
    }

    /** Result of validating a downloaded APK before installation. */
    data class ApkValidation(
        val valid: Boolean,
        val versionCode: Long = 0,
        val versionName: String = "",
        val reason: String = ""
    )

    /** Asks GitHub for the newest commit touching the APK path (IO thread). */
    suspend fun check(ctx: Context): Check = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(COMMITS_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 15000
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            conn.disconnect()
            if (code !in 200..299) return@withContext Check.Failed("GitHub API HTTP $code")
            val arr = JSONArray(text)
            if (arr.length() == 0) return@withContext Check.Failed("no history for $APK_PATH")
            val latest = arr.getJSONObject(0)
            val sha = latest.optString("sha", "")
            val date = latest.optJSONObject("commit")?.optString("date", "").orEmpty()
                .take(10)
            val releaseNotes = latest.optJSONObject("commit")?.optString("message", "").orEmpty()
                .take(500)
            prefs(ctx).edit().putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply()
            if (sha.isBlank()) {
                Check.Failed("could not read latest commit")
            } else if (storedSha(ctx).isNotBlank() && sha == storedSha(ctx)) {
                Check.UpToDate
            } else {
                Check.UpdateAvailable(sha, date, releaseNotes)
            }
        } catch (e: Exception) {
            Check.Failed(e.message ?: "network error")
        }
    }

    /**
     * Downloads the raw APK with progress (0..1). Uses 4 parallel range
     * requests when the server honors them, else a single stream.
     * Returns the file, or null with the reason appended to [errors].
     * Marks the sha when [sha] is set.
     *
     * Pause-and-continue: when [resume] is true and a partial file exists,
     * the download continues from the saved byte offset instead of restarting.
     */
    suspend fun download(
        ctx: Context,
        sha: String?,
        onProgress: (Float) -> Unit,
        errors: MutableList<String> = mutableListOf(),
        resume: Boolean = false
    ): File? = withContext(Dispatchers.IO) {
        val dir = File(ctx.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val dest = File(dir, "karen.apk")
        val part = File(dir, "karen.apk.part")
        downloadPaused = false
        try {
            // Resume: keep the partial file and continue from its length.
            val resumeOffset = if (resume && part.exists() && part.length() > 0) part.length() else 0L
            if (resumeOffset == 0L && part.exists()) part.delete()
            val total = probeLength()
            // Part file is already complete — just finalize it.
            if (resumeOffset > 0 && total != null && resumeOffset >= total && total > 0) {
                part.renameTo(dest)
                prefs(ctx).edit()
                    .putString(KEY_PENDING_APK, dest.absolutePath)
                    .apply {
                        if (!sha.isNullOrBlank()) putString(KEY_SHA, sha)
                    }
                    .apply()
                onProgress(1f)
                return@withContext dest
            }
            // Segmented is a fresh-download optimization; resume uses single-stream append.
            val file = if (total != null && total > 8_000_000 && resumeOffset == 0L) {
                downloadSegmented(dest, part, total, onProgress, errors)
            } else {
                downloadSingle(dest, part, resumeOffset, onProgress, errors)
            }
            if (file == null || file.length() < 1_000_000) {
                if (file != null && !resume) file.delete()
                if (errors.isEmpty()) errors.add("downloaded file too small — retry on Wi-Fi")
                return@withContext null
            }
            // Validate before promoting to pending.
            val validation = validateApk(ctx, file)
            if (!validation.valid) {
                file.delete()
                part.delete()
                errors.add("APK validation failed: ${validation.reason}")
                return@withContext null
            }
            prefs(ctx).edit()
                .putString(KEY_PENDING_APK, file.absolutePath)
                .putString(KEY_PENDING_SHA, sha.orEmpty())
                .apply {
                    if (!sha.isNullOrBlank()) putString(KEY_SHA, sha)
                }
                .apply()
            onProgress(1f)
            file
        } catch (e: Exception) {
            errors.add(e.message ?: "download failed")
            null
        }
    }

    /** Total size when the server honors range requests, else null. */
    fun probeLength(): Long? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(RAW_URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Range", "bytes=0-0")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_PARTIAL) return null
            val cr = conn.getHeaderField("Content-Range") ?: return null
            cr.substringAfterLast('/').toLongOrNull()?.takeIf { it > 0 }
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun downloadSingle(
        dest: File,
        part: File,
        resumeOffset: Long,
        onProgress: (Float) -> Unit,
        errors: MutableList<String>
    ): File? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(RAW_URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 60000
                setRequestProperty("Accept", "application/octet-stream")
                if (resumeOffset > 0) {
                    setRequestProperty("Range", "bytes=$resumeOffset-")
                }
            }
            val code = conn.responseCode
            if (resumeOffset > 0 && code != HttpURLConnection.HTTP_PARTIAL) {
                // Server ignored the Range header — restart from scratch.
                errors.add("server ignored resume, restarting")
                return@withContext downloadSingle(dest, part, 0L, onProgress, errors)
            }
            if (code !in 200..299) {
                errors.add("download HTTP $code")
                return@withContext null
            }
            val total = conn.contentLengthLong.takeIf { it > 0 }
            val expectedTotal = if (resumeOffset > 0 && total != null) resumeOffset + total else total
            conn.inputStream.use { input ->
                val mode = if (resumeOffset > 0) "rw" else "wt"
                java.io.RandomAccessFile(part, mode).use { raf ->
                    if (resumeOffset > 0) raf.seek(resumeOffset)
                    val buf = ByteArray(1024 * 1024)
                    var done = resumeOffset
                    while (true) {
                        while (downloadPaused) delay(200)
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        done += n
                        val denom = expectedTotal ?: done
                        onProgress((done.toFloat() / denom).coerceIn(0f, 1f))
                    }
                }
            }
            part.renameTo(dest)
            dest
        } catch (e: Exception) {
            errors.add(e.message ?: "download failed")
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun downloadSegmented(
        dest: File,
        part: File,
        total: Long,
        onProgress: (Float) -> Unit,
        errors: MutableList<String>
    ): File? = withContext(Dispatchers.IO) {
        val parts = 4
        try {
            java.io.RandomAccessFile(part, "rw").use { it.setLength(total) }
            val done = java.util.concurrent.atomic.AtomicLong(0)
            coroutineScope {
                (0 until parts).map { i ->
                    async(Dispatchers.IO) {
                        val start = i * total / parts
                        val end = if (i == parts - 1) total - 1 else (i + 1) * total / parts - 1
                        var conn: HttpURLConnection? = null
                        try {
                            conn = (URL(RAW_URL).openConnection() as HttpURLConnection).apply {
                                instanceFollowRedirects = true
                                connectTimeout = 15000
                                readTimeout = 60000
                                setRequestProperty("Range", "bytes=$start-$end")
                            }
                            if (conn.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                                throw java.io.IOException("range rejected (HTTP ${conn.responseCode})")
                            }
                            conn.inputStream.use { input ->
                                java.io.RandomAccessFile(part, "rw").use { raf ->
                                    raf.seek(start)
                                    val buf = ByteArray(1024 * 1024)
                                    var remaining = end - start + 1
                                    while (remaining > 0) {
                                        while (downloadPaused) delay(200)
                                        val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                        if (n < 0) break
                                        raf.write(buf, 0, n)
                                        remaining -= n
                                        val d = done.addAndGet(n.toLong())
                                        onProgress((d.toFloat() / total).coerceIn(0f, 1f))
                                    }
                                    if (remaining != 0L) throw java.io.IOException("short segment")
                                }
                            }
                        } finally {
                            try {
                                conn?.disconnect()
                            } catch (_: Exception) {
                            }
                        }
                    }
                }.awaitAll()
            }
            part.renameTo(dest)
            dest
        } catch (e: Exception) {
            // Range path failed (proxy stripped it, etc.): single stream.
            part.delete()
            errors.add("parallel failed (${e.message}), retrying single stream")
            downloadSingle(dest, part, 0L, onProgress, errors)
        }
    }

    // ---- APK validation (package name, version code, signing cert) ----

    /** Read the installed app's version code. */
    fun installedVersionCode(ctx: Context): Long {
        return try {
            val pm = ctx.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(ctx.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else info.versionCode.toLong()
        } catch (_: Exception) { 0L }
    }

    /** Read the installed app's signing certificate bytes. */
    fun installedSigningCert(ctx: Context): ByteArray? {
        return try {
            val pm = ctx.packageManager
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
            info.signatures?.firstOrNull()?.toByteArray()
        } catch (_: Exception) { null }
    }

    /** Read version code from a candidate APK file. */
    fun apkVersionCode(ctx: Context, file: File): Long {
        return try {
            val info = ctx.packageManager.getPackageArchiveInfo(
                file.absolutePath, 0
            ) ?: return 0L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else info.versionCode.toLong()
        } catch (_: Exception) { 0L }
    }

    /** Read signing certificate bytes from a candidate APK file. */
    fun apkSigningCert(ctx: Context, file: File): ByteArray? {
        return try {
            val info = ctx.packageManager.getPackageArchiveInfo(
                file.absolutePath, PackageManager.GET_SIGNATURES
            ) ?: return null
            info.signatures?.firstOrNull()?.toByteArray()
        } catch (_: Exception) { null }
    }

    /** Validate a candidate APK: package name, version code, signature. */
    fun validateApk(ctx: Context, file: File): ApkValidation {
        if (!file.exists() || file.length() < 1_000_000) {
            return ApkValidation(false, reason = "File missing or too small")
        }
        val info = try {
            ctx.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
        } catch (e: Exception) {
            return ApkValidation(false, reason = "Could not read APK: ${e.message}")
        } ?: return ApkValidation(false, reason = "Invalid APK structure")

        if (info.packageName != ctx.packageName) {
            return ApkValidation(false, reason = "Package name mismatch (${info.packageName})")
        }
        val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        val installedCode = installedVersionCode(ctx)
        if (vCode < installedCode) {
            return ApkValidation(false, reason = "Version $vCode is older than installed $installedCode")
        }
        val installedCert = installedSigningCert(ctx)
        val apkCert = apkSigningCert(ctx, file)
        if (installedCert == null || apkCert == null || !installedCert.contentEquals(apkCert)) {
            return ApkValidation(false, reason = "Signing certificate mismatch")
        }
        return ApkValidation(true, vCode, info.versionName.orEmpty())
    }

    // ---- Pending APK lifecycle ----

    /** Delete the pending APK and clear all update metadata. */
    fun cleanupPendingApk(ctx: Context) {
        try {
            pendingApk(ctx)?.delete()
        } catch (_: Exception) {
        }
        try {
            File(ctx.getExternalFilesDir(null), "updates/karen.apk.part").delete()
        } catch (_: Exception) {
        }
        prefs(ctx).edit()
            .remove(KEY_PENDING_APK)
            .remove(KEY_PENDING_SHA)
            .apply()
    }

    /**
     * Post-install detection: if a pending update was recorded and the app
     * is running (possibly new version), clean up and return true.
     * Called on app startup.
     */
    fun checkPostInstall(ctx: Context): Boolean {
        val pendingSha = prefs(ctx).getString(KEY_PENDING_SHA, "").orEmpty()
        if (pendingSha.isBlank()) return false
        // The app is running — either the update succeeded or was cancelled.
        // Either way, clean up the pending state.
        cleanupPendingApk(ctx)
        prefs(ctx).edit()
            .putString(KEY_SHA, pendingSha)
            .putBoolean(KEY_JUST_INSTALLED, true)
            .apply()
        return true
    }

    /** Whether the app just completed an update install. Clears the flag. */
    fun consumeJustInstalled(ctx: Context): Boolean {
        val v = prefs(ctx).getBoolean(KEY_JUST_INSTALLED, false)
        if (v) prefs(ctx).edit().putBoolean(KEY_JUST_INSTALLED, false).apply()
        return v
    }

    /** Hands the downloaded APK to the system installer. */
    fun install(ctx: Context, file: File): Boolean {
        return try {
            val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Session install: writes the APK through PackageInstaller and reopens
     * the app on success (InstallStatusReceiver). Falls back to [install]
     * when sessions are unavailable. The system may still show one
     * confirmation tap for unknown sources — that cannot be bypassed.
     */
    suspend fun installAndReopen(ctx: Context, file: File): String =
        withContext(Dispatchers.IO) {
            var session: android.content.pm.PackageInstaller.Session? = null
            try {
                val pi = ctx.packageManager.packageInstaller
                val params = android.content.pm.PackageInstaller.SessionParams(
                    android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
                )
                val sessionId = pi.createSession(params)
                session = pi.openSession(sessionId)
                java.io.FileInputStream(file).use { input ->
                    val out = session.openWrite("karen", 0, file.length())
                    try {
                        input.copyTo(out, 1024 * 1024)
                        session.fsync(out)
                    } finally {
                        try {
                            out.close()
                        } catch (_: Exception) {
                        }
                    }
                }
                val statusIntent = Intent(ctx, InstallStatusReceiver::class.java)
                    .setAction(ACTION_INSTALL_STATUS)
                val sender = android.app.PendingIntent.getBroadcast(
                    ctx,
                    sessionId,
                    statusIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_MUTABLE
                )
                session.commit(sender.intentSender)
                try {
                    session.close()
                } catch (_: Exception) {
                }
                "Installing… the app reopens when done."
            } catch (e: Exception) {
                try {
                    session?.close()
                } catch (_: Exception) {
                }
                if (install(ctx, file)) "Opening installer…" else "Install blocked (${e.message ?: "session failed"})"
            }
        }

    const val ACTION_INSTALL_STATUS = "com.karen.INSTALL_STATUS"
}

/**
 * PackageInstaller callback: reopens Karen after a session update, or
 * falls back to the classic installer UI on failure.
 */
class InstallStatusReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != AppUpdate.ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(
            android.content.pm.PackageInstaller.EXTRA_STATUS,
            android.content.pm.PackageInstaller.STATUS_FAILURE
        )
        if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
            try {
                val launch = ctx.packageManager
                    .getLaunchIntentForPackage(ctx.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (launch != null) ctx.startActivity(launch)
            } catch (_: Exception) {
            }
            return
        }
        // Session failed (often needs one manual tap): classic installer.
        try {
            val path = ctx.getSharedPreferences("karen_app_update", Context.MODE_PRIVATE)
                .getString("pending_apk", "").orEmpty()
            val file = File(path)
            if (path.isNotBlank() && file.exists()) {
                AppUpdate.install(ctx, file)
            } else {
                android.widget.Toast.makeText(ctx, "Update install needs one manual tap — retry Download & install", android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
        }
    }
}
