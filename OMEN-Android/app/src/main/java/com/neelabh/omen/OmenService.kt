package com.neelabh.omen

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import android.os.BatteryManager
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
import android.view.KeyEvent
import java.text.DateFormat
import java.util.*

class OmenService : Service(), TextToSpeech.OnInitListener {
    companion object { @Volatile var running = false }

    private lateinit var tts: TextToSpeech
    private lateinit var act: Actions
    private var rec: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private var speaking = false
    private var alive = true
    private var awaitUntil = 0L
    private lateinit var overlay: OverlayUi
    private var ringing = false
    private var callerNumber: String? = null
    private var engagedUntil = 0L
    private var commandWindowUntil = 0L
    private var wakeEngine: WakeEngine? = null
    private var wakeBlockedUntil = 0L
    private var wakeFails = 0
    private var errStreak = 0
    private var defaultLang = false
    private var preferOffline = false
    private var ttsReady = false
    private var paused = false
    private var typedMode = false
    private val pending = mutableListOf<Pair<String, Boolean>>()
    private val history = mutableListOf<Pair<String, String>>()
    private val wake = Regex("\\b(omen|oman|owen|ohmen|o men|oh men|amen|omin|omn|ommen|aumen|ooman|aman|amaan|ahmen|aaman|ohman|(?:hey|hi|ok|okay) (?:women|woman)|^(?:women|woman))\\b")
    private val cmdVerb = Regex("^(open|launch|call|dial|phone|set|search|look up|google|research|message|whatsapp|text|sms|timer|alarm|remind|show|play|pause|resume|next|skip|navigate|directions|flashlight|torch|volume|mute|unmute|note|take a note|read|generate|create|make|draw|imagine|goodbye|answer|decline|what|who|when|where|why|how|tell|weather|news|find)\\b")
    @Volatile private var lastCb = 0L
    private val numWords = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6",
        "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10", "fifteen" to "15", "twenty" to "20", "thirty" to "30")
    private val prefs by lazy { getSharedPreferences("omen", MODE_PRIVATE) }

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (i?.getBooleanExtra("pause", false) == true) {
            paused = true; wakeEngine?.stop(); main.post { rec?.destroy(); rec = null }
            main.postDelayed({ if (paused) { paused = false; listen() } }, 25000)
        }
        if (i?.getBooleanExtra("resume", false) == true) { paused = false; listen() }
        i?.getStringExtra("say")?.let { t ->
            if (ttsReady && prefs.getBoolean("voice", true)) { typedMode = false; say(t, false) }
        }
        i?.getStringExtra("cmd")?.let { c ->
            val search = i.getBooleanExtra("search", false)
            if (ttsReady) typed(c, search, i.getBooleanExtra("spoken", false)) else pending.add(c to search)
        }
        return START_STICKY
    }

    private fun typed(c: String, search: Boolean, spoken: Boolean = false) {
        typedMode = !spoken
        ui("thinking")
        Thread {
            try {
                val cmd = c.trim().replace(Regex("^(omen|oman)[, ]+", RegexOption.IGNORE_CASE), "")
                if (search) searchWeb(cmd) else execute(cmd, spoken)
            } catch (e: Exception) { say("Something broke. ${e.message}") }
        }.start()
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        act = Actions(this)
        overlay = OverlayUi(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("omen", "OMEN", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, "omen").setContentTitle("OMEN is watching from the shadows")
            .setContentText("Say \"Hey Omen\" from any app").setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1, n)
        tts = TextToSpeech(this, this)
        main.postDelayed(watchdog, 15000)
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
            override fun onStart(id: String?) { speaking = true; wakeEngine?.stop(); main.post { rec?.cancel() } }
            override fun onDone(id: String?) { speaking = false; ui("idle"); main.postDelayed({ listen() }, 300) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { speaking = false; main.post { listen() } }
        })
        ttsReady = true
        if (pending.isEmpty()) say("Omen online. Speak.", false)
        pending.toList().forEach { typed(it.first, it.second) }
        pending.clear()
    }

    private fun say(t: String, log: Boolean = true) {
        ui("speaking", reply = t, log = log)
        if (typedMode && !prefs.getBoolean("voice", true)) { main.postDelayed({ ui("idle") }, 400); return }
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    private fun activate() { main.post { if (!MainActivity.visible) overlay.show() } }

    private fun ui(state: String, user: String? = null, reply: String? = null, heard: String? = null, log: Boolean = true) {
        user?.let { ChatLog.add(this, true, it.removePrefix("› ")) }
        if (reply != null && state == "speaking" && log) ChatLog.add(this, false, reply)
        sendBroadcast(Intent("com.neelabh.omen.UI").setPackage(packageName).putExtra("state", state).putExtra("user", user).putExtra("reply", reply).putExtra("heard", heard))
        main.post { overlay.update(state, user, reply) }
    }

    private val doListen = Runnable {
        if (!alive || speaking || paused) return@Runnable
        lastCb = System.currentTimeMillis()
        val nowMs = System.currentTimeMillis()
        if (wakeMode() && nowMs > commandWindowUntil && nowMs > engagedUntil && nowMs >= awaitUntil) {
            rec?.destroy(); rec = null
            startWake(); return@Runnable
        }
        wakeEngine?.stop()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            ui("listening", heard = "no speech service. install the Google app")
            main.postDelayed({ listen() }, 6000); return@Runnable
        }
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(this).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) {
                    lastCb = System.currentTimeMillis(); errStreak = 0
                    val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val best = list?.firstOrNull { wake.containsMatchIn(it.lowercase()) } ?: list?.firstOrNull()
                    best?.let { handle(it) }
                    main.postDelayed({ listen() }, 250)
                }
                override fun onError(e: Int) {
                    lastCb = System.currentTimeMillis()
                    val benign = e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    if (benign) errStreak = 0 else errStreak++
                    when (e) {
                        12, 13 -> defaultLang = true   // language pack unavailable: use the phone's default language
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> preferOffline = true
                    }
                    if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) ui("listening", heard = "mic permission missing. open Settings")
                    else if (errStreak >= 4) ui("listening", heard = "mic problem (code $e), retrying")
                    val delay = if (benign) 250L else if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) 6000L else minOf(5000L, 500L * (1 shl minOf(errStreak, 4)))
                    main.postDelayed({ listen() }, delay)
                }
                override fun onReadyForSpeech(p: Bundle?) { lastCb = System.currentTimeMillis(); if (!speaking) ui("listening") }
                override fun onBeginningOfSpeech() { lastCb = System.currentTimeMillis(); errStreak = 0 }
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            val go = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            if (!defaultLang) go.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            if (preferOffline) go.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            try { r.startListening(go) } catch (e: Exception) { errStreak++; main.postDelayed({ listen() }, 1500) }
        }
    }

    private fun listen() { main.removeCallbacks(doListen); main.post(doListen) }

    private fun wakeMode() = System.currentTimeMillis() >= wakeBlockedUntil && prefs.getBoolean("hwake", true) && WakeModel.ready(this)

    private fun wakeFailed() {
        wakeFails++
        wakeBlockedUntil = System.currentTimeMillis() + (if (wakeFails < 3) 1500L else 60000L)
        if (wakeFails >= 3) wakeFails = 0
        listen()
    }

    private fun startWake() {
        if (wakeEngine == null) wakeEngine = WakeEngine(this, { main.post { onWakeDetected() } }, { main.post { wakeFailed() } })
        main.postDelayed({ if (alive && !speaking && !paused) wakeEngine?.start() }, 250)
    }

    /** "Hey Omen" heard (offline detector): pop up over whatever app is open, greet, then listen for the command. */
    private fun onWakeDetected() {
        val now = System.currentTimeMillis()
        wakeFails = 0; errStreak = 0
        commandWindowUntil = now + 10000; awaitUntil = now + 12000
        activate()
        say("What can I do for you, sir?", false)
    }

    // restarts the microphone if the recogniser silently dies
    private val watchdog = object : Runnable {
        override fun run() {
            if (!alive) return
            if (!speaking && !paused && System.currentTimeMillis() - lastCb > 25000) listen()
            main.postDelayed(this, 15000)
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
                        main.postDelayed({ if (ringing) { commandWindowUntil = System.currentTimeMillis() + 40000; say("Incoming call from ${nameFor(callerNumber)}. Say answer, or decline.") } }, 1200)
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
        typedMode = false
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
        val now = System.currentTimeMillis()
        val engaged = now < engagedUntil
        if (m != null) {
            cmd = t.substring(m.range.last + 1).trim(' ', ',', '.', '!')
            if (cmd.isEmpty()) { awaitUntil = now + 12000; activate(); say("What can I do for you, sir?", false); return }
        } else if (now >= awaitUntil && !engaged && prefs.getBoolean("wake", true)) {
            if (!(prefs.getBoolean("smart", true) && cmdVerb.containsMatchIn(t))) { ui("listening", heard = raw); return }
        }
        awaitUntil = 0L
        activate()
        ui("thinking", user = "› $raw")
        Thread { try { execute(cmd) } catch (e: Exception) { say("Something broke. ${e.message}") }; engagedUntil = System.currentTimeMillis() + 20000 }.start()
    }

    private fun searchWeb(q: String) {
        val key = prefs.getString("key", "") ?: ""
        if (key.isBlank()) return say("Give me your Groq key in the settings.")
        ChatLog.add(this, false, "Searching the web…"); ui("thinking")
        showResearch(Brain.researchRefs(key, q))
    }

    private fun showResearch(r: Brain.Research) {
        val refs = if (r.refs.isEmpty()) "" else "\n\nReferences:\n" + r.refs.mapIndexed { i, p -> "${i + 1}. ${p.first}\n${p.second}" }.joinToString("\n")
        ChatLog.add(this, false, r.text + refs)
        say(r.text, false)
    }

    private fun readLink(url: String, task: String) {
        val key = prefs.getString("key", "") ?: ""
        if (key.isBlank()) return say("Give me your Groq key in the settings.")
        ChatLog.add(this, false, "Reading the page…"); ui("thinking")
        showResearch(Brain.askAboutPage(key, url, task))
    }

    private fun generateImage(prompt: String) {
        ChatLog.add(this, false, "Creating your image… this can take up to a minute."); ui("thinking")
        val path = Brain.generateImage(this, prompt)
        ChatLog.add(this, false, "\u0001IMG:$path\u0001$prompt")
        say("Your image is ready.", false)
    }

    private fun imageSearch(q: String) {
        ChatLog.add(this, false, "Searching images…"); ui("thinking")
        val paths = Brain.images(this, q)
        if (paths.isEmpty()) return say("I found no images of $q.")
        ChatLog.add(this, false, "\u0001GAL:" + paths.joinToString("|") + "\u0001Images of $q (Wikimedia Commons)")
        say("Here are images of $q.", false)
    }

    private fun looksLikeQuestion(t: String): Boolean {
        if (Regex("joke|hello|how are you|your name|thank|good (morning|night|evening)").containsMatchIn(t)) return false
        return t.endsWith("?") || Regex("^(who|what|when|where|why|how|which|is|are|was|were|do|does|did|can|will|should|tell|give|explain|latest|news|weather|price|score|define|meaning)\\b").containsMatchIn(t)
    }

    private fun execute(c: String, searchFallback: Boolean = false) {
        var t = c.lowercase()
        numWords.forEach { (k, v) -> t = t.replace(Regex("\\b$k\\b"), v) }
        var m: MatchResult?
        // instant local answers
        if (Regex("\\b(what('s| is)? the time|what time is it|time now|current time|tell me the time)\\b").containsMatchIn(t))
            return say("It is " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()) + ".")
        if (Regex("\\b(today'?s date|what('s| is)? (the )?date|what day is it|which day is it|what('s| is)? the day)\\b").containsMatchIn(t))
            return say("Today is " + java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date()) + ".")
        if (Regex("\\bbattery\\b").containsMatchIn(t) && Regex("level|percent|charge|how much|status|left").containsMatchIn(t))
            return say("Battery is at " + getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + " percent.")
        val urlM = Regex("https?://\\S+").find(c)
        if (urlM != null) return readLink(urlM.value.trimEnd('.', ',', ')'), c.replace(urlM.value, "").trim().ifEmpty { "Summarize this page." })
        // phone controls
        if (Regex("\\b(flashlight|torch)\\b").containsMatchIn(t))
            return say(act.flashlight(!Regex("\\b(off|disable|close|stop)\\b").containsMatchIn(t)))
        m = Regex("\\b(wi-?fi|bluetooth|airplane|location|display|sound) settings\\b").find(t)
        if (m != null) return say(act.settingsPanel(m.groupValues[1]))
        if (Regex("\\b(volume (up|down)|(increase|raise|decrease|lower)( the)? volume|louder|quieter|turn it (up|down)|unmute|mute|max volume|full volume)\\b").containsMatchIn(t)) {
            val kind = when {
                Regex("unmute").containsMatchIn(t) -> "unmute"
                Regex("mute").containsMatchIn(t) -> "mute"
                Regex("max|full").containsMatchIn(t) -> "max"
                Regex("down|decrease|lower|quieter").containsMatchIn(t) -> "down"
                else -> "up"
            }
            return say(act.volume(kind))
        }
        if (Regex("^(?:pause|stop)(?: the)?(?: music| song| video| playback| player)?$").containsMatchIn(t)) return say(act.media(KeyEvent.KEYCODE_MEDIA_PAUSE))
        if (Regex("^(?:resume|continue|play)(?: the)?(?: music| song| video| playback| player)?$").containsMatchIn(t)) return say(act.media(KeyEvent.KEYCODE_MEDIA_PLAY))
        if (Regex("^(?:next|skip)(?: the)?(?: song| track| video)?$").containsMatchIn(t)) return say(act.media(KeyEvent.KEYCODE_MEDIA_NEXT))
        if (Regex("^(?:previous|go back)(?: the)?(?: song| track| video)?$").containsMatchIn(t)) return say(act.media(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        m = Regex("^(?:take a note|make a note|add a note|note down|note)\\s*(?:that|to)?[:,]?\\s+(.+)").find(t)
        if (m != null) return say(act.addNote(m.groupValues[1]))
        if (Regex("\\b(read|show|list|what are)( me)?( my)? notes\\b").containsMatchIn(t)) return say(act.notes())
        m = Regex("^(?:navigate to|directions to|take me to|route to|drive to|how do i get to)\\s+(.+)").find(t)
        if (m != null) return say(act.navigate(m.groupValues[1]))
        m = Regex("^play\\s+(.+?)(?:\\s+on\\s+(youtube|spotify))?$").find(t)
        if (m != null) return say(act.playOn(m.groupValues[1], m.groupValues[2]))
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
        if (m != null) {
            val rest = m.groupValues[1]
            val spk = Regex("\\b(speaker|loudspeaker|speakerphone)\\b").containsMatchIn(rest)
            val who = rest.split(Regex("\\s+(?:and|on|with|in|then|using)\\s+"))[0].trim()
            return say(act.call(who, spk))
        }
        m = Regex("(?:whatsapp|message|text|sms)\\s+(.+?)\\s+(?:saying|that|say)\\s+(.+)").find(t)
        if (m != null) {
            val sms = Regex("^(text|sms)\\b").containsMatchIn(t)
            return say(if (sms) act.sms(m.groupValues[1], m.groupValues[2]) else act.whatsapp(m.groupValues[1], m.groupValues[2]))
        }
        m = Regex("^(?:generate|create|make|draw|paint|render|design)(?:\\s+me)?(?:\\s+an?)?\\s+(?:image|picture|photo|logo|poster|wallpaper|illustration|artwork|drawing|painting)\\s*(?:of|for|showing|with|about)?\\s*(.+)").find(t)
            ?: Regex("^(?:imagine|draw|paint)\\s+(.+)").find(t)
        if (m != null) return generateImage(m.groupValues[1])
        m = Regex("^(?:show|find|get|search|give|display)(?:\\s+me)?(?:\\s+(?:some|a few))?\\s+(?:images?|pictures?|photos?|pics?)\\s+(?:of|for)\\s+(.+)").find(t)
            ?: Regex("^(?:images?|pictures?|photos?|pics?)\\s+(?:of|for)\\s+(.+)").find(t)
        if (m != null) return imageSearch(m.groupValues[1])
        m = Regex("^(?:search for|search|look up|google|research|find out about|find out|tell me about)\\s+(.+)").find(t)
        if (m != null) {
            return searchWeb(m.groupValues[1])
        }
        if (Regex("goodbye|shut down|stop listening").containsMatchIn(t)) { say("Back to the shadows."); main.postDelayed({ stopSelf() }, 2500); return }
        if (searchFallback || looksLikeQuestion(t)) searchWeb(c) else ai(c)
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
            out.toString().trim().let { if (it.isNotEmpty()) say(it) }
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
        "image_search" -> { imageSearch(a.optString("query")); "" }
        "research" -> { searchWeb(a.optString("query")); "" }
        "generate_image" -> { generateImage(a.optString("prompt")); "" }
        "read_website" -> { readLink(a.optString("url"), a.optString("question").ifBlank { "Summarize this page." }); "" }
        else -> "I do not know that one."
    }

    override fun onDestroy() {
        running = false; alive = false
        try { overlay.hide() } catch (e: Exception) {}
        try { wakeEngine?.close() } catch (e: Throwable) {}
        rec?.destroy(); tts.shutdown()
        try { unregisterReceiver(phone) } catch (e: Exception) {}
        ui("idle", reply = "Back in the shadows.")
        super.onDestroy()
    }
}
