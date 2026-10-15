package com.example.droneclassification

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.droneclassification.audio.AudioFileLoader
import com.example.droneclassification.audio.AudioProcessor
import com.example.droneclassification.audio.LiveAudioRecorder
import com.example.droneclassification.ml.AudioClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ClassificationUiState {
    object Initial : ClassificationUiState
    object Processing : ClassificationUiState
    data class Success(
        val confidence: Float,
        val isDrone: Boolean,
        val alertTriggered: Boolean = false
    ) : ClassificationUiState
    data class Error(val message: String) : ClassificationUiState
}

class DroneClassificationViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow<ClassificationUiState>(ClassificationUiState.Initial)
    val uiState: StateFlow<ClassificationUiState> = _uiState.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _currentAudioWaveform = MutableStateFlow(FloatArray(0))
    val currentAudioWaveform: StateFlow<FloatArray> = _currentAudioWaveform.asStateFlow()

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    private val classifier = AudioClassifier(application)
    private val recorder = LiveAudioRecorder()
    private val fileLoader = AudioFileLoader(application)

    // ToneGenerator can crash on emulators without audio hardware — create lazily & nullable
    private val toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_ALARM, 100)
    } catch (e: Exception) {
        Log.w(TAG, "ToneGenerator creation failed (common on emulators). Alert sounds disabled.", e)
        null
    }
    private var lastAlertTime = 0L

    private val _threshold = MutableStateFlow(0.8f)
    val threshold: StateFlow<Float> = _threshold.asStateFlow()

    private var recordingJob: Job? = null
    private val liveAudioBuffer = FloatArray(AudioProcessor.CLIP_SAMPLES)
    private var liveAudioBufferOffset = 0

    companion object {
        private const val TAG = "ViewModel"
    }

    init {
        initializeModels()
    }

    private fun initializeModels() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                classifier.initialize()
                _isModelReady.value = true
                Log.d(TAG, "Audio model initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Audio model initialization failed", e)
                _uiState.value = ClassificationUiState.Error("TFLite initialization failed: ${e.message}")
            }
        }
    }

    fun startLiveClassification() {
        if (!_isModelReady.value) return
        stopLiveClassification()
        _isRecording.value = true
        _uiState.value = ClassificationUiState.Initial

        liveAudioBufferOffset = 0

        // Start the foreground service to keep inference alive when screen is off
        ClassificationService.start(getApplication())

        recordingJob = viewModelScope.launch {
            recorder.audioData.collect { data ->
                _currentAudioWaveform.value = data
                var dataOffset = 0
                while (dataOffset < data.size) {
                    val remainingInBuffer = AudioProcessor.CLIP_SAMPLES - liveAudioBufferOffset
                    val toCopy = minOf(data.size - dataOffset, remainingInBuffer)

                    System.arraycopy(data, dataOffset, liveAudioBuffer, liveAudioBufferOffset, toCopy)
                    liveAudioBufferOffset += toCopy
                    dataOffset += toCopy

                    if (liveAudioBufferOffset >= AudioProcessor.CLIP_SAMPLES) {
                        val clip = liveAudioBuffer.copyOf()
                        processAudioClip(clip)
                        liveAudioBufferOffset = 0
                    }
                }
            }
        }
        viewModelScope.launch {
            recorder.startRecording()
        }
    }

    fun stopLiveClassification() {
        val wasRecording = _isRecording.value
        _isRecording.value = false
        recorder.stopRecording()
        recordingJob?.cancel()
        recordingJob = null

        // Stop the foreground service and release wake lock
        if (wasRecording) {
            ClassificationService.stop(getApplication())
        }
    }

    fun classifyFile(uri: Uri) {
        if (!_isModelReady.value) return
        stopLiveClassification()
        _uiState.value = ClassificationUiState.Processing
        viewModelScope.launch {
            try {
                val audio = fileLoader.loadAudio(uri)
                if (audio != null) {
                    var maxConfidence = 0f
                    AudioProcessor.extractClips(audio) { clip ->
                        val confidence = classifier.classify(AudioProcessor.normalizeRms(clip))
                        if (confidence > maxConfidence) maxConfidence = confidence
                    }

                    if (maxConfidence > 0 || audio.size >= AudioProcessor.CLIP_SAMPLES) {
                        val isDrone = maxConfidence >= _threshold.value
                        if (isDrone) playAlertSound()
                        _uiState.value = ClassificationUiState.Success(
                            confidence = maxConfidence,
                            isDrone = isDrone,
                            alertTriggered = isDrone
                        )
                    } else {
                        _uiState.value = ClassificationUiState.Error("Audio file too short")
                    }
                } else {
                    _uiState.value = ClassificationUiState.Error("Failed to load audio file")
                }
            } catch (e: AudioClassifier.ClassificationException) {
                Log.e(TAG, "Classification failed on file", e)
                _uiState.value = ClassificationUiState.Error("Inference engine error: ${e.message}")
            }
        }
    }

    fun setThreshold(value: Float) {
        _threshold.value = value
        val currentState = _uiState.value
        if (currentState is ClassificationUiState.Success) {
            _uiState.value = currentState.copy(isDrone = currentState.confidence >= value)
        }
    }

    private suspend fun processAudioClip(clip: FloatArray) = withContext(Dispatchers.Default) {
        try {
            val normalized = AudioProcessor.normalizeRms(clip)
            val confidence = classifier.classify(normalized)
            val isDrone = confidence >= _threshold.value

            if (isDrone) {
                playAlertSound()
            }

            _uiState.value = ClassificationUiState.Success(
                confidence = confidence,
                isDrone = isDrone,
                alertTriggered = isDrone
            )
        } catch (e: AudioClassifier.ClassificationException) {
            // Inference is fundamentally broken — stop scanning and show error
            Log.e(TAG, "Inference engine failed — stopping live classification", e)
            withContext(Dispatchers.Main) {
                stopLiveClassification()
                _uiState.value = ClassificationUiState.Error("Inference stopped: ${e.message}")
            }
        }
    }

    private fun playAlertSound() {
        val now = System.currentTimeMillis()
        if (now - lastAlertTime > 1000) { // Throttle alerts to once per second
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_PIP, 150)
                lastAlertTime = now
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play alert sound", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopLiveClassification()
        try {
            toneGenerator?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing ToneGenerator", e)
        }
        classifier.close()
    }
}
