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
import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.12.3: the one button, drawn to look lit and heavy. Ambient blue and
 * violet light, two faint scan arcs, the sources ProkNet sees as small
 * green dots on the arc, a radar wedge that turns while searching, and a
 * sphere with a specular highlight, a dark edge, a drop shadow and a
 * luminous rim.
 */
class PulseButtonView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var label: String = ""
        set(v) { field = v; invalidate() }
    var searching: Boolean = false
        set(v) { if (field != v) { field = v; startedAt = System.currentTimeMillis(); invalidate() } }
    /** How many usable sources are around: each is a dot on the arc. */
    var sources: Int = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    private var startedAt = 0L
    private val d = resources.displayMetrics.density
    private val brand = context.getColor(R.color.brand)
    private val ok = context.getColor(R.color.ok)
    private val violet = 0xFF7C5CFF.toInt()

    private val ambient = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.3f * d }
    private val wedge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0); maskFilter = BlurMaskFilter(22f * d, BlurMaskFilter.Blur.NORMAL) }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; maskFilter = BlurMaskFilter(6f * d, BlurMaskFilter.Blur.NORMAL) }
    private val sphere = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = Color.argb(110, 255, 255, 255) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(7f * d, BlurMaskFilter.Blur.NORMAL) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.06f; setShadowLayer(6f * d, 0f, 2f * d, Color.argb(120, 0, 0, 0)) }

    init { isClickable = true; isFocusable = true; setLayerType(LAYER_TYPE_SOFTWARE, null) }

    private fun a(c: Int, alpha: Int) = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (300 * d).toInt())
    }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f
        val r = 82f * d
        val outer = minOf(width, height) / 2f - 6f * d
        val t = ((System.currentTimeMillis() - startedAt) % 6000L) / 6000f

        // ambient light: blue high-left, violet low-right
        ambient.shader = RadialGradient(cx - outer * 0.45f, cy - outer * 0.5f, outer * 1.1f, intArrayOf(a(brand, 70), a(brand, 0)), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx - outer * 0.45f, cy - outer * 0.5f, outer * 1.1f, ambient)
        ambient.shader = RadialGradient(cx + outer * 0.6f, cy + outer * 0.55f, outer * 0.9f, intArrayOf(a(violet, 48), a(violet, 0)), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx + outer * 0.6f, cy + outer * 0.55f, outer * 0.9f, ambient)

        // two faint scan arcs, brighter where the light is
        for ((i, rr) in listOf(r + 42f * d, r + 84f * d).withIndex()) {
            val m = Matrix().apply { postRotate(-130f + i * 25f, cx, cy) }
            arc.shader = SweepGradient(cx, cy, intArrayOf(a(brand, 0), a(brand, if (i == 0) 120 else 70), a(brand, 0), a(brand, 0)), floatArrayOf(0f, 0.28f, 0.62f, 1f)).apply { setLocalMatrix(m) }
            canvas.drawCircle(cx, cy, rr, arc)
        }

        // the radar wedge while searching
        if (searching) {
            val m = Matrix().apply { postRotate(t * 360f * 2f, cx, cy) }
            wedge.shader = SweepGradient(cx, cy, intArrayOf(a(brand, 0), a(brand, 0), a(brand, 95)), floatArrayOf(0f, 0.72f, 1f)).apply { setLocalMatrix(m) }
            canvas.drawCircle(cx, cy, outer * 0.98f, wedge)
            postInvalidateOnAnimation()
        }

        // the sources as dots on the first arc
        if (sources > 0) {
            val n = minOf(sources, 8)
            for (i in 0 until n) {
                val ang = (-60.0 + i * (360.0 / n) + (if (searching) t * 40.0 else 0.0)) * Math.PI / 180.0
                val px = cx + (r + 42f * d) * cos(ang).toFloat(); val py = cy + (r + 42f * d) * sin(ang).toFloat()
                dotGlow.color = a(ok, 160); canvas.drawCircle(px, py, 7f * d, dotGlow)
                dot.color = ok; canvas.drawCircle(px, py, 4f * d, dot)
            }
        }

        // the sphere: shadow, halo, body, rim
        canvas.drawOval(RectF(cx - r * 0.82f, cy + r * 0.55f, cx + r * 0.82f, cy + r * 1.2f), shadow)
        halo.strokeWidth = 5f * d; halo.color = a(brand, 120)
        canvas.drawCircle(cx, cy, r + 2f * d, halo)
        val pressed = isPressed
        sphere.shader = RadialGradient(cx - r * 0.32f, cy - r * 0.38f, r * 1.55f,
            intArrayOf(if (pressed) 0xFF6FA5FF.toInt() else 0xFFCFE3FF.toInt(), 0xFF5A98FF.toInt(), 0xFF2458E6.toInt(), 0xFF10236E.toInt()),
            floatArrayOf(0f, 0.18f, 0.62f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, sphere)
        canvas.drawCircle(cx, cy, r - 0.8f * d, rim)

        // the words
        text.textSize = 20f * d
        val lines = label.split('\n')
        val lh = 25f * d
        var y = cy - (lines.size - 1) * lh / 2f + 7f * d
        for (l in lines) { canvas.drawText(l, cx, y, text); y += lh }
    }
}
