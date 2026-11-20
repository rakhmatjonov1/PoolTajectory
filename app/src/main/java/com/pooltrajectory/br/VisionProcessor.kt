package com.pooltrajectory.br

import android.graphics.Color
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
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

    // Последняя подтверждённая позиция используется только как небольшой бонус
    // при выборе между несколькими кандидатами, но не подменяет новое обнаружение.
    private var lastCueSmallX: Double = -1.0
    private var lastCueSmallY: Double = -1.0

    fun analyze(rgbaFull: Mat, renderedOverlay: AnalysisResult? = null): AnalysisResult {
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
        val blurredBg = Mat()
        val diffMap = Mat()
        val foregroundMask = Mat()
        val whiteMask = Mat()
        val cueCoreMask = Mat()

        try {
            Imgproc.resize(rgbaFull, rgbaSmall, Size(), scale, scale, Imgproc.INTER_LINEAR)
            Imgproc.cvtColor(rgbaSmall, bgrSmall, Imgproc.COLOR_RGBA2BGR)

            roiBgr = Mat(bgrSmall, tableSmall)
            Imgproc.cvtColor(roiBgr, roiHsv, Imgproc.COLOR_BGR2HSV)

            val ballRadiusSmall = (tableSmall.width / 38.0).coerceIn(5.0, 24.0)

            // 1. Вычитание размытого фона (не зависит от цвета сукна)
            val blurSize = (ballRadiusSmall * 3.5).toInt().let { if (it % 2 == 0) it + 1 else it }.coerceIn(15, 61)
            Imgproc.GaussianBlur(roiBgr, blurredBg, Size(blurSize.toDouble(), blurSize.toDouble()), 0.0)
            Core.absdiff(roiBgr, blurredBg, diffMap)

            val diffGray = Mat()
            Imgproc.cvtColor(diffMap, diffGray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.threshold(diffGray, foregroundMask, 22.0, 255.0, Imgproc.THRESH_BINARY)
            diffGray.release()

            val cleanKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
            try {
                Imgproc.morphologyEx(foregroundMask, foregroundMask, Imgproc.MORPH_OPEN, cleanKernel)
            } finally {
                cleanKernel.release()
            }

            // MediaProjection на некоторых прошивках включает собственное окно HUD
            // в захватываемый кадр. Удаляем из рабочих масок только уже нарисованные
            // линии/кольца предыдущего кадра, чтобы они не становились ложными целями.
            if (renderedOverlay != null) {
                suppressRenderedOverlay(foregroundMask, renderedOverlay, tableSmall, ballRadiusSmall)
            }

            // 2. Выделяем белые области (биток + прицельная линия + белые полоски шаров)
            Core.inRange(roiHsv, Scalar(0.0, 0.0, 160.0), Scalar(180.0, 90.0, 255.0), whiteMask)
            if (renderedOverlay != null) {
                suppressRenderedOverlay(whiteMask, renderedOverlay, tableSmall, ballRadiusSmall)
            }

            // 3. Удаляем тонкие линии небольшим эллиптическим ядром. Слишком большое
            // ядро (как в прежней версии) разрушало сам белый шар и оставляло лишь блики.
            val coreDiameter = (ballRadiusSmall * 0.65).toInt()
                .coerceAtLeast(3).let { if (it % 2 == 0) it + 1 else it }
            val coreKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE,
                Size(coreDiameter.toDouble(), coreDiameter.toDouble())
            )
            try {
                Imgproc.morphologyEx(whiteMask, cueCoreMask, Imgproc.MORPH_OPEN, coreKernel)
            } finally {
                coreKernel.release()
            }

            val detectedCue = findBestCueBall(
                cueCoreMask, roiHsv, tableSmall.width, tableSmall.height, ballRadiusSmall
            )

            // Не рисуем траекторию по старым координатам: устаревшая точка битка
            // визуально выглядит как настоящее обнаружение и уводит все расчёты.
            val cueSmall: Pair<Double, Double>? = detectedCue
            if (cueSmall != null) {
                lastCueSmallX = cueSmall.first
                lastCueSmallY = cueSmall.second
            } else {
                lastCueSmallX = -1.0
                lastCueSmallY = -1.0
            }

            if (cueSmall == null) {
                return AnalysisResult(tableFull, null, emptyList(), null, null, Color.CYAN, "Ищу белый биток...")
            }

            val cueFull = BallPoint(
                x = ((cueSmall.first + tableSmall.x) * invScale).toFloat(),
                y = ((cueSmall.second + tableSmall.y) * invScale).toFloat(),
                radius = (ballRadiusSmall * invScale).toFloat(),
                cue = true,
                colorInt = Color.WHITE
            )

            // 4. Ищем направление белой линии прицела вокруг битка
            val aim = detectAimLineFromWhiteMask(whiteMask, roiBgr, cueSmall.first, cueSmall.second, ballRadiusSmall)
                ?: return AnalysisResult(tableFull, cueFull, emptyList(), null, null, Color.CYAN, "Биток найден | Наведите кий")

            // 5. Ray-Cast сканирование препятствий строго вдоль луча
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
                "Отскоки | Сила: $cueForceLevel/11"
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
        } catch (e: Exception) {
            Log.e("PoolTrajectoryCV", "Ошибка анализа кадра", e)
            return AnalysisResult(tableFull, null, emptyList(), null, null, Color.CYAN, "Ошибка анализа кадра")
        } finally {
            releaseAll(rgbaSmall, bgrSmall, roiBgr, roiHsv, blurredBg, diffMap, foregroundMask, whiteMask, cueCoreMask)
        }
    }

    private fun suppressRenderedOverlay(
        mask: Mat,
        rendered: AnalysisResult,
        tableSmall: Rect,
        ballRadiusSmall: Double
    ) {
        val overlayMask = Mat.zeros(mask.rows(), mask.cols(), CvType.CV_8UC1)
        val inverseMask = Mat()
        val width = max(3, (ballRadiusSmall * 0.22).toInt())

        fun localPoint(x: Float, y: Float): org.opencv.core.Point = org.opencv.core.Point(
            x * 0.5 - tableSmall.x,
            y * 0.5 - tableSmall.y
        )

        try {
            rendered.trajectory.forEach { segment ->
                Imgproc.line(
                    overlayMask, localPoint(segment.x1, segment.y1), localPoint(segment.x2, segment.y2),
                    Scalar(255.0), width
                )
                Imgproc.circle(overlayMask, localPoint(segment.x2, segment.y2), width, Scalar(255.0), -1)
            }
            rendered.targetVector?.let { vector ->
                Imgproc.line(
                    overlayMask, localPoint(vector.x1, vector.y1), localPoint(vector.x2, vector.y2),
                    Scalar(255.0), width
                )
                Imgproc.circle(overlayMask, localPoint(vector.x2, vector.y2), width, Scalar(255.0), -1)
            }
            rendered.cueBall?.let { cue ->
                Imgproc.circle(
                    overlayMask, localPoint(cue.x, cue.y), (cue.radius * 0.5).toInt().coerceAtLeast(2),
                    Scalar(255.0), width
                )
            }
            rendered.ghost?.let { ghost ->
                Imgproc.circle(
                    overlayMask, localPoint(ghost.x, ghost.y), (ghost.radius * 0.5).toInt().coerceAtLeast(2),
                    Scalar(255.0), width
                )
            }
            Core.bitwise_not(overlayMask, inverseMask)
            Core.bitwise_and(mask, inverseMask, mask)
        } finally {
            overlayMask.release()
            inverseMask.release()
        }
    }

    fun getActiveTableRect(screenW: Int, screenH: Int): Rect {
        calibratedTableRect?.let {
            if (it.width > 120 && it.height > 70 && it.x >= 0 && it.y >= 0 &&
                it.x + it.width <= screenW && it.y + it.height <= screenH
            ) {
                return it
            }
        }
        val left = (screenW * 0.172).toInt()
        val top = (screenH * 0.195).toInt()
        val right = (screenW * 0.828).toInt()
        val bottom = (screenH * 0.865).toInt()
        return Rect(left, top, (right - left).coerceAtLeast(100), (bottom - top).coerceAtLeast(60))
    }

    /**
     * Ищет компактную почти круглую белую область.
     * Контуры устойчивее поиска максимальной суммы в скользящем квадрате:
     * длинная прицельная линия и белая полоса полосатого шара обычно не проходят
     * фильтры круглости, пропорций и ожидаемого размера.
     */
    private fun findBestCueBall(
        cueCoreMask: Mat,
        roiHsv: Mat,
        w: Int,
        h: Int,
        ballR: Double
    ): Pair<Double, Double>? {
        val working = cueCoreMask.clone()
        val hierarchy = Mat()
        val contours = ArrayList<org.opencv.core.MatOfPoint>()
        var bestScore = 0.0
        var bestX = -1.0
        var bestY = -1.0

        try {
            Imgproc.findContours(
                working, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )

            val expectedArea = Math.PI * ballR * ballR * 0.80
            val minArea = Math.PI * ballR * ballR * 0.18
            val maxArea = Math.PI * ballR * ballR * 1.45

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < minArea || area > maxArea) continue

                val bounds = Imgproc.boundingRect(contour)
                val shortSide = min(bounds.width, bounds.height).toDouble()
                val longSide = max(bounds.width, bounds.height).toDouble()
                if (shortSide < ballR * 0.65 || longSide > ballR * 2.40) continue

                val aspect = shortSide / longSide.coerceAtLeast(1.0)
                if (aspect < 0.66) continue

                val contour2f = org.opencv.core.MatOfPoint2f(*contour.toArray())
                val perimeter = try {
                    Imgproc.arcLength(contour2f, true)
                } finally {
                    contour2f.release()
                }
                if (perimeter <= 0.0) continue
                val circularity = (4.0 * Math.PI * area / (perimeter * perimeter)).coerceIn(0.0, 1.0)
                if (circularity < 0.42) continue

                val moments = Imgproc.moments(contour)
                val m00 = moments.get_m00()
                if (kotlin.math.abs(m00) < 1e-6) continue
                val cx = moments.get_m10() / m00
                val cy = moments.get_m01() / m00
                if (cx < 1.0 || cy < 1.0 || cx >= w - 1.0 || cy >= h - 1.0) continue
                if (inPocketZone(cx, cy, w.toDouble(), h.toDouble(), ballR)) continue

                // Проверяем, что внутри предполагаемого шара действительно светлая
                // малонасыщенная область, а не случайный белый элемент интерфейса.
                val sampleR = (ballR * 0.42).toInt().coerceAtLeast(2)
                val x0 = (cx.toInt() - sampleR).coerceIn(0, roiHsv.cols() - 1)
                val y0 = (cy.toInt() - sampleR).coerceIn(0, roiHsv.rows() - 1)
                val x1 = (cx.toInt() + sampleR + 1).coerceIn(x0 + 1, roiHsv.cols())
                val y1 = (cy.toInt() + sampleR + 1).coerceIn(y0 + 1, roiHsv.rows())
                val hsvPatch = roiHsv.submat(Rect(x0, y0, x1 - x0, y1 - y0))
                val maskPatch = cueCoreMask.submat(Rect(x0, y0, x1 - x0, y1 - y0))
                val meanHsv = try {
                    Core.mean(hsvPatch, maskPatch)
                } finally {
                    hsvPatch.release()
                    maskPatch.release()
                }
                val meanSaturation = meanHsv.`val`[1]
                val meanValue = meanHsv.`val`[2]
                if (meanValue < 150.0 || meanSaturation > 95.0) continue

                val areaFit = (1.0 - kotlin.math.abs(area - expectedArea) / (expectedArea * 1.6))
                    .coerceIn(0.0, 1.0)
                val valueScore = ((meanValue - 150.0) / 105.0).coerceIn(0.0, 1.0)
                val saturationScore = (1.0 - meanSaturation / 110.0).coerceIn(0.0, 1.0)
                val trackingBonus = if (lastCueSmallX >= 0.0 && lastCueSmallY >= 0.0) {
                    val distance = hypot(cx - lastCueSmallX, cy - lastCueSmallY)
                    if (distance < ballR * 3.0) 0.10 * (1.0 - distance / (ballR * 3.0)) else 0.0
                } else 0.0

                val score = circularity * 0.34 + aspect * 0.18 + areaFit * 0.18 +
                    valueScore * 0.12 + saturationScore * 0.10 + trackingBonus
                if (score > bestScore) {
                    bestScore = score
                    bestX = cx
                    bestY = cy
                }
            }
        } finally {
            contours.forEach { it.release() }
            working.release()
            hierarchy.release()
        }

        return if (bestScore >= 0.50 && bestX >= 0.0 && bestY >= 0.0) {
            Pair(bestX, bestY)
        } else {
            null
        }
    }

    /**
     * Ищем прицельную линию по белой маске вокруг битка (игнорируя сам круг битка)
     */
    private fun detectAimLineFromWhiteMask(
        whiteMask: Mat, roiBgr: Mat, cueX: Double, cueY: Double, ballR: Double
    ): Pair<Double, Double>? {
        val searchRadius = (ballR * 13.0).toInt().coerceAtLeast(55)
        val x0 = (cueX - searchRadius).toInt().coerceIn(0, whiteMask.cols() - 1)
        val y0 = (cueY - searchRadius).toInt().coerceIn(0, whiteMask.rows() - 1)
        val x1 = (cueX + searchRadius).toInt().coerceIn(x0 + 1, whiteMask.cols())
        val y1 = (cueY + searchRadius).toInt().coerceIn(y0 + 1, whiteMask.rows())

        val localWhite = whiteMask.submat(Rect(x0, y0, x1 - x0, y1 - y0)).clone()
        val localCueX = cueX - x0
        val localCueY = cueY - y0

        // Закрашиваем сам белый биток чёрным, чтобы его края не давали ложных отрезков
        Imgproc.circle(
            localWhite,
            org.opencv.core.Point(localCueX, localCueY),
            (ballR * 1.25).toInt(),
            Scalar(0.0),
            -1
        )

        val lines = Mat()
        return try {
            Imgproc.HoughLinesP(
            localWhite, lines, 1.0, PI / 180.0,
            max(12.0, ballR * 0.75), ballR * 1.25, ballR * 0.9
        )

            var best: Pair<Double, Double>? = null
            var bestScore = 0.0
            for (i in 0 until lines.rows()) {
                val v = lines.get(i, 0) ?: continue
                val lx1 = v[0]; val ly1 = v[1]; val lx2 = v[2]; val ly2 = v[3]
                val dx = lx2 - lx1
                val dy = ly2 - ly1
                val length = hypot(dx, dy)
                if (length < ballR * 1.1) continue

                val cross = abs((localCueX - lx1) * dy - (localCueY - ly1) * dx) / max(0.001, length)
                if (cross > ballR * 0.45) continue

                val ux = dx / length
                val uy = dy / length
                val dist1 = hypot(localCueX - lx1, localCueY - ly1)
                val dist2 = hypot(localCueX - lx2, localCueY - ly2)
                if (min(dist1, dist2) > ballR * 5.0) continue

                val score = length - cross * 3.0
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
            localWhite.release()
            lines.release()
        }
    }

    data class RayCastOutput(
        val segments: List<Segment>,
        val ghost: BallPoint?,
        val targetVector: Segment?,
        val targetColor: Int
    )

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

        // Шаг небольшой, чтобы точка первого контакта не перескакивала глубоко внутрь шара.
        val stepSize = max(1.0, ballRadiusSmall * 0.12)
        val checkR = (ballRadiusSmall * 0.45).toInt().coerceAtLeast(2)
        val patchArea = (2 * checkR + 1) * (2 * checkR + 1)
        val pixelBuf = ByteArray(1)
        val obstacleMask = foregroundMask.clone()

        try {
            // Убираем собственный силуэт битка из карты препятствий, чтобы луч
            // не принимал стартовый шар за ближайшую цель. Небольшой запас нужен
            // из-за антиалиасинга по краю спрайта.
            Imgproc.circle(
                obstacleMask,
                org.opencv.core.Point(cueSmallX, cueSmallY),
                (ballRadiusSmall * 1.10).toInt().coerceAtLeast(2),
                Scalar(0.0),
                -1
            )

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
            // Начинаем после внешнего края битка, а не после его диаметра.
            // Прежнее значение 2.15R пропускало близкие шары и смещало ghost-point.
            val startScanT = if (bounceCount == 0) ballRadiusSmall * 1.02 else ballRadiusSmall * 0.55
            var t = startScanT

            while (t < maxSegmentT) {
                val testX = x + dx * t
                val testY = y + dy * t

                if (!inPocketZone(testX, testY, tableSmall.width.toDouble(), tableSmall.height.toDouble(), ballRadiusSmall)) {
                    val ix = testX.toInt()
                    val iy = testY.toInt()
                    if (ix in checkR until (obstacleMask.cols() - checkR) &&
                        iy in checkR until (obstacleMask.rows() - checkR)
                    ) {
                        obstacleMask.get(iy, ix, pixelBuf)
                        if (pixelBuf[0] != 0.toByte()) {
                            val sub = obstacleMask.submat(Rect(ix - checkR, iy - checkR, 2 * checkR + 1, 2 * checkR + 1))
                            val density = Core.countNonZero(sub).toDouble() / patchArea
                            sub.release()

                            if (density > 0.18) {
                                // testX/testY — первая точка пересечения луча с контуром
                                // прицельного шара. Центр шара лежит примерно на +R,
                                // а центр битка в момент контакта — на -R от этой точки.
                                val ghostSmallX = testX - dx * ballRadiusSmall
                                val ghostSmallY = testY - dy * ballRadiusSmall
                                val targetSmallX = testX + dx * ballRadiusSmall
                                val targetSmallY = testY + dy * ballRadiusSmall

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
        } finally {
            obstacleMask.release()
        }
    }

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

        if (v < 75.0) return Color.parseColor("#B0BEC5")
        if (s < 70.0) return Color.parseColor("#00E5FF")

        return when {
            h in 18.0..34.0 -> Color.parseColor("#FFD600")
            h in 10.0..17.9 -> Color.parseColor("#FF6D00")
            h < 10.0 || h > 166.0 -> Color.parseColor("#FF1744")
            h in 38.0..86.0 -> Color.parseColor("#00E676")
            h in 98.0..126.0 -> Color.parseColor("#2979FF")
            h in 126.1..165.9 -> Color.parseColor("#D500F9")
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
