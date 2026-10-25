package com.neelabh.omen

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class OmenView(c: Context) : View(c) {
    @Volatile var state = "idle"
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private val rnd = java.util.Random(7)
    private val dots = List(16) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), 0.3f + rnd.nextFloat() * 0.9f, 3f + rnd.nextFloat() * 6f) }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val speed = when (state) { "idle" -> 0.6f; "listening" -> 1.2f; "thinking" -> 2.2f; else -> 1.6f }
        t += 0.016f * speed
        p.reset(); p.isAntiAlias = true
        p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFF06060C.toInt(), 0xFF15172B.toInt(), 0xFF08080F.toInt()), null, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, p)
        p.shader = null
        // drifting shadow wisps
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        p.maskFilter = BlurMaskFilter(22f, BlurMaskFilter.Blur.NORMAL)
        for (i in 0 until 7) {
            val ph = t + i * 1.3f; val y = h * (0.1f + i * 0.13f)
            val path = Path(); path.moveTo(-40f, y)
            path.cubicTo(w * 0.3f, y + sin(ph) * 120f, w * 0.65f, y - cos(ph * 0.8f) * 140f, w + 40f, y + sin(ph * 0.6f) * 90f)
            p.strokeWidth = 16f + i * 6f; p.color = Color.argb(34 + i * 7, 141, 155, 184)
            cv.drawPath(path, p)
        }
        p.maskFilter = null
        // floating motes
        p.style = Paint.Style.FILL
        for (d in dots) {
            val x = d[0] * w + sin(t * d[2] + d[3]) * 30f
            val y = (((d[1] - t * 0.02f * d[2]) % 1f) + 1f) % 1f * h
            p.color = Color.argb(70, 190, 205, 235); cv.drawCircle(x, y, d[3] * 0.6f, p)
        }
        // the hooded orb with glowing eyes
        val cx = w / 2; val cy = h * 0.38f
        val pulse = 1f + 0.07f * sin(t * 3f) * (if (state == "idle") 0.4f else 1.4f)
        val r = w * 0.2f * pulse
        val acc = when (state) { "listening" -> 0xFFA8B8FF.toInt(); "thinking" -> 0xFFC79CFF.toInt(); "speaking" -> 0xFFFFFFFF.toInt(); else -> 0xFF5C6B8A.toInt() }
        p.shader = RadialGradient(cx, cy, r * 2.6f, intArrayOf(Color.argb(110, Color.red(acc), Color.green(acc), Color.blue(acc)), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r * 2.6f, p); p.shader = null
        p.color = 0xFF04040A.toInt(); cv.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = acc; p.alpha = 170; cv.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.FILL; p.color = acc; p.alpha = 255
        for (s in intArrayOf(-1, 1)) {
            val e = Path(); val ex = cx + s * r * 0.38f
            e.moveTo(ex - s * r * 0.28f, cy - r * 0.02f)
            e.lineTo(ex + s * r * 0.28f, cy - r * 0.14f)
            e.lineTo(ex + s * r * 0.24f, cy + r * 0.06f)
            e.close(); cv.drawPath(e, p)
        }
        postInvalidateOnAnimation()
    }
}
