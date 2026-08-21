package fr.velo.cadence.navigation

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Annonces vocales des consignes de navigation. A velo, l'ecran n'est pas
 * toujours lisible ni regardable en securite : la voix reste le canal le plus
 * sur pour les indications de direction.
 */
class VoiceGuide(context: Context) {

    private var engine: TextToSpeech? = null
    private var ready = false

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = engine?.setLanguage(Locale.FRANCE)
                ready = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    fun speak(text: String) {
        if (!ready) return
        engine?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }
}
