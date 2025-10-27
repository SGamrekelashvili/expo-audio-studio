package expo.modules.audiostudio.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContentProviderCompat.requireContext
import com.github.squti.androidwaverecorder.RecorderState
import com.github.squti.androidwaverecorder.WaveRecorder
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.math.*

/**
 * MediaRecorderProvider with Silero VAD Integration
 * Optimized for 16kHz, mono, 16-bit configuration
 */
class MediaRecorderProviderWithSileroVAD(
    context: Context,
    SendStatusEvent: (Map<String, Any>) -> Unit,
    SendAmplitudeEvent: (Map<String, Any>) -> Unit,
    SendVoiceActivityEvent: (Map<String, Any>) -> Unit
) : AudioRecorderProvider {

    @Volatile private var waveRecorder: WaveRecorder? = null
    @Volatile private var recorderState: RecorderState? = null
    private val _recorderStatusStateFlow = MutableStateFlow(RecorderInnerState(RecorderState.STOP))
    private val _recorderTimeElapsedStateFlow = MutableStateFlow(RecorderProgress(0))
    private val _recorderMetricsFlow = MutableStateFlow(RecorderMetrics(0))
    private val sendStatusEvent = SendStatusEvent
    private val sendAmplitudeEvent = SendAmplitudeEvent
    private val sendVoiceActivityEvent = SendVoiceActivityEvent
    @Volatile private var lastAmplitude: Float = -160.0f

    @Volatile private var webRTCVad: VadSilero? = null
    @Volatile private var vadAudioRecord: AudioRecord? = null
    @Volatile private var vadJob: Job? = null
    @Volatile private var isVADActive = false
    
    @Volatile private var vadEventMode = "onEveryFrame"
    @Volatile private var vadThrottleMs = 100
    private var lastEventTime: Long = 0
    private var lastVoiceState = false
    @Volatile private var shouldAutoStartVAD = false

    private val vadLock = Any()
    

    private val vadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Configurable amplitude update frequency (default: 60 FPS = 16.67ms)
    @Volatile private var amplitudeUpdateIntervalMs: Long = 1000L / 60L
    @Volatile private var lastAmplitudeUpdateTime: Long = 0
    private val amplitudeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val frameSize = 512  // 32ms frames at 16kHz (required for Silero VAD)
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 4
    private val appContext = context
    
    fun setAmplitudeUpdateFrequency(frequencyHz: Double) {
        val clampedFrequency = maxOf(1.0, minOf(120.0, frequencyHz))
        amplitudeUpdateIntervalMs = (1000.0 / clampedFrequency).toLong()
    }
    
    override fun startRecording(context: Context, argument: RecordArgument): Boolean {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permission to record audio not granted")
            return false
        }

        waveRecorder?.let {
            try {
                it.stopRecording()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping previous recorder: ${e.message}")
            }
        }
        waveRecorder = null
        recorderState = null
        lastAmplitude = -160.0f
        lastAmplitudeUpdateTime = 0

        val file = File(argument.outputFile)
        file.parentFile?.mkdirs()

        waveRecorder = WaveRecorder(argument.outputFile).apply {
            configureWaveSettings {
                sampleRate = 16000
                channels = AudioFormat.CHANNEL_IN_MONO
                audioEncoding = AudioFormat.ENCODING_PCM_16BIT
            }
            
            noiseSuppressorActive = NoiseSuppressor.isAvailable()
            silenceDetection = false

            onStateChangeListener = { state ->
                recorderState = state
                
                val status = when(state) {
                    RecorderState.RECORDING -> "recording"
                    RecorderState.STOP -> "stopped"
                    RecorderState.PAUSE -> "paused"
                    RecorderState.SKIPPING_SILENCE -> "skipping_silence"
                }
                
                sendStatusEvent(mapOf("status" to status))
                
                if (state == RecorderState.RECORDING && shouldAutoStartVAD) {
                    shouldAutoStartVAD = false
                    vadScope.launch {
                        try {
                            startVoiceActivityDetection()
                        } catch (e: Exception) {
                            Log.e(TAG, "VAD auto-start failed: ${e.message}")
                        }
                    }
                }
            }

            onAmplitudeListener = { amplitude ->
                val linearAmp = amplitude.toDouble()
                val normalizedAmp = linearAmp / 32768.0
                val db = if (normalizedAmp > 0.0) {
                    20.0 * log10(normalizedAmp)
                } else {
                    -160.0
                }
                lastAmplitude = maxOf(-160.0f, db.toFloat())
                
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastAmplitudeUpdateTime >= amplitudeUpdateIntervalMs) {
                    lastAmplitudeUpdateTime = currentTime
                    amplitudeHandler.post {
                        sendAmplitudeEvent(mapOf("amplitude" to lastAmplitude))
                    }
                }
            }
        }

        return try {
            waveRecorder?.startRecording()
            true
        } catch (e: Exception) {
            Log.e(TAG, "startRecording: $e")
            waveRecorder = null
            false
        }
    }

    override fun stopRecording(): Boolean {
        return try {
            shouldAutoStartVAD = false
            stopVoiceActivityDetection()
            
            waveRecorder?.stopRecording()
            waveRecorder = null
            recorderState = null
            lastAmplitude = -160.0f
            lastAmplitudeUpdateTime = 0
            true
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording: $e")
            waveRecorder = null
            recorderState = null
            shouldAutoStartVAD = false
            lastAmplitude = -160.0f
            lastAmplitudeUpdateTime = 0
            false
        }
    }

    override fun pauseRecording(): Boolean {
        return try {
            shouldAutoStartVAD = false
            waveRecorder?.pauseRecording()
            true
        } catch (e: Exception) {
            Log.e(TAG, "pauseRecording: $e")
            false
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun resumeRecording(): Boolean {
        return try {
            waveRecorder?.resumeRecording()
            true
        } catch (e: Exception) {
            Log.e(TAG, "resumeRecording: $e")
            false
        }
    }

    override fun isPaused(): Boolean {
        return try {
            recorderState == RecorderState.PAUSE
        } catch (e: Exception) {
            Log.e(TAG, "Error checking paused state: ${e.message}")
            false
        }
    }

    // MARK: - Silero VAD Implementation

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun startVoiceActivityDetection(): String {
        if (!isRecording()) {
            Log.w(TAG, "❌ VAD cannot start - not recording")
            return "NotRecording: Voice activity detection requires active recording"
        }
        
        synchronized(vadLock) {
            if (isVADActive) {
                Log.w(TAG, "Silero VAD already active")
                return "AlreadyActive"
            }

            return try {
            webRTCVad = VadSilero(
                context = appContext,
                sampleRate = SampleRate.SAMPLE_RATE_16K,
                frameSize = FrameSize.FRAME_SIZE_512,
                mode = Mode.NORMAL,
                silenceDurationMs = 300,
                speechDurationMs = 50
            )

            vadAudioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (vadAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "❌ Failed to initialize AudioRecord for Silero VAD")
                cleanup()
                return "AudioRecordInitFailed"
            }

            vadAudioRecord?.startRecording()
            isVADActive = true

            vadJob = vadScope.launch {
                processVADAudioStream()
            }

                Log.d(TAG, "✅ Silero VAD started successfully")
                "Success"
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start Silero VAD: ${e.message}")
                cleanup()
                "Error: ${e.message}"
            }
        } // End synchronized block
    }

    private suspend fun processVADAudioStream() {
        val buffer = ShortArray(frameSize)
        var lastVoiceState = false
        var consecutiveErrors = 0
        val maxConsecutiveErrors = 5


        while (isVADActive && vadAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                val samplesRead = vadAudioRecord?.read(buffer, 0, frameSize) ?: 0

                if (samplesRead == frameSize) {
                    consecutiveErrors = 0
                    
                    val amplitude = calculateAmplitudeFromBuffer(buffer)
                    lastAmplitude = amplitude
                    
                    webRTCVad?.let { vad ->
                        val isVoice = vad.isSpeech(buffer)
                        val currentTime = System.currentTimeMillis()
                        val isStateChange = isVoice != lastVoiceState
                        
                        val shouldSendEvent = when (vadEventMode) {
                            "onChange" -> isStateChange
                            "throttled" -> isStateChange || (currentTime - lastEventTime) >= vadThrottleMs
                            else -> true
                        }

                        if (shouldSendEvent) {
                            val eventType = if (isStateChange) {
                                if (isVoice) "speech_start" else "silence_start"
                            } else {
                                if (isVoice) "speech_continue" else "silence_continue"
                            }
                            
                            val stateDuration = 0
                            
                            withContext(Dispatchers.Main) {
                                val result = mapOf(
                                    "isVoiceDetected" to isVoice,
                                    "timestamp" to currentTime,
                                    "confidence" to if (isVoice) 0.85 else 0.15,
                                    "audioLevel" to amplitude,
                                    "isStateChange" to isStateChange,
                                    "previousState" to lastVoiceState,
                                    "eventType" to eventType,
                                    "stateDuration" to stateDuration
                                )
                                sendVoiceActivityEvent(result)
                            }
                            
                            if (isStateChange) {
                                lastVoiceState = isVoice
                                Log.d(TAG, if (isVoice) "🎤 Voice detected (amp: ${amplitude.toInt()} dB)" else "🔇 Silence detected")
                            }
                            
                            lastEventTime = currentTime
                        }
                    }
                } else if (samplesRead < 0) {
                    consecutiveErrors++
                    Log.w(TAG, "⚠️ AudioRecord read error: $samplesRead (consecutive: $consecutiveErrors)")
                    
                    if (consecutiveErrors >= maxConsecutiveErrors) {
                        Log.e(TAG, "❌ Too many consecutive read errors, stopping VAD")
                        break
                    }
                } else {
                    Log.w(TAG, "⚠️ Incomplete frame read: $samplesRead/$frameSize samples")
                }

                // Optimal delay for 32ms frames
                delay(32)

            } catch (e: Exception) {
                consecutiveErrors++
                Log.e(TAG, "❌ Error in VAD processing: ${e.message} (consecutive: $consecutiveErrors)")
                
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    Log.e(TAG, "❌ Too many consecutive errors, stopping VAD")
                    break
                }
                
                delay(50) // Longer delay on error
            }
        }

        Log.d(TAG, "🛑 Silero VAD audio processing stopped")
    }

    override fun stopVoiceActivityDetection(): String {
        // THREAD SAFETY FIX: Synchronize VAD state changes
        synchronized(vadLock) {
            if (!isVADActive) {
                return "NotActive"
            }

            return try {
                isVADActive = false
                
                vadJob?.cancel()
                vadJob = null

                // Stop and release AudioRecord
                vadAudioRecord?.stop()
                vadAudioRecord?.release()
                vadAudioRecord = null

                // Close Silero VAD properly
                webRTCVad?.close()
                webRTCVad = null

                Log.d(TAG, "🛑 Silero VAD stopped successfully")
                "Success"

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error stopping Silero VAD: ${e.message}")
                cleanup()
                "Error: ${e.message}"
            }
        } // End synchronized block
    }

    private fun cleanup() {
        isVADActive = false
        vadJob?.cancel()
        vadJob = null
        
        try {
            vadAudioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        
        vadAudioRecord?.release()
        vadAudioRecord = null
        
        try {
            webRTCVad?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing Silero VAD: ${e.message}")
        }
        webRTCVad = null
    }
    
    fun destroy() {
        cleanup()
        vadScope.cancel()
    }

    override fun setVoiceActivityThreshold(threshold: Float): String {
        // Silero VAD uses predefined modes (NORMAL, AGGRESSIVE)
        // rather than configurable thresholds. This method is provided for API compatibility.
        return if (threshold >= 0.0f && threshold <= 1.0f) {
            "NotSupported: Silero VAD uses predefined sensitivity modes. Current mode: NORMAL"
        } else {
            "InvalidThreshold: Threshold must be between 0.0 and 1.0"
        }
    }
    
    fun setVADEventMode(mode: String, throttleMs: Int = 100): String {
        return try {
            when (mode) {
                "onChange", "onEveryFrame", "throttled" -> {
                    vadEventMode = mode
                    if (mode == "throttled") {
                        vadThrottleMs = throttleMs
                    }
                    "VAD event mode set to: $mode" + if (mode == "throttled") " with ${throttleMs}ms throttle" else ""
                }
                else -> "Invalid mode: $mode. Valid modes are: onChange, onEveryFrame, throttled"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
    
    fun requestAutoStartVAD() {
        shouldAutoStartVAD = true
    }

    override fun isVoiceActivityDetectionActive(): Boolean = isVADActive

    override fun isRecording(): Boolean {
        return waveRecorder != null && try {
            recorderState == RecorderState.RECORDING
        } catch (e: Exception) {
            Log.e(TAG, "Error checking recording state: ${e.message}")
            false
        }
    }

    override fun getCurrentAmplitude(): Float? {
        return if (isRecording()) lastAmplitude else -160.0f
    }

    override fun recorderStatus(): StateFlow<RecorderInnerState> {
        return _recorderStatusStateFlow
    }

    override fun recorderTimeElapsed(): StateFlow<RecorderProgress> {
        return _recorderTimeElapsedStateFlow
    }

    override fun recorderMetrics(): StateFlow<RecorderMetrics> {
        return _recorderMetricsFlow
    }

    override fun releaseRecorder() {
        stopVoiceActivityDetection()
        

        waveRecorder?.stopRecording()
        waveRecorder = null
        
        vadScope.cancel()
    }
    
    /**
     * Calculate amplitude in decibels (dB) from audio buffer
     * @param buffer ShortArray containing PCM audio samples
     * @return Amplitude in dB (range: -160.0 to 0.0)
     */
    private fun calculateAmplitudeFromBuffer(buffer: ShortArray): Float {
        if (buffer.isEmpty()) return -160.0f
        
        // Calculate RMS (Root Mean Square) of the audio samples
        var sum = 0.0
        for (sample in buffer) {
            val normalized = sample.toDouble() / Short.MAX_VALUE
            sum += normalized * normalized
        }
        val rms = sqrt(sum / buffer.size)
        
        // Convert RMS to decibels
        // Reference: 0 dB = maximum amplitude (1.0)
        // Minimum: -160 dB (effectively silence)
        return if (rms > 0.0) {
            val db = 20.0 * log10(rms)
            maxOf(-160.0f, db.toFloat()) // Clamp to -160 dB minimum
        } else {
            -160.0f // Silence
        }
    }

    companion object {
        private const val TAG = "MediaRecorderSileroVAD"
    }
}
