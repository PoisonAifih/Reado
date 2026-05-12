package com.vivivy.reado

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AccService : AccessibilityService(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var buttonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        tts = TextToSpeech(this, this)
        Log.d("ReadoService", "Service Connected & TTS Initializing")

        buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) {
                Log.d("ReadoService", "Tombol Aksesibilitas Ditekan!")
                readScreenText()
            }
        }

        buttonCallback?.let {
            accessibilityButtonController.registerAccessibilityButtonCallback(it)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            TtsLanguageHelper.applyLocaleToTts(tts, TtsLanguageHelper.uiLocale)
            isTtsReady = true
            speakUi("Layanan pembaca layar Reado aktif")
        }
    }

    private fun speakUi(text: String) {
        if (!isTtsReady) return
        mainHandler.post {
            TtsLanguageHelper.applyLocaleToTts(tts, TtsLanguageHelper.uiLocale)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    private fun speakScreenContent(text: String) {
        if (!isTtsReady) return
        TtsLanguageHelper.identifyLocaleForText(text) { loc ->
            mainHandler.post {
                TtsLanguageHelper.applyLocaleToTts(tts, loc)
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun readScreenText() {
        val rootNode = rootInActiveWindow ?: return
        val screenText = StringBuilder()

        extractTextFromNode(rootNode, screenText)

        val finalResult = screenText.toString()
        Log.d("ReadoService", "Screen Text Found: $finalResult")

        if (finalResult.isNotBlank()) {
            speakScreenContent(finalResult)
        } else {
            speakUi("Tidak ada teks di layar")
        }
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return

        if (!node.text.isNullOrBlank()) {
            sb.append(node.text).append(". ")
        } else if (!node.contentDescription.isNullOrBlank()) {
            sb.append(node.contentDescription).append(". ")
        }

        for (i in 0 until node.childCount) {
            extractTextFromNode(node.getChild(i), sb)
        }
    }

    override fun onDestroy() {
        buttonCallback?.let {
            accessibilityButtonController.unregisterAccessibilityButtonCallback(it)
        }
        isTtsReady = false
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }
}