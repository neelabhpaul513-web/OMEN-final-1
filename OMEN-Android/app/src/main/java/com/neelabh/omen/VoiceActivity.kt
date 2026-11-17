package com.neelabh.omen

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Voice Control dashboard: status, live log, controls, microphone test and command list. */
class VoiceActivity : DashActivity() {
    private val h = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("omen", MODE_PRIVATE) }
    private lateinit var stat: TextView
    private lateinit var wakeInfo: TextView
    private lateinit var svcBtn: Button
    private lateinit var log: LinearLayout
    private lateinit var meter: ProgressBar
    private var tts: TextToSpeech? = null
    private var lastSig = ""
    private val tick = object : Runnable { override fun run() { refresh(); h.postDelayed(this, 1000) } }

    private fun sw(parent: ViewGroup, title: String, k: String, def: Boolean) {
        parent.addView(Switch(this).apply {
            text = title; setTextColor(Ui.TXT); isChecked = prefs.getBoolean(k, def); setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(k, v).apply() }
        })
    }

    private fun slider(parent: ViewGroup, title: String, k: String, def: Float, min: Float, max: Float) {
        val label = text(parent, title + ": " + String.format(Locale.US, "%.2f", prefs.getFloat(k, def)), 14f, Ui.TXT)
        parent.addView(SeekBar(this).apply {
            this.max = 100; progress = ((prefs.getFloat(k, def) - min) / (max - min) * 100).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + (max - min) * p / 100f
                    prefs.edit().putFloat(k, v).apply(); label.text = title + ": " + String.format(Locale.US, "%.2f", v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { if (OmenService.running) startService(Intent(this@VoiceActivity, OmenService::class.java).putExtra("tts", true)) }
            })
        })
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); build("Voice Control")
        val s = card(); section(s, "Status")
        stat = text(s, "", 15f)
        wakeInfo = text(s, "", 13f, Ui.MUT)
        svcBtn = button(s, "…") { toggleService() }
        button(s, "Download offline wake-word model (about 40 MB)") {
            if (WakeModel.ready(this)) { toast("Already downloaded"); return@button }
            wakeInfo.text = "Downloading… 0%"
            Thread {
                try { WakeModel.download(this) { p -> runOnUiThread { wakeInfo.text = "Downloading… $p%" } }; runOnUiThread { toast("Model ready. Send OMEN back and awaken it again.") } }
                catch (e: Exception) { runOnUiThread { wakeInfo.text = "Download failed: " + e.message } }
            }.start()
        }

        val l = card(); section(l, "Live activity (newest first)")
        log = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        l.addView(log)

        val c = card(); section(c, "Controls")
        sw(c, "Offline “Hey Omen” detector", "hwake", true)
        sw(c, "Require the wake word", "wake", true)
        sw(c, "Smart wake (clear commands work without Omen)", "smart", true)
        sw(c, "Speak replies to typed messages", "voice", true)
        slider(c, "Voice pitch (lower is deeper)", "pitch", 0.45f, 0.1f, 2f)
        slider(c, "Voice speed", "rate", 0.82f, 0.5f, 1.5f)
        button(c, "Test the voice") {
            tts?.shutdown()
            tts = TextToSpeech(this) { st ->
                if (st == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.UK; tts?.setPitch(prefs.getFloat("pitch", 0.45f)); tts?.setSpeechRate(prefs.getFloat("rate", 0.82f))
                    tts?.speak("What can I do for you, sir?", TextToSpeech.QUEUE_FLUSH, null, "t")
                }
            }
        }

        val m = card(); section(m, "Microphone test")
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        m.addView(meter, lp(MATCH, WRAP))
        button(m, "Test microphone (5 seconds)") { micTest() }

        val k = card(); section(k, "What you can say")
        text(k, "Wake: “Hey Omen”, “Hey Women”, “Omen”\n\n" +
            "Phone: open WhatsApp · call Rahul · call Rahul on speaker · message Rahul saying I am late · set an alarm for 6:30 am · timer for 10 minutes · flashlight on · volume up · pause · next song · navigate to the station · play lo-fi on YouTube · Wi-Fi settings\n\n" +
            "Knowledge: search for … · who is … · weather in … · show pictures of … · create an image of … · read my notes · take a note …\n\n" +
            "Pharma: full research on metformin · structure of aspirin · ADMET of ibuprofen · targets of imatinib · dock metformin with AMPK · screen aspirin, ibuprofen, naproxen · compounds active against EGFR\n\n" +
            "Camera: camera search", 13f, Ui.TXT)
    }

    private fun refresh() {
        val running = OmenService.running
        val wake = prefs.getBoolean("hwake", true) && WakeModel.ready(this)
        stat.text = "OMEN is " + (if (running) "awake" else "dormant") + "\nListening mode: " + (if (wake) "offline “Hey Omen” detector" else "Google speech listening") +
            "\n“Hey Omen” detections: " + VoiceLog.wakes + "   ·   mic errors: " + VoiceLog.errors + (if (VoiceLog.errors > 0) " (last code " + VoiceLog.lastErr + ")" else "") +
            "\nSpeech service: " + (if (SpeechRecognizer.isRecognitionAvailable(this)) "available" else "NOT installed (install the Google app)")
        wakeInfo.text = if (WakeModel.ready(this)) "Wake-word model: ready ✔" else "Wake-word model: not downloaded"
        svcBtn.text = if (running) "Send OMEN back" else "Awaken OMEN"
        val ev = VoiceLog.all(); val sig = ev.size.toString() + (ev.firstOrNull()?.t ?: 0L)
        if (sig != lastSig) {
            lastSig = sig; log.removeAllViews()
            if (ev.isEmpty()) text(log, "Nothing yet. Say “Hey Omen”.", 13f, Ui.MUT)
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            ev.take(25).forEach { e ->
                val color = when (e.kind) { "heard" -> Ui.TXT; "reply" -> Ui.ACC; "wake" -> Ui.OK; else -> Ui.BAD }
                text(log, fmt.format(Date(e.t)) + "  " + e.kind.uppercase() + "  " + e.text, 12f, color)
            }
        }
    }

    private fun toggleService() {
        if (OmenService.running) { stopService(Intent(this, OmenService::class.java)); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(Perms.list(), 1); return }
        ContextCompat.startForegroundService(this, Intent(this, OmenService::class.java))
    }

    @SuppressLint("MissingPermission")
    private fun micTest() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(Perms.list(), 1); return }
        if (OmenService.running) startService(Intent(this, OmenService::class.java).putExtra("pause", true))
        Thread {
            var peak = 0
            try {
                val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val ar = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8000))
                if (ar.state != AudioRecord.STATE_INITIALIZED) throw Exception("the microphone is busy")
                ar.startRecording()
                val buf = ShortArray(1600); val end = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < end) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n > 0) {
                        var mx = 0
                        for (i in 0 until n) mx = maxOf(mx, Math.abs(buf[i].toInt()))
                        peak = maxOf(peak, mx)
                        val lvl = minOf(100, mx * 100 / 12000)
                        runOnUiThread { meter.progress = lvl }
                    }
                }
                ar.stop(); ar.release()
                runOnUiThread { meter.progress = 0; toast(if (peak > 1500) "Microphone works (peak " + (peak * 100 / 32767) + "%)" else "Very quiet. Speak louder or check the microphone.") }
            } catch (e: Exception) { runOnUiThread { toast("Mic test failed: " + e.message) } }
            if (OmenService.running) startService(Intent(this, OmenService::class.java).putExtra("resume", true))
        }.start()
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }
    override fun onDestroy() { super.onDestroy(); try { tts?.shutdown() } catch (e: Exception) {} }
}
