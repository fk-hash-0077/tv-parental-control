package com.tvguard.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Seven bars, oldest day on the left, each labelled with its minutes and weekday. */
class HistoryChartView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var days: List<Pair<LocalDate, Int>> = emptyList()

    private val textColor = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).run {
        getColor(0, 0xFF000000.toInt()).also { recycle() }
    }
    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3D7BC8.toInt() }
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (days.isEmpty()) return
        val labelSpace = textPaint.textSize * 1.6f
        val valueSpace = textPaint.textSize * 1.4f
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
            canvas.drawText(minutes.toString(), cx, barTop - 4 * density, textPaint)
            canvas.drawText(
                day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                cx, height - textPaint.textSize * 0.4f, textPaint,
            )
        }
    }
}
