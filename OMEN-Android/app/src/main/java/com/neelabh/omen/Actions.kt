package com.neelabh.omen

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.view.KeyEvent
import org.json.JSONArray
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
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

    private fun canLaunch() = MainActivity.visible || Settings.canDrawOverlays(c)

    fun alarm(h: Int, m: Int): String {
        if (h !in 0..23 || m !in 0..59) return "That time makes no sense."
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        val label = DateFormat.getTimeInstance(DateFormat.SHORT).format(cal.time)
        if (canLaunch()) {
            val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "OMEN").putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (i.resolveActivity(c.packageManager) != null && go(i)) return "Alarm set in your Clock app for $label."
        }
        schedule(cal.timeInMillis, "Your alarm.")
        return "Alarm set for $label. Allow display over apps in setup, and I will use your Clock app."
    }

    fun timer(sec: Int): String {
        if (sec <= 0) return "For how long?"
        val say = if (sec >= 60) "${sec / 60} minutes" else "$sec seconds"
        if (canLaunch()) {
            val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, sec)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "OMEN").putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (i.resolveActivity(c.packageManager) != null && go(i)) return "Timer set in your Clock app for $say."
        }
        schedule(System.currentTimeMillis() + sec * 1000L, "Your timer is complete.")
        return "Timer set for $say."
    }

    private fun lookup(who: String): Pair<String, String>? {
        val digits = who.replace(Regex("[^\\d+]"), "")
        if (digits.length >= 7) return who to digits
        fun q(like: String): List<Pair<String, String>> = try {
            c.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$like%"), null)?.use { cur ->
                val out = mutableListOf<Pair<String, String>>()
                while (cur.moveToNext()) out.add((cur.getString(0) ?: "") to (cur.getString(1) ?: ""))
                out
            } ?: emptyList()
        } catch (e: Exception) { emptyList() }
        var r = q(who.trim())
        if (r.isEmpty()) {
            for (w in who.split(" ").filter { it.length >= 3 }.sortedByDescending { it.length }) {
                val x = q(w).distinctBy { it.first }
                if (x.size == 1) { r = x; break }
            }
        }
        return r.firstOrNull()
    }

    private fun resolveRaw(who: String): String? = lookup(who)?.second

    fun flashlight(on: Boolean): String = try {
        val cm = c.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        if (id == null) "This phone has no flashlight." else { cm.setTorchMode(id, on); if (on) "Flashlight on." else "Flashlight off." }
    } catch (e: Exception) { "I could not control the flashlight." }

    fun volume(kind: String): String {
        val am = c.getSystemService(AudioManager::class.java)
        val s = AudioManager.STREAM_MUSIC; val ui = AudioManager.FLAG_SHOW_UI
        when (kind) {
            "up" -> am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, ui)
            "down" -> am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, ui)
            "mute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, ui)
            "unmute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, ui)
            "max" -> am.setStreamVolume(s, am.getStreamMaxVolume(s), ui)
        }
        return "Done."
    }

    fun media(code: Int): String {
        val am = c.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "Done."
    }

    fun addNote(text: String): String {
        val p = c.getSharedPreferences("omen", Context.MODE_PRIVATE)
        val a = try { JSONArray(p.getString("notes", "[]")) } catch (e: Exception) { JSONArray() }
        a.put(text); p.edit().putString("notes", a.toString()).apply()
        return "Noted."
    }

    fun notes(): String {
        val a = try { JSONArray(c.getSharedPreferences("omen", Context.MODE_PRIVATE).getString("notes", "[]")) } catch (e: Exception) { JSONArray() }
        if (a.length() == 0) return "You have no notes."
        val n = a.length()
        return "Your notes. " + (maxOf(0, n - 5) until n).joinToString(". ") { "${it + 1}. ${a.getString(it)}" }
    }

    fun navigate(place: String): String {
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place))).setPackage("com.google.android.apps.maps")
        if (go(nav)) return "Navigating to $place."
        return if (go(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))) "Showing $place on the map." else "I could not open maps."
    }

    fun playOn(q: String, where: String): String {
        if (where == "spotify" && go(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q))).setPackage("com.spotify.music"))) return "Searching $q on Spotify."
        if (go(Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q))) return "Playing $q on YouTube."
        return if (go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))) "Searching $q on YouTube." else "I could not open YouTube."
    }

    fun settingsPanel(kind: String): String {
        val action = when (kind.replace("-", "")) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS; "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS; "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS; "sound" -> Settings.ACTION_SOUND_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return if (go(Intent(action))) "Opening $kind settings." else "I could not open settings."
    }

    private fun waNumber(raw: String): String {
        var d = raw.replace(Regex("\\D"), "")
        if (d.length == 11 && d.startsWith("0")) d = d.substring(1)
        if (d.length == 10) d = "91$d"
        return d
    }

    fun call(who: String, speaker: Boolean = false): String {
        val (name, raw) = lookup(who) ?: return "I cannot find $who in your contacts."
        return try {
            val extras = Bundle()
            if (speaker) extras.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
            c.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", raw, null), extras)
            "Calling $name" + (if (speaker) " on speaker." else ".")
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
