package net.prok.proknet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import net.prok.proknet.R

/**
 * v0.12.2: the one button, drawn. A lit sphere at the centre of thin rings on
 * a soft glow; while searching, rings expand and fade like a sonar sweep.
 */
class PulseButtonView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var label: String = ""
        set(v) { field = v; invalidate() }
    var searching: Boolean = false
        set(v) { if (field != v) { field = v; startedAt = System.currentTimeMillis(); invalidate() } }

    private var startedAt = 0L
    private val d = resources.displayMetrics.density
    private val brand = context.getColor(R.color.brand)
    private val deep = context.getColor(R.color.brand_deep)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d }
    private val sweep = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val sphere = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.argb(70, 255, 255, 255) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.04f }

    init { isClickable = true; isFocusable = true }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (320 * d).toInt())
    }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f
        val r = 75f * d
        val outer = minOf(width, height) / 2f - 4f * d

        // the glow
        glow.shader = RadialGradient(cx, cy, outer, intArrayOf(withAlpha(brand, 60), withAlpha(brand, 18), withAlpha(brand, 0)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, outer, glow)

        // the still rings
        var k = 0
        for (rr in listOf(r + 24f * d, r + 50f * d, r + 76f * d)) {
            ring.color = withAlpha(brand, listOf(70, 45, 28)[k++])
            canvas.drawCircle(cx, cy, rr, ring)
        }

        // the sweep while searching
        if (searching) {
            val t = ((System.currentTimeMillis() - startedAt) % 2000L) / 2000f
            for (phase in listOf(t, (t + 0.5f) % 1f)) {
                val rr = r + phase * (outer - r)
                sweep.color = withAlpha(brand, (150 * (1f - phase)).toInt())
                canvas.drawCircle(cx, cy, rr, sweep)
            }
            postInvalidateOnAnimation()
        }

        // the sphere
        val pressed = isPressed
        sphere.shader = RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 1.6f,
            intArrayOf(if (pressed) brand else 0xFF9CC3FF.toInt(), if (pressed) deep else brand, deep), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, sphere)
        canvas.drawCircle(cx, cy, r - 1f * d, rim)

        // the words
        text.textSize = 19f * d
        val lines = label.split('\n')
        val lh = 24f * d
        var y = cy - (lines.size - 1) * lh / 2f + 7f * d
        for (l in lines) { canvas.drawText(l, cx, y, text); y += lh }
    }
}
