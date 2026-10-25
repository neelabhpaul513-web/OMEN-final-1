package com.neelabh.omen

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
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
import java.io.File

class MainActivity : Activity() {
    companion object { @Volatile var visible = false }

    private val ACC = 0xFFA8B8FF.toInt()
    private val MUT = 0xFF8D9BB8.toInt()
    private val TXT = 0xFFE6ECFF.toInt()
    private val INK = 0xFF06060C.toInt()

    private val perms = mutableListOf(
        Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS
    ).apply { if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }.toTypedArray()

    private lateinit var view: OmenView
    private lateinit var status: TextView
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var btn: AwakenView
    private lateinit var input: EditText
    private lateinit var webBtn: ImageButton
    private lateinit var voiceBtn: ImageButton
    private lateinit var previewBox: LinearLayout
    private lateinit var previewImg: ImageView
    private var webMode = false
    private var pendingText: String? = null
    private var pendingSearch = false
    private val pendingImages = mutableListOf<File>()
    private lateinit var previewLabel: TextView
    private var renderedVersion = -1
    private val prefs by lazy { getSharedPreferences("omen", MODE_PRIVATE) }

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
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun icon(res: Int, tint: Int): Drawable? = ContextCompat.getDrawable(this, res)?.mutate()?.also { it.setTint(tint) }
    private fun tv(t: String, size: Float, color: Int) = TextView(this).apply { text = t; textSize = size; setTextColor(color); gravity = Gravity.CENTER }
    private fun circle(fill: Int, stroke: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill); setStroke(dp(1), stroke) }
    private fun pill(fill: Int, stroke: Int, r: Int) = GradientDrawable().apply { cornerRadius = dp(r).toFloat(); setColor(fill); setStroke(dp(1), stroke) }

    private fun circleBtn(res: Int, desc: String, f: () -> Unit) = ImageButton(this).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(TXT); contentDescription = desc
        background = circle(0xCC10121F.toInt(), 0xFF3A4156.toInt()); scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(11), dp(11), dp(11), dp(11)); setOnClickListener { f() }
    }

    private fun chip(label: String, res: Int, f: () -> Unit) = TextView(this).apply {
        text = label; textSize = 12f; letterSpacing = 0.1f; setTextColor(MUT); gravity = Gravity.CENTER
        compoundDrawablePadding = dp(6); setCompoundDrawablesWithIntrinsicBounds(icon(res, MUT), null, null, null)
        setPadding(0, dp(10), 0, dp(10)); isClickable = true; setOnClickListener { f() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = 0xFF05050B.toInt(); window.navigationBarColor = 0xFF07070E.toInt()
        val root = FrameLayout(this)
        view = OmenView(this); root.addView(view)
        val title = ImageView(this).apply { setImageResource(R.drawable.omen_wordmark); setColorFilter(0xFFC9D4F0.toInt()); adjustViewBounds = true }
        root.addView(title, FrameLayout.LayoutParams(dp(190), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(34) })

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4)) }
        col.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 44f))
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        scroll.addView(chat)
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 56f))

        status = tv("DORMANT", 12f, MUT).apply { letterSpacing = 0.3f }
        col.addView(status)

        // photo preview + notes actions (hidden until a photo is attached)
        previewBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val prow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        previewImg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = pill(0xFF10121F.toInt(), ACC, 12); clipToOutline = true }
        prow.addView(previewImg, LinearLayout.LayoutParams(dp(56), dp(56)))
        previewLabel = tv("", 12f, MUT).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        prow.addView(previewLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        prow.addView(circleBtn(R.drawable.ic_close, "Remove photos") { clearPhoto() }, LinearLayout.LayoutParams(dp(36), dp(36)))
        previewBox.addView(prow)
        val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun act(label: String, mode: String) = TextView(this).apply {
            text = label; textSize = 13f; setTextColor(ACC); setPadding(dp(14), dp(8), dp(14), dp(8))
            background = pill(0xCC10121F.toInt(), ACC, 18); isClickable = true
            setOnClickListener { sendPhotos(mode, input.text.toString().trim()) }
        }
        for ((l, md) in listOf("Read text" to "read", "Summarize" to "summary", "Key points" to "key", "Quiz me" to "quiz"))
            acts.addView(act(l, md), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        previewBox.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(acts) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        col.addView(previewBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        // row A: text box + send
        val rowA = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Type or ask OMEN…"; setHintTextColor(0xFF6F7A94.toInt()); setTextColor(TXT)
            background = pill(0xCC10121F.toInt(), 0xFF3A4156.toInt(), 24); setPadding(dp(18), dp(8), dp(18), dp(8)); isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, _, _ -> submit(); true }
        }
        rowA.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
        val send = circleBtn(R.drawable.ic_send, "Send") { submit() }.apply { background = circle(ACC, ACC); imageTintList = ColorStateList.valueOf(INK) }
        rowA.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        col.addView(rowA, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        // row B: tools
        val photo = circleBtn(R.drawable.ic_photo, "Add photo") { pickPhoto() }
        val mic = circleBtn(R.drawable.ic_mic, "Speak") { startVoice() }
        webBtn = circleBtn(R.drawable.ic_search, "Web search mode") {
            webMode = !webMode
            webBtn.background = if (webMode) circle(0xFF3A4570.toInt(), ACC) else circle(0xCC10121F.toInt(), 0xFF3A4156.toInt())
            webBtn.imageTintList = ColorStateList.valueOf(if (webMode) ACC else TXT)
            toast(if (webMode) "Web search mode ON" else "Chat mode")
        }
        voiceBtn = circleBtn(if (prefs.getBoolean("voice", true)) R.drawable.ic_vol_on else R.drawable.ic_vol_off, "Spoken replies") {
            val v = !prefs.getBoolean("voice", true); prefs.edit().putBoolean("voice", v).apply()
            voiceBtn.setImageResource(if (v) R.drawable.ic_vol_on else R.drawable.ic_vol_off)
            toast(if (v) "Typed replies will be spoken" else "Typed replies are silent")
        }
        val rowB = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (v in listOf(photo, mic, webBtn, voiceBtn)) {
            val cell = FrameLayout(this); cell.addView(v, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
            rowB.addView(cell, LinearLayout.LayoutParams(0, dp(46), 1f))
        }
        col.addView(rowB, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        btn = AwakenView(this).apply { onToggle = { toggle() } }
        col.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)).apply { topMargin = dp(2) })

        val links = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        links.addView(chip("HISTORY", R.drawable.ic_history) { showHistory() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        links.addView(chip("SETUP", R.drawable.ic_gear) { setup() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        links.addView(chip("CLEAR", R.drawable.ic_delete) { ChatLog.clear(this); render() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(links)

        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        render(); refresh()
        if (prefs.getString("key", "").isNullOrBlank()) setup()
    }

    override fun onStart() {
        super.onStart(); visible = true
        ContextCompat.registerReceiver(this, uiRx, IntentFilter("com.neelabh.omen.UI"), ContextCompat.RECEIVER_NOT_EXPORTED)
        render(); refresh()
    }
    override fun onStop() { super.onStop(); visible = false; try { unregisterReceiver(uiRx) } catch (e: Exception) {} }
    override fun onResume() { super.onResume(); AlarmReceiver.rt?.stop(); refresh() }

    // ---------- images ----------
    private fun decode(path: String, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= max && o.outHeight / (s * 2) >= max) s *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun showFull(path: String) {
        val iv = ImageView(this).apply { setImageBitmap(decode(path, 1400)); adjustViewBounds = true; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        AlertDialog.Builder(this).setView(iv).setPositiveButton("Close", null).show()
    }

    private fun thumb(path: String, sizeDp: Int) = ImageView(this).apply {
        setImageBitmap(decode(path, dp(sizeDp) * 2)); scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { marginEnd = dp(6) }
        setOnClickListener { showFull(path) }
    }

    private fun savePhoto(uri: Uri): File {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (o.outWidth / s > 1800 || o.outHeight / s > 1800) s *= 2
        var bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
            ?: throw Exception("cannot decode")
        val orient = try { contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } ?: 1 } catch (e: Exception) { 1 }
        val deg = when (orient) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        if (deg != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        val dir = File(filesDir, "chat_images"); dir.mkdirs()
        val f = File(dir, "p${System.currentTimeMillis()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        return f
    }

    private fun pickPhoto() {
        if (pendingImages.size >= 5) { toast("Up to 5 photos at a time"); return }
        try {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 78)
        } catch (e: Exception) { toast("No gallery app found") }
    }

    private fun updatePreview() {
        if (pendingImages.isEmpty()) { previewBox.visibility = View.GONE; return }
        previewBox.visibility = View.VISIBLE
        previewImg.setImageBitmap(decode(pendingImages.last().path, dp(112)))
        previewLabel.text = (if (pendingImages.size == 1) "1 photo attached" else "${pendingImages.size} pages attached") + ". Tap an action or ask your own question."
    }

    private fun clearPhoto() { pendingImages.clear(); updatePreview() }

    private fun notesPrompt(mode: String, typed: String): String {
        val base = "These are photos of the user's notes (handwritten or printed). Read them carefully and in order. Use plain text only: no markdown, no asterisks; use simple dashes and numbers. "
        return base + when (mode) {
            "read" -> "Transcribe all the text exactly as written, keeping headings, bullet points, numbering, formulas and table layout. Write [illegible] for words you cannot read. Output only the transcription."
            "summary" -> "Write a clear summary: first a one-line topic, then the main points as short dashed lines. Fix obvious spelling slips."
            "key" -> "List the key terms with short definitions, and the important facts and formulas. Then give 3 likely exam questions."
            "quiz" -> "Create 6 quiz questions from these notes (mix of short answer and multiple choice), each followed by a brief answer."
            "custom" -> "Do this: $typed"
            else -> "First transcribe the notes accurately, then add a section titled SUMMARY with the key points."
        }
    }

    private fun sendPhotos(mode: String, typed: String) {
        val key = prefs.getString("key", "") ?: ""
        if (key.isBlank()) { toast("Add your Groq key in SETUP first"); return }
        val files = pendingImages.toList()
        if (files.isEmpty()) return
        val prompt = notesPrompt(mode, typed)
        val label = when (mode) { "read" -> "Read text"; "summary" -> "Summarize"; "key" -> "Key points"; "quiz" -> "Quiz me"; "custom" -> typed; else -> "Read and summarize" }
        clearPhoto(); input.setText("")
        val head = if (files.size == 1) "IMG:${files[0].path}" else "GAL:" + files.joinToString("|") { it.path }
        ChatLog.add(this, true, "\u0001$head\u0001$label"); render()
        view.state = "thinking"; status.text = "THINKING"
        Thread {
            val ans = try { Brain.vision(key, prompt, files.map { it.readBytes() }) } catch (e: Exception) {
                try { ocrFallback(key, files, mode, prompt) } catch (e2: Exception) { "Photo analysis failed: ${e.message}" }
            }
            ChatLog.add(this, false, ans)
            runOnUiThread { view.state = "idle"; status.text = stateLabel(); render() }
            if (OmenService.running && prefs.getBoolean("voice", true) && ans.length <= 500) {
                try { startService(Intent(this, OmenService::class.java).putExtra("say", ans)) } catch (e: Exception) {}
            }
        }.start()
    }

    /** No vision model on the Groq key: read the text on this phone, then let the chat model analyse it. */
    private fun ocrFallback(key: String, files: List<File>, mode: String, prompt: String): String {
        val text = files.mapIndexed { i, f -> (if (files.size > 1) "--- page ${i + 1} ---\n" else "") + Ocr.read(f.path) }.joinToString("\n\n").trim()
        if (text.isBlank()) return "I could not find any readable text in the photo."
        if (mode == "read") return text + "\n\n(read with offline OCR on this phone; handwriting may be inaccurate)"
        return Brain.notesFromText(key, text, prompt) + "\n\n(based on offline OCR text)"
    }

    // ---------- chat ----------
    private fun bubble(t: String, user: Boolean) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(TXT); setTextIsSelectable(true)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
        background = pill(if (user) 0xFF222845.toInt() else 0xCC10121F.toInt(), if (user) ACC else 0xFF3A4156.toInt(), 18)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { gravity = if (user) Gravity.END else Gravity.START; topMargin = dp(6) }
    }

    private fun bubbleView(t: String, user: Boolean): View {
        if (!t.startsWith("\u0001")) return bubble(t, user)
        val end = t.indexOf('\u0001', 1)
        val head = t.substring(1, end); val cap = t.substring(end + 1)
        val paths = head.substringAfter(':').split('|').filter { it.isNotBlank() }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
            background = pill(if (user) 0xFF222845.toInt() else 0xCC10121F.toInt(), if (user) ACC else 0xFF3A4156.toInt(), 18)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = if (user) Gravity.END else Gravity.START; topMargin = dp(6) }
        }
        if (head.startsWith("IMG:")) {
            if (paths.isNotEmpty()) box.addView(thumb(paths[0], 220))
        } else {
            val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            paths.forEach { r.addView(thumb(it, 150)) }
            hs.addView(r)
            box.addView(hs, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.78).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (cap.isNotBlank()) box.addView(TextView(this).apply { text = cap; textSize = 14f; setTextColor(TXT); setPadding(dp(4), dp(6), dp(4), 0) })
        return box
    }

    private fun render() {
        if (renderedVersion == ChatLog.version) return
        renderedVersion = ChatLog.version
        chat.removeAllViews()
        val items = ChatLog.all(this)
        if (items.isEmpty()) chat.addView(bubble("Ask me anything. 📷 add a photo, ask \"show pictures of…\", use the mic, or say \"Omen\" after you awaken me.", false))
        items.forEach { chat.addView(bubbleView(it.second, it.first)) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refresh() {
        btn.awake = OmenService.running
        if (view.state == "idle") status.text = stateLabel()
    }

    private fun submit() {
        val t = input.text.toString().trim()
        if (pendingImages.isNotEmpty()) { sendPhotos(if (t.isEmpty()) "default" else "custom", t); return }
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
        if (rc == 78) {
            val uris = mutableListOf<Uri>()
            d?.clipData?.let { cd -> for (n in 0 until cd.itemCount) uris.add(cd.getItemAt(n).uri) }
            if (uris.isEmpty()) d?.data?.let { uris.add(it) }
            if (res == RESULT_OK && uris.isNotEmpty()) {
                Thread {
                    var failed = 0
                    for (u in uris.take(maxOf(0, 5 - pendingImages.size))) {
                        try { val f = savePhoto(u); runOnUiThread { pendingImages.add(f); updatePreview(); input.requestFocus() } }
                        catch (e: Exception) { failed++ }
                    }
                    if (failed > 0) runOnUiThread { toast("Could not read $failed image(s)") }
                }.start()
            }
            return
        }
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
        if (!Settings.canDrawOverlays(this)) Toast.makeText(this, "Open SETUP and allow 'display over apps' so I can use your Clock and open apps by voice.", Toast.LENGTH_LONG).show()
        refresh()
    }

    private fun showHistory() {
        val h = QueryHistory.all(this)
        if (h.isEmpty()) { toast("No history yet"); return }
        val lv = ListView(this)
        lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, h)
        val dlg = AlertDialog.Builder(this).setTitle("History").setView(lv)
            .setNegativeButton("Close", null).setNeutralButton("Clear history") { _, _ -> QueryHistory.clear(this); toast("History cleared") }.create()
        lv.setOnItemClickListener { _, _, pos, _ -> input.setText(h[pos]); input.setSelection(input.text.length); dlg.dismiss() }
        dlg.show()
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
        lay.addView(Button(this).apply {
            fun lbl() = "Smart wake (run clear commands without Omen): " + (if (prefs.getBoolean("smart", true)) "ON" else "OFF")
            text = lbl()
            setOnClickListener { prefs.edit().putBoolean("smart", !prefs.getBoolean("smart", true)).apply(); text = lbl() }
        })
        b("1. Allow display over apps (Clock alarms + open apps by voice)") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        b("2. Enable WhatsApp auto-send (optional)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        b("3. Allow background use (battery)") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        AlertDialog.Builder(this).setTitle("OMEN setup").setView(lay).setPositiveButton("Close", null).show()
    }
}
