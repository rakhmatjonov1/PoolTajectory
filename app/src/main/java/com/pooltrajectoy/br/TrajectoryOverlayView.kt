package com.pooltrajectory.br

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

class TrajectoryOverlayView(context: Context) : View(context) {
    @Volatile private var result = AnalysisResult(null, emptyList(), emptyList(), null, null, "Ожидание")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeCap = Paint.Cap.ROUND
    }

    fun setAnalysis(value: AnalysisResult) {
        result = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = result
        data.table?.let {
            paint.color = Color.argb(190, 40, 220, 120)
            paint.strokeWidth = 3f
            canvas.drawRect(it.x.toFloat(), it.y.toFloat(), (it.x + it.width).toFloat(), (it.y + it.height).toFloat(), paint)
        }
        data.balls.forEach { ball ->
            paint.color = if (ball.cue) Color.WHITE else Color.YELLOW
            paint.strokeWidth = 3f
            canvas.drawCircle(ball.x, ball.y, ball.radius, paint)
        }
        data.trajectory.forEach { segment ->
            paint.color = Color.CYAN
            paint.strokeWidth = 4f
            if (segment.dashed) {
                paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 9f), 0f)
            } else {
                paint.pathEffect = null
            }
            canvas.drawLine(segment.x1, segment.y1, segment.x2, segment.y2, paint)
        }
        paint.pathEffect = null
        data.ghost?.let {
            paint.color = Color.WHITE
            paint.strokeWidth = 2.5f
            canvas.drawCircle(it.x, it.y, it.radius, paint)
        }
        data.targetVector?.let {
            paint.color = Color.rgb(255, 90, 40)
            paint.strokeWidth = 4f
            canvas.drawLine(it.x1, it.y1, it.x2, it.y2, paint)
            val angle = kotlin.math.atan2((it.y2 - it.y1).toDouble(), (it.x2 - it.x1).toDouble())
            val size = 16f
            val path = Path().apply {
                moveTo(it.x2, it.y2)
                lineTo(
                    it.x2 - size * kotlin.math.cos(angle - 0.55).toFloat(),
                    it.y2 - size * kotlin.math.sin(angle - 0.55).toFloat()
                )
                moveTo(it.x2, it.y2)
                lineTo(
                    it.x2 - size * kotlin.math.cos(angle + 0.55).toFloat(),
                    it.y2 - size * kotlin.math.sin(angle + 0.55).toFloat()
                )
            }
            canvas.drawPath(path, paint)
        }
    }
}
