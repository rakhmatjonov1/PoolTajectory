package com.pooltrajectory.br

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class TrajectoryOverlayView(context: Context) : View(context) {
    @Volatile private var result = AnalysisResult(null, emptyList(), emptyList(), null, null, Color.CYAN, "Ожидание")

    @Volatile var overlayEnabled: Boolean = true
    @Volatile var showBallMarkers: Boolean = true
    @Volatile var lineThickness: Float = 3.2f

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(120, 0, 0, 0)
    }

    private val guidelinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(240, 255, 255, 255)
    }

    private val ballRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val targetLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
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

        // 1. Цветовая индикация всех распознанных шаров на столе
        if (showBallMarkers) {
            data.balls.forEach { ball ->
                shadowPaint.strokeWidth = 4.0f
                canvas.drawCircle(ball.x, ball.y, ball.radius, shadowPaint)

                ballRingPaint.color = ball.type.colorInt
                ballRingPaint.strokeWidth = if (ball.cue) 3.0f else 2.2f
                canvas.drawCircle(ball.x, ball.y, ball.radius, ballRingPaint)
            }
        }

        // 2. Продлённые белые линии траектории битка с учётом силы кия (1–11)
        data.trajectory.forEachIndexed { index, segment ->
            val alpha = (245 - index * 40).coerceAtLeast(110)

            shadowPaint.strokeWidth = lineThickness + 2.2f
            canvas.drawLine(segment.x1, segment.y1, segment.x2, segment.y2, shadowPaint)

            guidelinePaint.alpha = alpha
            guidelinePaint.strokeWidth = lineThickness
            canvas.drawLine(segment.x1, segment.y1, segment.x2, segment.y2, guidelinePaint)

            // Точка в месте касания борта или остановки шара
            dotPaint.color = Color.argb(alpha, 255, 255, 255)
            canvas.drawCircle(segment.x2, segment.y2, lineThickness * 1.3f, dotPaint)
        }

        // 3. Призрачный шар в точке контакта (Ghost Ball)
        data.ghost?.let {
            shadowPaint.strokeWidth = lineThickness + 2.0f
            canvas.drawCircle(it.x, it.y, it.radius, shadowPaint)

            guidelinePaint.alpha = 245
            guidelinePaint.strokeWidth = lineThickness * 0.85f
            canvas.drawCircle(it.x, it.y, it.radius, guidelinePaint)

            dotPaint.color = data.targetColor
            canvas.drawCircle(it.x, it.y, lineThickness * 1.4f, dotPaint)
        }

        // 4. Траектория движения прицельного шара (окрашена в цвет этого шара)
        data.targetVector?.let {
            shadowPaint.strokeWidth = lineThickness + 2.2f
            canvas.drawLine(it.x1, it.y1, it.x2, it.y2, shadowPaint)

            targetLinePaint.color = data.targetColor
            targetLinePaint.strokeWidth = lineThickness + 0.4f
            canvas.drawLine(it.x1, it.y1, it.x2, it.y2, targetLinePaint)

            dotPaint.color = data.targetColor
            canvas.drawCircle(it.x2, it.y2, lineThickness * 1.4f, dotPaint)
        }
    }
}
