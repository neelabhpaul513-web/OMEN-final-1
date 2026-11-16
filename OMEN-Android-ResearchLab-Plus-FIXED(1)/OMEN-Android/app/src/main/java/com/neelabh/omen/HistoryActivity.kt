package com.neelabh.omen

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/** Full history: every question, search, photo and command, with answers, filters and search. */
class HistoryActivity : Activity() {
    private val ACC = 0xFFA8B8FF.toInt()
    private val MUT = 0xFF9A9AA2.toInt()
    private val TXT = 0xFFEDEBE6.toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val TYPES = listOf("All", "Pharma research", "Web search", "Image search", "Image creation", "Photo search", "Photo", "Document", "Chat", "Command")
    private var all = listOf<HistoryStore.Entry>()
    private var filterType = "All"
    private var query = ""
    private val rows = mutableListOf<Any>()
    private lateinit var list: ListView
    private lateinit var count: TextView
    private lateinit var empty: TextView
    private lateinit var chips: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
    private fun pill(fill: Int, stroke: Int, r: Int) = GradientDrawable().apply { cornerRadius = dp(r).toFloat(); setColor(fill); setStroke(dp(1), stroke) }
    private fun typeColor(t: String) = when (t) {
        "Pharma research" -> 0xFFFF7A90.toInt(); "Web search" -> 0xFF5C8DFF.toInt(); "Image search" -> 0xFF8A6CFF.toInt(); "Image creation" -> 0xFFC76CFF.toInt()
        "Photo search", "Photo" -> 0xFF3CC9A8.toInt(); "Document" -> 0xFFE0A93C.toInt(); "Command" -> 0xFF9A9AA2.toInt(); else -> 0xFF6C7BD8.toInt()
    }

