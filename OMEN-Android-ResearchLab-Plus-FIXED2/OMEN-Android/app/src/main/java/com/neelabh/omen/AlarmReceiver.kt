package com.neelabh.omen

import android.app.*
import android.content.*
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat

class AlarmReceiver : BroadcastReceiver() {
    companion object { var rt: Ringtone? = null }

    override fun onReceive(c: Context, i: Intent) {
        val msg = i.getStringExtra("msg") ?: "Alarm"
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("alarm", "OMEN alarms", NotificationManager.IMPORTANCE_HIGH))
        val show = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(2, NotificationCompat.Builder(c, "alarm").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("OMEN").setContentText(msg).setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM).setFullScreenIntent(show, true).setAutoCancel(true).build())
        rt?.stop()
        rt = RingtoneManager.getRingtone(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        rt?.play()
        Handler(Looper.getMainLooper()).postDelayed({ rt?.stop() }, 30000)
    }
}
