package com.neelabh.omen

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat

/** Every setting lives here (like Claude's settings screen), not on the chat screen. */
class SettingsActivity : Activity() {
    private val ACC = 0xFFA8B8FF.toInt()
    private val MUT = 0xFF9A9AA2.toInt()
    private val TXT = 0xFFEDEBE6.toInt()
    private val prefs by lazy { getSharedPreferences("omen", MODE_PRIVATE) }
    private lateinit var access: LinearLayout
    private lateinit var wakeInfo: TextView
    private val perms = Perms.list()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xFF1B1B1F.toInt()); setStroke(dp(1), 0x22FFFFFF) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = 0xFF0E0E11.toInt(); window.navigationBarColor = 0xFF0E0E11.toInt()
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0E0E11.toInt()) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(34), dp(8), dp(10)) }
        bar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); imageTintList = ColorStateList.valueOf(TXT); background = null; setOnClickListener { finish() }
        }, lp(dp(48), dp(48)))
        bar.addView(TextView(this).apply { text = "Settings"; textSize = 24f; setTextColor(TXT) }, lp(WRAP, WRAP).apply { marginStart = dp(6) })
        page.addView(bar)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(40)) }
        page.addView(ScrollView(this).apply { addView(body) }, lp(MATCH, 0).apply { weight = 1f })

        fun header(t: String) = body.addView(TextView(this).apply { text = t.uppercase(); textSize = 12f; letterSpacing = 0.15f; setTextColor(MUT); setPadding(dp(6), dp(22), 0, dp(8)) })
        fun note(c: LinearLayout, t: String) = c.addView(TextView(this).apply { text = t; textSize = 13f; setTextColor(MUT); setPadding(0, 0, 0, dp(10)) })
        fun toggle(c: LinearLayout, title: String, sub: String, key: String, def: Boolean, onChange: (Boolean) -> Unit = {}) {
            c.addView(Switch(this).apply {
                text = title; textSize = 16f; setTextColor(TXT); isChecked = prefs.getBoolean(key, def); setPadding(0, dp(12), 0, dp(4))
                setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(key, v).apply(); onChange(v) }
            })
            note(c, sub)
        }
        fun button(c: LinearLayout, t: String, f: () -> Unit) = c.addView(Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } })

        // --- AI ---
        header("AI brain (Groq)")
        val ai = card(); body.addView(ai)
        val key = EditText(this).apply { hint = "Groq API key (gsk_...)"; setText(prefs.getString("key", "")); isSingleLine = true; setTextColor(TXT); setHintTextColor(MUT) }
        ai.addView(key)
        button(ai, "Save and test key") {
            val k = key.text.toString().trim(); prefs.edit().putString("key", k).apply()
            Brain.bad.clear(); Brain.models = emptyList(); Brain.brain = ""
            Thread {
                try { Brain.load(k); runOnUiThread { Toast.makeText(this, "Connected. Brain: ${Brain.brain}. Search: ${Brain.web.ifEmpty { "not on this account" }}", Toast.LENGTH_LONG).show() } }
                catch (e: Exception) { runOnUiThread { Toast.makeText(this, "Key problem: ${e.message}", Toast.LENGTH_LONG).show() } }
            }.start()
        }
        val nameBox = EditText(this).apply { hint = "Your name (shown in the menu)"; setText(prefs.getString("name", "")); isSingleLine = true; setTextColor(TXT); setHintTextColor(MUT) }
        ai.addView(nameBox)
        button(ai, "Save name") { prefs.edit().putString("name", nameBox.text.toString().trim()).apply(); toast("Saved") }

        // --- voice ---
        header("Voice and “Hey Omen”")
        val voice = card(); body.addView(voice)
        toggle(voice, "Offline “Hey Omen” detector", "Listens on the phone itself, so it works from any app and when offline. Needs the small speech model below.", "hwake", true)
        wakeInfo = TextView(this).apply { textSize = 14f; setTextColor(ACC); setPadding(0, 0, 0, dp(6)) }
        voice.addView(wakeInfo)
        button(voice, "Download wake-word model (about 40 MB)") {
            if (WakeModel.ready(this)) { toast("Already downloaded"); return@button }
            wakeInfo.text = "Downloading… 0%"
            Thread {
                try {
                    WakeModel.download(this) { p -> runOnUiThread { wakeInfo.text = "Downloading… $p%" } }
                    runOnUiThread {
                        updateWakeInfo(); toast("Ready. Send OMEN back and awaken it again.")
                        if (OmenService.running) { stopService(Intent(this, OmenService::class.java)); ContextCompat.startForegroundService(this, Intent(this, OmenService::class.java)) }
                    }
                } catch (e: Exception) { runOnUiThread { wakeInfo.text = "Download failed: ${e.message}" } }
            }.start()
        }
        toggle(voice, "Require the wake word", "When off, OMEN answers everything it hears.", "wake", true)
        toggle(voice, "Smart wake", "Clear commands like “open YouTube” work without saying Omen.", "smart", true)
        toggle(voice, "Speak replies to typed messages", "Voice commands are always answered aloud.", "voice", true)

        // --- appearance ---
        header("Appearance")
        val look = card(); body.addView(look)
        toggle(look, "Animated wind background", "Black wind-wave video behind the chat. Turn off to save battery.", "wind", true)
        look.addView(TextView(this).apply { text = "Chat bubble opacity (lower = more transparent)"; textSize = 14f; setTextColor(TXT); setPadding(0, dp(8), 0, 0) })
        look.addView(SeekBar(this).apply {
            max = 80; progress = prefs.getInt("glass", 28)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { prefs.edit().putInt("glass", p).apply(); ChatLog.version++ }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        // --- access ---
        header("Access and permissions")
        access = card(); body.addView(access)

        // --- data ---
        header("Data")
        val data = card(); body.addView(data)
        button(data, "Clear current chat") { ChatLog.clear(this); toast("Chat cleared") }
        button(data, "Clear history (Recents)") { QueryHistory.clear(this); toast("History cleared") }
        button(data, "Clear notes") { prefs.edit().remove("notes").apply(); toast("Notes cleared") }

        header("About")
        val about = card(); body.addView(about)
        note(about, "OMEN · native Android assistant · brain by Groq · offline wake word by Vosk")
        setContentView(page)
    }

    override fun onResume() { super.onResume(); updateWakeInfo(); buildAccess() }

    private fun updateWakeInfo() { wakeInfo.text = if (WakeModel.ready(this)) "Wake-word model: ready ✔" else "Wake-word model: not downloaded (OMEN uses Google listening instead)" }

    private fun buildAccess() {
        access.removeAllViews()
        fun ok(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        val pm = getSystemService(PowerManager::class.java)
        val ask = { requestPermissions(perms, 1) }
        val items = listOf<Triple<String, Boolean, () -> Unit>>(
            Triple("Microphone", ok(Manifest.permission.RECORD_AUDIO), ask),
            Triple("Phone: place and answer calls", ok(Manifest.permission.CALL_PHONE) && ok(Manifest.permission.ANSWER_PHONE_CALLS) && ok(Manifest.permission.READ_PHONE_STATE), ask),
            Triple("Contacts", ok(Manifest.permission.READ_CONTACTS), ask),
            Triple("Call log (caller names)", ok(Manifest.permission.READ_CALL_LOG), ask),
            Triple("SMS", ok(Manifest.permission.SEND_SMS), ask),
            Triple("Notifications", Build.VERSION.SDK_INT < 33 || ok(Manifest.permission.POST_NOTIFICATIONS), ask),
            Triple("Display over apps (“Hey Omen” panel, Clock alarms, open apps)", Settings.canDrawOverlays(this),
                { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }),
            Triple("WhatsApp auto-send (Accessibility)", OmenAccess.alive, { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),
            Triple("Battery: unrestricted background use", pm.isIgnoringBatteryOptimizations(packageName),
                { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
        )
        for (item in items) {
            val fix = item.third
            access.addView(TextView(this).apply {
                text = (if (item.second) "✔  " else "✖  ") + item.first + (if (item.second) "" else "\n      Tap to allow")
                textSize = 15f; setTextColor(if (item.second) TXT else 0xFFFFB3BE.toInt()); setPadding(0, dp(12), 0, dp(12))
                if (!item.second) { isClickable = true; setOnClickListener { fix() } }
            })
        }
        access.addView(Button(this).apply { text = "Allow all permissions"; isAllCaps = false; setOnClickListener { requestPermissions(perms, 1) } })
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) { super.onRequestPermissionsResult(rc, p, r); buildAccess() }
}
