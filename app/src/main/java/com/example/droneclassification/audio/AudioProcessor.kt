package com.example.droneclassification.audio

import kotlin.math.sqrt

object AudioProcessor {
    const val SAMPLE_RATE = 16000
    const val WINDOW_DURATION_SEC = 2
    const val CLIP_SAMPLES = SAMPLE_RATE * WINDOW_DURATION_SEC // 32000
    const val HOP_SAMPLES = CLIP_SAMPLES / 2 // 50% overlap (16000)

    fun computeRms(audio: FloatArray): Float {
        if (audio.isEmpty()) return 0.0f
        var sum = 0.0
        for (sample in audio) {
            sum += (sample.toDouble() * sample.toDouble())
        }
        return sqrt(sum / audio.size).toFloat()
    }

    fun normalizeRms(audio: FloatArray, targetRms: Float = 0.1f): FloatArray {
        val rms = computeRms(audio)
        if (rms < 1e-10f) return audio // No need to copy if returning original
        val factor = targetRms / rms
        val result = FloatArray(audio.size)
        for (i in audio.indices) {
            result[i] = audio[i] * factor
        }
        return result
    }

    fun extractClips(audio: FloatArray, onClip: (FloatArray) -> Unit) {
        if (audio.size < CLIP_SAMPLES) return
        for (start in 0..audio.size - CLIP_SAMPLES step HOP_SAMPLES) {
            onClip(audio.copyOfRange(start, start + CLIP_SAMPLES))
        }
    }

    fun isValidNoiseClip(clip: FloatArray, minRms: Float = 0.001f, peakRatio: Float = 5.0f): Boolean {
        val rms = computeRms(clip)
        if (rms < minRms) return false
        var maxAbs = 0.0f
        for (sample in clip) {
            val abs = if (sample < 0) -sample else sample
            if (abs > maxAbs) maxAbs = abs
        }
        return maxAbs <= peakRatio * rms
    }
}
