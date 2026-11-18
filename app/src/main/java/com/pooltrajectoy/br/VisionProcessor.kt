package com.pooltrajectory.br

import android.graphics.Color
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
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

enum class BallType(val label: String, val colorInt: Int) {
    CUE("Биток", Color.WHITE),
    YELLOW("Жёлтый (1/9)", Color.parseColor("#FFD600")),
    BLUE("Синий (2/10)", Color.parseColor("#2979FF")),
    RED("Красный (3/11)", Color.parseColor("#FF1744")),
    PURPLE("Фиолетовый (4/12)", Color.parseColor("#D500F9")),
    ORANGE("Оранжевый (5/13)", Color.parseColor("#FF6D00")),
    GREEN("Зелёный (6/14)", Color.parseColor("#00E676")),
    MAROON("Бордовый (7/15)", Color.parseColor("#8E0000")),
    EIGHT("Чёрный (8)", Color.parseColor("#263238")),
    UNKNOWN("Шар", Color.parseColor("#00E5FF"))
}

data class BallPoint(
    val x: Float,
    val y: Float,
    val radius: Float,
    val cue: Boolean,
    val type: BallType = if (cue) BallType.CUE else BallType.UNKNOWN
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
    val balls: List<BallPoint>,
    val trajectory: List<Segment>,
    val ghost: BallPoint?,
    val targetVector: Segment?,
    val targetColor: Int,
    val status: String
)

class VisionProcessor {
    @Volatile var maxBounces = 3
    @Volatile var cueForceLevel = 7 // Уровень силы кия от 1 до 11
    @Volatile var lastDetectedTable: Rect? = null

