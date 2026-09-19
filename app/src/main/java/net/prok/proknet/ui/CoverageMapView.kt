package net.prok.proknet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * v0.12.2: what ProkNet has seen around this phone, drawn honestly.
 *
 * Without a position: a sonar. You are the centre; every known source is a
 * dot placed by how recently it was seen (fresh near the centre, old at the
 * edge), coloured by status, labelled. With a position: the coarse cells
 * around your cell.
 */
class CoverageMapView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    class Mark(val id: String, val name: String, val status: Coverage.ZoneStatus, val ageMs: Long)

    var cells: List<CoverageModel.Cell> = emptyList(); private set
    var marks: List<Mark> = emptyList(); private set
    var myZone: String = CoverageModel.NO_ZONE; private set
    var onCellTap: ((zoneId: String, cell: CoverageModel.Cell?) -> Unit)? = null
    var onMarkTap: ((mark: Mark) -> Unit)? = null

    private val range = 4
    private val d = resources.displayMetrics.density
    private val brand = context.getColor(R.color.brand)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d }
    private val me = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = brand }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = context.getColor(R.color.on_brand) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val ok = context.getColor(R.color.ok)
    private val warn = context.getColor(R.color.warn)
    private val muted = context.getColor(R.color.nav_inactive)
    private val stroke = context.getColor(R.color.stroke)
    private val themeText = context.getColor(R.color.text)
    private val themeMuted = context.getColor(R.color.text_muted)
    private val cellGreen = context.getColor(R.color.map_green)
    private val cellYellow = context.getColor(R.color.map_yellow)
    private val cellRed = context.getColor(R.color.map_red)
    private val cellUnknown = context.getColor(R.color.map_unknown)

    private class Placed(val mark: Mark, val x: Float, val y: Float)
    private var placed: List<Placed> = emptyList()

    fun set(cells: List<CoverageModel.Cell>, myZone: String, marks: List<Mark>) { this.cells = cells; this.myZone = myZone; this.marks = marks; invalidate() }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
    private fun colorOf(s: Coverage.ZoneStatus) = when (s) { Coverage.ZoneStatus.GREEN -> ok; Coverage.ZoneStatus.YELLOW -> warn; Coverage.ZoneStatus.RED -> muted }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.92f).toInt())
    }

    private fun drawMe(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        glow.shader = RadialGradient(cx, cy, r * 4f, intArrayOf(withAlpha(brand, 110), withAlpha(brand, 0)), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 4f, glow)
        rim.strokeWidth = r * 0.5f
        canvas.drawCircle(cx, cy, r, rim)
        canvas.drawCircle(cx, cy, r, me)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (CoverageModel.zoneIndex(myZone) == null) drawSonar(canvas) else drawGrid(canvas)
    }

    /** Fresh = near the centre. */
    private fun distanceFor(ageMs: Long): Float = when {
        ageMs <= CoverageModel.FRESH_MS -> 0.30f
        ageMs <= 60 * 60_000L -> 0.30f + 0.25f * (ageMs - CoverageModel.FRESH_MS).toFloat() / (60 * 60_000L - CoverageModel.FRESH_MS)
        ageMs <= CoverageModel.RECENT_MS -> 0.55f + 0.30f * (ageMs - 60 * 60_000L).toFloat() / (CoverageModel.RECENT_MS - 60 * 60_000L)
        else -> 0.92f
    }

    private fun drawSonar(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val outer = minOf(width, height) / 2f - 14f * d
        // rings and a faint cross
        for (f in listOf(0.3f, 0.55f, 0.85f, 1f)) { ring.color = withAlpha(stroke, if (f == 1f) 255 else 170); canvas.drawCircle(cx, cy, outer * f, ring) }
        ring.color = withAlpha(stroke, 110)
        canvas.drawLine(cx - outer, cy, cx + outer, cy, ring); canvas.drawLine(cx, cy - outer, cx, cy + outer, ring)
        faint.color = themeMuted; faint.textSize = 10.5f * d
        canvas.drawText("récent", cx + outer * 0.3f, cy - 5f * d, faint)
        canvas.drawText("ancien", cx + outer * 0.92f, cy - 5f * d, faint)

        // the sources
        val out = ArrayList<Placed>()
        val sorted = marks.sortedBy { it.ageMs }.take(14)
        val n = sorted.size
        for ((i, m) in sorted.withIndex()) {
            val h = abs(m.id.hashCode())
            // spread evenly, with a little of the id so the picture is stable
            val angle = (i * 360f / maxOf(1, n) + (h % 40) - 20f - 90f) * Math.PI.toFloat() / 180f
            val dist = distanceFor(m.ageMs) * outer
            out += Placed(m, cx + dist * cos(angle), cy + dist * sin(angle))
        }
        placed = out
        drawMe(canvas, cx, cy, 7f * d)
        label.textSize = 11.5f * d
        for (p in placed) {
            val c = colorOf(p.mark.status)
            glow.shader = RadialGradient(p.x, p.y, 16f * d, intArrayOf(withAlpha(c, 90), withAlpha(c, 0)), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(p.x, p.y, 16f * d, glow)
            fill.color = c; canvas.drawCircle(p.x, p.y, 6f * d, fill)
            label.color = themeText
            val name = if (p.mark.name.length > 16) p.mark.name.take(15) + "…" else p.mark.name
            val left = p.x < cx
            label.textAlign = if (left) Paint.Align.RIGHT else Paint.Align.LEFT
            canvas.drawText(name, p.x + (if (left) -10f else 10f) * d, p.y + 4f * d, label)
        }
        if (marks.isEmpty()) {
            faint.color = themeMuted; faint.textSize = 13f * d
            canvas.drawText("Rien vu pour l'instant", cx, cy + outer * 0.72f, faint)
        }
    }

    private fun cellColor(c: CoverageModel.Cell?): Int = when (c?.status) {
        Coverage.ZoneStatus.GREEN -> cellGreen; Coverage.ZoneStatus.YELLOW -> cellYellow; Coverage.ZoneStatus.RED -> cellRed; null -> cellUnknown
    }

    private fun cellSize(): Float = minOf(width, height) / (2f * range + 1f)

    private fun drawGrid(canvas: Canvas) {
        val idx = CoverageModel.zoneIndex(myZone) ?: return
        val size = cellSize()
        val ox = (width - size * (2 * range + 1)) / 2f; val oy = (height - size * (2 * range + 1)) / 2f
        val byZone = cells.associateBy { it.zoneId }
        val gap = 2.5f * d
        for (row in 0..2 * range) for (col in 0..2 * range) {
            val zone = CoverageModel.zoneAt(idx.first + (range - row), idx.second + (col - range))
            fill.color = cellColor(byZone[zone])
            val r = RectF(ox + col * size + gap, oy + row * size + gap, ox + (col + 1) * size - gap, oy + (row + 1) * size - gap)
            canvas.drawRoundRect(r, 9f * d, 9f * d, fill)
        }
        placed = emptyList()
        drawMe(canvas, ox + (range + 0.5f) * size, oy + (range + 0.5f) * size, size * 0.17f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val idx = CoverageModel.zoneIndex(myZone)
        if (idx == null) {
            val hit = placed.minByOrNull { hypot(it.x - event.x, it.y - event.y) }
            if (hit != null && hypot(hit.x - event.x, hit.y - event.y) < 28f * d) onMarkTap?.invoke(hit.mark)
            else onCellTap?.invoke(CoverageModel.NO_ZONE, cells.firstOrNull { it.zoneId == CoverageModel.NO_ZONE })
            return true
        }
        val size = cellSize()
        val ox = (width - size * (2 * range + 1)) / 2f; val oy = (height - size * (2 * range + 1)) / 2f
        val col = ((event.x - ox) / size).toInt(); val row = ((event.y - oy) / size).toInt()
        if (col !in 0..2 * range || row !in 0..2 * range) return true
        val zone = CoverageModel.zoneAt(idx.first + (range - row), idx.second + (col - range))
        onCellTap?.invoke(zone, cells.firstOrNull { it.zoneId == zone })
        return true
    }
}
