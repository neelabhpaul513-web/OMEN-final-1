package com.neelabh.omen

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

class OmenService : Service(), TextToSpeech.OnInitListener {
    companion object { @Volatile var running = false }

    private lateinit var tts: TextToSpeech
    private lateinit var act: Actions
    private var rec: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private var speaking = false
    private var alive = true
    private var awaiting = false
    private var ringing = false
    private var callerNumber: String? = null
    private var engagedUntil = 0L
    private val history = mutableListOf<Pair<String, String>>()
    private val wake = Regex("\\b(omen|oman|owen|ohmen|o men)\\b")
    private val numWords = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6",
        "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10", "fifteen" to "15", "twenty" to "20", "thirty" to "30")
    private val prefs by lazy { getSharedPreferences("omen", MODE_PRIVATE) }

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY

    override fun onCreate() {
        super.onCreate()
        running = true
        act = Actions(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("omen", "OMEN", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, "omen").setContentTitle("OMEN is watching from the shadows")
            .setContentText("Say \"Omen\" followed by a command").setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1, n)
        tts = TextToSpeech(this, this)
        ContextCompat.registerReceiver(this, phone, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
    }

    // ---------- voice: low, slow, shadowy ----------
    override fun onInit(status: Int) {
        tts.language = Locale.US
        val v = tts.voices?.firstOrNull { it.locale.language == "en" && !it.isNetworkConnectionRequired &&
            Regex("iom|iol|rjs|gbb|tpd|sfg", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
        if (v != null) tts.voice = v
        tts.setPitch(0.45f); tts.setSpeechRate(0.82f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { speaking = true; main.post { rec?.cancel() } }
            override fun onDone(id: String?) { speaking = false; ui("idle"); main.postDelayed({ listen() }, 300) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { speaking = false; main.post { listen() } }
        })
        say("Omen online. Speak.")
    }

    private fun say(t: String) {
        ui("speaking", reply = t)
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    private fun ui(state: String, user: String? = null, reply: String? = null) {
        sendBroadcast(Intent("com.neelabh.omen.UI").setPackage(packageName).putExtra("state", state).putExtra("user", user).putExtra("reply", reply))
    }

    private fun listen() {
        if (!alive || speaking) return
        main.post {
            rec?.destroy()
            rec = SpeechRecognizer.createSpeechRecognizer(this).also { r ->
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(b: Bundle?) {
                        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { handle(it) }
                        main.postDelayed({ listen() }, 250)
                    }
                    override fun onError(e: Int) { main.postDelayed({ listen() }, if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 400) }
                    override fun onReadyForSpeech(p: Bundle?) { if (!speaking) ui("listening") }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(b: Bundle?) {}
                    override fun onEvent(t: Int, b: Bundle?) {}
                })
                r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN"))
            }
        }
    }

    // ---------- incoming calls ----------
    private val phone = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.let { callerNumber = it }
                    if (!ringing) {
                        ringing = true
                        main.postDelayed({ if (ringing) say("Incoming call from ${nameFor(callerNumber)}. Say answer, or decline.") }, 1200)
                    }
                }
                else -> { ringing = false; callerNumber = null }
            }
        }
    }

    private fun nameFor(num: String?): String {
        if (num == null) return "an unknown number"
        try {
            val u = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num))
            contentResolver.query(u, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) return it.getString(0) }
        } catch (e: Exception) {}
        return num.toCharArray().joinToString(" ")
    }

    // ---------- commands ----------
    @Suppress("DEPRECATION")
    private fun handle(raw: String) {
        val t = raw.lowercase().trim()
        if (ringing) {
            if (Regex("answer|pick up|accept").containsMatchIn(t)) { try { getSystemService(TelecomManager::class.java).acceptRingingCall() } catch (e: Exception) { say("I could not answer.") }; return }
            if (Regex("decline|reject|cut|hang up|ignore").containsMatchIn(t)) {
                try { if (Build.VERSION.SDK_INT >= 28) getSystemService(TelecomManager::class.java).endCall() } catch (e: Exception) { say("I could not decline.") }
                return
            }
        }
        var cmd = t
        val m = wake.find(t)
        val engaged = System.currentTimeMillis() < engagedUntil
        if (m != null) {
            cmd = t.substring(m.range.last + 1).trim(' ', ',', '.', '!')
            if (cmd.isEmpty()) { awaiting = true; say("I am listening."); return }
        } else if (!awaiting && !engaged) return
        awaiting = false
        ui("thinking", user = "› $raw")
        Thread { try { execute(cmd) } catch (e: Exception) { say("Something broke. ${e.message}") }; engagedUntil = System.currentTimeMillis() + 15000 }.start()
    }

    private fun execute(c: String) {
        var t = c
        numWords.forEach { (k, v) -> t = t.replace(Regex("\\b$k\\b"), v) }
        var m: MatchResult?
        m = Regex("^(?:please\\s+)?(?:open|launch|start)\\s+(.+)").find(t)
        if (m != null) return say(act.openApp(m.groupValues[1]))
        m = Regex("timer.*?(\\d+(?:\\.\\d+)?)\\s*(sec|min|hour|hr)").find(t) ?: Regex("(\\d+(?:\\.\\d+)?)\\s*(sec|min|hour|hr)\\w*\\s+timer").find(t)
        if (m != null) {
            val n = m.groupValues[1].toDouble(); val u = m.groupValues[2][0]
            return say(act.timer((n * (if (u == 'h') 3600 else if (u == 'm') 60 else 1)).toInt()))
        }
        if (!Regex("\\bin\\s+\\d").containsMatchIn(t)) {
            m = Regex("(?:alarm|wake me).*?(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?").find(t)
            if (m != null) {
                var h = m.groupValues[1].toInt(); val mi = m.groupValues[2].ifEmpty { "0" }.toInt(); val p = m.groupValues[3]
                if (p.startsWith("p") && h < 12) h += 12
                if (p.startsWith("a") && h == 12) h = 0
                return say(act.alarm(h, mi))
            }
        }
        m = Regex("^(?:call|dial|phone)\\s+(.+)").find(t)
        if (m != null) return say(act.call(m.groupValues[1]))
        m = Regex("(?:whatsapp|message|text|sms)\\s+(.+?)\\s+(?:saying|that|say)\\s+(.+)").find(t)
        if (m != null) {
            val sms = Regex("^(text|sms)\\b").containsMatchIn(t)
            return say(if (sms) act.sms(m.groupValues[1], m.groupValues[2]) else act.whatsapp(m.groupValues[1], m.groupValues[2]))
        }
        if (Regex("goodbye|shut down|stop listening").containsMatchIn(t)) { say("Back to the shadows."); main.postDelayed({ stopSelf() }, 2500); return }
        ai(c)
    }

    private fun ai(q: String) {
        val key = prefs.getString("key", "") ?: ""
        if (key.isBlank()) return say("Give me your Groq key in the settings.")
        val facts = prefs.getStringSet("facts", emptySet()) ?: emptySet()
        val sys = "You are OMEN, a calm, shadowy, loyal personal assistant inspired by the Valorant character. Speak in short, low-key, slightly mysterious sentences; never long. " +
            "Now: ${Date()}. Known facts about the user: ${facts.joinToString("; ")}. " +
            "Use tools for alarms, timers, opening apps, WhatsApp or SMS messages, calls, remembering user facts, and web research on any factual or current question. " +
            "For chit-chat or jokes answer directly in under 40 words, plain text. Reply in the user's language (English or Hinglish)."
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
        history.takeLast(8).forEach {
            msgs.put(JSONObject().put("role", "user").put("content", it.first))
            msgs.put(JSONObject().put("role", "assistant").put("content", it.second))
        }
        msgs.put(JSONObject().put("role", "user").put("content", q))
        val msg = try { Brain.chat(key, msgs, Brain.tools) } catch (e: Exception) {
            if (Regex("No Groq|No working|Invalid API", RegexOption.IGNORE_CASE).containsMatchIn(e.message ?: "")) throw e
            Brain.chat(key, msgs, null)
        }
        val calls = msg.optJSONArray("tool_calls")
        if (calls != null && calls.length() > 0) {
            val out = StringBuilder()
            for (i in 0 until calls.length()) {
                val f = calls.getJSONObject(i).getJSONObject("function")
                val args = try { JSONObject(f.optString("arguments", "{}")) } catch (e: Exception) { JSONObject() }
                out.append(runTool(f.getString("name"), args, key)).append(' ')
            }
            history.add(q to "(done)")
            say(out.toString().trim())
        } else {
            val a = Brain.clean(Brain.txt(msg)).ifEmpty { "Done." }
            history.add(q to a); say(a)
        }
    }

    private fun runTool(n: String, a: JSONObject, key: String): String = when (n) {
        "set_alarm" -> act.alarm(a.optInt("hour", -1), a.optInt("minute", 0))
        "set_timer" -> act.timer(a.optInt("seconds", 0))
        "open_app" -> act.openApp(a.optString("name"))
        "send_whatsapp" -> act.whatsapp(a.optString("to"), a.optString("message"))
        "send_sms" -> act.sms(a.optString("to"), a.optString("message"))
        "call" -> act.call(a.optString("to"))
        "remember" -> {
            val s = (prefs.getStringSet("facts", emptySet()) ?: emptySet()).toMutableSet(); s.add(a.optString("fact"))
            prefs.edit().putStringSet("facts", s.toList().takeLast(40).toSet()).apply(); "I will remember."
        }
        "research" -> { ui("thinking"); Brain.research(key, a.optString("query")) }
        else -> "I do not know that one."
    }

    override fun onDestroy() {
        running = false; alive = false
        rec?.destroy(); tts.shutdown()
        try { unregisterReceiver(phone) } catch (e: Exception) {}
        ui("idle", reply = "Back in the shadows.")
        super.onDestroy()
    }
}
