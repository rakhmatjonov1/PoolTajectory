package com.pooltrajectory.br

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class TrajectoryOverlayView(context: Context) : View(context) {
    @Volatile private var result = AnalysisResult(null, emptyList(), emptyList(), null, null, "Ожидание")

    @Volatile var overlayEnabled: Boolean = true
    @Volatile var showDebugContours: Boolean = false
    @Volatile var lineThickness: Float = 3.2f

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(115, 0, 0, 0)
    }

    private val guidelinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(235, 255, 255, 255)
    }

    private val targetLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#00E5FF")
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setAnalysis(value: AnalysisResult) {
        result = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!overlayEnabled) return

        val data = result

        // Отладочные контуры стола и шаров (выключены по умолчанию для чистого вида)
        if (showDebugContours) {
            data.table?.let {
                targetLinePaint.color = Color.argb(120, 0, 230, 118)
                targetLinePaint.strokeWidth = 2f
                canvas.drawRect(
                    it.x.toFloat(), it.y.toFloat(),
                    (it.x + it.width).toFloat(), (it.y + it.height).toFloat(),
                    targetLinePaint
                )
            }
            data.balls.forEach { ball ->
                targetLinePaint.color = if (ball.cue) Color.WHITE else Color.YELLOW
                targetLinePaint.strokeWidth = 2f
                canvas.drawCircle(ball.x, ball.y, ball.radius, targetLinePaint)
            }
        }

        // 1. Продлённые белые линии траектории битка и отскоки от бортов
        data.trajectory.forEachIndexed { index, segment ->
            val alpha = (240 - index * 45).coerceAtLeast(105)

            shadowPaint.strokeWidth = lineThickness + 2.2f
            canvas.drawLine(segment.x1, segment.y1, segment.x2, segment.y2, shadowPaint)

            guidelinePaint.alpha = alpha
            guidelinePaint.strokeWidth = lineThickness
            canvas.drawLine(segment.x1, segment.y1, segment.x2, segment.y2, guidelinePaint)

            // Точка касания борта на изломе луча
            if (index < data.trajectory.size - 1) {
                dotPaint.color = Color.argb(alpha, 255, 255, 255)
                canvas.drawCircle(segment.x2, segment.y2, lineThickness * 1.4f, dotPaint)
            }
        }

        // 2. Призрачный шар в точке контакта (Ghost Ball)
        data.ghost?.let {
            shadowPaint.strokeWidth = lineThickness + 2.0f
            canvas.drawCircle(it.x, it.y, it.radius, shadowPaint)

            guidelinePaint.alpha = 240
            guidelinePaint.strokeWidth = lineThickness * 0.85f
            canvas.drawCircle(it.x, it.y, it.radius, guidelinePaint)

            dotPaint.color = Color.parseColor("#00E5FF")
            canvas.drawCircle(it.x, it.y, lineThickness * 1.3f, dotPaint)
        }

        // 3. Продлённая линия движения прицельного шара
        data.targetVector?.let {
            shadowPaint.strokeWidth = lineThickness + 2.2f
            canvas.drawLine(it.x1, it.y1, it.x2, it.y2, shadowPaint)

            targetLinePaint.color = Color.parseColor("#00E5FF")
            targetLinePaint.strokeWidth = lineThickness
            canvas.drawLine(it.x1, it.y1, it.x2, it.y2, targetLinePaint)

            dotPaint.color = Color.parseColor("#00E5FF")
            canvas.drawCircle(it.x2, it.y2, lineThickness * 1.3f, dotPaint)
        }
    }
}
