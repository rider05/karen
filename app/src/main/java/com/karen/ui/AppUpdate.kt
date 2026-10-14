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
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * In-app update from the tracked APK in git:
 * https://github.com/rider05/karen/blob/main/apks/karen-debug.apk
 *
 * Check compares the latest commit touching that path (GitHub API) with
 * the sha stored at the last updater install. Download pulls the raw file
 * with progress, then fires the system installer (unknown-sources consent
 * is handled by the system, not bypassed).
 */
object AppUpdate {
    const val REPO = "rider05/karen"
    const val APK_PATH = "apks/karen-debug.apk"
    const val RAW_URL = "https://github.com/rider05/karen/raw/main/apks/karen-debug.apk"
    private const val COMMITS_URL =
        "https://api.github.com/repos/rider05/karen/commits?path=apks/karen-debug.apk&per_page=1"

    private const val PREFS = "karen_app_update"
    private const val KEY_SHA = "apk_sha"
    private const val KEY_CHECKED_AT = "checked_at"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun storedSha(ctx: Context): String = prefs(ctx).getString(KEY_SHA, "").orEmpty()
    fun lastCheckedAt(ctx: Context): Long = prefs(ctx).getLong(KEY_CHECKED_AT, 0L)

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
        data class UpdateAvailable(val sha: String, val date: String) : Check
        data object UpToDate : Check
        data class Failed(val reason: String) : Check
    }

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
            prefs(ctx).edit().putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply()
            if (sha.isBlank()) {
                Check.Failed("could not read latest commit")
            } else if (storedSha(ctx).isNotBlank() && sha == storedSha(ctx)) {
                Check.UpToDate
            } else {
                Check.UpdateAvailable(sha, date)
            }
        } catch (e: Exception) {
            Check.Failed(e.message ?: "network error")
        }
    }

    /**
     * Downloads the raw APK with progress (0..1). Returns the file, or null
     * with the reason appended to [errors]. Marks the sha when [sha] is set.
     */
    suspend fun download(
        ctx: Context,
        sha: String?,
        onProgress: (Float) -> Unit,
        errors: MutableList<String> = mutableListOf()
    ): File? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(RAW_URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 60000
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                errors.add("download HTTP $code")
                return@withContext null
            }
            val total = conn.contentLengthLong.takeIf { it > 0 }
            val dir = File(ctx.getExternalFilesDir(null), "updates").apply { mkdirs() }
            val dest = File(dir, "karen-debug.apk")
            val tmp = File(dir, "karen-debug.apk.part")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total != null) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (tmp.length() < 1_000_000) {
                tmp.delete()
                errors.add("downloaded file too small — retry on Wi-Fi")
                return@withContext null
            }
            tmp.renameTo(dest)
            if (!sha.isNullOrBlank()) {
                prefs(ctx).edit().putString(KEY_SHA, sha).apply()
            }
            onProgress(1f)
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
}
