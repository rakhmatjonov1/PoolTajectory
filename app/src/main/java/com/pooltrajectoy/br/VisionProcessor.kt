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
    private val ballRadius = 14.0
    private val maxBounces = 2

    fun analyze(rgba: Mat): AnalysisResult {
        if (rgba.empty() || rgba.cols() < 10 || rgba.rows() < 10) {
            return AnalysisResult(null, emptyList(), emptyList(), null, null, "Нет кадра")
        }

        val bgr = Mat()
        val hsv = Mat()
        val mask = Mat()
        val green = Mat()
        val blue = Mat()
        var kernel: Mat? = null
        var roi: Mat? = null
        val roiGray = Mat()
        val roiHsv = Mat()
        var maskRoi: Mat? = null
        val inverse = Mat()
        val circles = Mat()

        try {
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)

            Core.inRange(hsv, Scalar(30.0, 35.0, 35.0), Scalar(95.0, 255.0, 255.0), green)
            Core.inRange(hsv, Scalar(85.0, 45.0, 35.0), Scalar(135.0, 255.0, 255.0), blue)
            Core.bitwise_or(green, blue, mask)

            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            val maskClone = mask.clone()
            Imgproc.findContours(maskClone, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            maskClone.release()
            hierarchy.release()

            val minArea = rgba.cols().toDouble() * rgba.rows() * 0.10
            var table: Rect? = null
            var largest = 0.0
            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                val rect = Imgproc.boundingRect(contour)
                val ratio = rect.width.toDouble() / max(1, rect.height)
                if (area > minArea && ratio in 1.2..3.0 && area > largest) {
                    largest = area
                    table = rect
                }
                contour.release()
            }

            if (table == null) {
                return AnalysisResult(null, emptyList(), emptyList(), null, null, "Стол не найден")
            }

            roi = Mat(bgr, table)
            Imgproc.cvtColor(roi, roiGray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.cvtColor(roi, roiHsv, Imgproc.COLOR_BGR2HSV)
            maskRoi = Mat(mask, table)
            Core.bitwise_not(maskRoi, inverse)
            Imgproc.GaussianBlur(roiGray, roiGray, Size(5.0, 5.0), 1.2)

            Imgproc.HoughCircles(
                roiGray, circles, Imgproc.HOUGH_GRADIENT, 1.2,
                22.0, 100.0, 23.0, 9, 20
            )

            val found = ArrayList<BallPoint>()
            for (i in 0 until circles.cols()) {
                val c = circles.get(0, i) ?: continue
                val x = c[0]
                val y = c[1]
                val r = c[2]
                if (x < 0 || y < 0 || x >= table.width || y >= table.height) continue
                if (inPocketZone(x, y, table.width.toDouble(), table.height.toDouble())) continue
                if (found.any { hypot(it.x - (x + table.x), it.y - (y + table.y)) < ballRadius * 0.8 }) continue

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

                val isCue = mean.`val`[1] < 75.0 && mean.`val`[2] > 165.0
                found.add(BallPoint((x + table.x).toFloat(), (y + table.y).toFloat(), r.toFloat(), isCue))
            }

            val cue = found.firstOrNull { it.cue }
            val objects = found.filterNot { it.cue }
            val aim = if (cue != null) {
                detectAimLine(roi, (cue.x - table.x).toDouble(), (cue.y - table.y).toDouble())
            } else {
                null
            }

            var trajectory = emptyList<Segment>()
            var ghost: BallPoint? = null
            var targetVector: Segment? = null
            if (cue != null && aim != null) {
                val physics = trace(
                    cue.x.toDouble(), cue.y.toDouble(), aim.first, aim.second,
                    objects, table
                )
                trajectory = physics.first
                ghost = physics.second
                targetVector = physics.third
            }

            val status = when {
                cue == null -> "Биток не найден"
                aim == null -> "Линия прицеливания не найдена"
                else -> "Анализ активен"
            }
            return AnalysisResult(table, found, trajectory, ghost, targetVector, status)
        } catch (e: Exception) {
            return AnalysisResult(null, emptyList(), emptyList(), null, null, "Ошибка анализа кадра")
        } finally {
            releaseAll(bgr, hsv, mask, green, blue, kernel, roi, roiGray, roiHsv, maskRoi, inverse, circles)
        }
    }

    private fun detectAimLine(
        roi: Mat, cueX: Double, cueY: Double
    ): Pair<Double, Double>? {
        val gray = Mat()
        val edges = Mat()
        val lines = Mat()
        return try {
            Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.Canny(gray, edges, 45.0, 130.0)
            Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180.0, 30, 18.0, 12.0)

            var best: Pair<Double, Double>? = null
            var bestLength = 0.0
            for (i in 0 until lines.rows()) {
                val v = lines.get(i, 0) ?: continue
                val x1 = v[0]; val y1 = v[1]; val x2 = v[2]; val y2 = v[3]
                val dx = x2 - x1
                val dy = y2 - y1
                val length = hypot(dx, dy)
                if (length < 18.0) continue

                val cross = abs((cueX - x1) * dy - (cueY - y1) * dx) / max(0.001, length)
                if (cross > 5.0 || length <= bestLength) continue

                val ux = dx / length
                val uy = dy / length
                val dist1 = hypot(cueX - x1, cueY - y1)
                val dist2 = hypot(cueX - x2, cueY - y2)
                val sign = if (dist1 > dist2) -1.0 else 1.0
                best = Pair(ux * sign, uy * sign)
                bestLength = length
            }
            best
        } catch (_: Exception) {
            null
        } finally {
            releaseAll(gray, edges, lines)
        }
    }

    private fun inPocketZone(x: Double, y: Double, w: Double, h: Double): Boolean {
        val pockets = listOf(
            Pair(0.0, 0.0), Pair(w, 0.0), Pair(0.0, h), Pair(w, h),
            Pair(w / 2.0, 0.0), Pair(w / 2.0, h)
        )
        return pockets.any { hypot(x - it.first, y - it.second) < ballRadius * 1.8 }
    }

    private fun trace(
        startX: Double, startY: Double, dx0: Double, dy0: Double,
        balls: List<BallPoint>, table: Rect
    ): Triple<List<Segment>, BallPoint?, Segment?> {
        val segments = ArrayList<Segment>()
        var x = startX
        var y = startY
        var dx = dx0
        var dy = dy0

        for (bounceCount in 0..maxBounces) {
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
                    (hitBall.x + tx * 95.0).toFloat(),
                    (hitBall.y + ty * 95.0).toFloat()
                )
                return Triple(segments, ghost, vector)
            }

            if (!wallT.isFinite()) break

            val hx = x + dx * wallT
            val hy = y + dy * wallT
            segments.add(Segment(x.toFloat(), y.toFloat(), hx.toFloat(), hy.toFloat(), bounceCount > 0))

            if (bounceCount >= maxBounces) break

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
