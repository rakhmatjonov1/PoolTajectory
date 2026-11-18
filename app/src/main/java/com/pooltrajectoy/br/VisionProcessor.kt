package com.pooltrajectory.br

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class BallPoint(val x: Float, val y: Float, val radius: Float, val cue: Boolean)
data class Segment(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val dashed: Boolean = false)
data class AnalysisResult(
    val table: Rect?,
    val balls: List<BallPoint>,
    val trajectory: List<Segment>,
    val ghost: BallPoint?,
    val targetVector: Segment?,
    val status: String
)

class VisionProcessor {
    @Volatile var maxBounces = 2
    @Volatile var lastDetectedTable: Rect? = null
    @Volatile var lastDetectedBalls: List<BallPoint> = emptyList()

    /**
     * Умный анализ с 2x даунскейлингом для высокой скорости (без лагов)
     * и относительным масштабированием под любое разрешение экрана.
     */
    fun analyze(rgbaFull: Mat): AnalysisResult {
        if (rgbaFull.empty() || rgbaFull.cols() < 20 || rgbaFull.rows() < 20) {
            return AnalysisResult(null, emptyList(), emptyList(), null, null, "Нет кадра")
        }

        val scale = 0.5
        val invScale = 1.0 / scale

        val rgba = Mat()
        val bgr = Mat()
        val hsv = Mat()
        val mask = Mat()
        val green = Mat()
        val blue = Mat()
        var kernel: Mat? = null
        var roi: Mat? = null
        val roiGray = Mat()
        val roiHsv = Mat()
        val circles = Mat()

        try {
            Imgproc.resize(rgbaFull, rgba, Size(), scale, scale, Imgproc.INTER_LINEAR)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)

            // Диапазоны цвета сукна (классическое зелёное и синее сукно 8 Ball Pool)
            Core.inRange(hsv, Scalar(35.0, 45.0, 45.0), Scalar(90.0, 255.0, 255.0), green)
            Core.inRange(hsv, Scalar(92.0, 65.0, 55.0), Scalar(122.0, 255.0, 255.0), blue)
            Core.bitwise_or(green, blue, mask)

            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            val maskClone = mask.clone()
            Imgproc.findContours(maskClone, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            maskClone.release()
            hierarchy.release()

            val screenArea = rgba.cols().toDouble() * rgba.rows()
            val minArea = screenArea * 0.18
            val maxArea = screenArea * 0.85

            var tableSmall: Rect? = null
            var largest = 0.0
            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                val rect = Imgproc.boundingRect(contour)
                val ratio = rect.width.toDouble() / max(1, rect.height)
                // Игровой стол всегда имеет пропорции ~2:1 и не занимает весь экран от края до края
                if (area in minArea..maxArea && ratio in 1.55..2.45 && area > largest) {
                    largest = area
                    tableSmall = rect
                }
                contour.release()
            }

            if (tableSmall == null) {
                return AnalysisResult(null, emptyList(), emptyList(), null, null, "Стол не найден (поверните экран)")
            }

            // Динамический радиус шара относительно ширины стола (~1/38 от длины стола)
            val ballRadiusSmall = (tableSmall.width / 38.0).coerceIn(5.0, 22.0)
            val minR = (ballRadiusSmall * 0.72).toInt().coerceAtLeast(4)
            val maxR = (ballRadiusSmall * 1.32).toInt().coerceAtLeast(minR + 2)

            roi = Mat(bgr, tableSmall)
            Imgproc.cvtColor(roi, roiGray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.cvtColor(roi, roiHsv, Imgproc.COLOR_BGR2HSV)
            Imgproc.GaussianBlur(roiGray, roiGray, Size(5.0, 5.0), 1.1)

            Imgproc.HoughCircles(
                roiGray,
                circles,
                Imgproc.HOUGH_GRADIENT,
                1.2,
                ballRadiusSmall * 1.65,
                95.0,
                19.0,
                minR,
                maxR
            )

            val foundFull = ArrayList<BallPoint>()
            for (i in 0 until circles.cols()) {
                val c = circles.get(0, i) ?: continue
                val x = c[0]
                val y = c[1]
                val r = c[2]
                if (x < ballRadiusSmall * 0.5 || y < ballRadiusSmall * 0.5 ||
                    x >= tableSmall.width - ballRadiusSmall * 0.5 ||
                    y >= tableSmall.height - ballRadiusSmall * 0.5
                ) continue

                if (inPocketZone(x, y, tableSmall.width.toDouble(), tableSmall.height.toDouble(), ballRadiusSmall)) continue

                val fullX = ((x + tableSmall.x) * invScale).toFloat()
                val fullY = ((y + tableSmall.y) * invScale).toFloat()
                val fullR = (ballRadiusSmall * invScale).toFloat()

                if (foundFull.any { hypot(it.x - fullX, it.y - fullY) < fullR * 1.4 }) continue

                val cx = x.toInt().coerceIn(0, roiHsv.cols() - 1)
                val cy = y.toInt().coerceIn(0, roiHsv.rows() - 1)
                val rad = max(2, (r * 0.55).toInt())
                val x0 = max(0, cx - rad)
                val y0 = max(0, cy - rad)
                val x1 = min(roiHsv.cols(), cx + rad + 1)
                val y1 = min(roiHsv.rows(), cy + rad + 1)
                val colorRoi = roiHsv.submat(Rect(x0, y0, x1 - x0, y1 - y0))
                val mean = Core.mean(colorRoi)
                colorRoi.release()

                // Белый биток: низкая насыщенность S и высокая яркость V
                val isCue = mean.`val`[1] < 80.0 && mean.`val`[2] > 165.0
                foundFull.add(BallPoint(fullX, fullY, fullR, isCue))
            }

            val tableFull = Rect(
                (tableSmall.x * invScale).toInt(),
                (tableSmall.y * invScale).toInt(),
                (tableSmall.width * invScale).toInt(),
                (tableSmall.height * invScale).toInt()
            )
            val ballRadiusFull = ballRadiusSmall * invScale

            lastDetectedTable = tableFull
            lastDetectedBalls = foundFull

            val cue = foundFull.firstOrNull { it.cue }
            val objects = foundFull.filterNot { it.cue }

            val aim = if (cue != null) {
                val cueSmallX = (cue.x * scale) - tableSmall.x
                val cueSmallY = (cue.y * scale) - tableSmall.y
                detectAimLineNearCue(roi, cueSmallX, cueSmallY, ballRadiusSmall)
            } else {
                null
            }

            var trajectory = emptyList<Segment>()
            var ghost: BallPoint? = null
            var targetVector: Segment? = null
            if (cue != null && aim != null) {
                val physics = tracePhysics(
                    cue.x.toDouble(), cue.y.toDouble(),
                    aim.first, aim.second,
                    objects, tableFull, ballRadiusFull
                )
                trajectory = physics.first
                ghost = physics.second
                targetVector = physics.third
            }

            val status = when {
                cue == null -> "Биток не найден (${foundFull.size} ш.)"
                aim == null -> "Прицел не найден (${foundFull.size} ш.)"
                else -> "Авто-прицел активен"
            }
            return AnalysisResult(tableFull, foundFull, trajectory, ghost, targetVector, status)
        } catch (_: Exception) {
            return AnalysisResult(null, emptyList(), emptyList(), null, null, "Ошибка анализа")
        } finally {
            releaseAll(rgba, bgr, hsv, mask, green, blue, kernel, roi, roiGray, roiHsv, circles)
        }
    }

