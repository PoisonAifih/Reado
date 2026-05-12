package com.vivivy.reado

import android.speech.tts.TextToSpeech
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import java.util.Locale

object TtsLanguageHelper {

    val uiLocale: Locale = Locale("id", "ID")

    private val languageIdentifier: LanguageIdentifier by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LanguageIdentification.getClient()
    }

    fun identifyLocaleForText(text: String, onResult: (Locale) -> Unit) {
        val trimmed = text.trim()
        if (trimmed.length < 4) {
            onResult(uiLocale)
            return
        }
        languageIdentifier.identifyLanguage(trimmed)
            .addOnSuccessListener { code: String ->
                onResult(localeForLanguageCode(code))
            }
            .addOnFailureListener {
                onResult(uiLocale)
            }
    }

    fun localeForLanguageCode(code: String?): Locale {
        if (code.isNullOrBlank() || code == "und") return uiLocale
        return when (code) {
            "in", "id" -> uiLocale
            "en" -> Locale.US
            else -> {
                try {
                    Locale.forLanguageTag(code.replace('_', '-'))
                } catch (_: Exception) {
                    uiLocale
                }
            }
        }
    }

    fun applyLocaleToTts(tts: TextToSpeech, preferred: Locale): Locale {
        var result = tts.setLanguage(preferred)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (preferred != uiLocale) {
                result = tts.setLanguage(uiLocale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.language = Locale.US
                    return Locale.US
                }
                return uiLocale
            }
            tts.language = Locale.US
            return Locale.US
        }
        return preferred
    }
}
