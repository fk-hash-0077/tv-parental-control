package com.tvguard.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/** A round progress ring: how much of today's TV time is used. */
class RingView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val stroke = 13 * resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = ContextCompat.getColor(context, R.color.ringTrack)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.ok)
    }
    private val oval = RectF()
    private var fraction = 0f

    /** [fraction] from 0 to 1, drawn in [color]. */
    fun set(fraction: Float, color: Int) {
        this.fraction = fraction.coerceIn(0f, 1f)
        fill.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val half = stroke / 2
        oval.set(half, half, width - half, height - half)
        canvas.drawOval(oval, track)
        if (fraction > 0f) canvas.drawArc(oval, -90f, 360f * fraction, false, fill)
    }
}
