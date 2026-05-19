package com.vivivy.reado

import android.graphics.RectF

data class ScanResult(
    val text: String,
    val rect: RectF,
    val estimatedFontSize: Float = 0f
)