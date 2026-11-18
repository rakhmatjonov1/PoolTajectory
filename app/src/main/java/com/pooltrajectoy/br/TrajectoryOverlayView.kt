package com.pooltrajectory.br

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class TrajectoryOverlayView(context: Context) : View(context) {
    @Volatile private var result = AnalysisResult(null, emptyList(), emptyList(), null, null, "Ожидание")

    @Volatile var overlayEnabled: Boolean = true
    @Volatile var showTableAndBalls: Boolean = true
    @Volatile var lineThickness: Float = 4.0f

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dashEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)

    fun setAnalysis(value: AnalysisResult) {
        result = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!overlayEnabled) return

        val data = result

        // 1. Рамка стола и шаров
        if (showTableAndBalls) {
            data.table?.let {
                corePaint.pathEffect = null
                corePaint.color = Color.argb(120, 0, 230, 118)
                corePaint.strokeWidth = 2.5f
                canvas.drawRoundRect(
                    it.x.toFloat(), it.y.toFloat(),
                    (it.x + it.width).toFloat(), (it.y + it.height).toFloat(),
                    14f, 14f, corePaint
                )
            }

            data.balls.forEach { ball ->
                corePaint.pathEffect = null
                corePaint.color = if (ball.cue) Color.argb(230, 255, 255, 255) else Color.argb(185, 255, 214, 0)
                corePaint.strokeWidth = 2.5f
                canvas.drawCircle(ball.x, ball.y, ball.radius, corePaint)
            }
        }

        // 2. Линии траектории
        data.trajectory.forEach { segment ->
            val effect = if (segment.dashed) dashEffect else null

            glowPaint.pathEffect = effect
            glowPaint.color = Color.argb(80, 0, 229, 255)
            glowPaint.strokeWidth = lineThickness * 2.6f
            canvas.drawLine(segment.x1, segment.y1, segment.x2, segment.y2, glowPaint)

            corePaint.pathEffect = effect
            corePaint.color = Color.parseColor("#00E5FF")
            corePaint.strokeWidth = lineThickness
            canvas.drawLine(segment.x1, segment.y1, segment.x2, segment.y2, corePaint)
        }

        glowPaint.pathEffect = null
        corePaint.pathEffect = null

        // 3. Призрачный шар (точка контакта)
        data.ghost?.let {
            fillPaint.color = Color.argb(50, 255, 255, 255)
            canvas.drawCircle(it.x, it.y, it.radius, fillPaint)

            corePaint.color = Color.WHITE
            corePaint.strokeWidth = 2.5f
            canvas.drawCircle(it.x, it.y, it.radius, corePaint)

            fillPaint.color = Color.parseColor("#00E5FF")
            canvas.drawCircle(it.x, it.y, 4.5f, fillPaint)
        }

        // 4. Вектор движения прицельного шара
        data.targetVector?.let {
            glowPaint.color = Color.argb(85, 255, 61, 0)
            glowPaint.strokeWidth = lineThickness * 2.5f
            canvas.drawLine(it.x1, it.y1, it.x2, it.y2, glowPaint)

            corePaint.color = Color.parseColor("#FF5252")
            corePaint.strokeWidth = lineThickness + 0.5f
            canvas.drawLine(it.x1, it.y1, it.x2, it.y2, corePaint)

            val angle = atan2((it.y2 - it.y1).toDouble(), (it.x2 - it.x1).toDouble())
            val size = 16f + lineThickness
            val path = Path().apply {
                moveTo(it.x2, it.y2)
                lineTo(
                    it.x2 - size * cos(angle - 0.50).toFloat(),
                    it.y2 - size * sin(angle - 0.50).toFloat()
                )
                moveTo(it.x2, it.y2)
                lineTo(
                    it.x2 - size * cos(angle + 0.50).toFloat(),
                    it.y2 - size * sin(angle + 0.50).toFloat()
                )
            }
            canvas.drawPath(path, corePaint)
        }
    }
}
