package com.vivivy.reado

import android.content.Context
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.compose.ui.geometry.Size

class CameraManager(private val context: Context) {
    var imageCapture: ImageCapture? = null

    fun startCamera(onReady: () -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(resolutionSelector)
                .build()
            onReady()
        }, ContextCompat.getMainExecutor(context))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun capturePhoto(onImageReady: (InputImage, Size, ImageProxy) -> Unit, onError: () -> Unit) {
        val capture = imageCapture ?: return
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val rotation = imageProxy.imageInfo.rotationDegrees
                        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

                        val isPortrait = rotation == 90 || rotation == 270
                        val correctWidth = if (isPortrait) imageProxy.height else imageProxy.width
                        val correctHeight = if (isPortrait) imageProxy.width else imageProxy.height

                        onImageReady(inputImage, Size(correctWidth.toFloat(), correctHeight.toFloat()), imageProxy)
                    } else {
                        imageProxy.close()
                    }
                }
                override fun onError(exc: ImageCaptureException) { onError() }
            }
        )
    }
}