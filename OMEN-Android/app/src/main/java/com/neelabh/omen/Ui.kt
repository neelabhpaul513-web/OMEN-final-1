package com.neelabh.omen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

object Ui {
    val BG = 0xFF0E0E11.toInt()
    val CARD = 0xFF1B1B1F.toInt()
    val ACC = 0xFFA8B8FF.toInt()
    val MUT = 0xFF9A9AA2.toInt()
    val TXT = 0xFFEDEBE6.toInt()
    val OK = 0xFF7CFFB2.toInt()
    val BAD = 0xFFFF8A9A.toInt()
    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()
    fun pill(c: Context, fill: Int, stroke: Int, r: Int) = GradientDrawable().apply { cornerRadius = dp(c, r).toFloat(); setColor(fill); setStroke(dp(c, 1), stroke) }
}

/** Base class for the dashboard screens: dark page with a back arrow, title and scrolling body, plus small view builders. */
abstract class DashActivity : Activity() {
    protected val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    protected val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    protected lateinit var body: LinearLayout

    protected fun dp(v: Int) = Ui.dp(this, v)
    protected fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
    protected fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    protected fun key(): String = getSharedPreferences("omen", MODE_PRIVATE).getString("key", "") ?: ""
    protected fun ui(f: () -> Unit) = runOnUiThread { f() }
    protected fun bg(f: () -> Unit) = Thread { try { f() } catch (e: Exception) { runOnUiThread { toast("Error: " + (e.message ?: e.javaClass.simpleName)) } } }.start()
    protected fun openUrl(u: String) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) { toast("No browser found") } }

    protected fun build(title: String) {
        window.statusBarColor = Ui.BG; window.navigationBarColor = Ui.BG
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(34), dp(8), dp(8)) }
        bar.addView(ImageButton(this).apply { setImageResource(R.drawable.ic_back); imageTintList = ColorStateList.valueOf(Ui.TXT); background = null; setOnClickListener { finish() } }, lp(dp(48), dp(48)))
        bar.addView(TextView(this).apply { text = title; textSize = 22f; setTextColor(Ui.TXT) }, lp(WRAP, WRAP).apply { marginStart = dp(6) })
        page.addView(bar)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(40)) }
        page.addView(ScrollView(this).apply { addView(body) }, lp(MATCH, 0).apply { weight = 1f })
        setContentView(page)
    }

    protected fun card(parent: ViewGroup = body): LinearLayout {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); background = Ui.pill(this@DashActivity, Ui.CARD, 0x22FFFFFF, 18) }
        parent.addView(c, lp(MATCH, WRAP).apply { bottomMargin = dp(12) }); return c
    }

    protected fun showText(title: String, text: String) {
        val tv = TextView(this).apply { this.text = text; textSize = 14f; setTextColor(Ui.TXT); setPadding(dp(22), dp(14), dp(22), dp(6)); setTextIsSelectable(true) }
        android.text.util.Linkify.addLinks(tv, android.text.util.Linkify.WEB_URLS)
        android.app.AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(tv) }).setPositiveButton("Close", null).show()
    }

    protected fun section(parent: ViewGroup, t: String) = parent.addView(TextView(this).apply { text = t.uppercase(); textSize = 12f; letterSpacing = 0.12f; setTextColor(Ui.MUT); setPadding(0, dp(4), 0, dp(6)) })

    protected fun text(parent: ViewGroup, t: String, size: Float = 15f, color: Int = Ui.TXT, bold: Boolean = false): TextView {
        val v = TextView(this).apply { text = t; textSize = size; setTextColor(color); setPadding(0, dp(3), 0, dp(3)); if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD }
        parent.addView(v); return v
    }

    protected fun button(parent: ViewGroup, t: String, f: () -> Unit): Button {
        val b = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }
        parent.addView(b); return b
    }

    protected fun input(parent: ViewGroup, hint: String, value: String = ""): EditText {
        val e = EditText(this).apply { this.hint = hint; setText(value); setTextColor(Ui.TXT); setHintTextColor(Ui.MUT); isSingleLine = true }
        parent.addView(e); return e
    }

    protected fun chip(t: String, selected: Boolean, f: () -> Unit) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(if (selected) Ui.BG else Ui.TXT); setPadding(dp(14), dp(8), dp(14), dp(8)); isClickable = true
        background = Ui.pill(this@DashActivity, if (selected) Ui.ACC else Ui.CARD, if (selected) Ui.ACC else 0x22FFFFFF, 18); setOnClickListener { f() }
    }
}

/** Horizontal bar chart used by the analytics dashboards. */
class BarChartView(c: Context, private val rows: List<Pair<String, Int>>, private val color: Int = Ui.ACC) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = c.resources.displayMetrics.density

    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(MeasureSpec.getSize(w), maxOf(((rows.size * 30 + 8) * d).toInt(), (40 * d).toInt())) }

    private fun fit(s: String, w: Float): String {
        p.textSize = 12 * d
        if (p.measureText(s) <= w) return s
        var t = s; while (t.length > 3 && p.measureText("$t…") > w) t = t.dropLast(1)
        return "$t…"
    }

    override fun onDraw(cv: Canvas) {
        val mx = maxOf(1, rows.maxOfOrNull { it.second } ?: 1)
        val labelW = width * 0.38f; val valW = 44 * d
        rows.forEachIndexed { i, r ->
            val y = (i * 30 + 4) * d
            p.style = Paint.Style.FILL; p.textSize = 12 * d
            p.color = Ui.TXT; cv.drawText(fit(r.first, labelW - 8 * d), 0f, y + 17 * d, p)
            val full = width - labelW - valW
            p.color = 0x22FFFFFF; cv.drawRoundRect(labelW, y + 4 * d, labelW + full, y + 22 * d, 6 * d, 6 * d, p)
            p.color = color; cv.drawRoundRect(labelW, y + 4 * d, labelW + maxOf(full * r.second / mx, 4 * d), y + 22 * d, 6 * d, 6 * d, p)
            p.color = Ui.MUT; cv.drawText(r.second.toString(), width - valW + 8 * d, y + 17 * d, p)
        }
    }
}
