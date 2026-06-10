package com.vivivy.reado

import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OcrManager {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun processImage(inputImage: InputImage, onResult: (List<ScanResult>) -> Unit, onComplete: () -> Unit) {
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val results = mutableListOf<ScanResult>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val rect = line.boundingBox ?: continue
                        val text = line.text.trim()
                        if (text.isEmpty()) continue
                        val fontSize = rect.height().toFloat()
                        results.add(ScanResult(text, RectF(rect), fontSize))
                    }
                }
                if (results.isEmpty()) {
                    visionText.textBlocks.mapNotNull { block ->
                        val rect = block.boundingBox
                        if (rect != null) {
                            val lineCount = block.lines.size.coerceAtLeast(1)
                            val fontSize = rect.height().toFloat() / lineCount
                            ScanResult(block.text, RectF(rect), fontSize)
                        } else null
                    }.also { results.addAll(it) }
                }
                onResult(results)
            }
            .addOnFailureListener { e ->
                Log.e("OCR", "Recognition failed", e)
                onResult(emptyList())
            }
            .addOnCompleteListener {
                onComplete()
            }
    }
}