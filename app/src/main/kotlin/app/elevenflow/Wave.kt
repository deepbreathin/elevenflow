package app.elevenflow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View

/**
 * The sound-wave styles shown beside the bubble while recording. One renderer, used by the live
 * meter and by the animated previews in the app's settings, so what you pick is what you get.
 */
enum class WaveStyle(val label: String, val bars: Int) {
    NEON("Neon: green bars, red dot", 22),
    CLASSIC_ORANGE("Classic orange: the original, taller", 18),
    LINE("Line: a smooth glowing wave", 40),
    MINIMAL("Minimal: white bars, no background", 18);

    /** Meter height for a bubble of [bubblePx] height. */
    fun heightFor(bubblePx: Int, dp: Float): Int = when (this) {
        CLASSIC_ORANGE -> maxOf((44 * dp).toInt(), (bubblePx * 0.45f).toInt()).coerceAtMost((90 * dp).toInt())
        else -> (bubblePx * 0.5f).toInt().coerceIn((32 * dp).toInt(), (80 * dp).toInt())
    }

    /** Width-to-height ratio of the meter. The original was 104 × 34 dp. */
    val aspect get() = when (this) { CLASSIC_ORANGE -> 104f / 34f; else -> 3.4f }

    companion object {
        fun of(ctx: Context) = values().getOrElse(Settings.waveStyle(ctx)) { NEON }
    }
}

/** A scrolling history of mic levels plus everything needed to draw it in any style. */
class WaveView(ctx: Context, var style: WaveStyle) : View(ctx) {
    private val dp = ctx.resources.displayMetrics.density
    private var levels = FloatArray(style.bars)     // targets from the mic, newest at head-1
    private var shown = FloatArray(style.bars)      // eased values that are drawn
    private var head = 0

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }   // shadow-layer glows need software drawing

    fun setStyleTo(s: WaveStyle) {
        if (s == style) return
        style = s; levels = FloatArray(s.bars); shown = FloatArray(s.bars); head = 0; invalidate()
    }

    fun clear() { levels.fill(0f); shown.fill(0f); head = 0; invalidate() }
    fun push(v: Float) { levels[head] = v; head = (head + 1) % levels.size; if (style == WaveStyle.CLASSIC_ORANGE) invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t = SystemClock.uptimeMillis()
        when (style) {
            WaveStyle.NEON -> neon(c, w, h, t)
            WaveStyle.CLASSIC_ORANGE -> classic(c, w, h)
            WaveStyle.LINE -> line(c, w, h, t)
            WaveStyle.MINIMAL -> minimal(c, w, h, t)
        }
        // the classic look only redraws when new audio arrives, like the original; the rest animate
        if (style != WaveStyle.CLASSIC_ORANGE && visibility == VISIBLE) postInvalidateOnAnimation()
    }

    private fun ease(k: Int) = (levels[k] - shown[k]).let { shown[k] += it * 0.35f; shown[k] }
    private fun ripple(t: Long, i: Int) = 0.08f + 0.05f * Math.sin(t / 140.0 + i * 0.7).toFloat()

    // 1. Neon: dark pill, thin green rim, blinking red dot, glowing green bars that ripple when quiet.
    private fun neon(c: Canvas, w: Float, h: Float, t: Long) {
        val r = h / 2
        p.style = Paint.Style.FILL; p.color = Color.parseColor("#EB0E0B16"); p.clearShadowLayer()
        c.drawRoundRect(1f, 1f, w - 1, h - 1, r, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp; p.color = Color.parseColor("#6639FF88")
        c.drawRoundRect(1f, 1f, w - 1, h - 1, r, r, p)
        p.style = Paint.Style.FILL; p.color = Color.parseColor("#FF3B4E"); p.alpha = if ((t / 500) % 2 == 0L) 255 else 90
        c.drawCircle(r, r, h * 0.12f, p)
        glow.style = Paint.Style.FILL; glow.color = Color.parseColor("#39FF88")
        glow.setShadowLayer(3 * dp, 0f, 0f, Color.parseColor("#9939FF88"))
        bars(c, left = h * 0.85f, right = w - h * 0.35f, h = h, maxH = h * 0.72f, widthFrac = 0.6f, paint = glow) { i, k ->
            maxOf(ease(k), ripple(t, i))
        }
    }

    // 2. Classic orange: the original meter (faint see-through pill, rounded bars, no dot, no easing),
    //    in orange and a little taller.
    private fun classic(c: Canvas, w: Float, h: Float) {
        p.style = Paint.Style.FILL; p.clearShadowLayer(); p.color = Color.parseColor("#40552400")
        c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, p)
        p.color = Color.parseColor("#E6FF8A1F")
        val n = levels.size
        val slot = (w - h * 0.6f) / n
        val bw = slot * 0.55f
        for (i in 0 until n) {
            val v = levels[(head + i) % n]
            val bh = maxOf(bw, v * (h - 10 * dp))
            val cx = h * 0.3f + slot * (i + 0.5f)
            c.drawRoundRect(cx - bw / 2, (h - bh) / 2, cx + bw / 2, (h + bh) / 2, bw / 2, bw / 2, p)
        }
    }

    // 3. Line: one smooth glowing curve whose height follows your voice, on a dark pill.
    private fun line(c: Canvas, w: Float, h: Float, t: Long) {
        val r = h / 2
        p.style = Paint.Style.FILL; p.clearShadowLayer(); p.color = Color.parseColor("#E60B1020")
        c.drawRoundRect(1f, 1f, w - 1, h - 1, r, r, p)
        val n = levels.size
        val left = r * 0.7f; val right = w - r * 0.7f
        path.reset()
        for (i in 0 until n) {
            val k = (head + i) % n
            val amp = maxOf(ease(k), 0.06f) * h * 0.4f
            val x = left + (right - left) * i / (n - 1)
            val y = r + amp * Math.sin(t / 90.0 + i * 0.9).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        glow.style = Paint.Style.STROKE; glow.strokeWidth = 2.5f * dp; glow.strokeCap = Paint.Cap.ROUND
        glow.strokeJoin = Paint.Join.ROUND; glow.color = Color.parseColor("#4FE3FF")
        glow.setShadowLayer(4 * dp, 0f, 0f, Color.parseColor("#AA4FE3FF"))
        c.drawPath(path, glow)
    }

    // 4. Minimal: white bars with a soft dark shadow (readable on light or dark screens), no background.
    private fun minimal(c: Canvas, w: Float, h: Float, t: Long) {
        glow.style = Paint.Style.FILL; glow.color = Color.WHITE
        glow.setShadowLayer(2 * dp, 0f, 0f, Color.parseColor("#B3000000"))
        bars(c, left = h * 0.2f, right = w - h * 0.2f, h = h, maxH = h * 0.85f, widthFrac = 0.5f, paint = glow) { i, k ->
            maxOf(ease(k), ripple(t, i) * 0.7f)
        }
    }

    private inline fun bars(c: Canvas, left: Float, right: Float, h: Float, maxH: Float, widthFrac: Float,
                            paint: Paint, value: (Int, Int) -> Float) {
        val n = levels.size
        val slot = (right - left) / n
        val bw = slot * widthFrac
        for (i in 0 until n) {
            val k = (head + i) % n
            val bh = maxOf(bw, value(i, k) * maxH)
            val cx = left + slot * (i + 0.5f)
            c.drawRoundRect(cx - bw / 2, (h - bh) / 2, cx + bw / 2, (h + bh) / 2, bw / 2, bw / 2, paint)
        }
    }
}
