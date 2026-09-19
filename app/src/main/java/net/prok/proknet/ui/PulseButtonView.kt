package net.prok.proknet.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import net.prok.proknet.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.12.4: the sphere is the interface. It breathes at rest, its arcs and
 * the source dots orbit slowly, it pulses while searching, ripples when
 * pressed, and turns green when connected. One word per state.
 */
class PulseButtonView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    enum class Mode { IDLE, SEARCHING, CONNECTING, ONLINE }

    var label: String = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    var mode: Mode = Mode.IDLE
        set(v) { if (field != v) { field = v; modeAt = System.currentTimeMillis(); invalidate() } }
    /** How many usable sources are around: each is a dot orbiting on the arc. */
    var sources: Int = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    private var modeAt = System.currentTimeMillis()
    private var pressAt = 0L
    private val d = resources.displayMetrics.density
    private val brand = context.getColor(R.color.brand)
    private val ok = context.getColor(R.color.ok)
    private val violet = 0xFF7C5CFF.toInt()

    private val ambient = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.3f * d }
    private val wedge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pulse = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0); maskFilter = BlurMaskFilter(22f * d, BlurMaskFilter.Blur.NORMAL) }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; maskFilter = BlurMaskFilter(7f * d, BlurMaskFilter.Blur.NORMAL) }
    private val sphere = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = Color.argb(110, 255, 255, 255) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(7f * d, BlurMaskFilter.Blur.NORMAL) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f; setShadowLayer(6f * d, 0f, 2f * d, Color.argb(120, 0, 0, 0)) }

    init { isClickable = true; isFocusable = true; setLayerType(LAYER_TYPE_SOFTWARE, null) }

    private fun a(c: Int, alpha: Int) = Color.argb(alpha.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (336 * d).toInt())
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (isPressed) pressAt = System.currentTimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        val cx = width / 2f; val cy = height / 2f
        val outer = minOf(width, height) / 2f - 4f * d
        val online = mode == Mode.ONLINE
        val tone = if (online) ok else brand

        // breathing: slow at rest, quicker while searching
        val period = if (mode == Mode.SEARCHING) 1500.0 else 3400.0
        val breath = sin(2 * PI * (now % period.toLong()) / period).toFloat()   // -1..1
        val r = 82f * d * (1f + 0.022f * breath)

        // ambient light, kept inside the view so no edge ever shows
        val inner = outer * 0.92f
        ambient.shader = RadialGradient(cx - inner * 0.12f, cy - inner * 0.14f, inner, intArrayOf(a(tone, 62 + (10 * breath).toInt()), a(tone, 0)), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx - inner * 0.12f, cy - inner * 0.14f, inner, ambient)
        ambient.shader = RadialGradient(cx + inner * 0.18f, cy + inner * 0.2f, inner * 0.78f, intArrayOf(a(violet, 46), a(violet, 0)), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx + inner * 0.18f, cy + inner * 0.2f, inner * 0.78f, ambient)

        // two scan arcs that turn slowly, brighter where the light is
        val spin = (now % 40000L) / 40000f * 360f
        val arcs = listOf(r + 34f * d, minOf(r + 66f * d, outer - 2f * d))
        for ((i, rr) in arcs.withIndex()) {
            val m = Matrix().apply { postRotate(-130f + i * 40f + spin * (if (i == 0) 1f else -0.6f), cx, cy) }
            arc.shader = SweepGradient(cx, cy, intArrayOf(a(tone, 0), a(tone, if (i == 0) 130 else 80), a(tone, 0), a(tone, 0)), floatArrayOf(0f, 0.28f, 0.62f, 1f)).apply { setLocalMatrix(m) }
            canvas.drawCircle(cx, cy, rr, arc)
        }

        // the radar wedge while searching
        if (mode == Mode.SEARCHING) {
            val m = Matrix().apply { postRotate((now % 3000L) / 3000f * 360f, cx, cy) }
            wedge.shader = SweepGradient(cx, cy, intArrayOf(a(tone, 0), a(tone, 0), a(tone, 95)), floatArrayOf(0f, 0.72f, 1f)).apply { setLocalMatrix(m) }
            canvas.drawCircle(cx, cy, outer * 0.98f, wedge)
        }

        // pulse rings while connecting
        if (mode == Mode.CONNECTING) {
            val t = ((now - modeAt) % 1800L) / 1800f
            for (phase in listOf(t, (t + 0.5f) % 1f)) {
                pulse.color = a(tone, (150 * (1f - phase)).toInt())
                canvas.drawCircle(cx, cy, r + phase * (outer - r), pulse)
            }
        }

        // the press ripple
        val sincePress = now - pressAt
        if (pressAt > 0 && sincePress < 700) {
            val f = sincePress / 700f
            pulse.color = a(tone, (200 * (1f - f)).toInt())
            canvas.drawCircle(cx, cy, r + f * (outer - r), pulse)
        }

        // the sources as dots orbiting the first arc
        if (sources > 0) {
            val n = minOf(sources, 8)
            val orbit = (now % (if (mode == Mode.SEARCHING) 8000L else 24000L)) / (if (mode == Mode.SEARCHING) 8000f else 24000f) * 360f
            for (i in 0 until n) {
                val ang = (-60.0 + i * (360.0 / n) + orbit) * PI / 180.0
                val px = cx + arcs[0] * cos(ang).toFloat(); val py = cy + arcs[0] * sin(ang).toFloat()
                dotGlow.color = a(ok, 160); canvas.drawCircle(px, py, 7f * d, dotGlow)
                dot.color = ok; canvas.drawCircle(px, py, 4f * d, dot)
            }
        }

        // the sphere: shadow, halo, body, rim
        canvas.drawOval(RectF(cx - r * 0.82f, cy + r * 0.55f, cx + r * 0.82f, cy + r * 1.2f), shadow)
        halo.strokeWidth = 6f * d; halo.color = a(tone, 100 + (40 * breath).toInt())
        canvas.drawCircle(cx, cy, r + 2f * d, halo)
        val pressed = isPressed
        sphere.shader = if (online) RadialGradient(cx - r * 0.32f, cy - r * 0.38f, r * 1.55f,
            intArrayOf(0xFFE3FFF1.toInt(), 0xFF5FE3A8.toInt(), 0xFF1DA86E.toInt(), 0xFF0B4A36.toInt()), floatArrayOf(0f, 0.18f, 0.62f, 1f), Shader.TileMode.CLAMP)
        else RadialGradient(cx - r * 0.32f, cy - r * 0.38f, r * 1.55f,
            intArrayOf(if (pressed) 0xFF6FA5FF.toInt() else 0xFFCFE3FF.toInt(), 0xFF5A98FF.toInt(), 0xFF2458E6.toInt(), 0xFF10236E.toInt()), floatArrayOf(0f, 0.18f, 0.62f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, sphere)
        canvas.drawCircle(cx, cy, r - 0.8f * d, rim)

        // the word
        text.textSize = (if (label.length > 9) 17f else 20f) * d
        val lines = label.split('\n')
        val lh = 25f * d
        var y = cy - (lines.size - 1) * lh / 2f + 7f * d
        for (l in lines) { canvas.drawText(l, cx, y, text); y += lh }

        if (isAttachedToWindow && isShown) postInvalidateOnAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); if (visibility == VISIBLE) invalidate() }
}
