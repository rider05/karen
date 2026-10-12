package com.karen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.karen.ui.UserPrefs

/**
 * Re-arms the wake-word listener after reboot / app update when enabled.
 */
class WakeWordBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        try {
            if (UserPrefs.wakeEnabled(context.applicationContext)) {
                WakeWordService.start(context.applicationContext)
            }
        } catch (_: Exception) {
        }
    }
}
