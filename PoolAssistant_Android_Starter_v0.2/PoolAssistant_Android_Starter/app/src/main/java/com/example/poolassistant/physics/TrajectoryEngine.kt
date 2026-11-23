package com.example.poolassistant.physics

import com.example.poolassistant.vision.Point2
import kotlin.math.sqrt

data class Segment(val start: Point2, val end: Point2)
data class CushionBounds(val left: Double, val top: Double, val right: Double, val bottom: Double)

/**
 * Геометрическая заготовка для расчёта прямого луча и отражений.
 * Здесь пока нет моделирования вращения, трения и потери энергии.
 */
object TrajectoryEngine {
    fun normalize(v: Point2): Point2 {
        val length = sqrt(v.x * v.x + v.y * v.y)
        require(length > 0.0) { "Direction vector must be non-zero" }
        return Point2(v.x / length, v.y / length)
    }

    fun reflectAtVerticalCushion(direction: Point2): Point2 =
        Point2(-direction.x, direction.y)

    fun reflectAtHorizontalCushion(direction: Point2): Point2 =
        Point2(direction.x, -direction.y)

    fun distance(a: Point2, b: Point2): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
