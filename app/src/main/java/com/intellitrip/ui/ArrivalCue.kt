package com.intellitrip.ui

import android.content.Context
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.VibrationEffect
import android.os.Vibrator

/** V2 arrival cue: one soft tone + one tick. Singletons, no assets. */
object ArrivalCue {
    fun playIfAllowed(context: Context, enabled: Boolean) {
        if (!enabled) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 30)
            tg.startTone(ToneGenerator.TONE_CDMA_ANSWER, 300)
            tg.release()
        }
        runCatching {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
