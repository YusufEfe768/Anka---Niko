package app.niko

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Niko'nun nefes alan küresi: boşta mavi, dinlerken pembe, konuşurken parlak turkuaz. */
class OrbView(ctx: Context) : View(ctx) {
    var mode = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val speed = when (mode) { 1 -> 0.10f; 2 -> 0.16f; else -> 0.04f }
        val r = min(width, height) * 0.30f * (1f + speed * sin(t * 2f * PI.toFloat()))
        val (c1, c2, c3) = when (mode) {
            1 -> Triple(0xFFFFE3F2.toInt(), 0xFFFF4FA3.toInt(), 0xFFFF7A45.toInt())
            2 -> Triple(0xFFCFF6FF.toInt(), 0xFF27C3FF.toInt(), 0xFF1B8FD1.toInt())
            else -> Triple(0xFFBFF0FF.toInt(), 0xFF27C3FF.toInt(), 0xFF7B5CFF.toInt())
        }
        paint.shader = null
        paint.color = (c2 and 0x00FFFFFF) or 0x33000000
        canvas.drawCircle(cx, cy, r * 1.3f, paint)
        paint.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.4f, intArrayOf(c1, c2, c3), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paint)
    }
}
