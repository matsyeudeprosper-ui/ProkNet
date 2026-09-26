package net.prok.proknet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import net.prok.proknet.R
import net.prok.proknet.core.ProkMap
import net.prok.proknet.core.WalkRoute
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * v0.19: the offline street map, drawn from the pack, no tiles, no network.
 *
 * The pedestrian graph's edges are the map: thin lines whose width follows the highway
 * class, so avenues read as avenues and footpaths as hairlines. On top: the free-spot pins
 * the screen hands in, the person's own position, and the walking route as a polyline.
 * Pan with one finger, pinch to zoom. "© OpenStreetMap contributors" stays on screen at
 * every zoom - that is the licence, not decoration.
 *
 * Drawing cost: every edge's endpoints are converted ONCE to a local metre frame and kept
 * in one FloatArray per class; each frame is a handful of `drawLines` calls under a
 * canvas transform. Minor classes are skipped when zoomed far out, where they would only
 * be noise. A city of 150 k edges draws in a few milliseconds on a low-end phone.
 */
class OfflineMapView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    class Pin(val id: String, val lat: Double, val lon: Double, val label: String, val color: Int, val selected: Boolean = false)

    var map: ProkMap? = null; private set
    var pins: List<Pin> = emptyList(); private set
    var me: DoubleArray? = null; private set
    var route: WalkRoute? = null; private set
    var onPinTap: ((Pin) -> Unit)? = null
    var onMapTap: ((lat: Double, lon: Double) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val brand = context.getColor(R.color.brand)
    private val bg = context.getColor(R.color.map_unknown)
    private val themeText = context.getColor(R.color.text)
    private val themeMuted = context.getColor(R.color.text_muted)
    private val onBrand = context.getColor(R.color.on_brand)

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = brand }
    private val routeHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = onBrand }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = onBrand }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeText; textSize = 11f * d }
    private val labelHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg; textSize = 11f * d; style = Paint.Style.STROKE; strokeWidth = 3f * d }
    private val attribution = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeMuted; textSize = 9.5f * d; textAlign = Paint.Align.RIGHT }
    private val attributionBg = Paint().apply { color = Color.argb(200, Color.red(bg), Color.green(bg), Color.blue(bg)) }

    // ---- the local frame: metres east / north of the pack's centre ------------------------

    private var lat0 = 0.0
    private var lon0 = 0.0
    private var mPerDegLat = 111_320.0
    private var mPerDegLon = 111_320.0

    /** One line list per class: x1,y1,x2,y2 in metres. Index = class (0..15). */
    private var lines: Array<FloatArray> = Array(16) { FloatArray(0) }
    private var bounds = floatArrayOf(-500f, -500f, 500f, 500f)   // minX, minY, maxX, maxY in metres

    // ---- the camera ---------------------------------------------------------------------------

    private var centerX = 0f      // metres
    private var centerY = 0f
    private var scale = 0.5f      // px per metre
    private val minScale = 0.02f
    private val maxScale = 8f
    private var fitted = false

    private fun mx(lon: Double): Float = ((lon - lon0) * mPerDegLon).toFloat()
    private fun my(lat: Double): Float = ((lat - lat0) * mPerDegLat).toFloat()
    private fun toPx(x: Float): Float = width / 2f + (x - centerX) * scale
    private fun toPy(y: Float): Float = height / 2f - (y - centerY) * scale
    private fun fromPx(px: Float): Float = centerX + (px - width / 2f) / scale
    private fun fromPy(py: Float): Float = centerY - (py - height / 2f) / scale

    fun setMap(m: ProkMap?) {
        map = m
        if (m == null) { lines = Array(16) { FloatArray(0) }; invalidate(); return }
        lat0 = (m.minLatMicro + m.maxLatMicro) / 2.0 / ProkMap.MICRO
        lon0 = (m.minLonMicro + m.maxLonMicro) / 2.0 / ProkMap.MICRO
        mPerDegLon = 111_320.0 * cos(Math.toRadians(lat0))
        val counts = IntArray(16)
        for (i in 0 until m.edgeCount) counts[m.edgeClass(i).coerceIn(0, 15)]++
        val arrays = Array(16) { FloatArray(counts[it] * 4) }
        val at = IntArray(16)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until m.edgeCount) {
            val c = m.edgeClass(i).coerceIn(0, 15)
            val a = m.edgeFrom(i); val b = m.edgeTo(i)
            val x1 = mx(m.nodeLon(a)); val y1 = my(m.nodeLat(a))
            val x2 = mx(m.nodeLon(b)); val y2 = my(m.nodeLat(b))
            val arr = arrays[c]; val k = at[c]
            arr[k] = x1; arr[k + 1] = y1; arr[k + 2] = x2; arr[k + 3] = y2
            at[c] = k + 4
            minX = min(minX, min(x1, x2)); maxX = max(maxX, max(x1, x2))
            minY = min(minY, min(y1, y2)); maxY = max(maxY, max(y1, y2))
        }
        lines = arrays
        if (m.edgeCount > 0) bounds = floatArrayOf(minX, minY, maxX, maxY)
        fitted = false
        invalidate()
    }

    fun setPins(p: List<Pin>) { pins = p; invalidate() }
    fun setMe(lat: Double, lon: Double) { me = doubleArrayOf(lat, lon); invalidate() }
    fun clearMe() { me = null; invalidate() }
    fun setRoute(r: WalkRoute?) { route = r; invalidate() }

    /** Put [lat],[lon] in the middle at a walking zoom. */
    fun centerOn(lat: Double, lon: Double, pxPerMetre: Float = 1.2f) {
        centerX = mx(lon); centerY = my(lat)
        scale = pxPerMetre.coerceIn(minScale, maxScale)
        fitted = true
        invalidate()
    }

    /** Show the whole route, or the whole city when there is none. */
    fun fit() {
        val r = route
        if (r != null && r.points.size >= 2) {
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (p in r.points) {
                val x = mx(p[1]); val y = my(p[0])
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            }
            fitBox(minX, minY, maxX, maxY, 48f * d)
        } else fitBox(bounds[0], bounds[1], bounds[2], bounds[3], 8f * d)
    }

    private fun fitBox(minX: Float, minY: Float, maxX: Float, maxY: Float, padPx: Float) {
        if (width == 0 || height == 0) return
        centerX = (minX + maxX) / 2f; centerY = (minY + maxY) / 2f
        val w = max(50f, maxX - minX); val h = max(50f, maxY - minY)
        scale = min((width - 2 * padPx) / w, (height - 2 * padPx) / h).coerceIn(minScale, maxScale)
        fitted = true
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val h = if (hMode == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else (w * 1.1f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!fitted) fit()
    }

    // ---- drawing ------------------------------------------------------------------------------

    /** Width in px at scale 1 (one metre per px), and the zoom below which the class is hidden. */
    private fun styleOf(cls: Int): Pair<Float, Float> = when (cls) {
        ProkMap.CLASS_PRIMARY -> 2.6f * d to 0f
        ProkMap.CLASS_SECONDARY -> 2.2f * d to 0f
        ProkMap.CLASS_TERTIARY -> 1.8f * d to 0f
        ProkMap.CLASS_RESIDENTIAL, ProkMap.CLASS_UNCLASSIFIED, ProkMap.CLASS_LIVING_STREET -> 1.2f * d to 0.08f
        ProkMap.CLASS_PEDESTRIAN -> 1.2f * d to 0.15f
        ProkMap.CLASS_SERVICE -> 0.8f * d to 0.3f
        ProkMap.CLASS_STEPS -> 1.0f * d to 0.5f
        else -> 0.7f * d to 0.3f          // footway, cycleway, path, track
    }

    private fun colorOf(cls: Int): Int = when (cls) {
        ProkMap.CLASS_PRIMARY, ProkMap.CLASS_SECONDARY -> Color.argb(255, 120, 132, 160)
        ProkMap.CLASS_TERTIARY -> Color.argb(255, 140, 150, 176)
        ProkMap.CLASS_RESIDENTIAL, ProkMap.CLASS_UNCLASSIFIED, ProkMap.CLASS_LIVING_STREET, ProkMap.CLASS_PEDESTRIAN -> Color.argb(255, 168, 178, 200)
        ProkMap.CLASS_STEPS -> Color.argb(255, 150, 130, 180)
        else -> Color.argb(255, 190, 200, 214)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(bg)
        val m = map
        if (m == null) {
            label.textAlign = Paint.Align.CENTER
            canvas.drawText("Carte hors ligne non téléchargée", width / 2f, height / 2f, label)
            label.textAlign = Paint.Align.LEFT
            drawAttribution(canvas)
            return
        }
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(scale, -scale)
        canvas.translate(-centerX, -centerY)
        // major roads last, so they sit on top of the lanes
        for (cls in 15 downTo 0) {
            val arr = lines[cls]
            if (arr.isEmpty()) continue
            val (w, minZoom) = styleOf(cls)
            if (scale < minZoom) continue
            line.color = colorOf(cls)
            line.strokeWidth = max(w, 1.5f) / scale
            canvas.drawLines(arr, line)
        }
        drawRoute(canvas)
        canvas.restore()
        drawPins(canvas)
        drawMe(canvas)
        drawScale(canvas)
        drawAttribution(canvas)
    }

    private fun drawRoute(canvas: Canvas) {
        val r = route ?: return
        if (r.points.size < 2) return
        val path = Path()
        for ((i, p) in r.points.withIndex()) {
            val x = mx(p[1]); val y = my(p[0])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        routeHalo.strokeWidth = 7f * d / scale
        routePaint.strokeWidth = 4f * d / scale
        canvas.drawPath(path, routeHalo)
        canvas.drawPath(path, routePaint)
    }

    private fun drawPins(canvas: Canvas) {
        for (p in pins) {
            val px = toPx(mx(p.lon)); val py = toPy(my(p.lat))
            if (px < -40f * d || px > width + 40f * d || py < -40f * d || py > height + 40f * d) continue
            val r = (if (p.selected) 9f else 6.5f) * d
            fill.color = p.color
            rim.strokeWidth = 2f * d
            canvas.drawCircle(px, py, r, fill)
            canvas.drawCircle(px, py, r, rim)
            if (p.selected || scale > 0.6f) {
                val text = if (p.label.length > 22) p.label.take(21) + "…" else p.label
                canvas.drawText(text, px + r + 4f * d, py + 4f * d, labelHalo)
                canvas.drawText(text, px + r + 4f * d, py + 4f * d, label)
            }
        }
    }

    private fun drawMe(canvas: Canvas) {
        val p = me ?: return
        val px = toPx(mx(p[1])); val py = toPy(my(p[0]))
        fill.color = Color.argb(60, Color.red(brand), Color.green(brand), Color.blue(brand))
        canvas.drawCircle(px, py, 18f * d, fill)
        fill.color = brand
        rim.strokeWidth = 2.5f * d
        canvas.drawCircle(px, py, 7f * d, fill)
        canvas.drawCircle(px, py, 7f * d, rim)
    }

    /** A bar of a round number of metres, so the person can feel the distance. */
    private fun drawScale(canvas: Canvas) {
        val targetPx = 80f * d
        val metres = targetPx / scale
        val nice = listOf(10f, 20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f).minByOrNull { kotlin.math.abs(it - metres) } ?: 100f
        val px = nice * scale
        val x = 12f * d; val y = height - 30f * d
        line.color = themeMuted; line.strokeWidth = 2f * d
        canvas.drawLine(x, y, x + px, y, line)
        canvas.drawLine(x, y - 4f * d, x, y + 4f * d, line)
        canvas.drawLine(x + px, y - 4f * d, x + px, y + 4f * d, line)
        label.textAlign = Paint.Align.LEFT
        val t = if (nice >= 1000f) (nice / 1000f).toInt().toString() + " km" else nice.toInt().toString() + " m"
        canvas.drawText(t, x, y - 7f * d, labelHalo)
        canvas.drawText(t, x, y - 7f * d, label)
    }

    private fun drawAttribution(canvas: Canvas) {
        val text = "© OpenStreetMap contributors"
        val w = attribution.measureText(text)
        canvas.drawRect(width - w - 12f * d, height - 18f * d, width.toFloat(), height.toFloat(), attributionBg)
        canvas.drawText(text, width - 6f * d, height - 5.5f * d, attribution)
    }

    // ---- touch: drag, pinch, tap ---------------------------------------------------------------

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val fx = fromPx(det.focusX); val fy = fromPy(det.focusY)
            scale = (scale * det.scaleFactor).coerceIn(minScale, maxScale)
            // keep the point under the fingers where it is
            centerX = fx - (det.focusX - width / 2f) / scale
            centerY = fy + (det.focusY - height / 2f) / scale
            invalidate()
            return true
        }
    })
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var moved = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y; downX = event.x; downY = event.y
                dragging = true; moved = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> dragging = false
            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaler.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX; val dy = event.y - lastY
                    if (hypot(event.x - downX, event.y - downY) > 8f * d) moved = true
                    centerX -= dx / scale
                    centerY += dy / scale
                    clampCenter()
                    invalidate()
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!moved && !scaler.isInProgress && event.pointerCount == 1) tap(event.x, event.y)
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); dragging = false }
        }
        return true
    }

    private fun clampCenter() {
        val pad = 2000f
        centerX = centerX.coerceIn(bounds[0] - pad, bounds[2] + pad)
        centerY = centerY.coerceIn(bounds[1] - pad, bounds[3] + pad)
    }

    private fun tap(x: Float, y: Float) {
        val hit = pins.minByOrNull { hypot(toPx(mx(it.lon)) - x, toPy(my(it.lat)) - y) }
        if (hit != null && hypot(toPx(mx(hit.lon)) - x, toPy(my(hit.lat)) - y) < 26f * d) {
            onPinTap?.invoke(hit)
            return
        }
        val lat = lat0 + fromPy(y) / mPerDegLat
        val lon = lon0 + fromPx(x) / mPerDegLon
        onMapTap?.invoke(lat, lon)
    }
}
