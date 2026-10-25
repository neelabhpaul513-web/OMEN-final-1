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
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val perms = mutableListOf(
        Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS
    ).apply { if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }.toTypedArray()

    private lateinit var view: OmenView
    private lateinit var status: TextView
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var btn: Button
    private lateinit var input: EditText
    private lateinit var webBtn: Button
    private lateinit var voiceBtn: Button
    private var webMode = false
    private var pendingText: String? = null
    private var pendingSearch = false

    private val uiRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            view.state = i.getStringExtra("state") ?: view.state
            status.text = stateLabel()
            i.getStringExtra("heard")?.let { status.text = "HEARD: " + it.take(40).uppercase() }
            render(); refresh()
        }
    }

    private fun stateLabel() = when (view.state) {
        "listening" -> "LISTENING"; "thinking" -> "THINKING"; "speaking" -> "SPEAKING"
        else -> if (OmenService.running) "WATCHING" else "DORMANT"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun pill(fill: Int, r: Int = 32) = GradientDrawable().apply { cornerRadius = dp(r).toFloat(); setColor(fill); setStroke(dp(2), 0xFF8D9BB8.toInt()) }
    private fun tv(t: String, size: Float, color: Int) = TextView(this).apply { text = t; textSize = size; setTextColor(color); gravity = Gravity.CENTER }
    private val prefs by lazy { getSharedPreferences("omen", MODE_PRIVATE) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = 0xFF06060C.toInt(); window.navigationBarColor = 0xFF08080F.toInt()
        val root = FrameLayout(this)
        view = OmenView(this); root.addView(view)
        val title = tv("O M E N", 28f, 0xFFC9D4F0.toInt()).apply { letterSpacing = 0.3f }
        root.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(44) })

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(10)) }
        col.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 47f))
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        scroll.addView(chat)
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 53f))

        status = tv("DORMANT", 12f, 0xFF8D9BB8.toInt()).apply { letterSpacing = 0.3f }
        col.addView(status)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Type or ask OMEN…"; setHintTextColor(0xFF6F7A94.toInt()); setTextColor(0xFFE6ECFF.toInt())
            background = pill(0xCC10121F.toInt(), 26); setPadding(dp(18), dp(8), dp(18), dp(8)); isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, _, _ -> submit(); true }
        }
        row.addView(input, LinearLayout.LayoutParams(0, dp(52), 1f))
        fun small(t: String, f: () -> Unit) = Button(this).apply {
            text = t; textSize = 18f; setTextColor(0xFFE6ECFF.toInt()); background = null
            minWidth = 0; minimumWidth = 0; setPadding(dp(6), 0, dp(6), 0); setOnClickListener { f() }
        }
        webBtn = small("🔍") { webMode = !webMode; webBtn.background = if (webMode) pill(0x663A4570, 20) else null
            Toast.makeText(this, if (webMode) "Web search mode ON" else "Chat mode", Toast.LENGTH_SHORT).show() }
        voiceBtn = small(if (prefs.getBoolean("voice", true)) "🔊" else "🔇") {
            val v = !prefs.getBoolean("voice", true); prefs.edit().putBoolean("voice", v).apply(); voiceBtn.text = if (v) "🔊" else "🔇"
            Toast.makeText(this, if (v) "Typed replies will be spoken" else "Typed replies are silent", Toast.LENGTH_SHORT).show() }
        val mic = small("🎤") { startVoice() }
        val send = small("➤") { submit() }
        row.addView(mic, LinearLayout.LayoutParams(dp(44), dp(52)))
        row.addView(webBtn, LinearLayout.LayoutParams(dp(46), dp(52)))
        row.addView(voiceBtn, LinearLayout.LayoutParams(dp(46), dp(52)))
        row.addView(send, LinearLayout.LayoutParams(dp(46), dp(52)))
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        btn = Button(this).apply { setTextColor(Color.WHITE); background = pill(0xCC10121F.toInt()); setOnClickListener { toggle() } }
        col.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) })

        val links = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun link(t: String, f: () -> Unit) = Button(this).apply { text = t; setTextColor(0xFF8D9BB8.toInt()); background = null; setOnClickListener { f() } }
        links.addView(link("SETUP") { setup() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        links.addView(link("CLEAR CHAT") { ChatLog.clear(this); render() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(links)

        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        render(); refresh()
        if (prefs.getString("key", "").isNullOrBlank()) setup()
    }

    override fun onStart() { super.onStart(); ContextCompat.registerReceiver(this, uiRx, IntentFilter("com.neelabh.omen.UI"), ContextCompat.RECEIVER_NOT_EXPORTED); render(); refresh() }
    override fun onStop() { super.onStop(); try { unregisterReceiver(uiRx) } catch (e: Exception) {} }
    override fun onResume() { super.onResume(); AlarmReceiver.rt?.stop(); refresh() }

    private fun bubble(t: String, user: Boolean) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(0xFFE6ECFF.toInt()); setTextIsSelectable(true)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat(); setColor(if (user) 0xFF222845.toInt() else 0xCC10121F.toInt())
            setStroke(dp(1), if (user) 0xFFA8B8FF.toInt() else 0xFF3A4156.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { gravity = if (user) Gravity.END else Gravity.START; topMargin = dp(6) }
    }

    private fun render() {
        chat.removeAllViews()
        val items = ChatLog.all(this)
        if (items.isEmpty()) chat.addView(bubble("Ask me anything. Tap 🔍 to search the web, or say \"Omen\" after you awaken me.", false))
        items.forEach { chat.addView(bubble(it.second, it.first)) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refresh() {
        btn.text = if (OmenService.running) "SEND ME BACK" else "AWAKEN OMEN"
        if (view.state == "idle") status.text = stateLabel()
    }

    private fun submit() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        ChatLog.add(this, true, t); render()
        send(t, webMode)
    }

    private fun send(t: String, search: Boolean, spoken: Boolean = false) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingText = t; pendingSearch = search; requestPermissions(perms, 1); return
        }
        ContextCompat.startForegroundService(this, Intent(this, OmenService::class.java).putExtra("cmd", t).putExtra("search", search).putExtra("spoken", spoken))
        view.state = "thinking"; status.text = "THINKING"
    }

    private fun startVoice() {
        if (OmenService.running) startService(Intent(this, OmenService::class.java).putExtra("pause", true))
        try {
            startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to OMEN"), 77)
        } catch (e: Exception) {
            Toast.makeText(this, "No speech service found. Install the Google app.", Toast.LENGTH_LONG).show()
            if (OmenService.running) startService(Intent(this, OmenService::class.java).putExtra("resume", true))
        }
    }

    override fun onActivityResult(rc: Int, res: Int, d: Intent?) {
        super.onActivityResult(rc, res, d)
        if (rc != 77) return
        if (OmenService.running) startService(Intent(this, OmenService::class.java).putExtra("resume", true))
        val t = d?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (res == RESULT_OK && !t.isNullOrBlank()) { ChatLog.add(this, true, t); render(); send(t, webMode, true) }
    }

    private fun toggle() {
        if (OmenService.running) { stopService(Intent(this, OmenService::class.java)); view.state = "idle"; refresh(); status.text = "DORMANT"; return }
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) start() else requestPermissions(perms, 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val t = pendingText
            if (t != null) { pendingText = null; send(t, pendingSearch) } else start()
        } else Toast.makeText(this, "I cannot hear without the microphone.", Toast.LENGTH_LONG).show()
    }

    private fun start() {
        ContextCompat.startForegroundService(this, Intent(this, OmenService::class.java))
        if (!Settings.canDrawOverlays(this)) Toast.makeText(this, "Open SETUP and allow 'display over apps' so I can open apps by voice.", Toast.LENGTH_LONG).show()
        refresh()
    }

    private fun setup() {
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
        lay.addView(Button(this).apply {
            fun lbl() = "Wake word \"Omen\" required: " + (if (prefs.getBoolean("wake", true)) "ON" else "OFF (answers everything it hears)")
            text = lbl()
            setOnClickListener { prefs.edit().putBoolean("wake", !prefs.getBoolean("wake", true)).apply(); text = lbl() }
        })
        b("1. Allow display over apps (open apps by voice)") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        b("2. Enable WhatsApp auto-send (optional)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        b("3. Allow background use (battery)") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        AlertDialog.Builder(this).setTitle("OMEN setup").setView(lay).setPositiveButton("Close", null).show()
    }
}