    fun analyze(rgbaFull: Mat): AnalysisResult {
        if (rgbaFull.empty() || rgbaFull.cols() < 20 || rgbaFull.rows() < 20) {
            return AnalysisResult(null, emptyList(), emptyList(), null, null, Color.CYAN, "Нет кадра")
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
        val distTransform = Mat()

        try {
            Imgproc.resize(rgbaFull, rgba, Size(), scale, scale, Imgproc.INTER_LINEAR)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)

            // 1. Поиск игрового стола по цвету сукна (зелёное или синее)
            Core.inRange(hsv, Scalar(35.0, 40.0, 40.0), Scalar(90.0, 255.0, 255.0), green)
            Core.inRange(hsv, Scalar(92.0, 55.0, 50.0), Scalar(120.0, 255.0, 255.0), blue)
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
            val minArea = screenArea * 0.16
            val maxArea = screenArea * 0.88

            var tableSmall: Rect? = null
            var largest = 0.0
            for (contour in tableContours) {
                val area = Imgproc.contourArea(contour)
                val rect = Imgproc.boundingRect(contour)
                val ratio = rect.width.toDouble() / max(1, rect.height)
                if (area in minArea..maxArea && ratio in 1.50..2.50 && area > largest) {
                    largest = area
                    tableSmall = rect
                }
                contour.release()
            }

            if (tableSmall == null) {
                return AnalysisResult(null, emptyList(), emptyList(), null, null, Color.CYAN, "Стол не найден")
            }

            // 2. Выделяем все объекты внутри стола, отличающиеся от цвета сукна
            tableHsv = Mat(hsv, tableSmall)
            tableBgr = Mat(bgr, tableSmall)
            tableClothMask = Mat(clothMask, tableSmall)

            // Адаптивно определяем точный цвет сукна по центру стола, чтобы отсечь даже водяной знак "8 POOL"
            val centerSample = tableHsv.submat(
                Rect(
                    (tableSmall.width * 0.35).toInt(),
                    (tableSmall.height * 0.35).toInt(),
                    (tableSmall.width * 0.30).toInt().coerceAtLeast(4),
                    (tableSmall.height * 0.30).toInt().coerceAtLeast(4)
                )
            )
            val clothMean = Core.mean(centerSample)
            centerSample.release()

            val clothH = clothMean.`val`[0]
            val clothS = clothMean.`val`[1]
            val clothV = clothMean.`val`[2]

            val exactClothMask = Mat()
            Core.inRange(
                tableHsv,
                Scalar((clothH - 14.0).coerceAtLeast(0.0), (clothS - 65.0).coerceAtLeast(25.0), (clothV - 65.0).coerceAtLeast(30.0)),
                Scalar((clothH + 14.0).coerceAtMost(180.0), 255.0, 255.0),
                exactClothMask
            )
            Core.bitwise_not(exactClothMask, objectsMask)
            exactClothMask.release()

            // Убираем мелкий шум и тонкую линию разметки дома
            val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            Imgproc.morphologyEx(objectsMask, objectsMask, Imgproc.MORPH_OPEN, openKernel)
            openKernel.release()

            val ballRadiusSmall = (tableSmall.width / 38.0).coerceIn(5.0, 22.0)

            // 3. Distance Transform: находит центры каждого шара даже если они стоят вплотную (в пирамиде)
            Imgproc.distanceTransform(objectsMask, distTransform, Imgproc.DIST_L2, 5)

            val minDist = (ballRadiusSmall * 0.58).toFloat()
            val maxDist = (ballRadiusSmall * 1.45).toFloat()
            val step = max(1, (ballRadiusSmall * 0.35).toInt())
            val minSpacing = ballRadiusSmall * 1.65

            data class Peak(val x: Int, val y: Int, val dist: Float)
            val peaks = ArrayList<Peak>()

            val rows = distTransform.rows()
            val cols = distTransform.cols()
            val rowBuf = FloatArray(cols)

            for (y in step until rows - step step step) {
                distTransform.get(y, 0, rowBuf)
                for (x in step until cols - step step step) {
                    val d = rowBuf[x]
                    if (d in minDist..maxDist) {
                        if (!inPocketZone(x.toDouble(), y.toDouble(), cols.toDouble(), rows.toDouble(), ballRadiusSmall)) {
                            peaks.add(Peak(x, y, d))
                        }
                    }
                }
            }

            // Сортируем пики от самых уверенных центров к краям и подавляем дубликаты (Non-Maximum Suppression)
            peaks.sortByDescending { it.dist }

            val foundFull = ArrayList<BallPoint>()
            val fullR = (ballRadiusSmall * invScale).toFloat()

            for (p in peaks) {
                val fullX = ((p.x + tableSmall.x) * invScale).toFloat()
                val fullY = ((p.y + tableSmall.y) * invScale).toFloat()
                if (foundFull.any { hypot(it.x - fullX, it.y - fullY) < fullR * 1.65 }) continue

                val type = classifyBallColor(tableHsv, p.x, p.y, ballRadiusSmall)
                // Игнорируем случайные артефакты фона, если цвет не похож ни на один шар
                if (type == BallType.UNKNOWN) continue

                foundFull.add(
                    BallPoint(
                        x = fullX,
                        y = fullY,
                        radius = fullR,
                        cue = (type == BallType.CUE),
                        type = type
                    )
                )
            }

            // Если нашлось несколько белых кандидатов (например, блик), оставляем только один самый вероятный биток
            val cueCandidates = foundFull.filter { it.cue }
            if (cueCandidates.size > 1) {
                val primaryCue = cueCandidates.first()
                for (i in foundFull.indices) {
                    if (foundFull[i].cue && foundFull[i] !== primaryCue) {
                        foundFull[i] = foundFull[i].copy(cue = false, type = BallType.YELLOW)
                    }
                }
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
            var hitBallColor = Color.parseColor("#00E5FF")

            if (cue != null && aim != null) {
                val physics = tracePhysics(
                    cue.x.toDouble(), cue.y.toDouble(),
                    aim.first, aim.second,
                    objects, tableFull, ballRadiusFull
                )
                trajectory = physics.first
                ghost = physics.second
                targetVector = physics.third
                hitBallColor = physics.fourth
            }

            val status = when {
                cue == null -> "Шаров: ${foundFull.size} | Ищу биток..."
                aim == null -> "Шаров: ${foundFull.size} | Наведите кий"
                else -> "Шаров: ${foundFull.size} | Сила: $cueForceLevel/11"
            }
            return AnalysisResult(tableFull, foundFull, trajectory, ghost, targetVector, hitBallColor, status)
        } catch (_: Exception) {
            return AnalysisResult(null, emptyList(), emptyList(), null, null, Color.CYAN, "Ошибка кадра")
        } finally {
            releaseAll(rgba, bgr, hsv, green, blue, clothMask, kernel, tableHsv, tableBgr, tableClothMask, objectsMask, distTransform)
        }
    }

    /**
     * Определяет цвет и тип шара (1–15 или биток) по центральной зоне шара в HSV
     */
    private fun classifyBallColor(tableHsv: Mat, cx: Int, cy: Int, ballR: Double): BallType {
        val sampleR = max(2, (ballR * 0.50).toInt())
        val x0 = (cx - sampleR).coerceIn(0, tableHsv.cols() - 1)
        val y0 = (cy - sampleR).coerceIn(0, tableHsv.rows() - 1)
        val x1 = (cx + sampleR + 1).coerceIn(x0 + 1, tableHsv.cols())
        val y1 = (cy + sampleR + 1).coerceIn(y0 + 1, tableHsv.rows())

        val patch = tableHsv.submat(Rect(x0, y0, x1 - x0, y1 - y0))
        val mean = Core.mean(patch)
        patch.release()

        val h = mean.`val`[0]
        val s = mean.`val`[1]
        val v = mean.`val`[2]

        // 1. Чёрный шар №8 (очень низкая яркость)
        if (v < 72.0) return BallType.EIGHT

        // 2. Белый биток (низкая насыщенность, высокая яркость)
        if (s < 78.0 && v > 165.0) return BallType.CUE

        // Если насыщенность слишком низкая и не белый — это серый блик или тень
        if (s < 65.0) return BallType.UNKNOWN

        // 3. Цветные шары по оттенку Hue (OpenCV H: 0..180)
        return when {
            h in 18.0..34.0 -> BallType.YELLOW      // 1, 9
            h in 10.0..17.9 -> BallType.ORANGE      // 5, 13
            h < 10.0 || h > 166.0 -> {
                if (v < 145.0) BallType.MAROON      // 7, 15 (тёмно-бордовый)
                else BallType.RED                   // 3, 11 (ярко-красный)
            }
            h in 38.0..86.0 -> BallType.GREEN       // 6, 14
            h in 98.0..126.0 -> BallType.BLUE       // 2, 10
            h in 126.1..165.9 -> BallType.PURPLE    // 4, 12
            else -> BallType.UNKNOWN
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

    private fun inPocketZone(x: Double, y: Double, w: Double, h: Double, ballR: Double): Boolean {
        val pockets = listOf(
            Pair(0.0, 0.0), Pair(w, 0.0), Pair(0.0, h), Pair(w, h),
            Pair(w / 2.0, 0.0), Pair(w / 2.0, h)
        )
        return pockets.any { hypot(x - it.first, y - it.second) < ballR * 2.0 }
    }

    data class PhysicsResult(
        val first: List<Segment>,
        val second: BallPoint?,
        val third: Segment?,
        val fourth: Int
    )

    /**
     * Физический расчёт траектории с учётом 11 уровней силы кия (cueForceLevel: 1..11)
     */
    fun tracePhysics(
        startX: Double, startY: Double,
        dx0: Double, dy0: Double,
        balls: List<BallPoint>,
        table: Rect,
        ballRadius: Double
    ): PhysicsResult {
        val segments = ArrayList<Segment>()
        var x = startX
        var y = startY
        var dx = dx0
        var dy = dy0

        val force = cueForceLevel.coerceIn(1, 11)
        // Максимальная дистанция пробега битка зависит от силы кия (от 0.45 ширины стола на силе 1 до 3.2 ширины стола на силе 11)
        var remainingEnergy = table.width * (0.35 + force * 0.26)
        // Количество отскоков также ограничивается энергией удара
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

            // Столкновение с прицельным шаром в пределах оставшейся энергии удара
            if (hitBall != null && ballT < wallT && ballT <= remainingEnergy) {
                val hx = x + dx * ballT
                val hy = y + dy * ballT
                segments.add(Segment(x.toFloat(), y.toFloat(), hx.toFloat(), hy.toFloat()))

                val energyAfterHit = (remainingEnergy - ballT).coerceAtLeast(ballRadius * 2.0)

                val nxLine = (hitBall.x - hx).toDouble()
                val nyLine = (hitBall.y - hy).toDouble()
                val nLen = hypot(nxLine, nyLine).coerceAtLeast(0.001)
                val tx = nxLine / nLen
                val ty = nyLine / nLen

                // Угол резки влияет на передачу импульса прицельному шару
                val cutFactor = (dx * tx + dy * ty).coerceIn(0.15, 1.0)
                val targetTravel = (energyAfterHit * cutFactor * 0.85).coerceIn(ballRadius * 2.5, table.width * 1.1)

                val ghost = BallPoint(hx.toFloat(), hy.toFloat(), ballRadius.toFloat(), true)
                val vector = Segment(
                    hitBall.x, hitBall.y,
                    (hitBall.x + tx * targetTravel).toFloat(),
                    (hitBall.y + ty * targetTravel).toFloat()
                )
                return PhysicsResult(segments, ghost, vector, hitBall.type.colorInt)
            }

            if (!wallT.isFinite()) break

            // Если энергии не хватает долететь до борта — заканчиваем луч в точке остановки шара
            if (wallT > remainingEnergy) {
                val stopX = x + dx * remainingEnergy
                val stopY = y + dy * remainingEnergy
                segments.add(Segment(x.toFloat(), y.toFloat(), stopX.toFloat(), stopY.toFloat(), bounceCount > 0))
                break
            }

            val hx = x + dx * wallT
            val hy = y + dy * wallT
            segments.add(Segment(x.toFloat(), y.toFloat(), hx.toFloat(), hy.toFloat(), bounceCount > 0))

            // Потеря энергии на пробег + отскок от резины борта (22%)
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

        return PhysicsResult(segments, null, null, Color.parseColor("#00E5FF"))
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
