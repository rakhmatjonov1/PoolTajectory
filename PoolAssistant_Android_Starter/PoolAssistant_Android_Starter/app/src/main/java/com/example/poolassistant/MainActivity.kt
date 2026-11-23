package com.example.poolassistant

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity
import android.graphics.Typeface

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "POOL ASSISTANT"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val status = TextView(this).apply {
            text = "Версия 0.1.0\n\nКаркас приложения создан.\nСледующий этап: калибровка стола и тестовое распознавание шаров."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 48, 24, 48)
            addView(title)
            addView(status)
        }
        setContentView(layout)
    }
}
