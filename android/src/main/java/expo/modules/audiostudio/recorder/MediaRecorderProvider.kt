package expo.modules.audiostudio.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.github.squti.androidwaverecorder.RecorderState
import com.github.squti.androidwaverecorder.WaveRecorder
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import expo.modules.audiostudio.AudioChunkManager
import expo.modules.kotlin.AppContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10
import kotlin.math.min

class MediaRecorderProvider(
    context: Context,
    private val sendStatusEvent: (Map<String, Any>) -> Unit,
    private val sendAmplitudeEvent: (Map<String, Any>) -> Unit,
    private val sendVoiceActivityEvent: (Map<String, Any>) -> Unit,
    private val sendChunkEvent: (Map<String, Any>) -> Unit,
    private val moduleAppContext: AppContext? = null
) : AudioRecorderProvider {
    private var audioChunkManager: AudioChunkManager? = null

    private val appContext = context.applicationContext

    private val waveRecorder = AtomicReference<WaveRecorder?>(null)
    private val recorderState = AtomicReference(RecorderState.STOP)

    private val _innerState = MutableStateFlow(RecorderInnerState(RecorderState.STOP))
    private val _progress = MutableStateFlow(RecorderProgress(0))
    private val _metrics = MutableStateFlow(RecorderMetrics(0))

    private val lastAmplitudeDb = AtomicReference(-160f)
    private val amplitudeUpdateIntervalMs = AtomicReference(1000L / 60L)
    private val lastAmplitudeSentAt = AtomicReference(0L)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val isActive = AtomicBoolean(true)

    private val vad = AtomicReference<VadSilero?>(null)
    private val isVADActive = AtomicBoolean(false)
    private val vadEventMode = AtomicReference("onEveryFrame")
    private val vadThrottleMs = AtomicReference(100)
    private var vadMode = Mode.NORMAL
    private val vadLock = Any()

    private val vadFrameBuffer = ShortArray(VAD_FRAME_SIZE)
    @Volatile private var vadFrameOffset = 0
    @Volatile private var lastVoiceState = false
    @Volatile private var lastVADEventAt = 0L

    private val amplitudeRunnable = Runnable {
        val amplitude = lastAmplitudeDb.get()
        if (isActive.get()) {
            sendAmplitudeEvent(mapOf("amplitude" to amplitude))
        }
    }

    private val chunkFlushRunnable = object : Runnable {
        override fun run() {
            if (!isActive.get() || !enableListenToChunks.get()) return
            val batch = audioChunkManager?.drainBatch()
            if (batch != null) {
                sendChunkEvent(batch)
            }
            mainHandler.postDelayed(this, CHUNK_FLUSH_INTERVAL_MS)
        }
    }

    private val shouldAutoStartVAD = AtomicBoolean(false)

    private val enableListenToChunks = AtomicBoolean(false)
    private val currentVoiceState = AtomicBoolean(false)

    private var ioScope: CoroutineScope? = null
    private fun getOrCreateScope(): CoroutineScope {
        return ioScope ?: CoroutineScope(Dispatchers.IO + SupervisorJob()).also {
            ioScope = it
        }
    }

    override fun setAmplitudeUpdateFrequency(frequencyHz: Double) {
        val f = frequencyHz.coerceIn(1.0, 120.0)
        amplitudeUpdateIntervalMs.set((1000.0 / f).toLong())
    }

    override fun recorderStatus(): StateFlow<RecorderInnerState> = _innerState
    override fun recorderTimeElapsed(): StateFlow<RecorderProgress> = _progress
    override fun recorderMetrics(): StateFlow<RecorderMetrics> = _metrics

    override fun isRecording(): Boolean = recorderState.get() == RecorderState.RECORDING
    override fun isPaused(): Boolean = recorderState.get() == RecorderState.PAUSE

    override fun getCurrentAmplitude(): Float? = if (isRecording()) lastAmplitudeDb.get() else -160f

    override fun isVoiceActivityDetectionActive(): Boolean = isVADActive.get()

    override fun releaseRecorder() {
        isActive.set(false)
        mainHandler.removeCallbacks(amplitudeRunnable)
        mainHandler.removeCallbacks(chunkFlushRunnable)

        try {
            stopVoiceActivityDetection()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VAD in releaseRecorder: ${e.message}")
        }
        try {
            waveRecorder.get()?.apply {
                onAmplitudeListener = null
                onStateChangeListener = null
                onAudioChunkCaptured = null
            }
            waveRecorder.get()?.stopRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording in releaseRecorder: ${e.message}")
        }

        waveRecorder.set(null)
        audioChunkManager?.stopStreaming()
        audioChunkManager = null

        ioScope?.let { scope ->
            try { scope.cancel() } catch (e: Exception) {
                Log.e(TAG, "Error cancelling IO scope: ${e.message}")
            }
        }
        ioScope = null
    }

    override fun startRecording(context: Context, argument: RecordArgument): Boolean {
        isActive.set(true)
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Record audio permission not granted")
            return false
        }

        try {
            waveRecorder.get()?.stopRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping previous recording: ${e.message}")
        }
        waveRecorder.set(null)
        recorderState.set(RecorderState.STOP)
        _innerState.value = RecorderInnerState(RecorderState.STOP)
        lastAmplitudeDb.set(-160f)
        lastAmplitudeSentAt.set(0L)

        val file = File(argument.outputFile)
        file.parentFile?.mkdirs()

        if (enableListenToChunks.get()) {
            if (audioChunkManager == null && moduleAppContext != null) {
                audioChunkManager = AudioChunkManager(moduleAppContext)
                audioChunkManager?.startStreaming()
            } else if (moduleAppContext == null) {
                Log.w(TAG, "Cannot enable chunk streaming - AppContext not available")
            }
        }

        val wr = WaveRecorder(argument.outputFile).apply {
            configureWaveSettings {
                sampleRate = SAMPLE_RATE
                channels = CHANNEL_CONFIG
                audioEncoding = AUDIO_ENCODING
            }
            noiseSuppressorActive = NoiseSuppressor.isAvailable()
            silenceDetection = false

            onAudioChunkCaptured = listener@{ rawPcm ->
                if (!isActive.get() || rawPcm.isEmpty()) return@listener

                if (isVADActive.get()) {
                    processVADChunk(rawPcm)
                }

                if (enableListenToChunks.get()) {
                    audioChunkManager?.processChunk(rawPcm, currentVoiceState.get())
                }
            }

            onStateChangeListener = { state ->
                recorderState.set(state)
                _innerState.value = RecorderInnerState(state)
                val statusStr = when (state) {
                    RecorderState.RECORDING -> "recording"
                    RecorderState.PAUSE -> "paused"
                    RecorderState.STOP -> "stopped"
                    RecorderState.SKIPPING_SILENCE -> "skipping_silence"
                }
                mainHandler.post {
                    if (isActive.get()) {
                        sendStatusEvent(mapOf("status" to statusStr))
                    }
                }
                if (state == RecorderState.RECORDING && shouldAutoStartVAD.getAndSet(false)) {
                    getOrCreateScope().launch {
                        try {
                            startVoiceActivityDetection()
                        } catch (e: Exception) {
                            Log.e(TAG, "VAD auto-start failed: ${e.message}")
                        }
                    }
                }
            }

            onAmplitudeListener = { amplitudeShort ->
                val normalized = (amplitudeShort.toDouble() / 32768.0).coerceIn(0.0, 1.0)
                val db = if (normalized > 0.0) 20.0 * log10(normalized) else -160.0
                lastAmplitudeDb.set(db.toFloat())

                val now = System.currentTimeMillis()
                if (isActive.get() && now - lastAmplitudeSentAt.get() >= amplitudeUpdateIntervalMs.get()) {
                    lastAmplitudeSentAt.set(now)
                    mainHandler.removeCallbacks(amplitudeRunnable)
                    mainHandler.post(amplitudeRunnable)
                }
            }
        }

        return try {
            wr.startRecording()
            waveRecorder.set(wr)
            if (enableListenToChunks.get()) {
                mainHandler.postDelayed(chunkFlushRunnable, CHUNK_FLUSH_INTERVAL_MS)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            waveRecorder.set(null)
            false
        }
    }

    /**
     * Converts raw PCM bytes from WaveRecorder into short samples and accumulates
     * them into 512-sample frames for the Silero VAD.
     */
    private fun processVADChunk(rawPcm: ByteArray) {
        val shorts = ShortArray(rawPcm.size / 2)
        ByteBuffer.wrap(rawPcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

        var offset = 0
        while (offset < shorts.size) {
            val toCopy = min(VAD_FRAME_SIZE - vadFrameOffset, shorts.size - offset)
            System.arraycopy(shorts, offset, vadFrameBuffer, vadFrameOffset, toCopy)
            vadFrameOffset += toCopy
            offset += toCopy

            if (vadFrameOffset >= VAD_FRAME_SIZE) {
                processVADFrame(vadFrameBuffer.copyOf())
                vadFrameOffset = 0
            }
        }
    }

    private fun processVADFrame(frame: ShortArray) {
        val vadInstance = vad.get() ?: return
        if (!isVADActive.get()) return

        try {
            val isVoice = vadInstance.isSpeech(frame)
            currentVoiceState.set(isVoice)
            val now = System.currentTimeMillis()
            val isChange = isVoice != lastVoiceState

            val shouldEmit = when (vadEventMode.get()) {
                "onChange" -> isChange
                "throttled" -> isChange || (now - lastVADEventAt) >= vadThrottleMs.get()
                else -> true
            }

            if (shouldEmit) {
                val eventType = if (isChange) {
                    if (isVoice) "speech_start" else "silence_start"
                } else {
                    if (isVoice) "speech_continue" else "silence_continue"
                }

                val snapshot = mapOf<String, Any>(
                    "isVoiceDetected" to isVoice,
                    "timestamp" to now,
                    "confidence" to if (isVoice) 0.85 else 0.15,
                    "isStateChange" to isChange,
                    "previousState" to lastVoiceState,
                    "eventType" to eventType
                )

                mainHandler.post {
                    if (isActive.get() && isVADActive.get()) {
                        sendVoiceActivityEvent(snapshot)
                    }
                }

                if (isChange) lastVoiceState = isVoice
                lastVADEventAt = now
            }
        } catch (e: Exception) {
            Log.e(TAG, "VAD frame processing error: ${e.message}")
        }
    }

    override fun stopRecording(): Boolean {
        shouldAutoStartVAD.set(false)
        isActive.set(false)
        mainHandler.removeCallbacks(amplitudeRunnable)
        mainHandler.removeCallbacks(chunkFlushRunnable)
        stopVoiceActivityDetection()

        audioChunkManager?.stopStreaming()
        audioChunkManager = null
        _progress.value = RecorderProgress(0)
        _metrics.value = RecorderMetrics(0)

        ioScope?.let { scope ->
            try { scope.cancel() } catch (_: Exception) {}
        }
        ioScope = null

        return try {
            waveRecorder.get()?.apply {
                onAmplitudeListener = null
                onStateChangeListener = null
                onAudioChunkCaptured = null
            }
            waveRecorder.get()?.stopRecording()
            waveRecorder.set(null)
            recorderState.set(RecorderState.STOP)
            _innerState.value = RecorderInnerState(RecorderState.STOP)
            lastAmplitudeDb.set(-160f)
            true
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording failed", e)
            waveRecorder.set(null)
            recorderState.set(RecorderState.STOP)
            _innerState.value = RecorderInnerState(RecorderState.STOP)
            lastAmplitudeDb.set(-160f)
            false
        }
    }

    override fun pauseRecording(): Boolean = try {
        waveRecorder.get()?.pauseRecording()
        true
    } catch (e: Exception) {
        Log.e(TAG, "pauseRecording failed", e)
        false
    }

    override fun resumeRecording(): Boolean = try {
        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "resumeRecording: RECORD_AUDIO permission not granted")
            false
        } else {
            waveRecorder.get()?.resumeRecording()
            true
        }
    } catch (e: Exception) {
        Log.e(TAG, "resumeRecording failed", e)
        false
    }

    /**
     * Creates a Silero VAD instance. No separate AudioRecord needed;
     * audio data arrives from WaveRecorder's onAudioChunkCaptured callback.
     */
    override fun startVoiceActivityDetection(): String {
        if (!isRecording()) {
            Log.w(TAG, "VAD requested while not recording")
            return "NotRecording"
        }

        synchronized(vadLock) {
            if (isVADActive.get()) {
                Log.w(TAG, "VAD already active")
                return "AlreadyActive"
            }

            return try {
                val vadInstance = VadSilero(
                    context = appContext,
                    sampleRate = SampleRate.SAMPLE_RATE_16K,
                    frameSize = FrameSize.FRAME_SIZE_512,
                    mode = vadMode,
                    silenceDurationMs = 300,
                    speechDurationMs = 50
                )
                vad.set(vadInstance)

                vadFrameOffset = 0
                lastVoiceState = false
                lastVADEventAt = 0L
                currentVoiceState.set(false)

                isVADActive.set(true)
                Log.d(TAG, "VAD started successfully (shared audio stream)")
                "Success"
            } catch (e: Exception) {
                Log.e(TAG, "startVoiceActivityDetection error", e)
                isVADActive.set(false)
                try { vad.get()?.close() } catch (_: Exception) {}
                vad.set(null)
                "Error: ${e.message}"
            }
        }
    }

    override fun stopVoiceActivityDetection(): String {
        isVADActive.set(false)

        synchronized(vadLock) {
            return try {
                vadFrameOffset = 0
                try {
                    vad.get()?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing VAD: ${e.message}")
                }
                vad.set(null)
                Log.d(TAG, "VAD stopped successfully")
                "Success"
            } catch (e: Exception) {
                Log.e(TAG, "stopVoiceActivityDetection error", e)
                "Error: ${e.message}"
            }
        }
    }

    override fun setVoiceActivityThreshold(threshold: Float): String {
        return if (threshold <= 0.5f) {
            vadMode = Mode.NORMAL
            "Normal Used on $threshold"
        } else if (threshold <= 0.75f) {
            vadMode = Mode.AGGRESSIVE
            "Aggressive Used on $threshold"
        } else {
            vadMode = Mode.VERY_AGGRESSIVE
            "Very Aggressive Used on $threshold"
        }
    }

    override fun setVADEventMode(mode: String, throttleMs: Int): String {
        return when (mode) {
            "onChange", "onEveryFrame", "throttled" -> {
                vadEventMode.set(mode)
                if (mode == "throttled") vadThrottleMs.set(throttleMs)
                "VAD event mode set to $mode${if (mode == "throttled") " (${throttleMs}ms)" else ""}"
            }
            else -> "Invalid mode"
        }
    }

    override fun setListenToChunks(enabled: Boolean): Boolean {
        enableListenToChunks.set(enabled)
        return enabled
    }

    override fun requestAutoStartVAD() {
        shouldAutoStartVAD.set(true)
    }

    companion object {
        private const val TAG = "MediaRecorderProvider"
        private const val VAD_FRAME_SIZE = 512
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_FLUSH_INTERVAL_MS = 100L
        private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
