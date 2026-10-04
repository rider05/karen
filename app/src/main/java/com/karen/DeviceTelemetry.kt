package com.karen

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File

/**
 * Real-time, on-device telemetry. Every value is read straight from the OS —
 * nothing is hardcoded. Screens poll [rememberDeviceTelemetry] to stay live.
 */
data class DeviceTelemetry(
    val totalRamGb: Double = 0.0,
    val freeRamGb: Double = 0.0,
    val usedRamPercent: Int = 0,
    val batteryPercent: Int = 0,
    val batteryTempC: Float = 0f,
    val batteryCharging: Boolean = false,
    val storageTotalGb: Double = 0.0,
    val storageFreeGb: Double = 0.0,
    val cpuCores: Int = 0,
    /** 0..1 while running; <0 when the API is unavailable. */
    val thermalHeadroom: Float = -1f,
    val networkUp: Boolean = false,
    val cellular: Boolean = false,
    val wifi: Boolean = false,
    val uptimeMinutes: Long = 0L,
    val deviceModel: String = "",
    val deviceManufacturer: String = "",
    val androidVersion: String = "",
    val sdkInt: Int = 0,
    val securityPatch: String = "",
)

fun readDeviceTelemetry(context: Context): DeviceTelemetry {
    val app = context.applicationContext

    // RAM via ActivityManager
    val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    val totalGb = mi.totalMem / 1_073_741_824.0
    val freeGb = mi.availMem / 1_073_741_824.0
    val usedPct = ((totalGb - freeGb) / totalGb * 100).toInt().coerceIn(0, 100)

    // Battery via sticky broadcast intent (flag required on Android 13+)
    val batteryIntent: Intent? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    } catch (_: Throwable) { null }
    val battPct = batteryIntent?.let {
        val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) (level * 100 / scale) else 0
    } ?: 0
    val battTempC = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL

    // Internal storage via StatFs
    val stat = StatFs(app.filesDir.absolutePath)
    val totalStorage = stat.totalBytes / 1_073_741_824.0
    val freeStorage = stat.availableBytes / 1_073_741_824.0

    // Thermal headroom (API 29+)
    val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
            (app.getSystemService(Context.POWER_SERVICE) as PowerManager).getThermalHeadroom(10)
        } catch (_: Throwable) { -1f }
    } else -1f

    // Network state
    val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    val netUp = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val wifiUp = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    val cellularUp = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

    return DeviceTelemetry(
        totalRamGb = totalGb,
        freeRamGb = freeGb,
        usedRamPercent = usedPct,
        batteryPercent = battPct,
        batteryTempC = battTempC,
        batteryCharging = charging,
        storageTotalGb = totalStorage,
        storageFreeGb = freeStorage,
        cpuCores = Runtime.getRuntime().availableProcessors(),
        thermalHeadroom = headroom,
        networkUp = netUp,
        cellular = cellularUp,
        wifi = wifiUp,
        uptimeMinutes = SystemClock.elapsedRealtime() / 60_000L,
        deviceModel = Build.MODEL,
        deviceManufacturer = Build.MANUFACTURER,
        androidVersion = Build.VERSION.RELEASE ?: "",
        sdkInt = Build.VERSION.SDK_INT,
        securityPatch = Build.VERSION.SECURITY_PATCH ?: "",
    )
}

@Composable
fun rememberDeviceTelemetry(intervalMs: Long = 2000L): DeviceTelemetry {
    val context = LocalContext.current.applicationContext
    val telemetry = remember { mutableStateOf(readDeviceTelemetry(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            telemetry.value = readDeviceTelemetry(context)
            kotlinx.coroutines.delay(intervalMs)
        }
    }
    return telemetry.value
}

/** Human-readable byte size, e.g. "420 KB". */
fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824L -> String.format("%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

/** Lists real files stored in the app's own sandbox. */
fun sandboxFiles(context: Context): List<File> {
    val dirs = listOf(context.filesDir, context.cacheDir, File(context.filesDir, "models"))
    return dirs
        .filter { it.exists() && it.isDirectory }
        .flatMap { it.listFiles()?.toList() ?: emptyList() }
        .filter { it.isFile }
        .sortedByDescending { it.lastModified() }
}
