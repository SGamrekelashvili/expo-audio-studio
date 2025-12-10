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
import expo.modules.audiostudio.AudioChunkManager
import expo.modules.kotlin.AppContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10

class MediaRecorderProvider(
    context: Context,
    private val sendStatusEvent: (Map<String, Any>) -> Unit,
    private val sendAmplitudeEvent: (Map<String, Any>) -> Unit,
    private val sendVoiceActivityEvent: (Map<String, Any>) -> Unit,
    private val  sendChunkEvent: (Map<String, Any>) -> Unit,
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
    private val vadAudioRecord = AtomicReference<AudioRecord?>(null)
    private val vadJob = AtomicReference<Job?>(null)
    private val isVADActive = AtomicBoolean(false)
    private val vadEventMode = AtomicReference("onEveryFrame")
    private val vadThrottleMs = AtomicReference(100)
    private var vadMode = Mode.NORMAL

    private val amplitudeRunnable = Runnable {
        val amplitude = lastAmplitudeDb.get()
        if (isActive.get()) {
            sendAmplitudeEvent(mapOf("amplitude" to amplitude))
        }
    }

    private val vadEventData = mutableMapOf<String, Any>()
    private val vadRunnable = Runnable {
        if (isActive.get() && isVADActive.get()) {
            sendVoiceActivityEvent(vadEventData)
        }
    }

    private val shouldAutoStartVAD = AtomicBoolean(false)
    private val vadLock = Any()
    private val isVADReleased = AtomicBoolean(false)

    private val sampleRateConfig = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val frameSize = 512
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRateConfig, channelConfig, audioFormat) * 4

    private var tickJob: Job? = null
    private var startedAtMs: Long = 0
    private val enableListenToChunks = AtomicBoolean(false)
    private val currentVoiceState = AtomicBoolean(false)


    private var ioScope: CoroutineScope? = null
    private fun getOrCreateScope(): CoroutineScope {
        return ioScope ?: CoroutineScope(Dispatchers.IO + SupervisorJob()).also {
            ioScope = it
        }
    }

    private var vadScope: CoroutineScope? = null
    private fun getOrCreateVADScope(): CoroutineScope {
        return vadScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "VAD coroutine error: ${e.message}", e)
        }).also {
            vadScope = it
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
    override fun isPaused(): Boolean  = recorderState.get() == RecorderState.PAUSE

    override fun getCurrentAmplitude(): Float? = if (isRecording()) lastAmplitudeDb.get() else -160f

    override fun isVoiceActivityDetectionActive(): Boolean = isVADActive.get()

    override fun releaseRecorder() {
        isActive.set(false)
        mainHandler.removeCallbacks(amplitudeRunnable)
        mainHandler.removeCallbacks(vadRunnable)
        stopTicks()

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

        // Cleanup audio chunk manager
        audioChunkManager?.stopStreaming()
        audioChunkManager = null

        // Cancel all coroutine scopes to prevent memory leaks
        vadScope?.let { scope ->
            try {
                scope.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling VAD scope: ${e.message}")
            }
        }
        vadScope = null
        
        ioScope?.let { scope ->
            try {
                scope.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling IO scope: ${e.message}")
            }
        }
        ioScope = null
    }
    override fun startRecording(context: Context, argument: RecordArgument): Boolean {
        // Ensure fresh active state for this recording session
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

        val wr = WaveRecorder(argument.outputFile).apply {
            configureWaveSettings {
                sampleRate = sampleRateConfig
                channels = channelConfig
                audioEncoding = audioFormat
            }
            noiseSuppressorActive = NoiseSuppressor.isAvailable()
            silenceDetection = false

            if(enableListenToChunks.get()){
                if (audioChunkManager == null && moduleAppContext != null) {
                    audioChunkManager = AudioChunkManager(moduleAppContext)
                    audioChunkManager?.startStreaming()
                } else if (moduleAppContext == null) {
                    Log.w(TAG, "Cannot enable chunk streaming - AppContext not available")
                }
                
                onAudioChunkCaptured = listener@{ state ->
                    if (!isActive.get() || state.isEmpty()) {
                        return@listener
                    }
                    
                    audioChunkManager?.processChunk(state, currentVoiceState.get())
                    
                    val chunks = audioChunkManager?.getAllChunks()
                    if (!chunks.isNullOrEmpty()) {
                        sendChunkEvent(mapOf(
                            "chunks" to chunks,
                            "type" to "batch",
                            "format" to "uint8array"
                        ))
                    }
                }
            }

            onStateChangeListener = { state ->
                recorderState.set(state)
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
                        if (shouldAutoStartVAD.get()) {
                            shouldAutoStartVAD.set(false)
                            getOrCreateScope().launch {
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
                val normalized = (amplitudeShort.toDouble() / 32768.0).coerceIn(0.0, 1.0)
                val db = if (normalized > 0.0) 20.0 * log10(normalized) else -160.0
                lastAmplitudeDb.set(db.toFloat())

                val now = System.currentTimeMillis()
                if (isActive.get() && now - lastAmplitudeSentAt.get() >= amplitudeUpdateIntervalMs.get()) {
                    lastAmplitudeSentAt.set(now)

                    // Remove any pending amplitude callback
                    mainHandler.removeCallbacks(amplitudeRunnable)

                    // Post reusable amplitude runnable
                    mainHandler.postDelayed(amplitudeRunnable, 1)
                }
            }
        }

        return try {
            wr.startRecording()
            waveRecorder.set(wr)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            waveRecorder.set(null)
            false
        }
    }

    override fun stopRecording(): Boolean {
        shouldAutoStartVAD.set(false)
        // Stop UI callbacks and mark inactive for this session
        isActive.set(false)
        mainHandler.removeCallbacks(amplitudeRunnable)
        mainHandler.removeCallbacks(vadRunnable)
        stopTicks() // Stop before VAD cleanup
        stopVoiceActivityDetection()

        audioChunkManager?.stopStreaming()
        audioChunkManager = null
        // Reset emitted flows to avoid holding onto old RecorderProgress/RecorderMetrics instances
        _progress.value = RecorderProgress(0)
        _metrics.value = RecorderMetrics(0)
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
            Log.d(TAG, "Recording resumed successfully")
            true
        }
    } catch (e: Exception) {
        Log.e(TAG, "resumeRecording failed", e)
        false
    }



    override fun startVoiceActivityDetection(): String {
        if (!isRecording()) {
            Log.w(TAG, "VAD requested while not recording")
            return "NotRecording"
        }
        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "startVoiceActivityDetection: RECORD_AUDIO permission not granted")
            return "PermissionDenied"
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
                isVADReleased.set(false)

                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.UNPROCESSED,
                    sampleRateConfig,
                    channelConfig,
                    audioFormat,
                    bufferSize * 2
                )

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord init failed for VAD")
                    audioRecord.release()
                    vad.set(null)
                    return "AudioRecordInitFailed"
                }

                vadAudioRecord.set(audioRecord)
                audioRecord.startRecording()
                isVADActive.set(true)

                // Use dedicated VAD scope
                val job = getOrCreateVADScope().launch {
                    processVAD()
                }
                vadJob.set(job)

                Log.d(TAG, "VAD started successfully")
                "Success"
            } catch (e: Exception) {
                Log.e(TAG, "startVoiceActivityDetection error", e)
                cleanupVAD()
                "Error: ${e.message}"
            }
        }
    }

    override fun stopVoiceActivityDetection(): String {
        Log.d(TAG, "stopVoiceActivityDetection called")

        // First, set the flag to stop the loop IMMEDIATELY
        isVADActive.set(false)
        mainHandler.removeCallbacks(vadRunnable)

        // Cancel the job OUTSIDE synchronized block to avoid deadlock
        val job = vadJob.getAndSet(null)
        if (job != null) {
            try {
                // Don't use runBlocking - just cancel and move on
                job.cancel()
                Log.d(TAG, "VAD job cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling VAD job: ${e.message}")
            }
        }
        
        try {
            vadScope?.cancel()
            vadScope = null
            Log.d(TAG, "VAD scope cancelled successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling VAD scope: ${e.message}")
        }

        synchronized(vadLock) {
            return try {
                vadAudioRecord.get()?.let { record ->
                    try {
                        if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            record.stop()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping VAD AudioRecord: ${e.message}")
                    }
                    try {
                        if (record.state == AudioRecord.STATE_INITIALIZED) {
                            record.release()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing VAD AudioRecord: ${e.message}")
                    }
                }
                vadAudioRecord.set(null)

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

    private suspend fun processVAD() {
        Log.d(TAG, "processVAD started")
        val buf = ShortArray(frameSize)
        var consecutiveErrors = 0
        val maxErrors = 5
        var localLastVoiceState = false
        var localLastVADEventAt = 0L
        
        try {
            while (isActive.get() && isVADActive.get()) {
                // Check for cancellation explicitly
                yield()
                
                val audioRecord = vadAudioRecord.get()
                if (audioRecord == null || audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.d(TAG, "VAD loop breaking - no valid audio record")
                    break
                }
                
                val read = audioRecord.read(buf, 0, frameSize)
                
                when {
                    read == frameSize -> {
                        consecutiveErrors = 0
                        val vadInstance = vad.get()
                        if (vadInstance == null) {
                            Log.w(TAG, "VAD instance is null")
                            break
                        }

                        val isVoice = vadInstance.isSpeech(buf)
                        currentVoiceState.set(isVoice)
                        val now = System.currentTimeMillis()
                        val isChange = isVoice != localLastVoiceState

                        val shouldEmit = when (vadEventMode.get()) {
                            "onChange" -> isChange
                            "throttled" -> isChange || (now - localLastVADEventAt) >= vadThrottleMs.get()
                            else -> true
                        }

                        if (shouldEmit) {
                            val eventType = if (isChange) {
                                if (isVoice) "speech_start" else "silence_start"
                            } else {
                                if (isVoice) "speech_continue" else "silence_continue"
                            }

                            // Update reusable mutable map instead of creating a new map
                            vadEventData["isVoiceDetected"] = isVoice
                            vadEventData["timestamp"] = now
                            vadEventData["confidence"] = if (isVoice) 0.85 else 0.15
                            vadEventData["isStateChange"] = isChange
                            vadEventData["previousState"] = localLastVoiceState
                            vadEventData["eventType"] = eventType

                            // Remove any pending VAD callback
                            mainHandler.removeCallbacks(vadRunnable)

                            // Post reusable vadRunnable
                            mainHandler.post(vadRunnable)

                            if (isChange) localLastVoiceState = isVoice
                            localLastVADEventAt = now
                        }

                    }
                    read < 0 -> {
                        Log.w(TAG, "VAD read error: $read")
                        if (++consecutiveErrors >= maxErrors) {
                            Log.e(TAG, "Max VAD errors reached")
                            break
                        }
                    }
                }
                
                // Use a simple delay instead of complex timing logic
                delay(32)
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "VAD cancelled normally")
            throw e  // Rethrow to properly propagate cancellation
        } catch (e: Exception) {
            Log.e(TAG, "VAD error: ${e.message}", e)
        } finally {
            Log.d(TAG, "processVAD ended")
        }
    }

    private fun cleanupVAD() {
        synchronized(vadLock) {
            if (isVADReleased.get()) {
                return
            }

            isVADReleased.set(true)
            isVADActive.set(false)

            // Cancel job first
            vadJob.get()?.cancel()
            vadJob.set(null)
            
            // Cancel vadScope to prevent any lingering coroutines
            try {
                vadScope?.cancel()
                vadScope = null
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling vadScope in cleanupVAD: ${e.message}")
            }

            vadAudioRecord.get()?.let { record ->
                try {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in cleanupVAD stop: ${e.message}")
                }

                try {
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        record.release()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in cleanupVAD release: ${e.message}")
                }
            }
            vadAudioRecord.set(null)

            try {
                vad.get()?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error in cleanupVAD close: ${e.message}")
            }
            vad.set(null)
        }
    }

    override fun setVoiceActivityThreshold(threshold: Float): String {
            if(threshold <= 0.5f){
                vadMode = Mode.NORMAL
                return "Normal Used on $threshold"
            }else if (threshold <= 0.75f){
                vadMode = Mode.AGGRESSIVE
                return "Aggressive Used on $threshold"
            }else{
                vadMode = Mode.VERY_AGGRESSIVE
                return "Very Aggressive Used on $threshold"
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
    private fun isActiveState(): Boolean = isActive.get()

    private fun startTicks() {
        tickJob?.cancel()
        tickJob = getOrCreateScope().launch {
            while (isActiveState()) {  // This should work fine
                delay(100)
                val elapsed = System.currentTimeMillis() - startedAtMs
                _progress.value = RecorderProgress(elapsed / 1000)
                _metrics.value = RecorderMetrics((elapsed / 1000).toInt())
            }
        }
    }

    private fun stopTicks() {
        tickJob?.cancel()
        tickJob = null
    }

    companion object {
        private const val TAG = "MediaRecorderProvider"
    }
}
