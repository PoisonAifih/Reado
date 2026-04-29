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
                val results = visionText.textBlocks.mapNotNull { block ->
                    val rect = block.boundingBox
                    if (rect != null) ScanResult(block.text, RectF(rect)) else null
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