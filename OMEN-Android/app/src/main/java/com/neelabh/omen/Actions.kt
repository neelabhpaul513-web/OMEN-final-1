package com.neelabh.omen

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.SmsManager
import java.text.DateFormat
import java.util.Calendar

/** Everything OMEN can do on the phone itself: opens real apps (not websites), alarms, calls, messages. */
class Actions(private val c: Context) {
    private fun go(i: Intent): Boolean = try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    fun openApp(name: String): String {
        val pm = c.packageManager
        val q = norm(name.replace(Regex("\\b(app|application)\\b", RegexOption.IGNORE_CASE), ""))
        if (q.isEmpty()) return "Which app?"
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val aliases = mapOf("insta" to "instagram", "yt" to "youtube", "gpay" to "googlepay", "fb" to "facebook", "msgs" to "messages")
        val want = aliases[q] ?: q
        fun score(label: String): Int = when {
            label == want -> 4
            label.startsWith(want) || want.startsWith(label) -> 3
            label.contains(want) -> 2
            else -> 0
        }
        val best = apps.map { it to score(norm(it.loadLabel(pm).toString())) }.filter { it.second > 0 }.maxByOrNull { it.second }
        if (best == null) return "I cannot find an app called $name."
        val pkg = best.first.activityInfo.packageName
        val i = pm.getLaunchIntentForPackage(pkg) ?: return "That app will not open."
        val label = best.first.loadLabel(pm)
        return if (go(i)) "Opening $label." else "Android blocked it. Grant the overlay permission in OMEN settings."
    }

    private fun schedule(at: Long, msg: String) {
        val pi = PendingIntent.getBroadcast(c, (at / 1000).toInt(), Intent(c, AlarmReceiver::class.java).putExtra("msg", msg),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val show = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        c.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
    }

    fun alarm(h: Int, m: Int): String {
        if (h !in 0..23 || m !in 0..59) return "That time makes no sense."
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        schedule(cal.timeInMillis, "Your alarm.")
        return "Alarm set for " + DateFormat.getTimeInstance(DateFormat.SHORT).format(cal.time) + "."
    }

    fun timer(sec: Int): String {
        if (sec <= 0) return "For how long?"
        schedule(System.currentTimeMillis() + sec * 1000L, "Your timer is complete.")
        return "Timer set for " + (if (sec >= 60) "${sec / 60} minutes" else "$sec seconds") + "."
    }

    private fun resolveRaw(who: String): String? {
        val digits = who.replace(Regex("[^\\d+]"), "")
        if (digits.length >= 7) return digits
        try {
            c.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$who%"), null)?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        } catch (e: Exception) {}
        return null
    }

    private fun waNumber(raw: String): String {
        var d = raw.replace(Regex("\\D"), "")
        if (d.length == 11 && d.startsWith("0")) d = d.substring(1)
        if (d.length == 10) d = "91$d"
        return d
    }

    fun call(who: String): String {
        val raw = resolveRaw(who) ?: return "I cannot find $who in your contacts."
        return try {
            c.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", raw, null), Bundle())
            "Calling $who."
        } catch (e: SecurityException) { "I need the phone permission." }
    }

    fun whatsapp(who: String, body: String): String {
        val raw = resolveRaw(who) ?: return "I cannot find $who in your contacts."
        val pm = c.packageManager
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { pm.getLaunchIntentForPackage(it) != null } ?: return "WhatsApp is not installed."
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${waNumber(raw)}?text=" + Uri.encode(body))).setPackage(pkg)
        if (OmenAccess.alive) OmenAccess.arm()
        if (!go(i)) return "I could not open WhatsApp."
        return if (OmenAccess.alive) "Sending it." else "Message is ready. Press send."
    }

    @Suppress("DEPRECATION")
    fun sms(who: String, body: String): String {
        val num = resolveRaw(who) ?: return "I cannot find $who in your contacts."
        return try {
            val sm = if (android.os.Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sm.sendMultipartTextMessage(num, null, sm.divideMessage(body), null, null)
            "Message sent to $who."
        } catch (e: Exception) { "I could not send it." }
    }
}
