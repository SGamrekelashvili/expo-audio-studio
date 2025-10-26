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

    // THREAD SAFETY FIX: Add @Volatile for variables accessed from multiple threads
    @Volatile private var waveRecorder: WaveRecorder? = null
    @Volatile private var recorderState: RecorderState? = null
    private val _recorderStatusStateFlow = MutableStateFlow(RecorderInnerState(RecorderState.STOP))
    private val _recorderTimeElapsedStateFlow = MutableStateFlow(RecorderProgress(0))
    private val _recorderMetricsFlow = MutableStateFlow(RecorderMetrics(0))
    private val sendStatusEvent = SendStatusEvent
    private val sendAmplitudeEvent = SendAmplitudeEvent
    private val sendVoiceActivityEvent = SendVoiceActivityEvent
    @Volatile private var lastAmplitude: Float = -160.0f

    // Silero VAD components - THREAD SAFETY FIX: Add @Volatile and synchronization
    @Volatile private var webRTCVad: VadSilero? = null
    @Volatile private var vadAudioRecord: AudioRecord? = null
    @Volatile private var vadJob: Job? = null
    @Volatile private var isVADActive = false
    @Volatile private var isVADEnabledFromJS = false  // VAD OPTIMIZATION: Track JS enable state
    
    // Thread synchronization lock for VAD operations
    private val vadLock = Any()
    
    // VAD OPTIMIZATION: Helper to check if VAD should be active
    private fun shouldVADBeActive(): Boolean {
        return isRecording() && isVADEnabledFromJS
    }
    
    // MEMORY LEAK FIX: Use managed coroutine scope with SupervisorJob
    private val vadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Configurable amplitude update frequency (default: 60 FPS = 16.67ms)
    @Volatile private var amplitudeUpdateIntervalMs: Long = 1000L / 60L // 60 Hz for smooth 60 FPS animations
    @Volatile private var amplitudeTimer: java.util.Timer? = null
    private val amplitudeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    
    // Audio configuration optimized for Silero VAD
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val frameSize = 512  // 32ms frames at 16kHz (required for Silero VAD)
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 4
    private val appContext = context
    
    // Function to set amplitude update frequency from JavaScript
    fun setAmplitudeUpdateFrequency(frequencyHz: Double) {
        // Clamp frequency between 1 Hz and 120 Hz for reasonable performance
        val clampedFrequency = maxOf(1.0, minOf(120.0, frequencyHz))
        amplitudeUpdateIntervalMs = (1000.0 / clampedFrequency).toLong()
        
        Log.d(TAG, "Amplitude frequency set to $clampedFrequency Hz (${amplitudeUpdateIntervalMs}ms interval)")
    }
    
    // Start custom amplitude monitoring with configurable frequency
    private fun startAmplitudeMonitoring() {
        stopAmplitudeMonitoring() // Stop any existing timer
        
        amplitudeTimer = java.util.Timer()
        amplitudeTimer?.schedule(object : java.util.TimerTask() {
            override fun run() {
                val currentAmplitude = getCurrentAmplitude() ?: -160.0f
                amplitudeHandler.post {
                    val result = mapOf("amplitude" to currentAmplitude)
                    sendAmplitudeEvent(result)
                }
            }
        }, 0, amplitudeUpdateIntervalMs)
    }
    
    // Stop amplitude monitoring
    private fun stopAmplitudeMonitoring() {
        amplitudeTimer?.cancel()
        amplitudeTimer = null
    }
    
    override fun startRecording(context: Context, argument: RecordArgument): Boolean {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permission to record audio not granted")
            return false
        }

        // Make sure parent directory exists
        val file = File(argument.outputFile)
        file.parentFile?.mkdirs()

        waveRecorder = WaveRecorder(argument.outputFile).apply {
            // Audio quality settings optimized for AI/speech recognition
            configureWaveSettings {
                sampleRate = 16000                              // 16kHz - perfect for speech, smaller files
                channels = AudioFormat.CHANNEL_IN_MONO          // Mono - half the file size
                audioEncoding = AudioFormat.ENCODING_PCM_16BIT  // 16-bit depth - proper constant
            }
            
            noiseSuppressorActive = NoiseSuppressor.isAvailable()
            
            // Simple recording without silence detection (VAD handles this)
            silenceDetection = false

            onStateChangeListener = { state ->
                val result = mapOf(
                    "status" to when(state) {
                        RecorderState.RECORDING -> "recording"
                        RecorderState.STOP -> "stopped"
                        RecorderState.PAUSE -> "paused"
                        RecorderState.SKIPPING_SILENCE -> "skipping_silence"
                    }
                )

                recorderState = state
                sendStatusEvent(result)
            }

            // Use library amplitude listener to update lastAmplitude
            // WaveRecorder returns amplitude in linear scale (0-32768), convert to dB
            onAmplitudeListener = { amplitude ->
                // Convert linear amplitude to decibels
                val linearAmp = amplitude.toDouble()
                val normalizedAmp = linearAmp / 32768.0 // Normalize to 0.0-1.0
                lastAmplitude = if (normalizedAmp > 0.0) {
                    val db = 20.0 * log10(normalizedAmp)
                    maxOf(-160.0f, db.toFloat()) // Clamp to -160 dB minimum
                } else {
                    -160.0f // Silence
                }
                
                // Debug logging (remove in production)
                if (Math.random() < 0.05) { // Log 5% of samples to avoid spam
                    Log.d(TAG, "📊 Amplitude: linear=$linearAmp, normalized=${"%.4f".format(normalizedAmp)}, dB=${"%.1f".format(lastAmplitude)}")
                }
            }
        }

        return try {
            waveRecorder?.startRecording()
            // Start our custom amplitude monitoring
            startAmplitudeMonitoring()
            true
        } catch (e: Exception) {
            Log.e(TAG, "startRecording: $e")
            false
        }
    }

    override fun stopRecording(): Boolean {
        return try {
            // Stop WebRTC VAD first
            stopVoiceActivityDetection()
            
            // Stop our custom amplitude monitoring
            stopAmplitudeMonitoring()
            
            waveRecorder?.stopRecording()
            recorderState = null
            lastAmplitude = -160.0f
            true
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording: $e")
            recorderState = null
            false
        }
    }

    override fun pauseRecording(): Boolean {
        return try {
            Log.d(TAG, "Pausing recording...")
            waveRecorder?.pauseRecording()
            Log.d(TAG, "Recording paused successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "pauseRecording: $e")
            false
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun resumeRecording(): Boolean {
        return try {
            Log.d(TAG, "Resuming recording...")
            waveRecorder?.resumeRecording()
            
            // VAD OPTIMIZATION: Auto-restart VAD if it was enabled from JS
            if (isVADEnabledFromJS && !isVADActive) {
                Log.d(TAG, "Auto-restarting VAD after recording resume")
                startVoiceActivityDetection()
            }
            
            Log.d(TAG, "Recording resumed successfully")
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
        // VAD OPTIMIZATION: Only start if recording is active
        if (!isRecording()) {
            Log.w(TAG, "❌ VAD cannot start - not recording")
            return "NotRecording: Voice activity detection requires active recording"
        }
        
        // THREAD SAFETY FIX: Synchronize VAD state changes
        synchronized(vadLock) {
            if (isVADActive) {
                Log.w(TAG, "Silero VAD already active")
                return "AlreadyActive"
            }
            
            // VAD OPTIMIZATION: Mark as enabled from JS
            isVADEnabledFromJS = true

            return try {
            // Create Silero VAD instance (don't use .use block - we need to manage lifecycle manually)
            webRTCVad = VadSilero(
                context = appContext,
                sampleRate = SampleRate.SAMPLE_RATE_16K,
                frameSize = FrameSize.FRAME_SIZE_512,
                mode = Mode.NORMAL,
                silenceDurationMs = 300,
                speechDurationMs = 50
            )

            // Initialize AudioRecord for VAD (separate from WaveRecorder)
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

            // Start VAD processing
            vadAudioRecord?.startRecording()
            isVADActive = true

            // Start VAD processing coroutine using managed scope
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

        Log.d(TAG, "🎙️ Silero VAD audio processing started (frameSize: $frameSize)")

        while (isVADActive && vadAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                val samplesRead = vadAudioRecord?.read(buffer, 0, frameSize) ?: 0

                if (samplesRead == frameSize) {
                    // Reset error counter on successful read
                    consecutiveErrors = 0
                    
                    // Calculate amplitude from audio buffer
                    val amplitude = calculateAmplitudeFromBuffer(buffer)
                    lastAmplitude = amplitude
                    
                    // Process audio frame with Silero VAD
                    webRTCVad?.let { vad ->
                        val isVoice = vad.isSpeech(buffer)

                        // Only send event if voice state changed
                        if (isVoice != lastVoiceState) {
                            lastVoiceState = isVoice
                            
                            withContext(Dispatchers.Main) {
                                val result = mapOf(
                                    "isVoiceDetected" to isVoice,
                                    "timestamp" to System.currentTimeMillis(),
                                    "confidence" to if (isVoice) 0.85 else 0.15,
                                    "audioLevel" to amplitude,
                                    "isStateChange" to true,
                                    "previousState" to lastVoiceState,
                                    "eventType" to if (isVoice) "speech_start" else "silence_start"
                                )
                                sendVoiceActivityEvent(result)
                            }

                            Log.d(TAG, if (isVoice) "🎤 Voice detected (amp: ${amplitude.toInt()} dB)" else "🔇 Silence detected")
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
                isVADEnabledFromJS = false  // VAD OPTIMIZATION: Clear JS enable state
                
                // Cancel VAD processing job
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
    
    // MEMORY LEAK FIX: Add proper scope cleanup method
    fun destroy() {
        cleanup()
        vadScope.cancel() // Cancel all coroutines and clean up scope
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
        // VAD OPTIMIZATION: Stop VAD and clear JS enable state
        stopVoiceActivityDetection()
        isVADEnabledFromJS = false
        
        // Stop amplitude monitoring
        stopAmplitudeMonitoring()
        
        // Release recorder
        waveRecorder?.stopRecording()
        waveRecorder = null
        
        // MEMORY LEAK FIX: Cancel coroutine scope to prevent memory leaks
        vadScope.cancel()
        
        Log.d(TAG, "✅ Recorder and all resources released")
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
