package com.neelabh.omen

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val perms = mutableListOf(
        Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS
    ).apply { if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }.toTypedArray()

    private lateinit var view: OmenView
    private lateinit var status: TextView
    private lateinit var userT: TextView
    private lateinit var replyT: TextView
    private lateinit var btn: Button

    private val uiRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            view.state = i.getStringExtra("state") ?: view.state
            i.getStringExtra("user")?.let { userT.text = it }
            i.getStringExtra("reply")?.let { replyT.text = it }
            status.text = when (view.state) { "listening" -> "LISTENING"; "thinking" -> "THINKING"; "speaking" -> "SPEAKING"; else -> if (OmenService.running) "WATCHING" else "DORMANT" }
            refresh()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun pill(fill: Int) = GradientDrawable().apply { cornerRadius = dp(32).toFloat(); setColor(fill); setStroke(dp(2), 0xFF8D9BB8.toInt()) }
    private fun tv(t: String, size: Float, color: Int) = TextView(this).apply { text = t; textSize = size; setTextColor(color); gravity = Gravity.CENTER }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = 0xFF06060C.toInt(); window.navigationBarColor = 0xFF08080F.toInt()
        val root = FrameLayout(this)
        view = OmenView(this); root.addView(view)
        val title = tv("O M E N", 30f, 0xFFC9D4F0.toInt()).apply { letterSpacing = 0.3f }
        root.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(56) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; setPadding(dp(24), 0, dp(24), dp(36)) }
        status = tv("DORMANT", 13f, 0xFF8D9BB8.toInt()).apply { letterSpacing = 0.3f }
        userT = tv("", 14f, 0xFF8D9BB8.toInt()); replyT = tv("Say \"Omen\" after you wake me.", 17f, 0xFFE6ECFF.toInt())
        col.addView(status); col.addView(userT); col.addView(replyT)
        btn = Button(this).apply { setTextColor(Color.WHITE); background = pill(0xCC10121F.toInt()); setOnClickListener { toggle() } }
        col.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)).apply { topMargin = dp(18) })
        val gear = Button(this).apply { text = "SETUP"; setTextColor(0xFF8D9BB8.toInt()); background = null; setOnClickListener { setup() } }
        col.addView(gear)
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        refresh()
        if (getSharedPreferences("omen", MODE_PRIVATE).getString("key", "").isNullOrBlank()) setup()
    }

    override fun onStart() { super.onStart(); ContextCompat.registerReceiver(this, uiRx, IntentFilter("com.neelabh.omen.UI"), ContextCompat.RECEIVER_NOT_EXPORTED); refresh() }
    override fun onStop() { super.onStop(); try { unregisterReceiver(uiRx) } catch (e: Exception) {} }
    override fun onResume() { super.onResume(); AlarmReceiver.rt?.stop(); refresh() }

    private fun refresh() { btn.text = if (OmenService.running) "SEND ME BACK" else "AWAKEN OMEN"; if (!OmenService.running) status.text = "DORMANT" }

    private fun toggle() {
        if (OmenService.running) { stopService(Intent(this, OmenService::class.java)); view.state = "idle"; btn.text = "AWAKEN OMEN"; status.text = "DORMANT"; return }
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) start() else requestPermissions(perms, 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else Toast.makeText(this, "I cannot hear without the microphone.", Toast.LENGTH_LONG).show()
    }

    private fun start() {
        ContextCompat.startForegroundService(this, Intent(this, OmenService::class.java))
        if (!Settings.canDrawOverlays(this)) Toast.makeText(this, "Open SETUP and allow 'display over apps' so I can open apps by voice.", Toast.LENGTH_LONG).show()
        refresh()
    }

    private fun setup() {
        val prefs = getSharedPreferences("omen", MODE_PRIVATE)
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), 0) }
        val key = EditText(this).apply { hint = "Groq API key (gsk_...)"; setText(prefs.getString("key", "")); isSingleLine = true }
        lay.addView(key)
        fun b(t: String, f: () -> Unit) = lay.addView(Button(this).apply { text = t; setOnClickListener { f() } })
        b("Save and test key") {
            val k = key.text.toString().trim(); prefs.edit().putString("key", k).apply()
            Brain.bad.clear(); Brain.models = emptyList(); Brain.brain = ""
            Thread {
                try { Brain.load(k); runOnUiThread { Toast.makeText(this, "Connected. Brain: ${Brain.brain}. Search: ${Brain.web.ifEmpty { "not on this account" }}", Toast.LENGTH_LONG).show() } }
                catch (e: Exception) { runOnUiThread { Toast.makeText(this, "Key problem: ${e.message}", Toast.LENGTH_LONG).show() } }
            }.start()
        }
        b("1. Allow display over apps (open apps by voice)") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        b("2. Enable WhatsApp auto-send (optional)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        b("3. Allow background use (battery)") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        AlertDialog.Builder(this).setTitle("OMEN setup").setView(lay).setPositiveButton("Close", null).show()
    }
}
