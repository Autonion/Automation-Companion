package com.autonion.automationcompanion.features.system_context_automation.location.helpers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.autonion.automationcompanion.features.system_context_automation.location.engine.time.TimeTickReceiver

object AppInitManager {

    fun init(context: Context) {
        scheduleTimeTick(context)
    }

    private fun scheduleTimeTick(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, TimeTickReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            2001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        am.setRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis(),
            60_000L, // every minute
            pi
        )
    }
}
