package com.example.droneclassification.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class LiveAudioRecorder {
    private val sampleRate = AudioProcessor.SAMPLE_RATE
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val _audioData = MutableSharedFlow<FloatArray>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioData: SharedFlow<FloatArray> = _audioData

    private val isRecording = AtomicBoolean(false)

    companion object {
        private const val TAG = "LiveAudioRecorder"
        /** Stop after this many consecutive read errors. */
        private const val MAX_READ_ERRORS = 10
    }

    @SuppressLint("MissingPermission")
    suspend fun startRecording() = withContext(Dispatchers.IO) {
        if (isRecording.get()) return@withContext
        isRecording.set(true)

        // Validate buffer size before creating AudioRecord
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid buffer size from getMinBufferSize: $bufferSize. " +
                    "Audio hardware may not be available (common on emulators).")
            isRecording.set(false)
            return@withContext
        }

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize (state=${audioRecord.state}). " +
                    "Microphone may not be available.")
            audioRecord.release()
            isRecording.set(false)
            return@withContext
        }

        audioRecord.startRecording()
        Log.d(TAG, "Recording started. Sample rate=$sampleRate, buffer=$bufferSize")

        val shortBuffer = ShortArray(AudioProcessor.CLIP_SAMPLES / 2) // Chunk size
        var consecutiveErrors = 0

        try {
            while (isRecording.get() && isActive) {
                val read = audioRecord.read(shortBuffer, 0, shortBuffer.size)

                when {
                    read > 0 -> {
                        consecutiveErrors = 0 // Reset on successful read
                        val floatArray = FloatArray(read)
                        for (i in 0 until read) {
                            floatArray[i] = shortBuffer[i] / 32768.0f
                        }
                        _audioData.emit(floatArray)
                    }
                    read == 0 -> {
                        // No data available yet, continue
                        Log.v(TAG, "AudioRecord.read returned 0 bytes")
                    }
                    else -> {
                        // read is ERROR (-1) or ERROR_BAD_VALUE (-2) or ERROR_DEAD_OBJECT (-6)
                        consecutiveErrors++
                        Log.e(TAG, "AudioRecord.read error: $read " +
                                "(consecutive errors: $consecutiveErrors/$MAX_READ_ERRORS)")

                        if (consecutiveErrors >= MAX_READ_ERRORS) {
                            Log.e(TAG, "Too many consecutive read errors. Stopping recording.")
                            break
                        }
                    }
                }
            }
        } finally {
            try {
                audioRecord.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioRecord.stop() failed — may already be stopped", e)
            }
            audioRecord.release()
            isRecording.set(false)
            Log.d(TAG, "Recording stopped and AudioRecord released.")
        }
    }

    fun stopRecording() {
        isRecording.set(false)
    }
}
