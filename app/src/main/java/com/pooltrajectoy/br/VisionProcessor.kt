package com.pooltrajectory.br

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
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
    @Volatile var maxBounces = 3
    @Volatile var lastDetectedTable: Rect? = null

    fun analyze(rgbaFull: Mat): AnalysisResult {
        if (rgbaFull.empty() || rgbaFull.cols() < 20 || rgbaFull.rows() < 20) {
            return AnalysisResult(null, emptyList(), emptyList(), null, null, "Нет кадра")
        }

        val scale = 0.5
        val invScale = 1.0 / scale

        val rgba = Mat()
        val bgr = Mat()
        val hsv = Mat()
        val green = Mat()
        val blue = Mat()
        val clothMask = Mat()
        var kernel: Mat? = null
        var tableHsv: Mat? = null
        var tableBgr: Mat? = null
        var tableClothMask: Mat? = null
        val objectsMask = Mat()
        val whiteMask = Mat()

        try {
            Imgproc.resize(rgbaFull, rgba, Size(), scale, scale, Imgproc.INTER_LINEAR)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)

            // 1. Находим область игрового сукна (зелёный или синий стол)
            Core.inRange(hsv, Scalar(35.0, 45.0, 45.0), Scalar(90.0, 255.0, 255.0), green)
            Core.inRange(hsv, Scalar(92.0, 65.0, 55.0), Scalar(122.0, 255.0, 255.0), blue)
            Core.bitwise_or(green, blue, clothMask)

            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
            Imgproc.morphologyEx(clothMask, clothMask, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.morphologyEx(clothMask, clothMask, Imgproc.MORPH_OPEN, kernel)

            val tableContours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            val clothClone = clothMask.clone()
            Imgproc.findContours(clothClone, tableContours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            clothClone.release()
            hierarchy.release()

            val screenArea = rgba.cols().toDouble() * rgba.rows()
            val minArea = screenArea * 0.18
            val maxArea = screenArea * 0.85

            var tableSmall: Rect? = null
            var largest = 0.0
            for (contour in tableContours) {
                val area = Imgproc.contourArea(contour)
                val rect = Imgproc.boundingRect(contour)
                val ratio = rect.width.toDouble() / max(1, rect.height)
                if (area in minArea..maxArea && ratio in 1.55..2.45 && area > largest) {
                    largest = area
                    tableSmall = rect
                }
                contour.release()
            }

            if (tableSmall == null) {
                return AnalysisResult(null, emptyList(), emptyList(), null, null, "Стол не найден")
            }

            // 2. Вырезаем область стола и отсекаем цвет сукна
            // Водяной знак "8 POOL" близок по цвету к сукну, поэтому исчезает после inRange
            tableHsv = Mat(hsv, tableSmall)
            tableBgr = Mat(bgr, tableSmall)
            tableClothMask = Mat(clothMask, tableSmall)

            // Инвертируем маску сукна: остаются только объекты, отличающиеся от стола (шары, кий, лузы)
            Core.bitwise_not(tableClothMask, objectsMask)

            // Дополнительно выделяем чисто белые пиксели для точного поиска битка
            Core.inRange(tableHsv, Scalar(0.0, 0.0, 185.0), Scalar(180.0, 60.0, 255.0), whiteMask)
            Core.bitwise_or(objectsMask, whiteMask, objectsMask)

            val smallKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
            Imgproc.morphologyEx(objectsMask, objectsMask, Imgproc.MORPH_OPEN, smallKernel)
            smallKernel.release()

            val ballRadiusSmall = (tableSmall.width / 38.0).coerceIn(5.0, 22.0)
            val expectedArea = PI * ballRadiusSmall * ballRadiusSmall
            val minBallArea = expectedArea * 0.45
            val maxBallArea = expectedArea * 2.1

            val objContours = ArrayList<MatOfPoint>()
            val objHierarchy = Mat()
            val objClone = objectsMask.clone()
            Imgproc.findContours(objClone, objContours, objHierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            objClone.release()
            objHierarchy.release()

            data class CandidateBall(val x: Double, val y: Double, val r: Double, val whiteRatio: Double)
            val candidates = ArrayList<CandidateBall>()

            for (contour in objContours) {
                val area = Imgproc.contourArea(contour)
                if (area in minBallArea..maxBallArea) {
                    val contour2f = MatOfPoint2f(*contour.toArray())
                    val perimeter = Imgproc.arcLength(contour2f, true)
                    contour2f.release()

                    // Проверка округлости: отсекаем длинный кий и углы луз
                    val circularity = if (perimeter > 0.0) (4.0 * PI * area) / (perimeter * perimeter) else 0.0
                    val rect = Imgproc.boundingRect(contour)
                    val aspect = rect.width.toDouble() / max(1, rect.height)

                    if (circularity > 0.68 && aspect in 0.72..1.38) {
                        val cx = rect.x + rect.width / 2.0
                        val cy = rect.y + rect.height / 2.0

                        if (!inPocketZone(cx, cy, tableSmall.width.toDouble(), tableSmall.height.toDouble(), ballRadiusSmall) &&
                            cx > ballRadiusSmall * 0.7 && cx < tableSmall.width - ballRadiusSmall * 0.7 &&
                            cy > ballRadiusSmall * 0.7 && cy < tableSmall.height - ballRadiusSmall * 0.7
                        ) {
                            // Считаем долю белых пикселей внутри контура шара
                            val subWhite = whiteMask.submat(rect)
                            val whitePixels = Core.countNonZero(subWhite).toDouble()
                            subWhite.release()
                            val whiteRatio = whitePixels / max(1.0, (rect.width * rect.height).toDouble())

                            candidates.add(CandidateBall(cx, cy, ballRadiusSmall, whiteRatio))
                        }
                    }
                }
                contour.release()
            }

            // Определяем биток как шар с максимальной долей белого цвета (порог > 32%)
            val bestCue = candidates
                .filter { it.whiteRatio > 0.32 }
                .maxByOrNull { it.whiteRatio }

            val foundFull = ArrayList<BallPoint>()
            for (cand in candidates) {
                val fullX = ((cand.x + tableSmall.x) * invScale).toFloat()
                val fullY = ((cand.y + tableSmall.y) * invScale).toFloat()
                val fullR = (ballRadiusSmall * invScale).toFloat()
                if (foundFull.any { hypot(it.x - fullX, it.y - fullY) < fullR * 1.4 }) continue
                val isCue = (cand === bestCue)
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

            val cue = foundFull.firstOrNull { it.cue }
            val objects = foundFull.filterNot { it.cue }

            val aim = if (cue != null) {
                val cueSmallX = (cue.x * scale) - tableSmall.x
                val cueSmallY = (cue.y * scale) - tableSmall.y
                detectAimLineNearCue(tableBgr, cueSmallX, cueSmallY, ballRadiusSmall)
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
                cue == null -> "Биток не найден"
                aim == null -> "Наведите кий"
                else -> "Авто-прицел активен"
            }
            return AnalysisResult(tableFull, foundFull, trajectory, ghost, targetVector, status)
        } catch (_: Exception) {
            return AnalysisResult(null, emptyList(), emptyList(), null, null, "Ошибка кадра")
        } finally {
            releaseAll(
                rgba, bgr, hsv, green, blue, clothMask, kernel,
                tableHsv, tableBgr, tableClothMask, objectsMask, whiteMask
            )
        }
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
            Imgproc.HoughLinesP(edges, lines, 1.0, PI / 180.0, 20, ballR * 1.4, ballR * 1.2)

            var best: Pair<Double, Double>? = null
            var bestScore = 0.0
            for (i in 0 until lines.rows()) {
                val v = lines.get(i, 0) ?: continue
                val lx1 = v[0]; val ly1 = v[1]; val lx2 = v[2]; val ly2 = v[3]
                val dx = lx2 - lx1
                val dy = ly2 - ly1
                val length = hypot(dx, dy)
                if (length < ballR * 1.3) continue

                val cross = abs((localCueX - lx1) * dy - (localCueY - ly1) * dx) / max(0.001, length)
                if (cross > ballR * 0.42) continue

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

    private fun inPocketZone(x: Double, y: Double, w: Double, h: Double, ballR: Double): Boolean {
        val pockets = listOf(
            Pair(0.0, 0.0), Pair(w, 0.0), Pair(0.0, h), Pair(w, h),
            Pair(w / 2.0, 0.0), Pair(w / 2.0, h)
        )
        return pockets.any { hypot(x - it.first, y - it.second) < ballR * 2.0 }
    }

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
                    (hitBall.x + tx * (ballRadius * 14.0)).toFloat(),
                    (hitBall.y + ty * (ballRadius * 14.0)).toFloat()
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
