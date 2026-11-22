package cz.dpf.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class GraphSeries(val label: String, val color: Int, val points: List<ChartPoint>)

/** Small dependency-free, accessibility-labeled line chart for Android View UI. */
class TelemetryChart(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(48, 64, 78); strokeWidth = density
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(154, 175, 187); textSize = 11 * density
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.1f * density
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val formatter = SimpleDateFormat("dd.MM. HH:mm", Locale.forLanguageTag("cs-CZ"))
    private var lines: List<GraphSeries> = emptyList()
    var fixedRange: ClosedFloatingPointRange<Double>? = null

    fun update(series: List<GraphSeries>) {
        lines = series
        contentDescription = series.joinToString("; ") {
            it.label + ": " + (it.points.lastOrNull()?.value?.let { v -> "%.1f".format(Locale.ROOT, v) } ?: "bez dat")
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (208 * density).toInt())
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val left = 44 * density
        val right = width - 12 * density
        val top = 18 * density
        val bottom = height - 30 * density
        c.drawColor(Color.rgb(19, 31, 44))
        val all = lines.flatMap { it.points }.filter { it.value.isFinite() }
        if (all.isEmpty() || right <= left) {
            text.textAlign = Paint.Align.CENTER
            c.drawText("Zatím bez měření", width / 2f, height / 2f, text)
            text.textAlign = Paint.Align.LEFT
            return
        }
        val minT = all.minOf { it.timeMs }
        val maxT = max(minT + 60_000, all.maxOf { it.timeMs })
        val lo: Double
        val hi: Double
        if (fixedRange != null) {
            lo = fixedRange!!.start
            hi = fixedRange!!.endInclusive
        } else {
            val minimum = all.minOf { it.value }
            val maximum = all.maxOf { it.value }
            val padding = max((maximum - minimum) * 0.12, 1.0)
            lo = minimum - padding
            hi = maximum + padding
        }
        val xx: (Long) -> Float = { t -> left + (right - left) * ((t - minT).toDouble() / (maxT - minT)).toFloat() }
        val yy: (Double) -> Float = { v -> bottom - (bottom - top) * ((v - lo) / (hi - lo)).toFloat() }
        text.textAlign = Paint.Align.RIGHT
        for (i in 0..4) {
            val v = lo + (hi - lo) * i / 4
            val y = yy(v)
            c.drawLine(left, y, right, y, grid)
            c.drawText("%.0f".format(Locale.ROOT, v), left - 7 * density, y + 4 * density, text)
        }
        text.textAlign = Paint.Align.LEFT
        c.drawText(formatter.format(Date(minT)), left, height - 9 * density, text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText(formatter.format(Date(maxT)), right, height - 9 * density, text)
        text.textAlign = Paint.Align.LEFT
        for (series in lines) {
            val pts = series.points.filter { it.value.isFinite() }
            if (pts.isEmpty()) continue
            val p = Path()
            pts.forEachIndexed { i, value ->
                if (i == 0) p.moveTo(xx(value.timeMs), yy(value.value))
                else p.lineTo(xx(value.timeMs), yy(value.value))
            }
            line.color = series.color
            c.drawPath(p, line)
            val newest = pts.last()
            dot.color = series.color
            c.drawCircle(xx(newest.timeMs), yy(newest.value), 3.6f * density, dot)
        }
    }
}
