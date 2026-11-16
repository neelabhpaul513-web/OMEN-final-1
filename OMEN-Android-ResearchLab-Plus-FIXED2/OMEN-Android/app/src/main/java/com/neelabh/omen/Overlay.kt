package com.neelabh.omen

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Glass panel with Omen's face that floats over any app when you say "Hey Omen". */
class OverlayUi(private val c: Context) {
    private val wm = c.getSystemService(WindowManager::class.java)
    private val h = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var face: OmenView? = null
    private var userT: TextView? = null
    private var replyT: TextView? = null
    private var pending = false
    private val hideRun = Runnable { pending = false; hide() }
    private fun dp(v: Int) = (v * c.resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    fun show() {
        h.removeCallbacks(hideRun); pending = false
        if (root != null || !Settings.canDrawOverlays(c)) return
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(30).toFloat(); setColor(0x99080A14.toInt()); setStroke(dp(1), 0x66A8B8FF) }
            setOnClickListener { hide() }
        }
        face = OmenView(c).apply { compact = true }
        box.addView(face, LinearLayout.LayoutParams(dp(76), dp(76)))
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        userT = TextView(c).apply { textSize = 12f; setTextColor(0xFF8D9BB8.toInt()); maxLines = 2 }
        replyT = TextView(c).apply { textSize = 15f; setTextColor(0xFFE6ECFF.toInt()); maxLines = 7; text = "Listening…" }
        col.addView(userT); col.addView(replyT)
        box.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        val fl = FrameLayout(c).apply { setPadding(dp(12), 0, dp(12), 0); addView(box) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM; y = dp(64) }
        try {
            fl.alpha = 0f; fl.translationY = dp(40).toFloat()
            wm.addView(fl, lp); root = fl
            fl.animate().alpha(1f).translationY(0f).setDuration(220).start()
        } catch (e: Exception) { root = null }
    }

    fun update(state: String, user: String?, reply: String?) {
        if (root == null) return
        face?.state = state
        user?.let { userT?.text = it.removePrefix("› ") }
        reply?.let { replyT?.text = if (it.length > 260) it.take(260) + "…" else it }
        if (state == "thinking" || state == "speaking") { h.removeCallbacks(hideRun); pending = false }
        else if (!pending) { pending = true; h.postDelayed(hideRun, 9000) }
    }

    fun hide() {
        h.removeCallbacks(hideRun); pending = false
        root?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        root = null
    }
}
