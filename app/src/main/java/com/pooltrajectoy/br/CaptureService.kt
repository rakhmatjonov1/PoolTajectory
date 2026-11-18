package com.pooltrajectory.br

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Rect
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot

class CaptureService : Service() {
    companion object {
        const val ACTION_START = "com.pooltrajectory.br.START"
        const val ACTION_STOP = "com.pooltrajectory.br.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "pooltrajectory_capture"
        private const val NOTIFICATION_ID = 7101
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var windowManager: WindowManager? = null
    private var overlayView: TrajectoryOverlayView? = null
    private var bubbleView: View? = null
    private var menuView: View? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var cueHandleView: View? = null
    private var aimHandleView: View? = null
    private var cueHandleParams: WindowManager.LayoutParams? = null
    private var aimHandleParams: WindowManager.LayoutParams? = null

    private var statusTextView: TextView? = null
    private var isMenuOpen = false
    @Volatile private var manualMode = false

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private val processing = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private var lastFrameTime = 0L
    private val vision = VisionProcessor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            ACTION_START -> startCapture(intent)
        }
        return START_NOT_STICKY
    }

    private fun reportErrorAndStop(e: Throwable) {
        val trace = e.stackTraceToString()
        getSharedPreferences("hud_logs", Context.MODE_PRIVATE)
            .edit()
            .putString("last_crash", trace)
            .apply()

        Handler(Looper.getMainLooper()).post {
            try {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("HUD Crash", trace))
                Toast.makeText(this, "Ошибка скопирована в буфер: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
        stopCapture()
    }

    private fun startCapture(intent: Intent) {
        try {
            createNotificationChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }

            if (!OpenCVLoader.initLocal()) {
                reportErrorAndStop(IllegalStateException("OpenCVLoader.initLocal() вернул false"))
                return
            }

            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            } ?: run {
                stopSelf()
                return
            }

            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            updateDisplayMetrics()

            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)

            workerThread = HandlerThread("PoolTrajectoryCV").also { it.start() }
            workerHandler = Handler(workerThread!!.looper)

            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                }
            }, workerHandler)

            setupOverlay()
            setupFloatingControls()
            setupManualHandles()
            createOrResizeProjection()
        } catch (t: Throwable) {
            reportErrorAndStop(t)
        }
    }

    private fun updateDisplayMetrics() {
        val wm = windowManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDensity = resources.displayMetrics.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            val oldW = screenWidth
            val oldH = screenHeight
            updateDisplayMetrics()
            if (oldW != screenWidth || oldH != screenHeight) {
                createOrResizeProjection()
                repositionFloatingElementsAfterRotation()
            }
        } catch (t: Throwable) {
            reportErrorAndStop(t)
        }
    }

    private fun repositionFloatingElementsAfterRotation() {
        val bParams = bubbleParams ?: return
        val bSize = dp(48)
        bParams.x = bParams.x.coerceIn(0, (screenWidth - bSize).coerceAtLeast(0))
        bParams.y = bParams.y.coerceIn(0, (screenHeight - bSize).coerceAtLeast(0))
        try { windowManager?.updateViewLayout(bubbleView, bParams) } catch (_: Exception) {}
        if (isMenuOpen) updateMenuPosition()

        if (manualMode) {
            resetManualHandlesToCenter()
            recalculateManualTrajectory()
        }
    }

    private fun createOrResizeProjection() {
        val proj = projection ?: return
        val width = screenWidth.coerceAtLeast(1)
        val height = screenHeight.coerceAtLeast(1)

        // Создаём новый ImageReader под актуальное разрешение (портрет или альбом)
        val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        newReader.setOnImageAvailableListener({ reader ->
            if (manualMode) {
                try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}
                return@setOnImageAvailableListener
            }

            val now = System.currentTimeMillis()
            if (now - lastFrameTime < 75 || !processing.compareAndSet(false, true)) {
                try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}
                return@setOnImageAvailableListener
            }
            lastFrameTime = now
            var image: Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    val bitmap = imageToBitmap(image)
                    if (bitmap != null) {
                        val mat = Mat()
                        Utils.bitmapToMat(bitmap, mat)
                        val result = vision.analyze(mat)
                        overlayView?.post {
                            if (!manualMode) {
                                overlayView?.setAnalysis(result)
                                if (isMenuOpen) {
                                    statusTextView?.text = "Статус: ${result.status}"
                                }
                            }
                        }
                        mat.release()
                        bitmap.recycle()
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try { image?.close() } catch (_: Exception) {}
                processing.set(false)
            }
        }, workerHandler)

        val oldReader = imageReader
        imageReader = newReader

        val existingDisplay = virtualDisplay
        if (existingDisplay == null) {
            // Первый запуск: создаём VirtualDisplay ровно один раз (требование Android 14/15)
            virtualDisplay = proj.createVirtualDisplay(
                "PoolTrajectoryDisplay",
                width,
                height,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface,
                null,
                workerHandler
            )
        } else {
            // Поворот экрана: обновляем размеры и Surface без повторного createVirtualDisplay
            existingDisplay.resize(width, height, screenDensity)
            existingDisplay.setSurface(newReader.surface)
        }

        try { oldReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { oldReader?.close() } catch (_: Exception) {}
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes.firstOrNull() ?: return null
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val paddedWidth = image.width + rowPadding / pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            padded.recycle()
            cropped
        } catch (_: Throwable) {
            null
        }
    }

    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= 26) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun setupOverlay() {
        overlayView = TrajectoryOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager?.addView(overlayView, params)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupManualHandles() {
        val handleSize = dp(40)

        cueHandleParams = WindowManager.LayoutParams(
            handleSize, handleSize, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        aimHandleParams = WindowManager.LayoutParams(
            handleSize, handleSize, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        cueHandleView = createHandleCircle("#FFFFFF", "#00E5FF")
        aimHandleView = createHandleCircle("#00E5FF", "#FFFFFF")

        attachHandleDrag(cueHandleView!!, cueHandleParams!!, handleSize)
        attachHandleDrag(aimHandleView!!, aimHandleParams!!, handleSize)
    }

    private fun createHandleCircle(fillHex: String, strokeHex: String): View {
        return object : View(this) {
            private val outerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(65, 0, 229, 255)
                style = Paint.Style.FILL
            }
            private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(strokeHex)
                style = Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
            }
            private val centerDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(fillHex)
                style = Paint.Style.FILL
            }

            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f
                val cy = height / 2f
                canvas.drawCircle(cx, cy, width / 2f - dp(2), outerRing)
                canvas.drawCircle(cx, cy, dp(11).toFloat(), stroke)
                canvas.drawCircle(cx, cy, dp(4).toFloat(), centerDot)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachHandleDrag(view: View, params: WindowManager.LayoutParams, size: Int) {
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (initX + (event.rawX - touchX).toInt()).coerceIn(0, (screenWidth - size).coerceAtLeast(0))
                    params.y = (initY + (event.rawY - touchY).toInt()).coerceIn(0, (screenHeight - size).coerceAtLeast(0))
                    try { windowManager?.updateViewLayout(view, params) } catch (_: Exception) {}
                    recalculateManualTrajectory()
                    true
                }
                else -> true
            }
        }
    }

    private fun setManualModeEnabled(enabled: Boolean) {
        manualMode = enabled
        val wm = windowManager ?: return
        if (enabled) {
            resetManualHandlesToCenter()
            try {
                wm.addView(cueHandleView, cueHandleParams)
                wm.addView(aimHandleView, aimHandleParams)
            } catch (_: Exception) {}
            recalculateManualTrajectory()
            statusTextView?.text = "Статус: Ручной прицел (60 FPS)"
        } else {
            try { wm.removeView(cueHandleView) } catch (_: Exception) {}
            try { wm.removeView(aimHandleView) } catch (_: Exception) {}
            statusTextView?.text = "Статус: Поиск стола..."
        }
    }

    private fun resetManualHandlesToCenter() {
        val half = dp(20)
        val table = vision.lastDetectedTable
        if (table != null && table.width > 100 && table.height > 80) {
            cueHandleParams?.x = (table.x + table.width / 3) - half
            cueHandleParams?.y = (table.y + table.height / 2) - half
            aimHandleParams?.x = (table.x + (table.width * 2) / 3) - half
            aimHandleParams?.y = (table.y + table.height / 3) - half
        } else {
            cueHandleParams?.x = (screenWidth / 3) - half
            cueHandleParams?.y = (screenHeight / 2) - half
            aimHandleParams?.x = ((screenWidth * 2) / 3) - half
            aimHandleParams?.y = (screenHeight / 2) - half
        }
        try {
            windowManager?.updateViewLayout(cueHandleView, cueHandleParams)
            windowManager?.updateViewLayout(aimHandleView, aimHandleParams)
        } catch (_: Exception) {}
    }

    private fun recalculateManualTrajectory() {
        val cParams = cueHandleParams ?: return
        val aParams = aimHandleParams ?: return
        val half = dp(20)

        val cueX = (cParams.x + half).toDouble()
        val cueY = (cParams.y + half).toDouble()
        val aimX = (aParams.x + half).toDouble()
        val aimY = (aParams.y + half).toDouble()

        val vx = aimX - cueX
        val vy = aimY - cueY
        val len = hypot(vx, vy).coerceAtLeast(0.001)
        val dx = vx / len
        val dy = vy / len

        val table = vision.lastDetectedTable ?: Rect(
            (screenWidth * 0.16).toInt(),
            (screenHeight * 0.18).toInt(),
            (screenWidth * 0.68).toInt(),
            (screenHeight * 0.68).toInt()
        )
        val ballRadius = (table.width / 38.0).coerceIn(10.0, 32.0)

        val physics = vision.tracePhysics(
            cueX, cueY, dx, dy,
            vision.lastDetectedBalls.filterNot { it.cue },
            table,
            ballRadius
        )
        overlayView?.setAnalysis(
            AnalysisResult(
                table = table,
                balls = emptyList(),
                trajectory = physics.first,
                ghost = physics.second,
                targetVector = physics.third,
                status = "Ручной режим"
            )
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingControls() {
        val bubbleSize = dp(48)
        bubbleParams = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(90)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        bubbleView = object : View(this) {
            private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E60F1622")
                style = Paint.Style.FILL
            }
            private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00E5FF")
                style = Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
            }
            private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00E5FF")
                style = Paint.Style.FILL
            }

            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f
                val cy = height / 2f
                val r = width / 2f - dp(2)
                canvas.drawCircle(cx, cy, r, bgPaint)
                canvas.drawCircle(cx, cy, r, strokePaint)
                val cross = dp(9).toFloat()
                canvas.drawLine(cx - cross, cy, cx - dp(3), cy, strokePaint)
                canvas.drawLine(cx + dp(3), cy, cx + cross, cy, strokePaint)
                canvas.drawLine(cx, cy - cross, cx, cy - dp(3), strokePaint)
                canvas.drawLine(cx, cy + dp(3), cx, cy + cross, strokePaint)
                canvas.drawCircle(cx, cy, dp(2).toFloat(), dotPaint)
            }
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        bubbleView?.setOnTouchListener { _, event ->
            val params = bubbleParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = (initialX + dx).coerceIn(0, (screenWidth - bubbleSize).coerceAtLeast(0))
                    params.y = (initialY + dy).coerceIn(0, (screenHeight - bubbleSize).coerceAtLeast(0))
                    windowManager?.updateViewLayout(bubbleView, params)
                    if (isMenuOpen && menuParams != null && menuView != null) {
                        updateMenuPosition()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleMiniMenu()
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(bubbleView, bubbleParams)
        buildMiniMenu()
    }

    private fun buildMiniMenu() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#F20E1420"))
                setStroke(dp(1), Color.parseColor("#00E5FF"))
                cornerRadius = dp(14).toFloat()
            }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val headerText = TextView(this).apply {
            text = "SMART HUD"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00E5FF"))
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8E9BAE"))
            setPadding(dp(8), dp(2), dp(4), dp(2))
            setOnClickListener { toggleMiniMenu() }
        }

        topRow.addView(headerText)
        topRow.addView(closeBtn)

        statusTextView = TextView(this).apply {
            text = "Статус: Авто-поиск"
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(2), 0, dp(10))
        }

        val modeBtn = createMiniToggle("Режим: АВТО (OpenCV)", true) { view, isAuto ->
            setManualModeEnabled(!isAuto)
            view.text = if (isAuto) "Режим: АВТО (OpenCV)" else "Режим: РУЧНОЙ (Маркеры)"
        }

        val toggleOverlayBtn = createMiniToggle("Оверлей: ВКЛ", true) { view, active ->
            overlayView?.overlayEnabled = active
            overlayView?.postInvalidate()
            view.text = if (active) "Оверлей: ВКЛ" else "Оверлей: ВЫКЛ"
        }

        val toggleBoxesBtn = createMiniToggle("Контуры стола: ВКЛ", true) { view, active ->
            overlayView?.showTableAndBalls = active
            overlayView?.postInvalidate()
            view.text = if (active) "Контуры стола: ВКЛ" else "Контуры стола: ВЫКЛ"
        }

        val bouncesBtn = createMiniChip("Отскоки от борта: 2") { view ->
            val next = when (vision.maxBounces) {
                1 -> 2
                2 -> 3
                else -> 1
            }
            vision.maxBounces = next
            view.text = "Отскоки от борта: $next"
            if (manualMode) recalculateManualTrajectory()
        }

        val widthBtn = createMiniChip("Толщина луча: Средняя") { view ->
            val current = overlayView?.lineThickness ?: 4.0f
            val (nextVal, label) = when {
                current < 3.5f -> 4.0f to "Средняя"
                current < 5.0f -> 6.0f to "Жирная"
                else -> 2.5f to "Тонкая"
            }
            overlayView?.lineThickness = nextVal
            overlayView?.postInvalidate()
            view.text = "Толщина луча: $label"
        }

        val exitBtn = TextView(this).apply {
            text = "ЗАКРЫТЬ ПРОГРАММУ"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FF6B8B"))
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A141D"))
                setStroke(dp(1), Color.parseColor("#6E263A"))
                cornerRadius = dp(8).toFloat()
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            layoutParams = params
            setOnClickListener { stopCapture() }
        }

        card.addView(topRow)
        card.addView(statusTextView)
        card.addView(modeBtn)
        card.addView(toggleOverlayBtn)
        card.addView(toggleBoxesBtn)
        card.addView(bouncesBtn)
        card.addView(widthBtn)
        card.addView(exitBtn)

        menuView = card
        menuParams = WindowManager.LayoutParams(
            dp(225),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun toggleMiniMenu() {
        val wm = windowManager ?: return
        val menu = menuView ?: return
        val params = menuParams ?: return
        if (isMenuOpen) {
            try { wm.removeView(menu) } catch (_: Exception) {}
            isMenuOpen = false
        } else {
            updateMenuPosition()
            try {
                wm.addView(menu, params)
                isMenuOpen = true
            } catch (_: Exception) {}
        }
    }

    private fun updateMenuPosition() {
        val bParams = bubbleParams ?: return
        val mParams = menuParams ?: return
        val menuW = dp(225)
        val bubbleW = dp(48)
        mParams.x = if (bParams.x + bubbleW + menuW + dp(12) < screenWidth) {
            bParams.x + bubbleW + dp(8)
        } else {
            (bParams.x - menuW - dp(8)).coerceAtLeast(dp(8))
        }
        mParams.y = bParams.y.coerceIn(dp(12), (screenHeight - dp(290)).coerceAtLeast(dp(12)))
        if (isMenuOpen) {
            try { windowManager?.updateViewLayout(menuView, mParams) } catch (_: Exception) {}
        }
    }

    private fun createMiniToggle(
        initialText: String,
        initialState: Boolean,
        onToggle: (TextView, Boolean) -> Unit
    ): TextView {
        var state = initialState
        return TextView(this).apply {
            text = initialText
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            layoutParams = params

            fun applyStyle() {
                if (state) {
                    setTextColor(Color.parseColor("#00E5FF"))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#132C3B"))
                        setStroke(dp(1), Color.parseColor("#0097A7"))
                        cornerRadius = dp(8).toFloat()
                    }
                } else {
                    setTextColor(Color.parseColor("#7A889B"))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#18202E"))
                        setStroke(dp(1), Color.parseColor("#2A364B"))
                        cornerRadius = dp(8).toFloat()
                    }
                }
            }

            applyStyle()
            setOnClickListener {
                state = !state
                applyStyle()
                onToggle(this, state)
            }
        }
    }

    private fun createMiniChip(initialText: String, onClick: (TextView) -> Unit): TextView {
        return TextView(this).apply {
            text = initialText
            textSize = 12f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#18202E"))
                setStroke(dp(1), Color.parseColor("#2A364B"))
                cornerRadius = dp(8).toFloat()
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            layoutParams = params
            setOnClickListener { onClick(this) }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PoolTrajectory HUD",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PoolTrajectory Smart HUD")
            .setContentText("Плавающий оверлей и анализ траекторий активны")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

    private fun stopCapture() {
        if (!stopping.compareAndSet(false, true)) return
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try { if (manualMode) windowManager?.removeView(cueHandleView) } catch (_: Exception) {}
        try { if (manualMode) windowManager?.removeView(aimHandleView) } catch (_: Exception) {}
        cueHandleView = null
        aimHandleView = null
        try { if (isMenuOpen) menuView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        menuView = null
        try { bubbleView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        bubbleView = null
        try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        overlayView = null
        try { workerThread?.quitSafely() } catch (_: Exception) {}
        workerThread = null
        workerHandler = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