    private fun dayLabel(t: Long): String {
        if (t == 0L) return "Earlier"
        val now = Calendar.getInstance(); val c = Calendar.getInstance().apply { timeInMillis = t }
        val sameYear = now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
        val diff = now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR)
        return when { sameYear && diff == 0 -> "Today"; sameYear && diff == 1 -> "Yesterday"; else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t)) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = 0xFF0E0E11.toInt(); window.navigationBarColor = 0xFF0E0E11.toInt()
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0E0E11.toInt()) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(34), dp(14), dp(6)) }
        bar.addView(ImageButton(this).apply { setImageResource(R.drawable.ic_back); imageTintList = ColorStateList.valueOf(TXT); background = null; setOnClickListener { finish() } }, lp(dp(48), dp(48)))
        bar.addView(TextView(this).apply { text = "History"; textSize = 24f; setTextColor(TXT) }, lp(0, WRAP).apply { weight = 1f; marginStart = dp(6) })
        count = TextView(this).apply { textSize = 13f; setTextColor(MUT) }
        bar.addView(count, lp(WRAP, WRAP).apply { marginEnd = dp(14) })
        bar.addView(TextView(this).apply {
            text = "Clear all"; textSize = 14f; setTextColor(0xFFFFB3BE.toInt()); setPadding(dp(8), dp(8), dp(8), dp(8)); isClickable = true
            setOnClickListener {
                AlertDialog.Builder(this@HistoryActivity).setTitle("Clear all history?").setMessage("This deletes every saved question, search and answer. It cannot be undone.")
                    .setPositiveButton("Clear all") { _, _ -> HistoryStore.clear(this@HistoryActivity); load() }.setNegativeButton("Cancel", null).show()
            }
        })
        page.addView(bar)
        page.addView(EditText(this).apply {
            hint = "Search your history"; setHintTextColor(MUT); setTextColor(TXT); isSingleLine = true; textSize = 16f
            background = pill(0xFF1B1B1F.toInt(), 0x22FFFFFF, 24); setPadding(dp(18), dp(10), dp(18), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; applyFilter() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }, lp(MATCH, WRAP).apply { marginStart = dp(16); marginEnd = dp(16); bottomMargin = dp(8) })
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), dp(6)) }
        page.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        empty = TextView(this).apply { text = "Nothing here yet. Your questions and searches will appear as you use OMEN."; textSize = 15f; setTextColor(MUT); setPadding(dp(24), dp(30), dp(24), 0) }
        page.addView(empty)
        list = ListView(this).apply { divider = null; dividerHeight = 0; isVerticalScrollBarEnabled = false }
        list.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int): Any = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getViewTypeCount() = 2
            override fun getItemViewType(position: Int) = if (rows[position] is String) 0 else 1
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val r = rows[position]
                if (r is String) return TextView(this@HistoryActivity).apply { text = r.uppercase(); textSize = 12f; letterSpacing = 0.15f; setTextColor(MUT); setPadding(dp(20), dp(18), dp(20), dp(6)) }
                val e = r as HistoryStore.Entry
                return LinearLayout(this@HistoryActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12))
                    val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    top.addView(TextView(context).apply { text = e.type; textSize = 11f; setTextColor(typeColor(e.type)); setPadding(dp(10), dp(3), dp(10), dp(3)); background = pill(0x11FFFFFF, typeColor(e.type), 10) })
                    top.addView(TextView(context).apply { text = if (e.t == 0L) "" else SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(Date(e.t)); textSize = 12f; setTextColor(MUT); gravity = Gravity.END },
                        lp(0, WRAP).apply { weight = 1f })
                    addView(top)
                    addView(TextView(context).apply { text = e.q; textSize = 16f; setTextColor(TXT); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0) })
                    if (e.a.isNotEmpty()) addView(TextView(context).apply { text = e.a; textSize = 14f; setTextColor(MUT); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) })
                }
            }
        }
        list.setOnItemClickListener { _, _, pos, _ -> (rows[pos] as? HistoryStore.Entry)?.let { show(it) } }
        page.addView(list, lp(MATCH, 0).apply { weight = 1f })
        setContentView(page)
        buildChips(); load()
    }

    private fun buildChips() {
        chips.removeAllViews()
        for (t in TYPES) {
            val sel = t == filterType
            chips.addView(TextView(this).apply {
                text = t; textSize = 13f; setTextColor(if (sel) 0xFF111114.toInt() else TXT); setPadding(dp(14), dp(8), dp(14), dp(8)); isClickable = true
                background = pill(if (sel) ACC else 0xFF1B1B1F.toInt(), if (sel) ACC else 0x22FFFFFF, 18)
                setOnClickListener { filterType = t; buildChips(); applyFilter() }
            }, lp(WRAP, WRAP).apply { marginEnd = dp(8) })
        }
    }

    private fun load() { all = HistoryStore.all(this); applyFilter() }

    private fun applyFilter() {
        val q = query.trim().lowercase()
        val f = all.filter { (filterType == "All" || it.type == filterType) && (q.isEmpty() || it.q.lowercase().contains(q) || it.a.lowercase().contains(q)) }
        rows.clear(); var lastDay = ""
        for (e in f) { val d = dayLabel(e.t); if (d != lastDay) { rows.add(d); lastDay = d }; rows.add(e) }
        count.text = "${f.size} of ${all.size}"
        empty.visibility = if (f.isEmpty()) View.VISIBLE else View.GONE
        (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    private fun show(e: HistoryStore.Entry) {
        val tv = TextView(this).apply {
            text = "You asked:\n" + e.q + "\n\nOMEN:\n" + (if (e.a.isEmpty()) "(no saved answer)" else e.a)
            textSize = 15f; setTextColor(TXT); setPadding(dp(22), dp(14), dp(22), dp(6)); setTextIsSelectable(true)
        }
        Linkify.addLinks(tv, Linkify.WEB_URLS)
        AlertDialog.Builder(this).setTitle(e.type).setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Ask again") { _, _ -> setResult(RESULT_OK, Intent().putExtra("ask", e.q)); finish() }
            .setNeutralButton("Copy") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("OMEN", e.q + "\n\n" + e.a))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Delete") { _, _ -> HistoryStore.delete(this, e); load() }.show()
    }
}
