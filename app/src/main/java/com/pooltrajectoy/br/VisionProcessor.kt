package com.pooltrajectory.br

import android.graphics.Color
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class BallPoint(
    val x: Float,
    val y: Float,
    val radius: Float,
    val cue: Boolean,
    val colorInt: Int = Color.WHITE
)

data class Segment(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val dashed: Boolean = false
)

data class AnalysisResult(
    val table: Rect?,
    val cueBall: BallPoint?,
    val trajectory: List<Segment>,
    val ghost: BallPoint?,
    val targetVector: Segment?,
    val targetColor: Int,
    val status: String
)

class VisionProcessor {
    @Volatile var maxBounces = 3
    @Volatile var cueForceLevel = 7 // 1..11
    @Volatile var calibratedTableRect: Rect? = null

    /**
     * Комбинированный конвейер:
     * 1. Жёсткая геометрия стола (из калибровки или безопасных пропорций).
     * 2. Вычитание размытого фона (absdiff) — работает на любом цвете стола (синий, зелёный, фиолетовый).
     * 3. Ray-Cast Vision — поиск препятствий только вдоль луча удара без лишних кругов на столе.
     */
    fun analyze(rgbaFull: Mat): AnalysisResult {
        if (rgbaFull.empty() || rgbaFull.cols() < 40 || rgbaFull.rows() < 40) {
            return AnalysisResult(null, null, emptyList(), null, null, Color.CYAN, "Нет кадра")
        }

        val fullW = rgbaFull.cols()
        val fullH = rgbaFull.rows()
        val tableFull = getActiveTableRect(fullW, fullH)

        if (tableFull.width < 100 || tableFull.height < 60) {
            return AnalysisResult(null, null, emptyList(), null, null, Color.CYAN, "Неверные границы стола")
        }

        val scale = 0.5
        val invScale = 1.0 / scale

        val tableSmall = Rect(
            (tableFull.x * scale).toInt().coerceIn(0, (fullW * scale).toInt() - 10),
            (tableFull.y * scale).toInt().coerceIn(0, (fullH * scale).toInt() - 10),
            (tableFull.width * scale).toInt().coerceAtLeast(20),
            (tableFull.height * scale).toInt().coerceAtLeast(20)
        )
        // Защита от выхода за границы кадра
        if (tableSmall.x + tableSmall.width > (fullW * scale).toInt()) {
            tableSmall.width = (fullW * scale).toInt() - tableSmall.x
        }
        if (tableSmall.y + tableSmall.height > (fullH * scale).toInt()) {
            tableSmall.height = (fullH * scale).toInt() - tableSmall.y
        }

        val rgbaSmall = Mat()
        val bgrSmall = Mat()
        var roiBgr: Mat? = null
        val roiHsv = Mat()
        val roiGray = Mat()
        val blurredBg = Mat()
        val diffMap = Mat()
        val foregroundMask = Mat()
        val whiteMask = Mat()

        try {
            Imgproc.resize(rgbaFull, rgbaSmall, Size(), scale, scale, Imgproc.INTER_LINEAR)
            Imgproc.cvtColor(rgbaSmall, bgrSmall, Imgproc.COLOR_RGBA2BGR)

            roiBgr = Mat(bgrSmall, tableSmall)
            Imgproc.cvtColor(roiBgr, roiHsv, Imgproc.COLOR_BGR2HSV)
            Imgproc.cvtColor(roiBgr, roiGray, Imgproc.COLOR_BGR2GRAY)

            val ballRadiusSmall = (tableSmall.width / 38.0).coerceIn(5.0, 24.0)

            // --- ВАРИАНТ 1: Вычитание размытого фона (не зависит от цвета сукна и виньетки) ---
            val blurSize = (ballRadiusSmall * 3.5).toInt().let { if (it % 2 == 0) it + 1 else it }.coerceIn(15, 61)
            Imgproc.GaussianBlur(roiBgr, blurredBg, Size(blurSize.toDouble(), blurSize.toDouble()), 0.0)
            Core.absdiff(roiBgr, blurredBg, diffMap)

            val diffGray = Mat()
            Imgproc.cvtColor(diffMap, diffGray, Imgproc.COLOR_BGR2GRAY)
            // Порог отличия объекта от гладкого фона стола (отсекает водяной знак "8 POOL" и виньетку)
            Imgproc.threshold(diffGray, foregroundMask, 24.0, 255.0, Imgproc.THRESH_BINARY)
            diffGray.release()

            val cleanKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
            Imgproc.morphologyEx(foregroundMask, foregroundMask, Imgproc.MORPH_OPEN, cleanKernel)
            cleanKernel.release()

            // --- Поиск только белого битка ---
            Core.inRange(roiHsv, Scalar(0.0, 0.0, 185.0), Scalar(180.0, 68.0, 255.0), whiteMask)
            Core.bitwise_and(whiteMask, foregroundMask, whiteMask)

            val cueSmall = findBestCueBall(whiteMask, tableSmall.width, tableSmall.height, ballRadiusSmall)
                ?: return AnalysisResult(tableFull, null, emptyList(), null, null, Color.CYAN, "Ищу белый биток...")

            val cueFull = BallPoint(
                x = ((cueSmall.first + tableSmall.x) * invScale).toFloat(),
                y = ((cueSmall.second + tableSmall.y) * invScale).toFloat(),
                radius = (ballRadiusSmall * invScale).toFloat(),
                cue = true,
                colorInt = Color.WHITE
            )

            // --- Поиск линии прицела в локальном окне вокруг битка ---
            val aim = detectAimLineNearCue(roiBgr, cueSmall.first, cueSmall.second, ballRadiusSmall)
                ?: return AnalysisResult(tableFull, cueFull, emptyList(), null, null, Color.CYAN, "Наведите кий на шар")

            // --- ВАРИАНТ 3: Ray-Cast Vision (сканирование препятствий строго вдоль луча) ---
            val rayResult = traceRayCast(
                cueSmallX = cueSmall.first,
                cueSmallY = cueSmall.second,
                dx0 = aim.first,
                dy0 = aim.second,
                tableSmall = tableSmall,
                ballRadiusSmall = ballRadiusSmall,
                foregroundMask = foregroundMask,
                roiHsv = roiHsv,
                invScale = invScale
            )

            val status = if (rayResult.ghost != null) {
                "Цель захвачена | Сила: $cueForceLevel/11"
            } else {
                "Отскоки от бортов | Сила: $cueForceLevel/11"
            }

            return AnalysisResult(
                table = tableFull,
                cueBall = cueFull,
                trajectory = rayResult.segments,
                ghost = rayResult.ghost,
                targetVector = rayResult.targetVector,
                targetColor = rayResult.targetColor,
                status = status
            )
        } catch (_: Exception) {
            return AnalysisResult(tableFull, null, emptyList(), null, null, Color.CYAN, "Ошибка кадра")
        } finally {
            releaseAll(rgbaSmall, bgrSmall, roiBgr, roiHsv, roiGray, blurredBg, diffMap, foregroundMask, whiteMask)
        }
    }

