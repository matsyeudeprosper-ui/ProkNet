package net.prok.proknet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import net.prok.proknet.core.Coverage
import net.prok.proknet.core.CoverageModel

/**
 * v0.12: the first map. Coarse cells around this phone's cell, coloured by
 * what ProkNet actually knows: available, can be organised, not yet covered.
 * No tiles, no server, no exact position: the phone is the centre cell.
 */
class CoverageMapView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var cells: List<CoverageModel.Cell> = emptyList()
        private set
    var myZone: String = CoverageModel.NO_ZONE
        private set
    var onCellTap: ((zoneId: String, cell: CoverageModel.Cell?) -> Unit)? = null

    private val range = 4
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.WHITE }
    private val me = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1B5FE0") }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#14181F"); textAlign = Paint.Align.CENTER }

    private val green = Color.parseColor("#7ED6A2")
    private val yellow = Color.parseColor("#F2D27A")
    private val red = Color.parseColor("#F2B8B8")
    private val unknown = Color.parseColor("#E1E5EC")

    fun set(cells: List<CoverageModel.Cell>, myZone: String) { this.cells = cells; this.myZone = myZone; invalidate() }

    private fun colorOf(c: CoverageModel.Cell?): Int = when (c?.status) {
        Coverage.ZoneStatus.GREEN -> green
        Coverage.ZoneStatus.YELLOW -> yellow
        Coverage.ZoneStatus.RED -> red
        null -> unknown
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.9f).toInt())
    }

    private fun cellSize(): Float = minOf(width, height) / (2f * range + 1f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val idx = CoverageModel.zoneIndex(myZone)
        val density = resources.displayMetrics.density
        text.textSize = 13f * density
        if (idx == null) {
            // no location: one cell, "around you"
            val c = cells.firstOrNull { it.zoneId == CoverageModel.NO_ZONE }
            fill.color = colorOf(c)
            val pad = 24f * density
            val r = RectF(pad, pad, width - pad, height - pad)
            canvas.drawRoundRect(r, 24f * density, 24f * density, fill)
            canvas.drawCircle(r.centerX(), r.centerY() - 20f * density, 8f * density, me)
            canvas.drawText("Autour de vous", r.centerX(), r.centerY() + 12f * density, text)
            canvas.drawText(c?.let { CoverageModel.cellWord(it.status) } ?: "Position inconnue", r.centerX(), r.centerY() + 32f * density, text)
            return
        }
        val size = cellSize()
        val ox = (width - size * (2 * range + 1)) / 2f
        val oy = (height - size * (2 * range + 1)) / 2f
        val byZone = cells.associateBy { it.zoneId }
        for (row in 0..2 * range) for (col in 0..2 * range) {
            val dLat = range - row; val dLon = col - range
            val zone = CoverageModel.zoneAt(idx.first + dLat, idx.second + dLon)
            fill.color = colorOf(byZone[zone])
            val r = RectF(ox + col * size + 2f, oy + row * size + 2f, ox + (col + 1) * size - 2f, oy + (row + 1) * size - 2f)
            canvas.drawRoundRect(r, 6f * density, 6f * density, fill)
        }
        val cx = ox + (range + 0.5f) * size; val cy = oy + (range + 0.5f) * size
        canvas.drawCircle(cx, cy, size * 0.22f, stroke)
        canvas.drawCircle(cx, cy, size * 0.16f, me)
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
