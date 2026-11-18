package com.pooltrajectory.br

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

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

    private var statusTextView: TextView? = null
    private var isMenuOpen = false

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

    private fun startCapture(intent: Intent) {
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())

            if (!OpenCVLoader.initLocal()) {
                stopSelf()
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

            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi

            workerThread = HandlerThread("PoolTrajectoryCV").also { it.start() }
            workerHandler = Handler(workerThread!!.looper)

            setupOverlay()
            setupFloatingControls()
            setupProjection()
        } catch (e: SecurityException) {
            stopCapture()
        } catch (e: Exception) {
            stopCapture()
        }
    }

    private fun setupProjection() {
        val width = screenWidth.coerceAtLeast(1)
        val height = screenHeight.coerceAtLeast(1)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            if (now - lastFrameTime < 90 || !processing.compareAndSet(false, true)) {
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
                            overlayView?.setAnalysis(result)
                            if (isMenuOpen) {
                                statusTextView?.text = "Статус: ${result.status}"
                            }
                        }
                        mat.release()
                        bitmap.recycle()
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { image?.close() } catch (_: Exception) {}
                processing.set(false)
            }
        }, workerHandler)

        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopCapture() }
        }, workerHandler)

        virtualDisplay = projection?.createVirtualDisplay(
            "PoolTrajectoryDisplay",
            width,
            height,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            workerHandler
        )
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
        } catch (_: Exception) {
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
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
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
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager?.addView(overlayView, params)
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
            y = dp(140)
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
                setColor(Color.parseColor("#F00E1420"))
                setStroke(dp(1), Color.parseColor("#00E5FF"))
                cornerRadius = dp(14).toFloat()
            }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val headerText = TextView(this).apply {
            text = "TRAJECTORY HUD"
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
            text = "Статус: Ожидание"
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(2), 0, dp(10))
        }

        val toggleOverlayBtn = createMiniToggle("Оверлей: ВКЛ", true) { view, active ->
            overlayView?.overlayEnabled = active
            overlayView?.postInvalidate()
            view.text = if (active) "Оверлей: ВКЛ" else "Оверлей: ВЫКЛ"
        }

        val toggleBoxesBtn = createMiniToggle("Контуры шаров: ВКЛ", true) { view, active ->
            overlayView?.showTableAndBalls = active
            overlayView?.postInvalidate()
            view.text = if (active) "Контуры шаров: ВКЛ" else "Контуры шаров: ВЫКЛ"
        }

        val bouncesBtn = createMiniChip("Отскоки от борта: 2") { view ->
            val next = when (vision.maxBounces) {
                1 -> 2
                2 -> 3
                else -> 1
            }
            vision.maxBounces = next
            view.text = "Отскоки от борта: $next"
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
        card.addView(toggleOverlayBtn)
        card.addView(toggleBoxesBtn)
        card.addView(bouncesBtn)
        card.addView(widthBtn)
        card.addView(exitBtn)

        menuView = card
        menuParams = WindowManager.LayoutParams(
            dp(220),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
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
        val menuW = dp(220)
        val bubbleW = dp(48)
        mParams.x = if (bParams.x + bubbleW + menuW + dp(12) < screenWidth) {
            bParams.x + bubbleW + dp(8)
        } else {
            (bParams.x - menuW - dp(8)).coerceAtLeast(dp(8))
        }
        mParams.y = bParams.y.coerceIn(dp(16), (screenHeight - dp(260)).coerceAtLeast(dp(16)))
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
            .setContentTitle("PoolTrajectory HUD")
            .setContentText("Плавающая панель управления активна")
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
        try {
            if (isMenuOpen) menuView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        menuView = null
        try {
            bubbleView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        bubbleView = null
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
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
