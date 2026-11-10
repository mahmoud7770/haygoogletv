package com.example.heygoogletv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && !action.endsWith("QUICKBOOT_POWERON")) return
        val hasKey = !context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            .getString("access_key", null).isNullOrBlank()
        if (hasKey) {
            context.startForegroundService(Intent(context, WakeService::class.java))
        }
    }
}
