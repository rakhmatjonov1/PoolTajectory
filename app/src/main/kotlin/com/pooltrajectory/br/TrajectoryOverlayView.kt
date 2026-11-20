package com.pooltrajectory.br

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View

class TrajectoryOverlayView(context: Context) : View(context) {
    @Volatile private var result = AnalysisResult(null, null, emptyList(), null, null, Color.CYAN, "Ожидание")

    @Volatile var overlayEnabled: Boolean = true
    @Volatile var showTableFrame: Boolean = false
    @Volatile var lineThickness: Float = 3.2f

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(125, 0, 0, 0)
    }

    private val guidelinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(245, 255, 255, 255)
    }

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(170, 0, 229, 255)
        strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
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

        // Показываем рамку стола во время калибровки
        if (showTableFrame) {
            data.table?.let {
                canvas.drawRect(
                    it.x.toFloat(), it.y.toFloat(),
                    (it.x + it.width).toFloat(), (it.y + it.height).toFloat(),
                    framePaint
                )
            }
        }

        // Аккуратное кольцо только вокруг найденного белого битка
        data.cueBall?.let { cue ->
            shadowPaint.strokeWidth = 3.5f
            canvas.drawCircle(cue.x, cue.y, cue.radius, shadowPaint)

            guidelinePaint.alpha = 220
            guidelinePaint.strokeWidth = 2.2f
            canvas.drawCircle(cue.x, cue.y, cue.radius, guidelinePaint)
        }

        // 1. Продлённые белые линии траектории битка
        data.trajectory.forEachIndexed { index, segment ->
            val alpha = (245 - index * 40).coerceAtLeast(115)

            shadowPaint.strokeWidth = lineThickness + 2.2f
            canvas.drawLine(segment.x1, segment.y1, segment.x2, segment.y2, shadowPaint)

            guidelinePaint.alpha = alpha
            guidelinePaint.strokeWidth = lineThickness
            canvas.drawLine(segment.x1, segment.y1, segment.x2, segment.y2, guidelinePaint)

            dotPaint.color = Color.argb(alpha, 255, 255, 255)
            canvas.drawCircle(segment.x2, segment.y2, lineThickness * 1.25f, dotPaint)
        }

        // 2. Призрачный шар в точке столкновения (Ghost Ball)
        data.ghost?.let {
            shadowPaint.strokeWidth = lineThickness + 2.0f
            canvas.drawCircle(it.x, it.y, it.radius, shadowPaint)

            guidelinePaint.alpha = 245
            guidelinePaint.strokeWidth = lineThickness * 0.85f
            canvas.drawCircle(it.x, it.y, it.radius, guidelinePaint)

            dotPaint.color = data.targetColor
            canvas.drawCircle(it.x, it.y, lineThickness * 1.4f, dotPaint)
        }

        // 3. Вектор движения прицельного шара (окрашен в цвет цели)
        data.targetVector?.let {
            shadowPaint.strokeWidth = lineThickness + 2.2f
            canvas.drawLine(it.x1, it.y1, it.x2, it.y2, shadowPaint)

            targetLinePaint.color = data.targetColor
            targetLinePaint.strokeWidth = lineThickness + 0.5f
            canvas.drawLine(it.x1, it.y1, it.x2, it.y2, targetLinePaint)

            dotPaint.color = data.targetColor
            canvas.drawCircle(it.x2, it.y2, lineThickness * 1.4f, dotPaint)
        }
    }
}