    /**
     * Возвращает откалиброванный прямоугольник стола или стандартные координаты поля в альбомном режиме
     */
    fun getActiveTableRect(screenW: Int, screenH: Int): Rect {
        calibratedTableRect?.let {
            if (it.width > 120 && it.height > 70 && it.x >= 0 && it.y >= 0 &&
                it.x + it.width <= screenW && it.y + it.height <= screenH
            ) {
                return it
            }
        }
        // Безопасная геометрия внутреннего поля стола 8 Ball Pool по умолчанию
        val left = (screenW * 0.172).toInt()
        val top = (screenH * 0.195).toInt()
        val right = (screenW * 0.828).toInt()
        val bottom = (screenH * 0.865).toInt()
        return Rect(left, top, (right - left).coerceAtLeast(100), (bottom - top).coerceAtLeast(60))
    }

    /**
     * Находит центр белого битка по плотности белых пикселей внутри круга радиуса ballR
     */
    private fun findBestCueBall(
        whiteMask: Mat, w: Int, h: Int, ballR: Double
    ): Pair<Double, Double>? {
        val integral = Mat()
        Imgproc.integral(whiteMask, integral, CvType.CV_32S)

        val r = (ballR * 0.85).toInt().coerceAtLeast(3)
        val boxArea = (2 * r + 1) * (2 * r + 1) * 255.0
        val step = max(2, (ballR * 0.35).toInt())

        var bestScore = 0.0
        var bestX = -1.0
        var bestY = -1.0

        val rowTop = IntArray(w + 1)
        val rowBot = IntArray(w + 1)

        for (y in r + 2 until h - r - 2 step step) {
            if (inPocketZone(0.0, y.toDouble(), w.toDouble(), h.toDouble(), ballR)) continue
            integral.get(y - r, 0, rowTop)
            integral.get(y + r + 1, 0, rowBot)

            for (x in r + 2 until w - r - 2 step step) {
                if (inPocketZone(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), ballR)) continue

                val sum = rowBot[x + r + 1] - rowBot[x - r] - rowTop[x + r + 1] + rowTop[x - r]
                val fillRatio = sum / boxArea

                // Белый биток заполняет от 45% до 92% квадрата (отсекаем слишком большие белые блики и мелкий шум)
                if (fillRatio in 0.42..0.92 && fillRatio > bestScore) {
                    bestScore = fillRatio
                    bestX = x.toDouble()
                    bestY = y.toDouble()
                }
            }
        }
        integral.release()

