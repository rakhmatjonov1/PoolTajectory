package com.pooltrajectory.br

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val captureRequestCode = 4101
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var overlayStatusBadge: TextView
    private lateinit var crashLogText: TextView
    private lateinit var crashCard: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Глобальный перехватчик любых крашей для диагностики
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            getSharedPreferences("hud_logs", Context.MODE_PRIVATE)
                .edit()
                .putString("last_crash", throwable.stackTraceToString())
                .commit()
            defaultHandler?.uncaughtException(thread, throwable)
        }

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F17"))
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(24))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "POOL TRAJECTORY"
            textSize = 23f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Color.WHITE)
            letterSpacing = 0.05f
        }

        val versionTag = TextView(this).apply {
            text = "SMART HUD"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00E5FF"))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = createRoundBox("#132A3A", "#00E5FF", 1, 8)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
            layoutParams = params
        }

        headerRow.addView(title)
        headerRow.addView(versionTag)

        val subtitle = TextView(this).apply {
            text = "Умный тактический оверлей с авто-распознаванием стола и ручными маркерами прицела."
            textSize = 14f
            setTextColor(Color.parseColor("#8E9BAE"))
            setPadding(0, dp(8), 0, dp(22))
            setLineSpacing(dp(3).toFloat(), 1f)
        }

        // Карточка лога сбоя (появляется только если был краш)
        crashCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = createRoundBox("#2A1219", "#FF5252", 1, 14)
            visibility = View.GONE
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
            layoutParams = params
        }

        val crashTitle = TextView(this).apply {
            text = "Обнаружен лог последнего сбоя:"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FF8A80"))
        }

        crashLogText = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#FFCDD2"))
            setPadding(0, dp(6), 0, dp(10))
            maxLines = 8
        }

        val clearLogBtn = createActionButton(
            text = "ОЧИСТИТЬ ЛОГ",
            bgHex = "#3D1822",
            strokeHex = "#7A293D",
            textHex = "#FFCDD2"
        ) {
            getSharedPreferences("hud_logs", Context.MODE_PRIVATE).edit().remove("last_crash").apply()
            crashCard.visibility = View.GONE
        }

        crashCard.addView(crashTitle)
        crashCard.addView(crashLogText)
        crashCard.addView(clearLogBtn)

        // Карточка разрешений
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = createRoundBox("#131A26", "#1F2B3E", 1, 16)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
            layoutParams = params
        }

        val statusHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val statusLabel = TextView(this).apply {
            text = "Отображение поверх окон"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        overlayStatusBadge = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }

        statusHeader.addView(statusLabel)
        statusHeader.addView(overlayStatusBadge)

        val statusDesc = TextView(this).apply {
            text = "Требуется для плавающей иконки меню и отрисовки линий прицеливания поверх игры."
            textSize = 13f
            setTextColor(Color.parseColor("#7A889B"))
            setPadding(0, dp(8), 0, dp(14))
        }

        val permBtn = createActionButton(
            text = "НАСТРОИТЬ РАЗРЕШЕНИЕ",
            bgHex = "#1B2638",
            strokeHex = "#2E405C",
            textHex = "#E2E8F0"
        ) {
            requestOverlayPermission()
        }

        statusCard.addView(statusHeader)
        statusCard.addView(statusDesc)
        statusCard.addView(permBtn)

        // Карточка запуска
        val controlCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = createRoundBox("#131A26", "#1F2B3E", 1, 16)
        }

        val controlTitle = TextView(this).apply {
            text = "Запуск оверлея"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(6))
        }

        val controlDesc = TextView(this).apply {
            text = "Нажмите на круглый значок прицела в углу экрана, чтобы открыть мини-окно управления (режимы Авто / Ручной, отскоки и толщина луча)."
            textSize = 13f
            setTextColor(Color.parseColor("#7A889B"))
            setPadding(0, 0, 0, dp(16))
        }

        val startBtn = createActionButton(
            text = "ЗАПУСТИТЬ ПЛАВАЮЩИЙ HUD",
            bgHex = "#00B8D4",
            strokeHex = "#00E5FF",
            textHex = "#061018"
        ) {
            requestCapture()
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(12))
        }

        val stopBtn = createActionButton(
            text = "ОСТАНОВИТЬ СЕРВИС",
            bgHex = "#26151B",
            strokeHex = "#5C2333",
            textHex = "#FF6B8B"
        ) {
            val intent = Intent(this@MainActivity, CaptureService::class.java)
                .setAction(CaptureService.ACTION_STOP)
            startService(intent)
            Toast.makeText(this@MainActivity, "Сервис остановлен", Toast.LENGTH_SHORT).show()
        }

        controlCard.addView(controlTitle)
        controlCard.addView(controlDesc)
        controlCard.addView(startBtn)
        controlCard.addView(spacer)
        controlCard.addView(stopBtn)

        container.addView(headerRow)
        container.addView(subtitle)
        container.addView(crashCard)
        container.addView(statusCard)
        container.addView(controlCard)

        rootScroll.addView(container)
        setContentView(rootScroll)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionBadge()
        val lastCrash = getSharedPreferences("hud_logs", Context.MODE_PRIVATE).getString("last_crash", null)
        if (!lastCrash.isNullOrBlank()) {
            crashLogText.text = lastCrash.take(600)
            crashCard.visibility = View.VISIBLE
        }
    }

    private fun updatePermissionBadge() {
        val granted = Settings.canDrawOverlays(this)
        if (granted) {
            overlayStatusBadge.text = "АКТИВНО"
            overlayStatusBadge.setTextColor(Color.parseColor("#00E676"))
            overlayStatusBadge.background = createRoundBox("#102E21", "#00E676", 1, 8)
        } else {
            overlayStatusBadge.text = "ТРЕБУЕТСЯ"
            overlayStatusBadge.setTextColor(Color.parseColor("#FFAB00"))
            overlayStatusBadge.background = createRoundBox("#33240B", "#FFAB00", 1, 8)
        }
    }

    private fun createActionButton(
        text: String,
        bgHex: String,
        strokeHex: String,
        textHex: String,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = 0.04f
            setTextColor(Color.parseColor(textHex))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = createRoundBox(bgHex, strokeHex, 1, 12)
            setOnClickListener { onClick() }
        }
    }

    private fun createRoundBox(fillHex: String, strokeHex: String, strokeWidthDp: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor(fillHex))
            setStroke(dp(strokeWidthDp), Color.parseColor(strokeHex))
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Разрешение уже выдано", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun requestCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Сначала разрешите отображение поверх приложений", Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4102)
        }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), captureRequestCode)
    }

    @Deprecated("Activity result API kept minimal for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequestCode) {
            if (resultCode != Activity.RESULT_OK || data == null) {
                Toast.makeText(this, "Захват экрана не разрешен", Toast.LENGTH_SHORT).show()
                return
            }
            val serviceIntent = Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
            else startService(serviceIntent)
            moveTaskToBack(true)
        }
    }
}
