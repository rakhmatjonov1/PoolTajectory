package com.example.poolassistant.vision

/**
 * Интерфейс распознавания. Реализация OpenCV добавляется после выбора
 * тестовых кадров и настройки масштаба/освещения.
 */
interface BallDetector {
    fun detect(frame: Any, table: TableModel): List<Ball>
}
