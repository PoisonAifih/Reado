package com.vivivy.reado

import android.content.pm.ActivityInfo
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import kotlin.math.abs
import kotlin.math.max

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    companion object {
        const val EXTRA_WATCH_COMMAND = "WATCH_COMMAND"

        private const val STATE_SCAN_MODE = "scan_mode"
        private const val STATE_MODE_ANNOUNCEMENT = "mode_announcement"

        private const val HELP_READ_ALOUD =
            "Panduan gestur Reado. Ketuk satu kali untuk jeda bacaan. " +
                "Ketuk dua kali untuk memindai teks di depan kamera dan membaca semua teks sekaligus dari atas ke bawah, mendahulukan judul dan teks berukuran besar. " +
                "Tekan lama untuk mendengar persentase baterai. " +
                "Usap lurus ke atas untuk menaikkan volume. " +
                "Usap lurus ke bawah untuk menurunkan volume. " +
                "Gambar huruf L: usap ke bawah lalu ke kanan tanpa mengangkat jari untuk mengulangi teks terakhir yang dibaca. " +
                "Tombol bantuan di pojok kanan atas menampilkan daftar gestur ini."

        private val HELP_BODY =
            """
            Ketuk 1x — Jeda bacaan
            Ketuk 2x — Pindai & baca semua teks sekaligus (judul besar dibaca duluan, lalu atas ke bawah)
            Mode Buku (kiri atas) — Landscape dua halaman buku, baca kiri lalu kanan
            Tekan lama — Baca persen baterai
            Usap atas — Volume naik
            Usap bawah — Volume turun
            Huruf L (bawah lalu kanan) — Ulangi teks terakhir
            """.trimIndent()
    }

    private lateinit var tts: TextToSpeech
    private lateinit var cameraManager: CameraManager
    private lateinit var ocrManager: OcrManager

    private var ocrResults by mutableStateOf<List<ScanResult>>(emptyList())
    private var capturedImageSize by mutableStateOf(Size.Zero)
    private var scanMode by mutableStateOf(ScanMode.PORTRAIT)
    private var lastSpokenText: String = ""
    private var pendingWatchCommand: String? = null
    private var pendingModeAnnouncement: String? = null
    private var shouldSpeakWelcome = true

    @Volatile
    private var ttsReady = false

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) speakUi("Aplikasi Siap") else speakUi("Izin kamera diperlukan")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)
        cameraManager = CameraManager(this)
        ocrManager = OcrManager()

        pendingWatchCommand = intent.getStringExtra(EXTRA_WATCH_COMMAND)

        savedInstanceState?.let { state ->
            scanMode = ScanMode.valueOf(state.getString(STATE_SCAN_MODE, ScanMode.PORTRAIT.name))
            pendingModeAnnouncement = state.getString(STATE_MODE_ANNOUNCEMENT)
            shouldSpeakWelcome = false
            applyOrientationForMode(scanMode)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        WearSyncHelper.openWatchApp(this)

        setContent {
            MaterialTheme {
                var showAccDialog by remember { mutableStateOf(!isAccessibilityServiceEnabled(this@MainActivity)) }
                var showHelp by remember { mutableStateOf(false) }

                LaunchedEffect(showHelp) {
                    if (showHelp) {
                        speakUi(HELP_READ_ALOUD, saveInHistory = false)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    val previewModifier = if (scanMode == ScanMode.BOOK) {
                        Modifier
                            .fillMaxWidth(0.95f)
                            .fillMaxHeight(0.88f)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.75f)
                    }

                    Box(
                        modifier = previewModifier
                            .clip(RoundedCornerShape(16.dp))
                    ) {
                        CameraPreview()
                        ResultOverlay(scanMode)
                    }

                    GesturePad(modifier = Modifier.fillMaxSize())

                    TextButton(
                        onClick = { toggleReadingMode() },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = if (scanMode == ScanMode.BOOK) "Mode Buku ✓" else "Mode Buku",
                            color = if (scanMode == ScanMode.BOOK) Color.Cyan else Color.White
                        )
                    }

                    TextButton(
                        onClick = { showHelp = true },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Text("Bantuan", color = Color.White)
                    }

                    if (showHelp) {
                        val scroll = rememberScrollState()
                        AlertDialog(
                            onDismissRequest = { showHelp = false },
                            title = { Text("Gestur Reado") },
                            text = {
                                Text(
                                    text = HELP_BODY,
                                    modifier = Modifier.verticalScroll(scroll)
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { showHelp = false }) {
                                    Text("Tutup")
                                }
                            }
                        )
                    }

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_WATCH_COMMAND)?.let { handleWatchCommand(it) }
    }

    override fun onResume() {
        super.onResume()
        WearSyncHelper.openWatchApp(this)
    }

    private fun handleWatchCommand(command: String) {
        when (command) {
            "SCAN"        -> performScan()
            "PAUSE"       -> speakUi("Jeda", saveInHistory = false)
            "VOLUME_UP"   -> { adjustVolume(raise = true);  speakUi("Volume naik",  saveInHistory = false) }
            "VOLUME_DOWN" -> { adjustVolume(raise = false); speakUi("Volume turun", saveInHistory = false) }
            "BATTERY"     -> speakUi("Baterai ${getBatteryPercentage()} persen", saveInHistory = false)
            "REPEAT"      -> {
                if (lastSpokenText.isNotEmpty()) speakDetectedText(lastSpokenText, saveInHistory = false)
                else speakUi("Belum ada teks untuk diulang", saveInHistory = false)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SCAN_MODE, scanMode.name)
        pendingModeAnnouncement?.let { outState.putString(STATE_MODE_ANNOUNCEMENT, it) }
    }

    private fun toggleReadingMode() {
        val enableBook = scanMode != ScanMode.BOOK
        scanMode = if (enableBook) ScanMode.BOOK else ScanMode.PORTRAIT
        applyOrientationForMode(scanMode)
        ocrResults = emptyList()
        capturedImageSize = Size.Zero

        val message = if (enableBook) "Mode buku aktif" else "Mode portrait aktif"
        pendingModeAnnouncement = message
        shouldSpeakWelcome = false
        announceModeChange()
    }

    private fun applyOrientationForMode(mode: ScanMode) {
        requestedOrientation = if (mode == ScanMode.BOOK) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun announceModeChange() {
        val message = pendingModeAnnouncement ?: return
        if (!ttsReady) return
        speakUi(message, saveInHistory = false)
        pendingModeAnnouncement = null
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

    private fun speakUi(text: String, saveInHistory: Boolean = true) {
        if (!ttsReady) return
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            TtsLanguageHelper.applyLocaleToTts(tts, TtsLanguageHelper.uiLocale)
            if (saveInHistory) {
                lastSpokenText = text
            }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    private fun speakDetectedText(text: String, saveInHistory: Boolean = true) {
        if (!ttsReady) return
        if (saveInHistory) {
            lastSpokenText = text
        }
        TtsLanguageHelper.identifyLocaleForText(text) { loc ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                TtsLanguageHelper.applyLocaleToTts(tts, loc)
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
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
    fun ResultOverlay(mode: ScanMode) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val zones = ScanModeConfig.zonesFor(mode)
            val cornerLen = 40f
            val guideColor = Color.White.copy(alpha = 0.85f)
            val strokeW = 5f

            zones.forEach { zone ->
                val zLeft = zone.left * size.width
                val zTop = zone.top * size.height
                val zRight = zone.right * size.width
                val zBottom = zone.bottom * size.height

                if (mode == ScanMode.BOOK) {
                    drawRect(
                        color = guideColor.copy(alpha = 0.55f),
                        topLeft = Offset(zLeft, zTop),
                        size = Size(zRight - zLeft, zBottom - zTop),
                        style = Stroke(width = strokeW)
                    )
                } else {
                    drawLine(guideColor, Offset(zLeft, zTop + cornerLen), Offset(zLeft, zTop), strokeWidth = strokeW)
                    drawLine(guideColor, Offset(zLeft, zTop), Offset(zLeft + cornerLen, zTop), strokeWidth = strokeW)
                    drawLine(guideColor, Offset(zRight - cornerLen, zTop), Offset(zRight, zTop), strokeWidth = strokeW)
                    drawLine(guideColor, Offset(zRight, zTop), Offset(zRight, zTop + cornerLen), strokeWidth = strokeW)
                    drawLine(guideColor, Offset(zLeft, zBottom - cornerLen), Offset(zLeft, zBottom), strokeWidth = strokeW)
                    drawLine(guideColor, Offset(zLeft, zBottom), Offset(zLeft + cornerLen, zBottom), strokeWidth = strokeW)
                    drawLine(guideColor, Offset(zRight - cornerLen, zBottom), Offset(zRight, zBottom), strokeWidth = strokeW)
                    drawLine(guideColor, Offset(zRight, zBottom), Offset(zRight, zBottom - cornerLen), strokeWidth = strokeW)
                }
            }

            if (mode == ScanMode.BOOK) {
                val midX = size.width / 2f
                drawLine(
                    color = guideColor.copy(alpha = 0.4f),
                    start = Offset(midX, size.height * 0.10f),
                    end = Offset(midX, size.height * 0.90f),
                    strokeWidth = 2f
                )
            }

            if (capturedImageSize == Size.Zero || ocrResults.isEmpty()) return@Canvas

            val scaleX = size.width / capturedImageSize.width
            val scaleY = size.height / capturedImageSize.height
            val scale = max(scaleX, scaleY)

            val scaledWidth = capturedImageSize.width * scale
            val scaledHeight = capturedImageSize.height * scale
            val offsetX = (size.width - scaledWidth) / 2f
            val offsetY = (size.height - scaledHeight) / 2f

            ocrResults.forEach { result ->
                val left = result.rect.left * scale + offsetX
                val top = result.rect.top * scale + offsetY
                val right = result.rect.right * scale + offsetX
                val bottom = result.rect.bottom * scale + offsetY

                drawRect(
                    color = Color.Cyan,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    style = Stroke(width = 6f)
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
                        onTap = { speakUi("Jeda", saveInHistory = false) },
                        onDoubleTap = { performScan() },
                        onLongPress = {
                            val battery = getBatteryPercentage()
                            speakUi("Baterai $battery persen", saveInHistory = false)
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
                                        speakDetectedText(lastSpokenText, saveInHistory = false)
                                    } else {
                                        speakUi("Belum ada teks untuk diulang", saveInHistory = false)
                                    }
                                }
                                // --- STRAIGHT UP: VOLUME UP ---
                                "UP" -> {
                                    adjustVolume(raise = true)
                                    speakUi("Volume naik", saveInHistory = false)
                                }
                                // --- STRAIGHT DOWN: VOLUME DOWN ---
                                "DOWN" -> {
                                    adjustVolume(raise = false)
                                    speakUi("Volume turun", saveInHistory = false)
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

    private fun performScan() {
        if (cameraManager.imageCapture == null) {
            speakUi("Kamera belum siap, coba lagi", saveInHistory = false)
            return
        }
        speakUi("Memindai", saveInHistory = false)
        cameraManager.capturePhoto(
            onImageReady = { inputImage, correctSize, imageProxy ->
                capturedImageSize = correctSize

                ocrManager.processImage(
                    inputImage = inputImage,
                    onResult = { rawResults ->
                        if (rawResults.isEmpty()) {
                            ocrResults = emptyList()
                            speakUi("Tidak ada teks terdeteksi, coba dekatkan kamera", saveInHistory = false)
                            return@processImage
                        }

                        val smartResults = ScanProcessor.process(
                            rawResults = rawResults,
                            imgWidth = correctSize.width,
                            imgHeight = correctSize.height,
                            mode = scanMode
                        )

                        ocrResults = smartResults

                        if (smartResults.isEmpty()) {
                            speakUi("Teks terlalu kecil atau di luar area scan, sesuaikan posisi", saveInHistory = false)
                        } else {
                            val fullText = smartResults.joinToString(" ") { it.text.trim() }
                            speakDetectedText(fullText)
                        }
                    },
                    onComplete = { imageProxy.close() }
                )
            },
            onError = { speakUi("Kamera error", saveInHistory = false) }
        )
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            TtsLanguageHelper.applyLocaleToTts(tts, TtsLanguageHelper.uiLocale)
            ttsReady = true
            when {
                pendingModeAnnouncement != null -> announceModeChange()
                shouldSpeakWelcome -> speakUi("Reado Siap")
            }
            pendingWatchCommand?.let { cmd ->
                pendingWatchCommand = null
                handleWatchCommand(cmd)
            }
        }
    }

    override fun onDestroy() {
        ttsReady = false
        tts.shutdown()
        super.onDestroy()
    }
}