package com.neelabh.omen

import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** The main AWAKEN / SEND ME BACK button: glowing, breathing, and it morphs colour when OMEN is awake. */
class AwakenView(c: Context) : View(c) {
    var awake = false
        set(v) { field = v; contentDescription = if (v) "Send OMEN back" else "Awaken OMEN"; invalidate() }
    var onToggle: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val arc = RectF()
    private var mix = 0f
    private var t = 0f
    private var last = 0L

    init {
        isClickable = true
        contentDescription = "Awaken OMEN"
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onToggle?.invoke() }
    }

    override fun setPressed(pressed: Boolean) { super.setPressed(pressed); invalidate() }

    override fun onDraw(cv: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else min(0.05f, (now - last) / 1e9f)
        last = now; t += dt
        val target = if (awake) 1f else 0f
        mix += (target - mix) * min(1f, dt * 7f)
        if (abs(target - mix) < 0.01f) mix = target

        val w = width.toFloat(); val h = height.toFloat()
        val sc = if (isPressed) 0.97f else 1f
        cv.save(); cv.scale(sc, sc, w / 2, h / 2)
        rect.set(11f * d, 11f * d, w - 11f * d, h - 11f * d)
        val rad = rect.height() / 2
        val pulse = 0.5f + 0.5f * sin(t * (if (awake) 1.6f else 2.6f))

        // breathing glow rings
        val glow = ColorUtils.blendARGB(0xFFA8B8FF.toInt(), 0xFFFF8A9A.toInt(), mix)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d
        for (k in 1..3) {
            val a = ((70 - k * 18) * (0.4f + 0.6f * pulse)).toInt().coerceIn(0, 255)
            p.color = ColorUtils.setAlphaComponent(glow, a)
            val g = k * 3.2f * d
            cv.drawRoundRect(rect.left - g, rect.top - g, rect.right + g, rect.bottom + g, rad + g, rad + g, p)
        }

        // body
        p.style = Paint.Style.FILL
        val c1 = ColorUtils.blendARGB(0xFFB9C6FF.toInt(), 0xFF1E1226.toInt(), mix)
        val c2 = ColorUtils.blendARGB(0xFF6C7BD8.toInt(), 0xFF0E1020.toInt(), mix)
        p.shader = LinearGradient(rect.left, 0f, rect.right, 0f, c1, c2, Shader.TileMode.CLAMP)
        cv.drawRoundRect(rect, rad, rad, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d
        p.color = ColorUtils.blendARGB(0xFFE3E9FF.toInt(), 0xFFFF8A9A.toInt(), mix)
        cv.drawRoundRect(rect, rad, rad, p)

        // icon + label, centred as one group
        val fg = ColorUtils.blendARGB(0xFF06060C.toInt(), 0xFFFFB3BE.toInt(), mix)
        val label = if (mix > 0.5f) "SEND ME BACK" else "AWAKEN OMEN"
        p.style = Paint.Style.FILL; p.color = fg; p.textSize = 15f * d
        p.typeface = Typeface.DEFAULT_BOLD; p.letterSpacing = 0.18f
        val tw = p.measureText(label); val ir = 8.5f * d; val gap = 14f * d
        val sx = (w - (ir * 2 + gap + tw)) / 2; val cy = h / 2
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.4f * d; p.strokeCap = Paint.Cap.ROUND
        arc.set(sx, cy - ir, sx + ir * 2, cy + ir)
        cv.drawArc(arc, -55f, 290f, false, p)
        cv.drawLine(sx + ir, cy - ir - 1.5f * d, sx + ir, cy - 1f * d, p)
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        val fm = p.fontMetrics
        cv.drawText(label, sx + ir * 2 + gap, cy - (fm.ascent + fm.descent) / 2, p)

        // "online" dot while awake
        if (mix > 0.01f) {
            p.color = ColorUtils.setAlphaComponent(0xFF7CFFB2.toInt(), (255 * mix * (0.5f + 0.5f * pulse)).toInt())
            cv.drawCircle(rect.right - 26f * d, cy, 4f * d, p)
        }
        cv.restore()
        postInvalidateDelayed(33)
    }
}