        return if (bestX > 0 && bestY > 0) Pair(bestX, bestY) else null
    }

    private fun detectAimLineNearCue(
        roi: Mat, cueX: Double, cueY: Double, ballR: Double
    ): Pair<Double, Double>? {
        val searchRadius = (ballR * 12.0).toInt().coerceAtLeast(50)
        val x0 = (cueX - searchRadius).toInt().coerceIn(0, roi.cols() - 1)
        val y0 = (cueY - searchRadius).toInt().coerceIn(0, roi.rows() - 1)
        val x1 = (cueX + searchRadius).toInt().coerceIn(x0 + 1, roi.cols())
        val y1 = (cueY + searchRadius).toInt().coerceIn(y0 + 1, roi.rows())

        val localRoi = roi.submat(Rect(x0, y0, x1 - x0, y1 - y0))
        val localCueX = cueX - x0
        val localCueY = cueY - y0

        val gray = Mat()
        val edges = Mat()
        val lines = Mat()
        return try {
            Imgproc.cvtColor(localRoi, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.Canny(gray, edges, 55.0, 145.0)
            Imgproc.HoughLinesP(edges, lines, 1.0, PI / 180.0, 18, ballR * 1.3, ballR * 1.2)

            var best: Pair<Double, Double>? = null
            var bestScore = 0.0
            for (i in 0 until lines.rows()) {
                val v = lines.get(i, 0) ?: continue
                val lx1 = v[0]; val ly1 = v[1]; val lx2 = v[2]; val ly2 = v[3]
                val dx = lx2 - lx1
                val dy = ly2 - ly1
                val length = hypot(dx, dy)
                if (length < ballR * 1.2) continue

                val cross = abs((localCueX - lx1) * dy - (localCueY - ly1) * dx) / max(0.001, length)
                if (cross > ballR * 0.45) continue

                val ux = dx / length
                val uy = dy / length
                val dist1 = hypot(localCueX - lx1, localCueY - ly1)
                val dist2 = hypot(localCueX - lx2, localCueY - ly2)
                if (min(dist1, dist2) > ballR * 4.5) continue

                val score = length - cross * 3.5
                if (score > bestScore) {
                    val sign = if (dist1 > dist2) -1.0 else 1.0
                    best = Pair(ux * sign, uy * sign)
                    bestScore = score
                }
            }
            best
        } catch (_: Exception) {
            null
        } finally {
            localRoi.release()
            releaseAll(gray, edges, lines)
        }
    }

    data class RayCastOutput(
        val segments: List<Segment>,
        val ghost: BallPoint?,
        val targetVector: Segment?,
        val targetColor: Int
    )

    /**
     * Вариант 3: Шагаем вдоль луча прицеливания и проверяем наличие препятствия
     * в радиусе столкновения (2 * ballRadius) только на пути битка.
     */
    private fun traceRayCast(
        cueSmallX: Double,
        cueSmallY: Double,
        dx0: Double,
        dy0: Double,
        tableSmall: Rect,
        ballRadiusSmall: Double,
        foregroundMask: Mat,
        roiHsv: Mat,
        invScale: Double
    ): RayCastOutput {
        val segments = ArrayList<Segment>()
        var x = cueSmallX
        var y = cueSmallY
        var dx = dx0
        var dy = dy0

        val force = cueForceLevel.coerceIn(1, 11)
        var remainingEnergy = tableSmall.width * (0.35 + force * 0.26)
        val effectiveBounces = min(maxBounces, (force + 1) / 3)

        val inset = ballRadiusSmall
        val left = inset
        val right = tableSmall.width - inset
        val top = inset
        val bottom = tableSmall.height - inset

        val stepSize = max(1.5, ballRadiusSmall * 0.25)
        val checkR = (ballRadiusSmall * 0.65).toInt().coerceAtLeast(2)
        val patchArea = (2 * checkR + 1) * (2 * checkR + 1)
        val pixelBuf = ByteArray(1)

        for (bounceCount in 0..effectiveBounces) {
            if (remainingEnergy <= 4.0) break

            var wallT = Double.POSITIVE_INFINITY
            var nx = 0.0
            var ny = 0.0

            if (dx < -1e-8) {
                val t = (left - x) / dx
                if (t > 0.001 && t < wallT) { wallT = t; nx = 1.0; ny = 0.0 }
            } else if (dx > 1e-8) {
                val t = (right - x) / dx
                if (t > 0.001 && t < wallT) { wallT = t; nx = -1.0; ny = 0.0 }
            }

            if (dy < -1e-8) {
                val t = (top - y) / dy
                if (t > 0.001 && t < wallT) { wallT = t; nx = 0.0; ny = 1.0 }
            } else if (dy > 1e-8) {
                val t = (bottom - y) / dy
                if (t > 0.001 && t < wallT) { wallT = t; nx = 0.0; ny = -1.0 }
            }

            if (!wallT.isFinite()) break

            val maxSegmentT = min(wallT, remainingEnergy)
            // Начинаем сканировать после 2.1 радиуса от стартовой точки, чтобы не задеть сам биток
            val startScanT = if (bounceCount == 0) ballRadiusSmall * 2.15 else ballRadiusSmall * 0.5
            var t = startScanT

            while (t < maxSegmentT) {
                val testX = x + dx * t
                val testY = y + dy * t

                // Проверяем, не попадает ли точка в зону лузы
                if (!inPocketZone(testX, testY, tableSmall.width.toDouble(), tableSmall.height.toDouble(), ballRadiusSmall)) {
                    val ix = testX.toInt()
                    val iy = testY.toInt()
                    if (ix in checkR until (foregroundMask.cols() - checkR) &&
                        iy in checkR until (foregroundMask.rows() - checkR)
                    ) {
                        foregroundMask.get(iy, ix, pixelBuf)
                        if (pixelBuf[0] != 0.toByte()) {
                            // Проверяем плотность объекта в окрестности, чтобы отсечь тонкую белую линию прицела игры
                            val sub = foregroundMask.submat(Rect(ix - checkR, iy - checkR, 2 * checkR + 1, 2 * checkR + 1))
                            val density = Core.countNonZero(sub).toDouble() / patchArea
                            sub.release()

                            if (density > 0.52) {
                                // Нашли реальный прицельный шар на пути луча!
                                // Откатываем центр призрачного шара на 1.85 радиуса назад от точки касания края шара
                                val ghostSmallX = (testX - dx * (ballRadiusSmall * 0.95))
                                val ghostSmallY = (testY - dy * (ballRadiusSmall * 0.95))
                                val targetSmallX = (testX + dx * (ballRadiusSmall * 0.95))
                                val targetSmallY = (testY + dy * (ballRadiusSmall * 0.95))

                                val fullStartX = ((x + tableSmall.x) * invScale).toFloat()
                                val fullStartY = ((y + tableSmall.y) * invScale).toFloat()
                                val fullGhostX = ((ghostSmallX + tableSmall.x) * invScale).toFloat()
                                val fullGhostY = ((ghostSmallY + tableSmall.y) * invScale).toFloat()
                                val fullTargetX = ((targetSmallX + tableSmall.x) * invScale).toFloat()
                                val fullTargetY = ((targetSmallY + tableSmall.y) * invScale).toFloat()
                                val fullR = (ballRadiusSmall * invScale).toFloat()

                                segments.add(Segment(fullStartX, fullStartY, fullGhostX, fullGhostY, bounceCount > 0))

                                val targetColor = sampleTargetBallColor(roiHsv, targetSmallX.toInt(), targetSmallY.toInt(), ballRadiusSmall)
                                val energyAfterHit = (remainingEnergy - t).coerceAtLeast(ballRadiusSmall * 3.0)
                                val targetTravelFull = (energyAfterHit * 0.85 * invScale).coerceIn(fullR * 3.0, tableSmall.width * invScale * 0.95)

                                val ghost = BallPoint(fullGhostX, fullGhostY, fullR, true, Color.WHITE)
                                val vector = Segment(
                                    fullTargetX,
                                    fullTargetY,
                                    (fullTargetX + dx * targetTravelFull).toFloat(),
                                    (fullTargetY + dy * targetTravelFull).toFloat()
                                )
                                return RayCastOutput(segments, ghost, vector, targetColor)
                            }
                        }
                    }
                }
                t += stepSize
            }

            // Препятствий на отрезке нет — доводим луч до борта или конца энергии
            val endX = x + dx * maxSegmentT
            val endY = y + dy * maxSegmentT

            segments.add(
                Segment(
                    ((x + tableSmall.x) * invScale).toFloat(),
                    ((y + tableSmall.y) * invScale).toFloat(),
                    ((endX + tableSmall.x) * invScale).toFloat(),
                    ((endY + tableSmall.y) * invScale).toFloat(),
                    bounceCount > 0
                )
            )

            if (wallT > remainingEnergy || bounceCount >= effectiveBounces) break

            remainingEnergy = (remainingEnergy - wallT) * 0.78
            val dot = dx * nx + dy * ny
            dx -= 2.0 * dot * nx
            dy -= 2.0 * dot * ny
            val length = hypot(dx, dy).coerceAtLeast(0.001)
            dx /= length
            dy /= length
            x = endX + dx * 0.5
            y = endY + dy * 0.5
        }

        return RayCastOutput(segments, null, null, Color.parseColor("#00E5FF"))
    }

    /**
     * Определяет цвет найденного прицельного шара в точке попадания луча
     */
    private fun sampleTargetBallColor(roiHsv: Mat, cx: Int, cy: Int, ballR: Double): Int {
        val sampleR = max(2, (ballR * 0.45).toInt())
        val x0 = (cx - sampleR).coerceIn(0, roiHsv.cols() - 1)
        val y0 = (cy - sampleR).coerceIn(0, roiHsv.rows() - 1)
        val x1 = (cx + sampleR + 1).coerceIn(x0 + 1, roiHsv.cols())
        val y1 = (cy + sampleR + 1).coerceIn(y0 + 1, roiHsv.rows())

        val patch = roiHsv.submat(Rect(x0, y0, x1 - x0, y1 - y0))
        val mean = Core.mean(patch)
        patch.release()

        val h = mean.`val`[0]
        val s = mean.`val`[1]
        val v = mean.`val`[2]

        if (v < 75.0) return Color.parseColor("#B0BEC5") // 8-й шар (подсвечиваем серебристо-белым для контраста)
        if (s < 70.0) return Color.parseColor("#00E5FF")

        return when {
            h in 18.0..34.0 -> Color.parseColor("#FFD600")      // Жёлтый (1/9)
            h in 10.0..17.9 -> Color.parseColor("#FF6D00")      // Оранжевый (5/13)
            h < 10.0 || h > 166.0 -> Color.parseColor("#FF1744") // Красный/Бордовый (3/11, 7/15)
            h in 38.0..86.0 -> Color.parseColor("#00E676")      // Зелёный (6/14)
            h in 98.0..126.0 -> Color.parseColor("#2979FF")     // Синий (2/10)
            h in 126.1..165.9 -> Color.parseColor("#D500F9")    // Фиолетовый (4/12)
            else -> Color.parseColor("#00E5FF")
        }
    }

    private fun inPocketZone(x: Double, y: Double, w: Double, h: Double, ballR: Double): Boolean {
        val pockets = listOf(
            Pair(0.0, 0.0), Pair(w, 0.0), Pair(0.0, h), Pair(w, h),
            Pair(w / 2.0, 0.0), Pair(w / 2.0, h)
        )
        return pockets.any { hypot(x - it.first, y - it.second) < ballR * 1.9 }
    }

    /**
     * Упрощённый физический расчёт для ручного режима (маркеры без OpenCV)
     */
    fun traceManual(
        startX: Double, startY: Double,
        dx0: Double, dy0: Double,
        table: Rect,
        ballRadius: Double
    ): List<Segment> {
        val segments = ArrayList<Segment>()
        var x = startX
        var y = startY
        var dx = dx0
        var dy = dy0

        val force = cueForceLevel.coerceIn(1, 11)
        var remainingEnergy = table.width * (0.35 + force * 0.26)
        val effectiveBounces = min(maxBounces, (force + 1) / 3)

        for (bounceCount in 0..effectiveBounces) {
            if (remainingEnergy <= 5.0) break

            val inset = ballRadius
            val left = table.x + inset
            val right = table.x + table.width - inset
            val top = table.y + inset
            val bottom = table.y + table.height - inset

            var wallT = Double.POSITIVE_INFINITY
            var nx = 0.0
            var ny = 0.0

            if (dx < -1e-8) {
                val t = (left - x) / dx
                if (t > 0.001 && t < wallT) { wallT = t; nx = 1.0; ny = 0.0 }
            } else if (dx > 1e-8) {
                val t = (right - x) / dx
                if (t > 0.001 && t < wallT) { wallT = t; nx = -1.0; ny = 0.0 }
            }

            if (dy < -1e-8) {
                val t = (top - y) / dy
                if (t > 0.001 && t < wallT) { wallT = t; nx = 0.0; ny = 1.0 }
            } else if (dy > 1e-8) {
                val t = (bottom - y) / dy
                if (t > 0.001 && t < wallT) { wallT = t; nx = 0.0; ny = -1.0 }
            }

            if (!wallT.isFinite()) break

            if (wallT > remainingEnergy) {
                segments.add(
                    Segment(
                        x.toFloat(), y.toFloat(),
                        (x + dx * remainingEnergy).toFloat(),
                        (y + dy * remainingEnergy).toFloat(),
                        bounceCount > 0
                    )
                )
                break
            }

            val hx = x + dx * wallT
            val hy = y + dy * wallT
            segments.add(Segment(x.toFloat(), y.toFloat(), hx.toFloat(), hy.toFloat(), bounceCount > 0))

            remainingEnergy = (remainingEnergy - wallT) * 0.78
            if (bounceCount >= effectiveBounces) break

            val dot = dx * nx + dy * ny
            dx -= 2.0 * dot * nx
            dy -= 2.0 * dot * ny
            val length = hypot(dx, dy).coerceAtLeast(0.001)
            dx /= length
            dy /= length
            x = hx + dx * 0.5
            y = hy + dy * 0.5
        }

        return segments
    }

    private fun releaseAll(vararg mats: Mat?) {
        mats.forEach {
            try {
                it?.release()
            } catch (_: Exception) {
            }
        }
    }
}
