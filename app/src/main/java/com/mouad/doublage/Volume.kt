package com.mouad.doublage

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.speech.tts.TextToSpeech

/** Volume de la voix traduite : de 20 % à 300 % (au-delà de 100 %, amplification). */
class Volume(context: Context) {
    private val session = context.getSystemService(AudioManager::class.java).generateAudioSessionId()
    private val ampli: LoudnessEnhancer? = try {
        LoudnessEnhancer(session).apply { enabled = true }
    } catch (e: Exception) { null }

    /** Paramètres à passer à tts.speak() pour un volume en pourcentage. */
    fun params(pourcent: Int): Bundle {
        try { ampli?.setTargetGain(Analyse.gainMb(pourcent)) } catch (_: Exception) {}
        return Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, session)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, Analyse.volumeTts(pourcent))
        }
    }

    fun liberer() { try { ampli?.release() } catch (_: Exception) {} }
}
