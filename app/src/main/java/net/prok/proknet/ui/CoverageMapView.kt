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

    /**
     * v0.17.8: how far out to draw, in cells.
     *
     * This used to be a constant 4, so the map was ALWAYS nine cells by nine - eighty
     * identical empty squares around one real one. The grid was not lying; there really
     * was nothing else known. But a 9x9 field is a promise of density the data could not
     * keep, and it read as a loading screen rather than a map.
     *
     * It now grows with what ProkNet has actually seen: one cell known means a confident
     * 3x3, and the map widens as the network does. Watching it grow is the point.
     */
    private val minRange = 1
    private val maxRange = 4
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

    /** The furthest cell ProkNet actually knows something about, clamped to what fits. */
    private fun rangeFor(idx: Pair<Long, Long>): Int {
        var r = minRange
        for (c in cells) {
            if (c.status == Coverage.ZoneStatus.RED) continue   // nothing known there
            val i = CoverageModel.zoneIndex(c.zoneId) ?: continue
            val d = maxOf(abs(i.first - idx.first), abs(i.second - idx.second)).toInt()
            if (d > r) r = d
        }
        return r.coerceIn(minRange, maxRange)
    }

    /** The square the grid lives in, leaving room for the compass and the scale. */
    private fun board(): RectF {
        val side = minOf(width.toFloat(), height - 34f * d) * 0.98f
        val cx = width / 2f; val cy = height / 2f + 5f * d
        return RectF(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
    }

    private fun cellSize(range: Int): Float = board().width() / (2f * range + 1f)

    /**
     * Unknown cells fade towards the edge.
     *
     * Eighty squares of one flat colour compete with the one square that means something.
     * Letting the outer rings recede turns the empty area into context instead of noise,
     * and puts the eye where the information is.
     */
    private fun unknownAlpha(ringDistance: Int, range: Int): Int {
        if (range <= 0) return 60
        val t = ringDistance.toFloat() / range
        return (58 - 34f * t).toInt().coerceIn(20, 58)
    }

    private fun drawGrid(canvas: Canvas) {
        val idx = CoverageModel.zoneIndex(myZone) ?: return
        val range = rangeFor(idx)
        val b = board()
        val size = cellSize(range)
        val byZone = cells.associateBy { it.zoneId }
        val gap = (if (range <= 1) 5f else 3f) * d
        val radius = (if (range <= 1) 16f else 10f) * d

        for (row in 0..2 * range) for (col in 0..2 * range) {
            val zone = CoverageModel.zoneAt(idx.first + (range - row), idx.second + (col - range))
            val cell = byZone[zone]
            val r = RectF(b.left + col * size + gap, b.top + row * size + gap,
                b.left + (col + 1) * size - gap, b.top + (row + 1) * size - gap)
            val ringDistance = maxOf(abs(row - range), abs(col - range))
            if (cell == null || cell.status == Coverage.ZoneStatus.RED) {
                // context, not content: it recedes towards the edge
                fill.color = withAlpha(cellUnknown, unknownAlpha(ringDistance, range))
                canvas.drawRoundRect(r, radius, radius, fill)
            } else {
                // something is known here, so it carries weight and a little light
                val c = cellColor(cell)
                glow.shader = RadialGradient(r.centerX(), r.centerY(), size * 0.78f,
                    intArrayOf(withAlpha(c, 86), withAlpha(c, 0)), null, Shader.TileMode.CLAMP)
                canvas.drawCircle(r.centerX(), r.centerY(), size * 0.78f, glow)
                fill.color = c
                canvas.drawRoundRect(r, radius, radius, fill)
            }
        }

        // your own square, unmistakably yours
        val mx = b.left + (range + 0.5f) * size
        val my = b.top + (range + 0.5f) * size
        val mine = RectF(mx - size / 2f + gap, my - size / 2f + gap, mx + size / 2f - gap, my + size / 2f - gap)
        ring.color = withAlpha(brand, 220); ring.strokeWidth = 2f * d
        canvas.drawRoundRect(mine, radius, radius, ring)
        placed = emptyList()
        drawMe(canvas, mx, my, size * (if (range <= 1) 0.10f else 0.17f))

        drawCompassAndScale(canvas, b, size)
    }

    /**
     * v0.17.8: which way is north, and how big is a square.
     *
     * "500 m" was only ever a caption under the map. Somebody reading a map needs to feel
     * the scale and know which way they are facing, and both are one line of drawing.
     */
    private fun drawCompassAndScale(canvas: Canvas, b: RectF, size: Float) {
        faint.textAlign = Paint.Align.CENTER
        faint.color = withAlpha(themeMuted, 190)
        faint.textSize = 10f * d
        canvas.drawText("N", b.centerX(), b.top - 9f * d, faint)
        ring.color = withAlpha(stroke, 200); ring.strokeWidth = 1f * d
        canvas.drawLine(b.centerX(), b.top - 24f * d, b.centerX(), b.top - 18f * d, ring)

        // a bar exactly one cell wide, so the number is something you can see
        val y = b.bottom + 14f * d
        val half = size / 2f
        ring.color = withAlpha(stroke, 220)
        canvas.drawLine(b.centerX() - half, y, b.centerX() + half, y, ring)
        canvas.drawLine(b.centerX() - half, y - 3f * d, b.centerX() - half, y + 3f * d, ring)
        canvas.drawLine(b.centerX() + half, y - 3f * d, b.centerX() + half, y + 3f * d, ring)
        faint.textSize = 9.5f * d
        canvas.drawText("500 m", b.centerX(), y + 13f * d, faint)
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
        val range = rangeFor(idx)
        val b = board()
        val size = cellSize(range)
        val col = ((event.x - b.left) / size).toInt(); val row = ((event.y - b.top) / size).toInt()
        if (col !in 0..2 * range || row !in 0..2 * range) return true
        val zone = CoverageModel.zoneAt(idx.first + (range - row), idx.second + (col - range))
        onCellTap?.invoke(zone, cells.firstOrNull { it.zoneId == zone })
        return true
    }
}
