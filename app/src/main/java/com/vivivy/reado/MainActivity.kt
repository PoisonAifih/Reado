package com.vivivy.reado

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var cameraManager: CameraManager
    private lateinit var ocrManager: OcrManager

    private var ocrResults by mutableStateOf<List<ScanResult>>(emptyList())
    private var capturedImageSize by mutableStateOf(Size.Zero)

    private var currentTextIndex by mutableStateOf(-1)
    private var lastSpokenText: String = ""

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) speak("Aplikasi Siap") else speak("Izin kamera diperlukan")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)
        cameraManager = CameraManager(this)
        ocrManager = OcrManager()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            MaterialTheme {
                var showAccDialog by remember { mutableStateOf(!isAccessibilityServiceEnabled(this@MainActivity)) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.75f)
                            .clip(RoundedCornerShape(16.dp))
                    ) {
                        CameraPreview()
                        ResultOverlay()
                    }

                    GesturePad(modifier = Modifier.fillMaxSize())

                    if (showAccDialog) {
                        AlertDialog(
                            onDismissRequest = { showAccDialog = false },
                            title = { Text("Izin Pembaca Layar") },
                            text = { Text("Agar Reado dapat membaca teks di aplikasi lain, Anda harus mengaktifkan layanan Aksesibilitas Reado di pengaturan perangkat Anda.") },
                            confirmButton = {
                                Button(onClick = {
                                    showAccDialog = false
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    startActivity(intent)
                                }) {
                                    Text("Buka Pengaturan")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showAccDialog = false }) {
                                    Text("Nanti")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedComponentName = android.content.ComponentName(context, AccService::class.java)
        val enabledServicesSetting = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServicesSetting.contains(expectedComponentName.flattenToString())
    }

    private fun getBatteryPercentage(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun adjustVolume(raise: Boolean) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val direction = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun speak(text: String, saveInHistory: Boolean = true) {
        if (saveInHistory) {
            lastSpokenText = text
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    @Composable
    fun CameraPreview() {
        AndroidView(factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                cameraManager.startCamera {
                    val resolutionSelector = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build()

                    val preview = androidx.camera.core.Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .build()

                    preview.setSurfaceProvider(this.surfaceProvider)
                    val selector = androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
                    androidx.camera.lifecycle.ProcessCameraProvider.getInstance(ctx).get()
                        .bindToLifecycle(this@MainActivity, selector, preview, cameraManager.imageCapture)
                }
            }
        }, modifier = Modifier.fillMaxSize())
    }

    @Composable
    fun ResultOverlay() {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (capturedImageSize == Size.Zero || ocrResults.isEmpty()) return@Canvas

            val scaleX = size.width / capturedImageSize.width
            val scaleY = size.height / capturedImageSize.height
            val scale = max(scaleX, scaleY)

            val scaledWidth = capturedImageSize.width * scale
            val scaledHeight = capturedImageSize.height * scale
            val offsetX = (size.width - scaledWidth) / 2f
            val offsetY = (size.height - scaledHeight) / 2f

            ocrResults.forEachIndexed { index, result ->
                val left = result.rect.left * scale + offsetX
                val top = result.rect.top * scale + offsetY
                val right = result.rect.right * scale + offsetX
                val bottom = result.rect.bottom * scale + offsetY

                val boxColor = if (index == currentTextIndex) Color.Green else Color.Cyan
                val strokeWidth = if (index == currentTextIndex) 12f else 6f

                drawRect(
                    color = boxColor,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    style = Stroke(width = strokeWidth)
                )
            }
        }
    }

    @Composable
    fun GesturePad(modifier: Modifier = Modifier) {
        val gestureSequence = remember { mutableStateListOf<String>() }
        var currentSegmentX by remember { mutableStateOf(0f) }
        var currentSegmentY by remember { mutableStateOf(0f) }
        val segmentThreshold = 60f

        Box(
            modifier = modifier
                .background(Color.Transparent)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { speak("Jeda", saveInHistory = false) },
                        onDoubleTap = {
                            speak("Memindai", saveInHistory = false)
                            cameraManager.capturePhoto(
                                onImageReady = { inputImage, correctSize, imageProxy ->
                                    capturedImageSize = correctSize

                                    ocrManager.processImage(
                                        inputImage = inputImage,
                                        onResult = { rawResults ->

                                            // --- 1. CLUSTERING LOGIC ---
                                            // Group blocks that are close to each other
                                            val thresholdX = correctSize.width * 0.15f // 15% of width distance allowed
                                            val thresholdY = correctSize.height * 0.10f // 10% of height distance allowed
                                            val mergedResults = mutableListOf<ScanResult>()
                                            val used = BooleanArray(rawResults.size)

                                            for (i in rawResults.indices) {
                                                if (used[i]) continue
                                                val currentRect = RectF(rawResults[i].rect)
                                                var currentText = rawResults[i].text
                                                used[i] = true

                                                var mergedAny = true
                                                while (mergedAny) {
                                                    mergedAny = false
                                                    for (j in rawResults.indices) {
                                                        if (used[j]) continue
                                                        val otherRect = rawResults[j].rect

                                                        // Calculate shortest distance between the two rectangles
                                                        val dx = max(0f, max(currentRect.left - otherRect.right, otherRect.left - currentRect.right))
                                                        val dy = max(0f, max(currentRect.top - otherRect.bottom, otherRect.top - currentRect.bottom))

                                                        if (dx < thresholdX && dy < thresholdY) {
                                                            // Combine text top-to-bottom
                                                            if (otherRect.top < currentRect.top) {
                                                                currentText = rawResults[j].text + " " + currentText
                                                            } else {
                                                                currentText = currentText + " " + rawResults[j].text
                                                            }
                                                            currentRect.union(otherRect)
                                                            used[j] = true
                                                            mergedAny = true
                                                        }
                                                    }
                                                }
                                                mergedResults.add(ScanResult(currentText, currentRect))
                                            }

                                            // --- 2. FILTERING LOGIC ---
                                            val imgCenterX = correctSize.width / 2f
                                            val imgCenterY = correctSize.height / 2f
                                            val totalArea = correctSize.width * correctSize.height

                                            val bigResults = mergedResults.filter { result ->
                                                val area = result.rect.width() * result.rect.height()
                                                area > (totalArea * 0.005f) // Ignore tiny noise
                                            }

                                            val smartResults = mutableListOf<ScanResult>()

                                            // --- 3. QUADRANT SORTING LOGIC ---
                                            if (bigResults.isNotEmpty()) {
                                                // Find the absolute center item
                                                val centerItem = bigResults.minByOrNull {
                                                    val dx = it.rect.centerX() - imgCenterX
                                                    val dy = it.rect.centerY() - imgCenterY
                                                    (dx * dx) + (dy * dy)
                                                }!!

                                                smartResults.add(centerItem)

                                                val remaining = bigResults.toMutableList()
                                                remaining.remove(centerItem)

                                                // Sort the rest into the specific flow
                                                smartResults.addAll(remaining.filter { it.rect.centerY() > imgCenterY && it.rect.centerX() <= imgCenterX }
                                                    .sortedWith(compareBy({ it.rect.top }, { it.rect.left }))) // Bottom Left

                                                smartResults.addAll(remaining.filter { it.rect.centerY() > imgCenterY && it.rect.centerX() > imgCenterX }
                                                    .sortedWith(compareBy({ it.rect.top }, { it.rect.left }))) // Bottom Right

                                                smartResults.addAll(remaining.filter { it.rect.centerY() <= imgCenterY && it.rect.centerX() <= imgCenterX }
                                                    .sortedWith(compareBy({ it.rect.top }, { it.rect.left }))) // Top Left

                                                smartResults.addAll(remaining.filter { it.rect.centerY() <= imgCenterY && it.rect.centerX() > imgCenterX }
                                                    .sortedWith(compareBy({ it.rect.top }, { it.rect.left }))) // Top Right
                                            }

                                            ocrResults = smartResults

                                            if (smartResults.isEmpty()) {
                                                currentTextIndex = -1
                                                speak("Tidak ada teks utama ditemukan", saveInHistory = false)
                                            } else {
                                                currentTextIndex = 0
                                                speak(smartResults[0].text) // Instantly read Center text
                                            }
                                        },
                                        onComplete = { imageProxy.close() }
                                    )
                                },
                                onError = { speak("Kamera error", saveInHistory = false) }
                            )
                        },
                        onLongPress = {
                            val battery = getBatteryPercentage()
                            speak("Baterai $battery persen", saveInHistory = false)
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            gestureSequence.clear()
                            currentSegmentX = 0f
                            currentSegmentY = 0f
                        },
                        onDragEnd = {
                            val shapeDrawn = gestureSequence.joinToString("_")

                            when (shapeDrawn) {
                                // --- L SHAPE: REPEAT ---
                                "DOWN_RIGHT" -> {
                                    if (lastSpokenText.isNotEmpty()) {
                                        speak(lastSpokenText, saveInHistory = false)
                                    } else {
                                        speak("Belum ada teks untuk diulang", saveInHistory = false)
                                    }
                                }
                                // --- STRAIGHT RIGHT: NEXT TEXT ---
                                "RIGHT" -> {
                                    if (ocrResults.isNotEmpty()) {
                                        currentTextIndex = (currentTextIndex + 1) % ocrResults.size
                                        speak(ocrResults[currentTextIndex].text)
                                    }
                                }
                                // --- STRAIGHT LEFT: PREVIOUS TEXT ---
                                "LEFT" -> {
                                    if (ocrResults.isNotEmpty()) {
                                        // The math smoothly wraps backward to the end of the array (Top Right)
                                        currentTextIndex = (currentTextIndex - 1 + ocrResults.size) % ocrResults.size
                                        speak(ocrResults[currentTextIndex].text)
                                    }
                                }
                                // --- STRAIGHT UP: VOLUME UP ---
                                "UP" -> {
                                    adjustVolume(raise = true)
                                    speak("Volume naik", saveInHistory = false)
                                }
                                // --- STRAIGHT DOWN: VOLUME DOWN ---
                                "DOWN" -> {
                                    adjustVolume(raise = false)
                                    speak("Volume turun", saveInHistory = false)
                                }
                                else -> {
                                    Log.d("Gesture", "Unknown shape: $shapeDrawn")
                                }
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            currentSegmentX += dragAmount.x
                            currentSegmentY += dragAmount.y

                            val absX = abs(currentSegmentX)
                            val absY = abs(currentSegmentY)

                            if (absX > segmentThreshold || absY > segmentThreshold) {
                                val currentDirection = if (absX > absY) {
                                    if (currentSegmentX > 0) "RIGHT" else "LEFT"
                                } else {
                                    if (currentSegmentY > 0) "DOWN" else "UP"
                                }

                                if (gestureSequence.isEmpty() || gestureSequence.last() != currentDirection) {
                                    gestureSequence.add(currentDirection)
                                    currentSegmentX = 0f
                                    currentSegmentY = 0f
                                }
                            }
                        }
                    )
                }
        )
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val locale = Locale("id", "ID")
            val result = tts.setLanguage(locale)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.language = Locale.US
            } else {
                speak("Reado Siap")
            }
        }
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }
}