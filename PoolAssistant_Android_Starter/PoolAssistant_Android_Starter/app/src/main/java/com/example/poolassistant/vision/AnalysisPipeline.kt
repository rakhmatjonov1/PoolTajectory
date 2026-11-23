package com.example.poolassistant.vision

class AnalysisPipeline(private val ballDetector: BallDetector) {
    fun analyze(frame: Any, table: TableModel, timestampMs: Long): FrameAnalysis {
        val balls = ballDetector.detect(frame, table)
        return FrameAnalysis(table, balls, timestampMs)
    }
}
