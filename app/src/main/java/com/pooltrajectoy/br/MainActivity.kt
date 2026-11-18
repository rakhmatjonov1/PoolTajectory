package com.pooltrajectory.br

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val captureRequestCode = 4101
    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
            setBackgroundColor(0xFF101820.toInt())
        }
        val title = TextView(this).apply {
            text = "PoolTrajectory BR"
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
        }
        val info = TextView(this).apply {
            text = "Анализ изображения экрана и учебная визуализация траекторий. " +
                "Перед запуском разрешите отображение поверх других приложений."
            textSize = 15f
            setTextColor(0xFFCFD8DC.toInt())
            setPadding(0, 18, 0, 28)
        }
        val overlayButton = Button(this).apply {
            text = "Разрешить оверлей"
            setOnClickListener { requestOverlayPermission() }
        }
        val startButton = Button(this).apply {
            text = "Начать захват экрана"
            setOnClickListener { requestCapture() }
        }
        val stopButton = Button(this).apply {
            text = "Остановить"
            setOnClickListener {
                val intent = Intent(this@MainActivity, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_STOP)
                startService(intent)
            }
        }
        layout.addView(title)
        layout.addView(info)
        layout.addView(overlayButton)
        layout.addView(startButton)
        layout.addView(stopButton)
        setContentView(layout)
    }

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

    @Deprecated("Activity result API kept minimal for compatibility with this sample")
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
