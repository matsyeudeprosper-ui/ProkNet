package net.prok.proknet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import net.prok.proknet.R
import net.prok.proknet.core.Coverage
import net.prok.proknet.core.CoverageModel

/**
 * v0.12: the first map. Coarse cells around this phone's cell, coloured by
 * what ProkNet actually knows. No tiles, no server, no exact position: the
 * phone is the glowing dot in the centre cell.
 */
class CoverageMapView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var cells: List<CoverageModel.Cell> = emptyList()
        private set
    var myZone: String = CoverageModel.NO_ZONE
        private set
    var onCellTap: ((zoneId: String, cell: CoverageModel.Cell?) -> Unit)? = null

    private val range = 4
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val me = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.brand) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = context.getColor(R.color.on_brand) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val green = context.getColor(R.color.map_green)
    private val yellow = context.getColor(R.color.map_yellow)
    private val red = context.getColor(R.color.map_red)
    private val unknown = context.getColor(R.color.map_unknown)
    private val darkText = context.getColor(R.color.legend_text)
    private val themeText = context.getColor(R.color.text)
    private val themeMuted = context.getColor(R.color.text_muted)

    fun set(cells: List<CoverageModel.Cell>, myZone: String) { this.cells = cells; this.myZone = myZone; invalidate() }

    private fun colorOf(c: CoverageModel.Cell?): Int = when (c?.status) {
        Coverage.ZoneStatus.GREEN -> green
        Coverage.ZoneStatus.YELLOW -> yellow
        Coverage.ZoneStatus.RED -> red
        null -> unknown
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.86f).toInt())
    }

    private fun cellSize(): Float = minOf(width, height) / (2f * range + 1f)

    private fun drawMe(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        glow.shader = RadialGradient(cx, cy, r * 3.2f, intArrayOf(me.color and 0x66FFFFFF, me.color and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 3.2f, glow)
        ring.strokeWidth = r * 0.45f
        canvas.drawCircle(cx, cy, r, ring)
        canvas.drawCircle(cx, cy, r, me)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val idx = CoverageModel.zoneIndex(myZone)
        val d = resources.displayMetrics.density
        label.textSize = 16f * d; sub.textSize = 13f * d
        if (idx == null) {
            // no position: one cell, "around you"
            val c = cells.firstOrNull { it.zoneId == CoverageModel.NO_ZONE }
            val col = colorOf(c)
            fill.color = col
            val pad = 20f * d
            val r = RectF(pad, pad, width - pad, height - pad)
            canvas.drawRoundRect(r, 28f * d, 28f * d, fill)
            val onPastel = col != unknown
            label.color = if (onPastel) darkText else themeText
            sub.color = if (onPastel) darkText else themeMuted
            drawMe(canvas, r.centerX(), r.centerY() - 26f * d, 9f * d)
            canvas.drawText("Autour de vous", r.centerX(), r.centerY() + 22f * d, label)
            canvas.drawText(c?.let { CoverageModel.cellWord(it.status) } ?: "Aucune observation", r.centerX(), r.centerY() + 44f * d, sub)
            return
        }
        val size = cellSize()
        val ox = (width - size * (2 * range + 1)) / 2f
        val oy = (height - size * (2 * range + 1)) / 2f
        val byZone = cells.associateBy { it.zoneId }
        val gap = 2.5f * d
        for (row in 0..2 * range) for (col in 0..2 * range) {
            val dLat = range - row; val dLon = col - range
            val zone = CoverageModel.zoneAt(idx.first + dLat, idx.second + dLon)
            fill.color = colorOf(byZone[zone])
            val r = RectF(ox + col * size + gap, oy + row * size + gap, ox + (col + 1) * size - gap, oy + (row + 1) * size - gap)
            canvas.drawRoundRect(r, 9f * d, 9f * d, fill)
        }
        drawMe(canvas, ox + (range + 0.5f) * size, oy + (range + 0.5f) * size, size * 0.17f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val idx = CoverageModel.zoneIndex(myZone)
        if (idx == null) { onCellTap?.invoke(CoverageModel.NO_ZONE, cells.firstOrNull { it.zoneId == CoverageModel.NO_ZONE }); return true }
        val size = cellSize()
        val ox = (width - size * (2 * range + 1)) / 2f
        val oy = (height - size * (2 * range + 1)) / 2f
        val col = ((event.x - ox) / size).toInt(); val row = ((event.y - oy) / size).toInt()
        if (col !in 0..2 * range || row !in 0..2 * range) return true
        val zone = CoverageModel.zoneAt(idx.first + (range - row), idx.second + (col - range))
        onCellTap?.invoke(zone, cells.firstOrNull { it.zoneId == zone })
        return true
    }
}