    /**
     * Ищем прицельную линию только в локальной зоне вокруг битка,
     * чтобы не цепляться за логотип "8 POOL" и разметку стола.
     */
    private fun detectAimLineNearCue(
        roi: Mat, cueX: Double, cueY: Double, ballR: Double
    ): Pair<Double, Double>? {
        val searchRadius = (ballR * 11.0).toInt().coerceAtLeast(45)
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
            Imgproc.Canny(gray, edges, 50.0, 140.0)
            Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180.0, 22, ballR * 1.5, ballR * 1.1)

            var best: Pair<Double, Double>? = null
            var bestScore = 0.0
            for (i in 0 until lines.rows()) {
                val v = lines.get(i, 0) ?: continue
                val lx1 = v[0]; val ly1 = v[1]; val lx2 = v[2]; val ly2 = v[3]
                val dx = lx2 - lx1
                val dy = ly2 - ly1
                val length = hypot(dx, dy)
                if (length < ballR * 1.4) continue

                // Перпендикулярное расстояние от центра битка до прямой
                val cross = abs((localCueX - lx1) * dy - (localCueY - ly1) * dx) / max(0.001, length)
                if (cross > ballR * 0.45) continue

                val ux = dx / length
                val uy = dy / length
                val dist1 = hypot(localCueX - lx1, localCueY - ly1)
                val dist2 = hypot(localCueX - lx2, localCueY - ly2)

                // Исключаем отрезки, находящиеся слишком далеко от битка
                if (min(dist1, dist2) > ballR * 4.5) continue

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
            localRoi.release()
            releaseAll(gray, edges, lines)
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
     * Общий физический движок расчёта траекторий и столкновений
     * (используется как в Авто-режиме, так и в Ручном режиме).
     */
    fun tracePhysics(
        startX: Double, startY: Double,
        dx0: Double, dy0: Double,
        balls: List<BallPoint>,
        table: Rect,
        ballRadius: Double
    ): Triple<List<Segment>, BallPoint?, Segment?> {
        val segments = ArrayList<Segment>()
        var x = startX
        var y = startY
        var dx = dx0
        var dy = dy0
        val currentMaxBounces = maxBounces

        for (bounceCount in 0..currentMaxBounces) {
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

            var hitBall: BallPoint? = null
            var ballT = Double.POSITIVE_INFINITY
            for (ball in balls) {
                val ox = x - ball.x
                val oy = y - ball.y
                val b = 2.0 * (ox * dx + oy * dy)
                val c = ox * ox + oy * oy - (2.0 * ballRadius) * (2.0 * ballRadius)
                val disc = b * b - 4.0 * c
                if (disc < 0.0) continue
                val t = (-b - sqrt(disc)) / 2.0
                if (t > 0.001 && t < ballT) {
                    ballT = t
                    hitBall = ball
                }
            }

            if (hitBall != null && ballT < wallT) {
                val hx = x + dx * ballT
                val hy = y + dy * ballT
                segments.add(Segment(x.toFloat(), y.toFloat(), hx.toFloat(), hy.toFloat()))

                val nxLine = (hitBall.x - hx).toDouble()
                val nyLine = (hitBall.y - hy).toDouble()
                val nLen = hypot(nxLine, nyLine).coerceAtLeast(0.001)
                val tx = nxLine / nLen
                val ty = nyLine / nLen

                val ghost = BallPoint(hx.toFloat(), hy.toFloat(), ballRadius.toFloat(), true)
                val vector = Segment(
                    hitBall.x, hitBall.y,
                    (hitBall.x + tx * (ballRadius * 7.5)).toFloat(),
                    (hitBall.y + ty * (ballRadius * 7.5)).toFloat()
                )
                return Triple(segments, ghost, vector)
            }

            if (!wallT.isFinite()) break

            val hx = x + dx * wallT
            val hy = y + dy * wallT
            segments.add(Segment(x.toFloat(), y.toFloat(), hx.toFloat(), hy.toFloat(), bounceCount > 0))

            if (bounceCount >= currentMaxBounces) break

            val dot = dx * nx + dy * ny
            dx -= 2.0 * dot * nx
            dy -= 2.0 * dot * ny
            val length = hypot(dx, dy).coerceAtLeast(0.001)
            dx /= length
            dy /= length
            x = hx + dx * 0.5
            y = hy + dy * 0.5
        }

        return Triple(segments, null, null)
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
