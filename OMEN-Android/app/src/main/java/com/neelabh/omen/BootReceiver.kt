package com.neelabh.omen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** Android 14 blocks microphone services from starting at boot, so we post a one-tap "awaken" notification instead. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("boot", "OMEN start", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(c, 3, Intent(c, MainActivity::class.java).putExtra("autostart", true), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(3, NotificationCompat.Builder(c, "boot").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("OMEN is dormant").setContentText("Tap to awaken, then say \"Hey Omen\" from anywhere")
            .setContentIntent(pi).setAutoCancel(true).build())
    }
}
