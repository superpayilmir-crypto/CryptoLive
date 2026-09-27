package com.cryptoticker.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.notifyEnabled(context)) {
            try {
                PriceNotificationService.start(context)
            } catch (_: Exception) {
            }
        }
    }
}
