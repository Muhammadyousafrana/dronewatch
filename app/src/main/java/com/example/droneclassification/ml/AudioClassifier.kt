package com.example.droneclassification.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.flex.FlexDelegate
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicInteger

/**
 * AudioClassifier using the LiteRT Interpreter API.
 * The Interpreter API is used here because the model contains Flex ops (TensorLists)
 * which are more robustly handled by the standard Interpreter + Flex delegate path
 * than the experimental high-level CompiledModel API in current LiteRT versions.
 */
class AudioClassifier(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var modelBuffer: ByteBuffer? = null
    private var flexDelegate: FlexDelegate? = null
    private val consecutiveFailures = AtomicInteger(0)

    companion object {
        private const val TAG = "AudioClassifier"
        private const val MODEL_FILE = "best_model4.tflite"
        /** After this many consecutive inference failures, classify() will throw. */
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }

    /**
     * Initializes the TFLite interpreter. Throws on failure so callers
     * can distinguish "model loaded" from "model failed to load".
     */
    fun initialize() {
        setupClassifier()
    }

    private fun setupClassifier() {
        // 1. Load the model file into a MappedByteBuffer.
        //    Use `use {}` to properly close the AssetFileDescriptor and FileInputStream
        //    so file descriptors are not leaked (emulators have tight FD limits).
        val afd = context.assets.openFd(MODEL_FILE)
        modelBuffer = afd.use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { fis ->
                val channel = fis.channel
                channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength
                )
            }
        }

        // 2. Configure Interpreter options
        val options = Interpreter.Options().apply {
            setNumThreads(4)

            // Explicitly add FlexDelegate to support Flex ops (e.g. FlexTensorListReserve)
            try {
                val delegate = FlexDelegate()
                addDelegate(delegate)
                flexDelegate = delegate
            } catch (t: Throwable) {
                Log.e(TAG, "FlexDelegate init failed — model may not run if it requires Flex ops.", t)
                // Don't rethrow here: the interpreter *might* work without it if the
                // model's flex ops are optional. Let the Interpreter constructor decide.
            }
        }

        // 3. Initialize the Interpreter — let exceptions propagate
        interpreter = Interpreter(modelBuffer!!, options)
        Log.d(TAG, "Successfully initialized LiteRT Interpreter with Flex support.")
    }

    /**
     * Runs inference on a single audio clip.
     *
     * @param audio FloatArray of length CLIP_SAMPLES (32000).
     * @return confidence score in [0, 1].
     * @throws ClassificationException after [MAX_CONSECUTIVE_FAILURES] consecutive failures,
     *         signalling that something is fundamentally broken and the caller should stop.
     */
    fun classify(audio: FloatArray): Float {
        val interp = interpreter
            ?: throw ClassificationException("Interpreter is null — model not initialized or was closed.")

        return try {
            // The model expects [1, 32000]
            val input = Array(1) { audio }
            val output = Array(1) { FloatArray(1) }

            interp.run(input, output)

            // Inference succeeded — reset failure counter
            consecutiveFailures.set(0)
            output[0][0]
        } catch (e: Exception) {
            val failures = consecutiveFailures.incrementAndGet()
            Log.e(TAG, "LiteRT inference failed ($failures/$MAX_CONSECUTIVE_FAILURES consecutive)", e)

            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                throw ClassificationException(
                    "Inference failed $failures consecutive times. Stopping. Last error: ${e.message}", e
                )
            }
            // Transient failure — return 0 and let the next cycle retry
            0f
        }
    }

    fun close() {
        try {
            interpreter?.close()
            interpreter = null
            flexDelegate?.close()
            flexDelegate = null
            modelBuffer = null
            consecutiveFailures.set(0)
        } catch (e: Exception) {
            Log.w(TAG, "Error closing TFLite components", e)
        }
    }

    /** Signals that inference is fundamentally broken and the caller should stop. */
    class ClassificationException(message: String, cause: Throwable? = null) :
        RuntimeException(message, cause)
}
