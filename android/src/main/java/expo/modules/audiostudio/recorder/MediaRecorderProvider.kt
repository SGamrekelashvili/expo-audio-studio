package expo.modules.audiostudio.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
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
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * MediaRecorder + Silero VAD; 16kHz mono PCM, same public API.
 */
class MediaRecorderProviderWithSileroVAD(
    context: Context,
    SendStatusEvent: (Map<String, Any>) -> Unit,
    SendAmplitudeEvent: (Map<String, Any>) -> Unit,
    SendVoiceActivityEvent: (Map<String, Any>) -> Unit
) : AudioRecorderProvider {

    private val appContext = context.applicationContext
    private val sendStatusEvent = SendStatusEvent
    private val sendAmplitudeEvent = SendAmplitudeEvent
    private val sendVoiceActivityEvent = SendVoiceActivityEvent

    @Volatile private var waveRecorder: WaveRecorder? = null
    @Volatile private var recorderState: RecorderState = RecorderState.STOP

    // observable state (was returning fresh flows each call — now stable)
    private val _innerState = MutableStateFlow(RecorderInnerState(RecorderState.STOP))
    private val _progress = MutableStateFlow(RecorderProgress(0))
    private val _metrics = MutableStateFlow(RecorderMetrics(0))

    // amplitude + cadence
    @Volatile private var lastAmplitudeDb: Float = -160f
    @Volatile private var amplitudeUpdateIntervalMs: Long = 1000L / 60L
    @Volatile private var lastAmplitudeSentAt: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    // VAD
    @Volatile private var vad: VadSilero? = null
    @Volatile private var vadAudioRecord: AudioRecord? = null
    @Volatile private var vadJob: Job? = null
    @Volatile private var isVADActive = false
    @Volatile private var vadEventMode = "onEveryFrame"
    @Volatile private var vadThrottleMs = 100
    private var lastVADEventAt = 0L
    private var lastVoiceState = false
    @Volatile private var shouldAutoStartVAD = false
    private val vadLock = Any()
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // audio constants
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val frameSize = 512 // 32ms @ 16kHz
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

    private var tickJob: Job? = null
    private var startedAtMs: Long = 0

    fun setAmplitudeUpdateFrequency(frequencyHz: Double) {
        val f = frequencyHz.coerceIn(1.0, 120.0)
        amplitudeUpdateIntervalMs = (1000.0 / f).toLong()
    }

    override fun recorderStatus(): StateFlow<RecorderInnerState> = _innerState
    override fun recorderTimeElapsed(): StateFlow<RecorderProgress> = _progress
    override fun recorderMetrics(): StateFlow<RecorderMetrics> = _metrics

    override fun isRecording(): Boolean = recorderState == RecorderState.RECORDING
    override fun isPaused(): Boolean  = recorderState == RecorderState.PAUSE

    override fun getCurrentAmplitude(): Float? = if (isRecording()) lastAmplitudeDb else -160f

    override fun isVoiceActivityDetectionActive(): Boolean = isVADActive

    override fun releaseRecorder() {
        try {
            stopVoiceActivityDetection()
        } catch (_: Exception) {}
        try {
            waveRecorder?.stopRecording()
        } catch (_: Exception) {}
        waveRecorder = null
        stopTicks()
        ioScope.cancel()
    }

    override fun startRecording(context: Context, argument: RecordArgument): Boolean {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Record audio permission not granted")
            return false
        }

        // clean previous
        try { waveRecorder?.stopRecording() } catch (_: Exception) {}
        waveRecorder = null
        recorderState = RecorderState.STOP
        _innerState.value = RecorderInnerState(RecorderState.STOP)
        lastAmplitudeDb = -160f
        lastAmplitudeSentAt = 0

        val file = File(argument.outputFile)
        file.parentFile?.mkdirs()

        val wr = WaveRecorder(argument.outputFile).apply {
            configureWaveSettings {
                sampleRate = this@MediaRecorderProviderWithSileroVAD.sampleRate
                channels = channelConfig
                audioEncoding = audioFormat
            }
            noiseSuppressorActive = NoiseSuppressor.isAvailable()
            silenceDetection = false

            onStateChangeListener = { state ->
                recorderState = state
                _innerState.value = RecorderInnerState(state)
                sendStatusEvent(mapOf("status" to when (state) {
                    RecorderState.RECORDING -> "recording"
                    RecorderState.PAUSE -> "paused"
                    RecorderState.STOP -> "stopped"
                    RecorderState.SKIPPING_SILENCE -> "skipping_silence"
                }))
                when (state) {
                    RecorderState.RECORDING -> {
                        startedAtMs = System.currentTimeMillis()
                        startTicks()
                        if (shouldAutoStartVAD) {
                            shouldAutoStartVAD = false
                            ioScope.launch {
                                try { startVoiceActivityDetection() } catch (e: Exception) {
                                    Log.e(TAG, "VAD auto-start failed: ${e.message}")
                                }
                            }
                        }
                    }
                    else -> stopTicks()
                }
            }

            onAmplitudeListener = { amplitudeShort ->
                // to dB
                val normalized = (amplitudeShort.toDouble() / 32768.0).coerceIn(0.0, 1.0)
                val db = if (normalized > 0.0) 20.0 * log10(normalized) else -160.0
                lastAmplitudeDb = db.toFloat()

                val now = System.currentTimeMillis()
                if (now - lastAmplitudeSentAt >= amplitudeUpdateIntervalMs) {
                    lastAmplitudeSentAt = now
                    mainHandler.post {
                        sendAmplitudeEvent(mapOf("amplitude" to lastAmplitudeDb))
                    }
                }
            }
        }

        return try {
            wr.startRecording()
            waveRecorder = wr
            true
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            waveRecorder = null
            false
        }
    }

    override fun stopRecording(): Boolean {
        shouldAutoStartVAD = false
        stopVoiceActivityDetection()
        stopTicks()
        return try {
            waveRecorder?.stopRecording()
            waveRecorder = null
            recorderState = RecorderState.STOP
            _innerState.value = RecorderInnerState(RecorderState.STOP)
            lastAmplitudeDb = -160f
            true
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording failed", e)
            waveRecorder = null
            recorderState = RecorderState.STOP
            _innerState.value = RecorderInnerState(RecorderState.STOP)
            lastAmplitudeDb = -160f
            false
        }
    }

    override fun pauseRecording(): Boolean = try {
        waveRecorder?.pauseRecording(); true
    } catch (e: Exception) {
        Log.e(TAG, "pauseRecording failed", e); false
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun resumeRecording(): Boolean = try {
        waveRecorder?.resumeRecording(); true
    } catch (e: Exception) {
        Log.e(TAG, "resumeRecording failed", e); false
    }

    // ---------------- VAD ----------------

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun startVoiceActivityDetection(): String {
        if (!isRecording()) {
            Log.w(TAG, "VAD requested while not recording")
            return "NotRecording"
        }
        synchronized(vadLock) {
            if (isVADActive) return "AlreadyActive"
            return try {
                vad = VadSilero(
                    context = appContext,
                    sampleRate = SampleRate.SAMPLE_RATE_16K,
                    frameSize = FrameSize.FRAME_SIZE_512,
                    mode = Mode.NORMAL,
                    silenceDurationMs = 300,
                    speechDurationMs = 50
                )

                // Use VOICE_RECOGNITION to (slightly) reduce contentions with MediaRecorder
                vadAudioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (vadAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord init failed for VAD")
                    cleanupVAD()
                    return "AudioRecordInitFailed"
                }

                vadAudioRecord!!.startRecording()
                isVADActive = true
                vadJob = ioScope.launch { processVAD() }
                "Success"
            } catch (e: Exception) {
                Log.e(TAG, "startVoiceActivityDetection error", e)
                cleanupVAD()
                "Error: ${e.message}"
            }
        }
    }

    override fun stopVoiceActivityDetection(): String {
        synchronized(vadLock) {
            if (!isVADActive) return "NotActive"
            return try {
                isVADActive = false
                vadJob?.cancel(); vadJob = null
                vadAudioRecord?.let {
                    try { it.stop() } catch (_: Exception) {}
                    try { it.release() } catch (_: Exception) {}
                }
                vadAudioRecord = null
                try { vad?.close() } catch (_: Exception) {}
                vad = null
                "Success"
            } catch (e: Exception) {
                Log.e(TAG, "stopVoiceActivityDetection error", e)
                cleanupVAD()
                "Error: ${e.message}"
            }
        }
    }

    private suspend fun processVAD() {
        val buf = ShortArray(frameSize)
        var consecutiveErrors = 0
        val maxErrors = 5

        while (isVADActive && vadAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                val read = vadAudioRecord?.read(buf, 0, frameSize) ?: 0
                if (read == frameSize) {
                    consecutiveErrors = 0

                    val isVoice = vad?.isSpeech(buf) ?: false
                    val now = System.currentTimeMillis()
                    val isChange = isVoice != lastVoiceState

                    val shouldEmit = when (vadEventMode) {
                        "onChange" -> isChange
                        "throttled" -> isChange || (now - lastVADEventAt) >= vadThrottleMs
                        else -> true
                    }
                    if (shouldEmit) {
                        val eventType = if (isChange) {
                            if (isVoice) "speech_start" else "silence_start"
                        } else {
                            if (isVoice) "speech_continue" else "silence_continue"
                        }
                        withContext(Dispatchers.Main) {
                            sendVoiceActivityEvent(
                                mapOf(
                                    "isVoiceDetected" to isVoice,
                                    "timestamp" to now,
                                    "confidence" to if (isVoice) 0.85 else 0.15,
                                    "isStateChange" to isChange,
                                    "previousState" to lastVoiceState,
                                    "eventType" to eventType
                                )
                            )
                        }
                        if (isChange) lastVoiceState = isVoice
                        lastVADEventAt = now
                    }
                } else if (read < 0) {
                    if (++consecutiveErrors >= maxErrors) break
                }
                delay(32) // ~frame time
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                if (++consecutiveErrors >= maxErrors) break
                delay(50)
            }
        }
        Log.d(TAG, "VAD loop end")
    }

    private fun cleanupVAD() {
        isVADActive = false
        try { vadJob?.cancel() } catch (_: Exception) {}
        vadJob = null
        try { vadAudioRecord?.stop() } catch (_: Exception) {}
        try { vadAudioRecord?.release() } catch (_: Exception) {}
        vadAudioRecord = null
        try { vad?.close() } catch (_: Exception) {}
        vad = null
    }

    override fun setVoiceActivityThreshold(threshold: Float): String {
        // left for API compatibility; Silero uses modes
        return if (threshold in 0f..1f) {
            "NotSupported: Silero uses modes (NORMAL/AGGRESSIVE)"
        } else "InvalidThreshold"
    }

    fun setVADEventMode(mode: String, throttleMs: Int = 100): String {
        return when (mode) {
            "onChange", "onEveryFrame", "throttled" -> {
                vadEventMode = mode
                if (mode == "throttled") vadThrottleMs = throttleMs
                "VAD event mode set to $mode${if (mode == "throttled") " (${throttleMs}ms)" else ""}"
            }
            else -> "Invalid mode"
        }
    }

    fun requestAutoStartVAD() { shouldAutoStartVAD = true }

    // ----- simple elapsed ticker so JS can render timers accurately
    private fun startTicks() {
        stopTicks()
        tickJob = ioScope.launch {
            while (recorderState == RecorderState.RECORDING) {
                val elapsed = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0)
                _progress.value = RecorderProgress((elapsed / 1000))
                _metrics.value = RecorderMetrics(0) // keep placeholder metric stable
                delay(200)
            }
        }
    }

    private fun stopTicks() {
        tickJob?.cancel()
        tickJob = null
    }

    companion object {
        private const val TAG = "MediaRecorderSileroVAD"
    }
}
