package com.neelabh.omen

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The face of Omen. compact = small floating face for the overlay; transparentBg = wind video plays behind it. */
class OmenView(c: Context) : View(c) {
    @Volatile var state = "idle"
    @Volatile var compact = false
    @Volatile var transparentBg = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val eye = Path()
    private var t = 0f
    private var last = 0L
    private var bg: Shader? = null
    private val rnd = java.util.Random(7)
    private val dots = List(14) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), 0.3f + rnd.nextFloat() * 0.9f, 3f + rnd.nextFloat() * 6f) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bg = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFF05050B.toInt(), 0xFF14172B.toInt(), 0xFF07070E.toInt()), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else min(0.05f, (now - last) / 1e9f)
        last = now
        val speed = when (state) { "idle" -> 0.5f; "listening" -> 1.1f; "thinking" -> 2.0f; else -> 1.5f }
        t += dt * speed

        if (!compact && !transparentBg) {
            p.shader = bg; p.style = Paint.Style.FILL
            cv.drawRect(0f, 0f, w, h, p)
            p.shader = null
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
            for (i in 0 until 6) {
                val ph = t + i * 1.3f; val y = h * (0.1f + i * 0.15f)
                path.reset(); path.moveTo(-40f, y)
                path.cubicTo(w * 0.3f, y + sin(ph) * 120f, w * 0.65f, y - cos(ph * 0.8f) * 140f, w + 40f, y + sin(ph * 0.6f) * 90f)
                p.strokeWidth = 40f + i * 8f; p.color = Color.argb(14 + i * 2, 141, 155, 184); cv.drawPath(path, p)
                p.strokeWidth = 5f; p.color = Color.argb(34 + i * 5, 160, 175, 210); cv.drawPath(path, p)
            }
            p.style = Paint.Style.FILL
            for (d in dots) {
                val x = d[0] * w + sin(t * d[2] + d[3]) * 30f
                val y = (((d[1] - t * 0.02f * d[2]) % 1f) + 1f) % 1f * h
                p.color = Color.argb(70, 190, 205, 235); cv.drawCircle(x, y, d[3] * 0.6f, p)
            }
        }

        val cx = w / 2
        val cy = if (compact) h / 2 else h * 0.30f
        val pulse = 1f + 0.06f * sin(t * 3f) * (if (state == "idle") 0.4f else 1.4f)
        val r = (if (compact) min(w, h) * 0.30f else w * 0.20f) * pulse
        val acc = when (state) { "listening" -> 0xFFA8B8FF.toInt(); "thinking" -> 0xFFC79CFF.toInt(); "speaking" -> 0xFFFFFFFF.toInt(); else -> 0xFF5C6B8A.toInt() }
        p.style = Paint.Style.FILL
        for (k in 8 downTo 1) {
            p.color = Color.argb(8, Color.red(acc), Color.green(acc), Color.blue(acc))
            cv.drawCircle(cx, cy, r * (1f + k * 0.17f), p)
        }
        p.color = 0xFF04040A.toInt(); cv.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = acc; p.alpha = 170; cv.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.FILL; p.color = acc; p.alpha = 255
        for (s in intArrayOf(-1, 1)) {
            eye.reset(); val ex = cx + s * r * 0.38f
            eye.moveTo(ex - s * r * 0.28f, cy - r * 0.02f)
            eye.lineTo(ex + s * r * 0.28f, cy - r * 0.14f)
            eye.lineTo(ex + s * r * 0.24f, cy + r * 0.06f)
            eye.close(); cv.drawPath(eye, p)
        }
        if (state == "idle") postInvalidateDelayed(33) else postInvalidateOnAnimation()
    }
}
