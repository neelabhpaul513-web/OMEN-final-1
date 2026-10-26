package com.neelabh.omen

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : Activity() {
    companion object { @Volatile var visible = false }

    private val ACC = 0xFFA8B8FF.toInt()
    private val MUT = 0xFF9A9AA2.toInt()
    private val TXT = 0xFFEDEBE6.toInt()
    private val INK = 0xFF111114.toInt()
    private val perms = Perms.list()

    private lateinit var view: OmenView
    private lateinit var wind: WindVideoView
    private lateinit var status: TextView
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var power: AwakenView
    private lateinit var input: EditText
    private lateinit var actionBtn: ImageButton
    private lateinit var modeChip: TextView
    private lateinit var previewBox: LinearLayout
    private lateinit var attachRow: LinearLayout
    private lateinit var chipRow: LinearLayout
    private lateinit var drawerRoot: FrameLayout
    private lateinit var scrim: View
    private lateinit var panel: LinearLayout
    private lateinit var histList: ListView
    private lateinit var histEmpty: TextView
    private var panelW = 0
    private var webMode = false
    private var pendingText: String? = null
    private var pendingSearch = false
    private val pendingImages = mutableListOf<File>()
    private val pendingDocs = mutableListOf<Pair<String, String>>()
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
        else -> if (OmenService.running) "WATCHING · SAY “HEY OMEN”" else "DORMANT"
    }

    // ---------- small helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun icon(res: Int, tint: Int): Drawable? = ContextCompat.getDrawable(this, res)?.mutate()?.also { it.setTint(tint) }
    private fun tv(t: String, size: Float, color: Int) = TextView(this).apply { text = t; textSize = size; setTextColor(color); gravity = Gravity.CENTER }
    private fun circle(fill: Int, stroke: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill); setStroke(dp(1), stroke) }
    private fun pill(fill: Int, stroke: Int, r: Int) = GradientDrawable().apply { cornerRadius = dp(r).toFloat(); setColor(fill); setStroke(dp(1), stroke) }

    private fun circleBtn(res: Int, desc: String, fill: Int = 0xFF2C2C30.toInt(), tint: Int = TXT, f: () -> Unit) = ImageButton(this).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(tint); contentDescription = desc
        background = circle(fill, 0x22FFFFFF); scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(11), dp(11), dp(11), dp(11)); setOnClickListener { f() }
    }

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    // ---------- screen ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = 0xFF05050B.toInt(); window.navigationBarColor = 0xFF07070E.toInt()
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF020205.toInt()) }
        wind = WindVideoView(this)
        root.addView(wind, FrameLayout.LayoutParams(MATCH, MATCH))
        view = OmenView(this); root.addView(view)
        wind.wanted = prefs.getBoolean("wind", true)
        wind.onReady = { runOnUiThread { view.transparentBg = true } }
        wind.onFail = { runOnUiThread { view.transparentBg = false } }

        // chat column: transparent chat + Claude-style input card
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(72), dp(14), dp(10)) }
        col.addView(View(this), lp(MATCH, 0).apply { weight = 20f })
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(56)) }
        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        scroll.addView(chat)
        col.addView(scroll, lp(MATCH, 0).apply { weight = 80f })
        status = tv("DORMANT", 11f, MUT).apply { letterSpacing = 0.25f; setPadding(0, dp(4), 0, dp(6)) }
        col.addView(status)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = pill(0xE61B1B1F.toInt(), 0x33FFFFFF, 28)
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        // attachments (photos / documents) appear at the top of the card
        previewBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, 0, 0, dp(8)) }
        attachRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        previewBox.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(attachRow) })
        previewBox.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) }, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        card.addView(previewBox)

        input = EditText(this).apply {
            hint = "Message OMEN or tap the waveform to talk"; setHintTextColor(0xFF8A8A92.toInt()); setTextColor(TXT)
            background = null; textSize = 17f; maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(4), dp(8), dp(4), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { updateAction() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        card.addView(input)

        val brow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val plus = circleBtn(R.drawable.ic_plus, "More") { }
        plus.setOnClickListener { showPlus(plus) }
        brow.addView(plus, lp(dp(44), dp(44)))
        modeChip = TextView(this).apply {
            textSize = 15f; setTextColor(TXT); gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), 0)
            background = pill(0xFF2C2C30.toInt(), 0x22FFFFFF, 22); isClickable = true
            setOnClickListener { setWebMode(!webMode) }
        }
        brow.addView(modeChip, lp(WRAP, dp(44)).apply { marginStart = dp(8) })
        brow.addView(View(this), lp(0, 1).apply { weight = 1f })
        val mic = circleBtn(R.drawable.ic_mic, "Dictate") { startDictation() }
        brow.addView(mic, lp(dp(44), dp(44)))
        actionBtn = circleBtn(R.drawable.ic_wave, "Talk", 0xFFF3F1EC.toInt(), INK) { if (hasContent()) submit() else startVoice() }
        brow.addView(actionBtn, lp(dp(44), dp(44)).apply { marginStart = dp(8) })
        card.addView(brow)
        col.addView(card)
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        // top bar: menu (left), wordmark, awaken power button (right)
        val topBar = FrameLayout(this)
        topBar.addView(circleBtn(R.drawable.ic_menu, "Menu", 0x66101018) { openDrawer() }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL))
        val title = ImageView(this).apply { setImageResource(R.drawable.omen_wordmark); setColorFilter(0xFFC9D4F0.toInt()); adjustViewBounds = true }
        topBar.addView(title, FrameLayout.LayoutParams(dp(104), WRAP, Gravity.CENTER))
        power = AwakenView(this).apply { compact = true; onToggle = { toggle() } }
        topBar.addView(power, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(topBar, FrameLayout.LayoutParams(MATCH, dp(66), Gravity.TOP).apply { topMargin = dp(8); marginStart = dp(14); marginEnd = dp(8) })

        buildDrawer(root)
        setContentView(root)
        setWebMode(false); updateAction(); render(); refresh()
        if (prefs.getString("key", "").isNullOrBlank()) { toast("Add your Groq key in Settings"); startActivity(Intent(this, SettingsActivity::class.java)) }
        handleIntent(intent)
    }

    override fun onNewIntent(i: Intent?) { super.onNewIntent(i); intent = i; handleIntent(i) }

    @Suppress("DEPRECATION")
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra("autostart", false) && !OmenService.running && perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) reallyStart()
        val uris = mutableListOf<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> {
                i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
                if (uris.isEmpty()) i.getStringExtra(Intent.EXTRA_TEXT)?.let { input.setText(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        if (uris.isNotEmpty()) { addUris(uris); intent = Intent() }
    }

    override fun onStart() {
        super.onStart(); visible = true
        ContextCompat.registerReceiver(this, uiRx, IntentFilter("com.neelabh.omen.UI"), ContextCompat.RECEIVER_NOT_EXPORTED)
        render(); refresh()
        if (prefs.getBoolean("wind", true)) wind.playNow() else { wind.stopForGood(); view.transparentBg = false }
    }
    override fun onDestroy() { super.onDestroy(); wind.release() }
    override fun onStop() { super.onStop(); visible = false; wind.holdNow(); try { unregisterReceiver(uiRx) } catch (e: Exception) {} }
    override fun onResume() { super.onResume(); AlarmReceiver.rt?.stop(); refresh() }
    override fun onBackPressed() { if (drawerRoot.visibility == View.VISIBLE) closeDrawer() else super.onBackPressed() }

    // ---------- drawer (Claude-style) ----------
    private fun buildDrawer(root: FrameLayout) {
        drawerRoot = FrameLayout(this).apply { visibility = View.GONE }
        scrim = View(this).apply { setBackgroundColor(0xAA000000.toInt()); setOnClickListener { closeDrawer() } }
        drawerRoot.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        panelW = (resources.displayMetrics.widthPixels * 0.84f).toInt()
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xF2111114.toInt()); setPadding(dp(18), dp(44), dp(18), dp(18)); isClickable = true }
        drawerRoot.addView(panel, FrameLayout.LayoutParams(panelW, MATCH))

        panel.addView(ImageView(this).apply { setImageResource(R.drawable.omen_wordmark); setColorFilter(0xFFE6ECFF.toInt()); adjustViewBounds = true }, lp(dp(120), WRAP).apply { bottomMargin = dp(18) })
        fun nav(res: Int, label: String, f: () -> Unit) = panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(14), dp(6), dp(14)); isClickable = true
            addView(ImageView(context).apply { setImageDrawable(icon(res, MUT)) }, lp(dp(26), dp(26)))
            addView(TextView(context).apply { text = label; textSize = 19f; setTextColor(0xFFD9D6CF.toInt()) }, lp(WRAP, WRAP).apply { marginStart = dp(18) })
            setOnClickListener { f() }
        })
        nav(R.drawable.ic_chat, "Chats") { closeDrawer() }
        nav(R.drawable.ic_file, "Notes") { closeDrawer(); ChatLog.add(this, true, "read my notes"); render(); send("read my notes", false) }
        nav(R.drawable.ic_attach, "Share a file") { closeDrawer(); pickDocument() }
        nav(R.drawable.ic_gear, "Settings") { closeDrawer(); startActivity(Intent(this, SettingsActivity::class.java)) }
        panel.addView(View(this).apply { setBackgroundColor(0x22FFFFFF) }, lp(MATCH, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(14) })
        panel.addView(TextView(this).apply { text = "Recents"; textSize = 16f; setTextColor(MUT); setPadding(dp(6), 0, 0, dp(6)) })
        histEmpty = TextView(this).apply { text = "Nothing here yet."; textSize = 15f; setTextColor(0xFF6E6E76.toInt()); setPadding(dp(6), dp(8), 0, 0) }
        panel.addView(histEmpty)
        histList = ListView(this).apply { divider = null; dividerHeight = 0; isVerticalScrollBarEnabled = false; setSelector(ColorDrawable(0x22FFFFFF)) }
        panel.addView(histList, lp(MATCH, 0).apply { weight = 1f })

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
        val initials = (prefs.getString("name", "") ?: "").trim().split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "OM" }
        bottom.addView(TextView(this).apply {
            text = initials; textSize = 18f; setTextColor(INK); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = circle(ACC, ACC); isClickable = true
            setOnClickListener { closeDrawer(); startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }, lp(dp(56), dp(56)))
        bottom.addView(View(this), lp(0, 1).apply { weight = 1f })
        bottom.addView(TextView(this).apply {
            text = "＋  New chat"; textSize = 18f; setTextColor(INK); gravity = Gravity.CENTER; setPadding(dp(26), dp(15), dp(26), dp(15))
            background = pill(0xFFF3F1EC.toInt(), 0xFFF3F1EC.toInt(), 30); isClickable = true
            setOnClickListener { ChatLog.clear(this@MainActivity); render(); closeDrawer() }
        }, lp(WRAP, WRAP))
        panel.addView(bottom)
        root.addView(drawerRoot, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun refreshHistory() {
        val h = QueryHistory.all(this)
        histEmpty.visibility = if (h.isEmpty()) View.VISIBLE else View.GONE
        histList.adapter = object : BaseAdapter() {
            override fun getCount() = h.size
            override fun getItem(position: Int): Any = h[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(13), dp(6), dp(13))
                addView(ImageView(context).apply { setImageDrawable(icon(R.drawable.ic_chat, 0xFF8A8A92.toInt())) }, lp(dp(22), dp(22)))
                addView(TextView(context).apply { text = h[position]; textSize = 17f; setTextColor(TXT); maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                    lp(0, WRAP).apply { weight = 1f; marginStart = dp(16) })
            }
        }
        histList.setOnItemClickListener { _, _, pos, _ -> input.setText(h[pos]); input.setSelection(input.text.length); closeDrawer() }
    }

    private fun openDrawer() {
        refreshHistory(); drawerRoot.visibility = View.VISIBLE
        scrim.alpha = 0f; scrim.animate().alpha(1f).setDuration(180).start()
        panel.translationX = -panelW.toFloat(); panel.animate().translationX(0f).setDuration(220).start()
    }
    private fun closeDrawer() {
        scrim.animate().alpha(0f).setDuration(160).start()
        panel.animate().translationX(-panelW.toFloat()).setDuration(180).withEndAction { drawerRoot.visibility = View.GONE }.start()
    }

    // ---------- "+" pop-up (all the extra tools live here) ----------
    private fun showPlus(anchor: View) {
        val items = listOf<Triple<Int, String, () -> Unit>>(
            Triple(R.drawable.ic_photo, "Photos", { pickPhoto() }),
            Triple(R.drawable.ic_attach, "Files and documents", { pickDocument() }),
            Triple(R.drawable.ic_search, "Web search: " + (if (webMode) "ON" else "OFF"), { setWebMode(!webMode) }),
            Triple(if (prefs.getBoolean("voice", true)) R.drawable.ic_vol_on else R.drawable.ic_vol_off, "Spoken replies: " + (if (prefs.getBoolean("voice", true)) "ON" else "OFF"),
                { prefs.edit().putBoolean("voice", !prefs.getBoolean("voice", true)).apply() }),
            Triple(R.drawable.ic_photo, "Create an image", { input.setText("Create an image of "); input.setSelection(input.text.length); input.requestFocus() })
        )
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = pill(0xFF242428.toInt(), 0x33FFFFFF, 22); setPadding(dp(6), dp(8), dp(6), dp(8)) }
        val pw = PopupWindow(box, WRAP, WRAP, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); pw.elevation = dp(8).toFloat()
        for (item in items) {
            val action = item.third
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(13), dp(22), dp(13)); isClickable = true
                addView(ImageView(context).apply { setImageDrawable(icon(item.first, TXT)) }, lp(dp(24), dp(24)))
                addView(TextView(context).apply { text = item.second; textSize = 17f; setTextColor(TXT) }, lp(WRAP, WRAP).apply { marginStart = dp(16) })
                setOnClickListener { pw.dismiss(); action() }
            })
        }
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        pw.showAsDropDown(anchor, 0, -(anchor.height + box.measuredHeight + dp(10)))
    }

    private fun setWebMode(on: Boolean) {
        webMode = on
        modeChip.text = if (on) "Web search" else "Chat"
        modeChip.setTextColor(if (on) ACC else TXT)
        modeChip.background = pill(if (on) 0xFF2F3550.toInt() else 0xFF2C2C30.toInt(), if (on) ACC else 0x22FFFFFF, 22)
    }

    private fun hasContent() = input.text.toString().isNotBlank() || pendingImages.isNotEmpty() || pendingDocs.isNotEmpty()
    private fun updateAction() {
        actionBtn.setImageResource(if (hasContent()) R.drawable.ic_send else R.drawable.ic_wave)
        actionBtn.contentDescription = if (hasContent()) "Send" else "Talk"
    }

    // ---------- images and documents ----------
    private fun decode(path: String, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= max && o.outHeight / (s * 2) >= max) s *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun showFull(path: String) {
        val iv = ImageView(this).apply { setImageBitmap(decode(path, 1400)); adjustViewBounds = true; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        AlertDialog.Builder(this).setView(iv).setPositiveButton("Close", null).setNeutralButton("Save to gallery") { _, _ -> saveToGallery(path) }.show()
    }

    private fun saveToGallery(path: String) {
        try {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "OMEN_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OMEN")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: throw Exception("insert")
            contentResolver.openOutputStream(uri)?.use { out -> File(path).inputStream().use { it.copyTo(out) } }
            toast("Saved to Pictures/OMEN")
        } catch (e: Exception) { toast("Could not save the image") }
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

    private fun pickDocument() {
        try {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "text/*", "application/json",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation", "image/*")), 80)
        } catch (e: Exception) { toast("No file manager found") }
    }

    /** Photos go to the notes scanner, everything else is read as a document. Works for picker and for "Share to OMEN". */
    private fun addUris(uris: List<Uri>) {
        toast("Reading…")
        Thread {
            var failed = ""
            for (u in uris.take(6)) {
                try {
                    val mime = contentResolver.getType(u) ?: ""
                    if (mime.startsWith("image/")) {
                        if (pendingImages.size < 5) { val f = savePhoto(u); runOnUiThread { pendingImages.add(f); updatePreview() } }
                    } else {
                        val d = DocReader.read(this, u); runOnUiThread { pendingDocs.add(d); updatePreview() }
                    }
                } catch (e: Exception) { failed = e.message ?: "unreadable file" }
            }
            if (failed.isNotEmpty()) runOnUiThread { toast(failed) }
        }.start()
    }

    private fun act(label: String, mode: String) = TextView(this).apply {
        text = label; textSize = 13f; setTextColor(ACC); setPadding(dp(14), dp(8), dp(14), dp(8))
        background = pill(0x33000000, ACC, 18); isClickable = true
        setOnClickListener { sendAttachments(mode, input.text.toString().trim()) }
    }

    private fun updatePreview() {
        attachRow.removeAllViews(); chipRow.removeAllViews()
        if (pendingImages.isEmpty() && pendingDocs.isEmpty()) { previewBox.visibility = View.GONE; updateAction(); return }
        previewBox.visibility = View.VISIBLE
        pendingImages.forEach { f ->
            attachRow.addView(ImageView(this).apply {
                setImageBitmap(decode(f.path, dp(112))); scaleType = ImageView.ScaleType.CENTER_CROP
                background = pill(0xFF10121F.toInt(), ACC, 12); clipToOutline = true
            }, lp(dp(56), dp(56)).apply { marginEnd = dp(8) })
        }
        pendingDocs.forEach { d ->
            attachRow.addView(TextView(this).apply {
                text = d.first; textSize = 13f; setTextColor(TXT); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(170)
                compoundDrawablePadding = dp(8); setCompoundDrawablesWithIntrinsicBounds(icon(R.drawable.ic_file, ACC), null, null, null)
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(14), 0); background = pill(0xFF2C2C30.toInt(), 0x22FFFFFF, 20)
            }, lp(WRAP, dp(40)).apply { marginEnd = dp(8) })
        }
        attachRow.addView(circleBtn(R.drawable.ic_close, "Remove all") { clearAttachments() }, lp(dp(34), dp(34)))
        val labels = if (pendingImages.isNotEmpty()) listOf("Read text" to "read", "Summarize" to "summary", "Key points" to "key", "Quiz me" to "quiz")
                     else listOf("Summarize" to "summary", "Key points" to "key", "Quiz me" to "quiz")
        for ((l, md) in labels) chipRow.addView(act(l, md), lp(WRAP, WRAP).apply { marginEnd = dp(8) })
        updateAction()
    }

    private fun clearAttachments() { pendingImages.clear(); pendingDocs.clear(); updatePreview() }

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

    private fun docPrompt(mode: String, typed: String): String = when (mode) {
        "key" -> "List the key terms with short definitions, and the important facts, dates and numbers. Then give 3 likely exam questions."
        "quiz" -> "Create 6 quiz questions from this document (mix of short answer and multiple choice), each followed by a brief answer."
        "custom" -> typed
        else -> "Summarize this document: first a one-line topic, then the main points as short dashed lines, then any dates, numbers, names or action items."
    }

    private fun sendAttachments(mode: String, typed: String) {
        if (pendingImages.isNotEmpty()) sendPhotos(if (mode == "default" && typed.isNotEmpty()) "custom" else mode, typed) else sendDocs(mode, typed)
    }

    private fun speakIfShort(ans: String) {
        if (OmenService.running && prefs.getBoolean("voice", true) && ans.length <= 500) {
            try { startService(Intent(this, OmenService::class.java).putExtra("say", ans)) } catch (e: Exception) {}
        }
    }

    private fun sendPhotos(mode: String, typed: String) {
        val key = prefs.getString("key", "") ?: ""
        if (key.isBlank()) { toast("Add your Groq key in Settings first"); return }
        val files = pendingImages.toList(); val docs = pendingDocs.toList()
        if (files.isEmpty()) return
        val extra = if (docs.isEmpty()) "" else "\n\nAlso use this attached document text:\n" + docs.joinToString("\n\n") { it.first + ":\n" + it.second }.take(8000)
        val prompt = notesPrompt(mode, typed) + extra
        val label = when (mode) { "read" -> "Read text"; "summary" -> "Summarize"; "key" -> "Key points"; "quiz" -> "Quiz me"; "custom" -> typed; else -> "Read and summarize" }
        clearAttachments(); input.setText("")
        val head = if (files.size == 1) "IMG:${files[0].path}" else "GAL:" + files.joinToString("|") { it.path }
        ChatLog.add(this, true, "\u0001$head\u0001$label"); render()
        view.state = "thinking"; status.text = "THINKING"
        Thread {
            val ans = try { Brain.vision(key, prompt, files.map { it.readBytes() }) } catch (e: Exception) {
                try { ocrFallback(key, files, mode, prompt) } catch (e2: Exception) { "Photo analysis failed: ${e.message}" }
            }
            ChatLog.add(this, false, ans)
            runOnUiThread { view.state = "idle"; status.text = stateLabel(); render() }
            speakIfShort(ans)
        }.start()
    }

    private fun sendDocs(mode: String, typed: String) {
        val key = prefs.getString("key", "") ?: ""
        if (key.isBlank()) { toast("Add your Groq key in Settings first"); return }
        val docs = pendingDocs.toList()
        if (docs.isEmpty()) return
        val prompt = docPrompt(if (mode == "default") "summary" else mode, typed)
        val label = when (mode) { "key" -> "Key points"; "quiz" -> "Quiz me"; "custom" -> typed; else -> "Summarize" }
        clearAttachments(); input.setText("")
        ChatLog.add(this, true, "\u0001DOC:" + docs.joinToString(", ") { it.first } + "\u0001$label"); render()
        view.state = "thinking"; status.text = "THINKING"
        Thread {
            val ans = try { Brain.askDocument(key, docs, prompt) } catch (e: Exception) { "Document analysis failed: ${e.message}" }
            ChatLog.add(this, false, ans)
            runOnUiThread { view.state = "idle"; status.text = stateLabel(); render() }
            speakIfShort(ans)
        }.start()
    }

    private fun ocrFallback(key: String, files: List<File>, mode: String, prompt: String): String {
        val text = files.mapIndexed { i, f -> (if (files.size > 1) "--- page ${i + 1} ---\n" else "") + Ocr.read(f.path) }.joinToString("\n\n").trim()
        if (text.isBlank()) return "I could not find any readable text in the photo."
        if (mode == "read") return text + "\n\n(read with offline OCR on this phone; handwriting may be inaccurate)"
        return Brain.notesFromText(key, text, prompt) + "\n\n(based on offline OCR text)"
    }

    // ---------- chat bubbles (glass) ----------
    private fun glassFill(user: Boolean): Int {
        val a = (prefs.getInt("glass", 28) * 255 / 100).coerceIn(0, 255)
        return if (user) Color.argb((a + 25).coerceAtMost(255), 34, 40, 69) else Color.argb(a, 16, 18, 31)
    }
    private fun glassStroke(user: Boolean): Int = if (user) 0x99A8B8FF.toInt() else 0x44A8B8FF

    private fun bubble(t: String, user: Boolean) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(TXT); setShadowLayer(6f, 0f, 1f, 0xDD000000.toInt())
        Linkify.addLinks(this, Linkify.WEB_URLS); movementMethod = LinkMovementMethod.getInstance(); setLinkTextColor(ACC)
        setOnLongClickListener { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("OMEN", t)); toast("Copied"); true }
        setPadding(dp(14), dp(10), dp(14), dp(10))
        maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
        background = pill(glassFill(user), glassStroke(user), 18)
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = if (user) Gravity.END else Gravity.START; topMargin = dp(6) }
    }

    private fun bubbleView(t: String, user: Boolean): View {
        if (!t.startsWith("\u0001")) return bubble(t, user)
        val end = t.indexOf('\u0001', 1)
        val head = t.substring(1, end); val cap = t.substring(end + 1)
        val paths = head.substringAfter(':').split('|').filter { it.isNotBlank() }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
            background = pill(glassFill(user), glassStroke(user), 18)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = if (user) Gravity.END else Gravity.START; topMargin = dp(6) }
        }
        when {
            head.startsWith("IMG:") -> if (paths.isNotEmpty()) box.addView(thumb(paths[0], 220))
            head.startsWith("DOC:") -> box.addView(TextView(this).apply {
                text = head.removePrefix("DOC:"); textSize = 14f; setTextColor(TXT); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(240)
                compoundDrawablePadding = dp(10); setCompoundDrawablesWithIntrinsicBounds(icon(R.drawable.ic_file, ACC), null, null, null)
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(10), dp(6))
            })
            else -> {
                val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                paths.forEach { r.addView(thumb(it, 150)) }
                hs.addView(r)
                box.addView(hs, lp((resources.displayMetrics.widthPixels * 0.78).toInt(), WRAP))
            }
        }
        if (cap.isNotBlank()) box.addView(TextView(this).apply { text = cap; textSize = 14f; setTextColor(TXT); setPadding(dp(4), dp(6), dp(4), 0) })
        return box
    }

    private fun render() {
        if (renderedVersion == ChatLog.version) return
        renderedVersion = ChatLog.version
        chat.removeAllViews()
        val items = ChatLog.all(this)
        if (items.isEmpty()) chat.addView(bubble("Say “Hey Omen” from any app once I am awake, or type below. Tap + for photos, files and more.", false))
        items.forEach { chat.addView(bubbleView(it.second, it.first)) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refresh() {
        power.awake = OmenService.running
        if (view.state == "idle") status.text = stateLabel()
    }

    // ---------- sending ----------
    private fun submit() {
        val t = input.text.toString().trim()
        if (pendingImages.isNotEmpty() || pendingDocs.isNotEmpty()) { sendAttachments(if (t.isEmpty()) "default" else "custom", t); return }
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

    private fun pauseService() { if (OmenService.running) startService(Intent(this, OmenService::class.java).putExtra("pause", true)) }
    private fun resumeService() { if (OmenService.running) startService(Intent(this, OmenService::class.java).putExtra("resume", true)) }

    private fun speechIntent(prompt: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN").putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)

    private fun startVoice() = listenFor(77, "Speak to OMEN")
    private fun startDictation() = listenFor(79, "Dictate your message")
    private fun listenFor(code: Int, prompt: String) {
        pauseService()
        try { startActivityForResult(speechIntent(prompt), code) }
        catch (e: Exception) { Toast.makeText(this, "No speech service found. Install the Google app.", Toast.LENGTH_LONG).show(); resumeService() }
    }

    override fun onActivityResult(rc: Int, res: Int, d: Intent?) {
        super.onActivityResult(rc, res, d)
        when (rc) {
            77, 79 -> {
                resumeService()
                val t = d?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (res == RESULT_OK && !t.isNullOrBlank()) {
                    if (rc == 79) { input.setText((input.text.toString() + " " + t).trim()); input.setSelection(input.text.length) }
                    else { ChatLog.add(this, true, t); render(); send(t, webMode, true) }
                }
            }
            78, 80 -> {
                val uris = mutableListOf<Uri>()
                d?.clipData?.let { cd -> for (n in 0 until cd.itemCount) uris.add(cd.getItemAt(n).uri) }
                if (uris.isEmpty()) d?.data?.let { uris.add(it) }
                if (res == RESULT_OK && uris.isNotEmpty()) addUris(uris)
            }
        }
    }

    // ---------- awaken / permissions ----------
    private fun toggle() {
        if (OmenService.running) { stopService(Intent(this, OmenService::class.java)); view.state = "idle"; power.awake = false; status.text = "DORMANT"; return }
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
        if (!WakeModel.ready(this) && !prefs.getBoolean("wakeAsked", false)) {
            prefs.edit().putBoolean("wakeAsked", true).apply()
            AlertDialog.Builder(this).setTitle("Offline “Hey Omen”")
                .setMessage("Download a small speech model (about 40 MB) so OMEN hears “Hey Omen” reliably from any app, even offline? You can also do this later in Settings.")
                .setPositiveButton("Download") { _, _ -> reallyStart(); downloadWakeModel() }
                .setNegativeButton("Later") { _, _ -> reallyStart() }.show()
            return
        }
        reallyStart()
    }

    private fun reallyStart() {
        ContextCompat.startForegroundService(this, Intent(this, OmenService::class.java))
        if (!Settings.canDrawOverlays(this)) Toast.makeText(this, "Open Settings and allow “display over apps” so “Hey Omen” can pop up over other apps.", Toast.LENGTH_LONG).show()
        refresh()
    }

    private fun downloadWakeModel() {
        Thread {
            try {
                WakeModel.download(this) { p -> runOnUiThread { status.text = "DOWNLOADING WAKE MODEL $p%" } }
                runOnUiThread {
                    toast("“Hey Omen” model ready"); status.text = stateLabel()
                    stopService(Intent(this, OmenService::class.java))
                    ContextCompat.startForegroundService(this, Intent(this, OmenService::class.java))
                }
            } catch (e: Exception) { runOnUiThread { toast("Download failed: ${e.message}"); status.text = stateLabel() } }
        }.start()
    }
}
