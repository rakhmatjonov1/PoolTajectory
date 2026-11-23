package com.example.poolassistant.vision

data class Point2(val x: Double, val y: Double)

data class Ball(
    val center: Point2,
    val radius: Double,
    val kind: BallKind,
    val confidence: Double
)

enum class BallKind { CUE, SOLID, STRIPE, EIGHT, UNKNOWN }

data class Pocket(val center: Point2, val radius: Double)

data class TableModel(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val pockets: List<Pocket>
)

data class FrameAnalysis(
    val table: TableModel?,
    val balls: List<Ball>,
    val timestampMs: Long
)
