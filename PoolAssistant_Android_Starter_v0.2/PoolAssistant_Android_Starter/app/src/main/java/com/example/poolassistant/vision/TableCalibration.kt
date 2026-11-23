package com.example.poolassistant.vision

/**
 * Reference calibration measured from the supplied 1536x691 screenshot.
 * Values are approximate and must be recalibrated for each device/layout.
 */
object TableCalibration {
    const val REFERENCE_WIDTH = 1536.0
    const val REFERENCE_HEIGHT = 691.0

    // Inner playable cloth bounds, excluding cushions.
    const val LEFT = 300.0
    const val TOP = 164.0
    const val RIGHT = 1235.0
    const val BOTTOM = 630.0

    val cueBallApprox = Point2(536.0, 397.0)

    val pocketCenters = listOf(
        Point2(286.0, 153.0),   // top-left
        Point2(768.0, 153.0),   // top-middle
        Point2(1250.0, 153.0),  // top-right
        Point2(286.0, 645.0),   // bottom-left
        Point2(768.0, 645.0),   // bottom-middle
        Point2(1250.0, 645.0)   // bottom-right
    )

    fun toTableCoordinates(screen: Point2): Point2 {
        val width = RIGHT - LEFT
        val height = BOTTOM - TOP
        return Point2(
            (screen.x - LEFT) / width,
            (screen.y - TOP) / height
        )
    }

    fun fromReferenceToCurrent(
        point: Point2,
        currentWidth: Double,
        currentHeight: Double
    ): Point2 = Point2(
        point.x * currentWidth / REFERENCE_WIDTH,
        point.y * currentHeight / REFERENCE_HEIGHT
    )
}
