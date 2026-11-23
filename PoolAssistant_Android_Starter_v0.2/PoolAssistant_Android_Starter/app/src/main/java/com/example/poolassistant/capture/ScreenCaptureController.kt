package com.example.poolassistant.capture

/**
 * Здесь будет реализация захвата через Android MediaProjection.
 * Захват требует явного системного разрешения пользователя.
 *
 * Следующий этап: ActivityResult API -> MediaProjection -> VirtualDisplay
 * -> ImageReader -> преобразование кадра для vision pipeline.
 */
interface ScreenCaptureController {
    fun start()
    fun stop()
}
