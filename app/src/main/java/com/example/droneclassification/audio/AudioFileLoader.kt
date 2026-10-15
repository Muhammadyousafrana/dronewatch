package com.example.droneclassification.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioFileLoader(private val context: Context) {

    suspend fun loadAudio(uri: Uri): FloatArray? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        // FIX: use try/finally so extractor.release() is always called,
        // preventing the "A resource failed to call close" warning.
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                extractor.setDataSource(fd.fileDescriptor)
            } ?: return@withContext null

            val trackIndex = selectAudioTrack(extractor)
            if (trackIndex < 0) return@withContext null

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null

            val originalSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val targetSampleRate = AudioProcessor.SAMPLE_RATE

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val chunks = mutableListOf<FloatArray>()
            var totalTargetSamples = 0
            val bufferInfo = MediaCodec.BufferInfo()
            var isEOS = false

            val resampleRatio = originalSampleRate.toDouble() / targetSampleRate.toDouble()

            try {
                while (!isEOS) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10_000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputBufferIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            isEOS = true
                        } else {
                            codec.queueInputBuffer(
                                inputBufferIndex, 0, sampleSize,
                                extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }

                    var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    while (outputBufferIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)!!
                        val shortBuffer = outputBuffer.asShortBuffer()
                        val availableSourceSamples = shortBuffer.remaining() / channels

                        if (availableSourceSamples > 0) {
                            // Downmix to mono and convert to float
                            val monoFloats = FloatArray(availableSourceSamples)
                            for (i in 0 until availableSourceSamples) {
                                var sum = 0f
                                repeat(channels) {
                                    sum += shortBuffer.get() / 32768.0f
                                }
                                monoFloats[i] = sum / channels
                            }

                            // Resample on the fly if needed
                            if (originalSampleRate == targetSampleRate) {
                                chunks.add(monoFloats)
                                totalTargetSamples += monoFloats.size
                            } else {
                                // Simple linear interpolation resampling in chunks
                                val numTargetSamples = (availableSourceSamples / resampleRatio).toInt()
                                if (numTargetSamples > 0) {
                                    val resampledChunk = FloatArray(numTargetSamples)
                                    for (i in 0 until numTargetSamples) {
                                        val pos = i * resampleRatio
                                        val idx = pos.toInt()
                                        val frac = (pos - idx).toFloat()
                                        resampledChunk[i] = if (idx + 1 < monoFloats.size) {
                                            monoFloats[idx] * (1f - frac) + monoFloats[idx + 1] * frac
                                        } else {
                                            monoFloats[idx]
                                        }
                                    }
                                    chunks.add(resampledChunk)
                                    totalTargetSamples += numTargetSamples
                                }
                            }
                        }

                        codec.releaseOutputBuffer(outputBufferIndex, false)
                        outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }

            // Flatten chunks into a single array
            val result = FloatArray(totalTargetSamples)
            var offset = 0
            for (chunk in chunks) {
                System.arraycopy(chunk, 0, result, offset, chunk.size)
                offset += chunk.size
            }
            chunks.clear()

            result
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            // Always release the extractor, even on error.
            extractor.release()
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return i
        }
        return -1
    }
}
