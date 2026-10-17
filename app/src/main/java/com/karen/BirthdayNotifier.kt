package com.karen

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.karen.ui.UserPrefs
import java.util.Calendar

/**
 * Birthday wish: schedules a notification on the user's birthday (month/day
 * from the stored DOB) and posts it from the receiver. The alarm is re-armed
 * on boot and whenever the DOB changes, so it survives reboots and updates.
 */
object BirthdayNotifier {
    private const val CHANNEL_ID = "karen_birthday"
    private const val NOTIF_ID = 4201
    const val ACTION_WISH = "com.karen.BIRTHDAY_WISH"

    /** Next birthday at 09:00 local, as epoch millis; 0 when no DOB stored. */
    private fun nextBirthdayAt(ctx: Context): Long {
        val dob = UserPrefs.dob(ctx)
        if (dob <= 0L) return 0L
        val dobCal = Calendar.getInstance().apply { timeInMillis = dob }
        val month = dobCal.get(Calendar.MONTH)
        val day = dobCal.get(Calendar.DAY_OF_MONTH)
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!next.after(now)) next.add(Calendar.YEAR, 1)
        return next.timeInMillis
    }

    /** (Re)arms the birthday alarm; cancels it when no DOB is stored. */
    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        val at = nextBirthdayAt(app)
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(app)
        if (at <= 0L) {
            am.cancel(pi)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun pendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, BirthdayReceiver::class.java).setAction(ACTION_WISH)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, 0, intent, flags)
    }

    /** Posts the wish immediately (receiver entry point). */
    fun postWish(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Birthdays", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Birthday wishes from Karen" }
            nm.createNotificationChannel(channel)
        }
        val name = UserPrefs.name(ctx).takeIf { it.isNotBlank() && it != "-" }
        val title = if (name != null) "Happy Birthday, $name! 🎂" else "Happy Birthday! 🎂"
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText("Karen wishes you a wonderful birthday.")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    /**
     * Launch-time safety net: wish immediately if today is the birthday and we
     * haven't already today (the alarm may have been missed while the app was
     * closed). Also (re)arms the alarm so it stays correct.
     */
    fun checkToday(ctx: Context) {
        val app = ctx.applicationContext
        val dob = UserPrefs.dob(app)
        if (dob > 0L) {
            val dobCal = Calendar.getInstance().apply { timeInMillis = dob }
            val now = Calendar.getInstance()
            if (dobCal.get(Calendar.MONTH) == now.get(Calendar.MONTH) &&
                dobCal.get(Calendar.DAY_OF_MONTH) == now.get(Calendar.DAY_OF_MONTH)
            ) {
                val today = now.get(Calendar.YEAR).toString() + "-" +
                    (now.get(Calendar.MONTH) + 1) + "-" + now.get(Calendar.DAY_OF_MONTH)
                if (UserPrefs.birthdayNotifiedFor(app) != today) {
                    postWish(app)
                    UserPrefs.setBirthdayNotifiedFor(app, today)
                }
            }
        }
        schedule(app)
    }
}

/**
 * Fires the scheduled birthday wish and re-arms for next year. Also re-arms
 * on boot so the alarm survives reboots.
 */
class BirthdayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        when (intent?.action) {
            BirthdayNotifier.ACTION_WISH -> {
                try {
                    BirthdayNotifier.postWish(app)
                } catch (_: Exception) {
                }
                BirthdayNotifier.schedule(app)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                try {
                    BirthdayNotifier.schedule(app)
                } catch (_: Exception) {
                }
            }
        }
    }
}
