package com.vivivy.reado

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.android.gms.wearable.Wearable
import kotlin.math.abs

class WearMainActivity : ComponentActivity() {

    companion object {
        private const val COMMAND_PATH = "/reado/gesture"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearApp() }
    }

    @Composable
    fun WearApp() {
        var statusText by remember { mutableStateOf("Siap") }
        var phoneConnected by remember { mutableStateOf(false) }
        val gestureSequence = remember { mutableStateListOf<String>() }
        var segmentX by remember { mutableStateOf(0f) }
        var segmentY by remember { mutableStateOf(0f) }
        val threshold = 70f

        LaunchedEffect(Unit) {
            Wearable.getNodeClient(this@WearMainActivity).connectedNodes
                .addOnSuccessListener { nodes ->
                    phoneConnected = nodes.isNotEmpty()
                    Log.d("WearReado", "HP terhubung: ${nodes.size}")
                }
        }

        MaterialTheme {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                statusText = "Jeda"
                                sendCommand("PAUSE")
                            },
                            onDoubleTap = {
                                statusText = "Memindai..."
                                sendCommand("SCAN")
                            },
                            onLongPress = {
                                statusText = "Baterai"
                                sendCommand("BATTERY")
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                gestureSequence.clear()
                                segmentX = 0f
                                segmentY = 0f
                            },
                            onDragEnd = {
                                when (gestureSequence.joinToString("_")) {
                                    "DOWN_RIGHT" -> {
                                        statusText = "Ulangi"
                                        sendCommand("REPEAT")
                                    }
                                    "UP" -> {
                                        statusText = "Volume +"
                                        sendCommand("VOLUME_UP")
                                    }
                                    "DOWN" -> {
                                        statusText = "Volume -"
                                        sendCommand("VOLUME_DOWN")
                                    }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                segmentX += dragAmount.x
                                segmentY += dragAmount.y
                                val absX = abs(segmentX)
                                val absY = abs(segmentY)
                                if (absX > threshold || absY > threshold) {
                                    val dir = if (absX > absY) {
                                        if (segmentX > 0) "RIGHT" else "LEFT"
                                    } else {
                                        if (segmentY > 0) "DOWN" else "UP"
                                    }
                                    if (gestureSequence.isEmpty() || gestureSequence.last() != dir) {
                                        gestureSequence.add(dir)
                                        segmentX = 0f
                                        segmentY = 0f
                                    }
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "READO",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = statusText,
                        color = Color.Cyan,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (phoneConnected) "HP terhubung" else "HP tidak ditemukan",
                        color = if (phoneConnected) Color(0xFF4CAF50) else Color(0xFFFF9800),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "2x ketuk = Pindai\n" +
                               "1x ketuk = Jeda\n" +
                               "Atas/Bawah = Volume\n" +
                               "L = Ulangi",
                        color = Color.Gray,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }

    private fun sendCommand(command: String) {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    Log.w("WearReado", "Tidak ada HP yang terhubung")
                    return@addOnSuccessListener
                }
                nodes.forEach { node ->
                    Wearable.getMessageClient(this).sendMessage(
                        node.id,
                        COMMAND_PATH,
                        command.toByteArray()
                    ).addOnFailureListener { e ->
                        Log.e("WearReado", "Gagal kirim '$command': ${e.message}")
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("WearReado", "Gagal ambil node: ${e.message}")
            }
    }
}
