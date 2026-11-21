package com.tvguard.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Seven bars, oldest day on the left, each labelled with its watch time (e.g. "5 h" over "20 min") and weekday. */
class HistoryChartView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var days: List<Pair<LocalDate, Int>> = emptyList()

    private val textColor = androidx.core.content.ContextCompat.getColor(context, R.color.muted)
    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = androidx.core.content.ContextCompat.getColor(context, R.color.accent) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 12 * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    /** [minutesByDay] keys are yyyy-MM-dd; missing days show as 0. */
    fun setData(minutesByDay: Map<String, Int>, today: LocalDate = LocalDate.now()) {
        days = (6 downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            day to (minutesByDay[day.toString()] ?: 0)
        }
        invalidate()
    }

    /** Total and daily average over the seven days, for the line under the chart. */
    fun totals(): Pair<Int, Int> {
        val total = days.sumOf { it.second }
        return total to (if (days.isEmpty()) 0 else total / days.size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (days.isEmpty()) return
        val labelSpace = textPaint.textSize * 1.6f
        // Room for a two-line label ("5 h" over "20 min") above the tallest bar.
        val valueSpace = textPaint.textSize * 2.6f
        val chartTop = valueSpace
        val chartBottom = height - labelSpace
        val max = days.maxOf { it.second }.coerceAtLeast(1)
        val slot = width / days.size.toFloat()
        val barWidth = slot * 0.6f

        days.forEachIndexed { i, (day, minutes) ->
            val cx = slot * i + slot / 2
            val barTop = chartBottom - (chartBottom - chartTop) * minutes / max
            canvas.drawRoundRect(
                cx - barWidth / 2, barTop, cx + barWidth / 2, chartBottom,
                4 * density, 4 * density, barPaint,
            )
            val lines = label(minutes)
            lines.forEachIndexed { line, text ->
                val y = barTop - 4 * density - (lines.size - 1 - line) * textPaint.textSize * 1.15f
                canvas.drawText(text, cx, y, textPaint)
            }
            canvas.drawText(
                day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                cx, height - textPaint.textSize * 0.4f, textPaint,
            )
        }
    }

    companion object {
        /** "45 min", "2 h" or "5 h" + "20 min" on two lines, so it fits above a narrow bar. */
        fun label(minutes: Int): List<String> = when {
            minutes < 60 -> listOf("$minutes min")
            minutes % 60 == 0 -> listOf("${minutes / 60} h")
            else -> listOf("${minutes / 60} h", "${minutes % 60} min")
        }
    }
}
