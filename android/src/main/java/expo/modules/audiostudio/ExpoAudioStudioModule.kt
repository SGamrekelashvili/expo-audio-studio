package expo.modules.audiostudio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import expo.modules.audiostudio.player.AudioPlayerProvider
import expo.modules.audiostudio.player.MediaPlayerProvider
import expo.modules.audiostudio.recorder.AudioRecorderProvider
import expo.modules.audiostudio.recorder.MediaRecorderProvider
import expo.modules.audiostudio.recorder.RecordArgument
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

class ExpoAudioStudioModule : Module() {
    private var lifecycleObserver: LifecycleEventObserver? = null
    private var observerActivity: LifecycleOwner? = null
    private var audioRecorderProvider: AudioRecorderProvider? = null
    private var audioPlayerProvider: AudioPlayerProvider? = null
    private var utilProvider: UtilProvider? = null
    private var lastRecordingOutput = ""

    private var audioManager: AudioManager? = null
    private var isVADEnabledFromJS = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var permissionRunnable: Runnable? = null

    private val context get() = requireNotNull(appContext.reactContext)

    private fun getAudioRecorderProvider(): AudioRecorderProvider {
        if (audioRecorderProvider == null) {
            val sendStatusEvent: (Map<String, Any>) -> Unit = { result ->
                val status = result["status"] ?: "stopped"
                sendEvent("onRecorderStatusChange", bundleOf("status" to status))
            }
            val sendAmplitudeEvent: (Map<String, Any>) -> Unit = { result ->
                val amplitude = result["amplitude"] ?: -160f
                sendEvent("onRecorderAmplitude", bundleOf("amplitude" to amplitude))
            }
            val sendVoiceActivityEvent: (Map<String, Any>) -> Unit = { result ->
                sendEvent(
                    "onVoiceActivityDetected",
                    bundleOf(
                        "isVoiceDetected" to (result["isVoiceDetected"] ?: false),
                        "timestamp" to (result["timestamp"] ?: System.currentTimeMillis())
                    )
                )
            }
            val sendChunkEvent: (Map<String, Any>) -> Unit = { result ->
                sendEvent("onAudioChunk", bundleOf(
                    "data" to (result["data"] as? String ?: ""),
                    "timestamp" to (result["timestamp"] as? Long ?: 0L),
                    "endTimestamp" to (result["endTimestamp"] as? Long ?: 0L),
                    "hasVoice" to (result["hasVoice"] as? Boolean ?: false),
                    "size" to (result["size"] as? Int ?: 0),
                    "encoding" to (result["encoding"] as? String ?: "base64")
                ))
            }

            audioRecorderProvider = MediaRecorderProvider(
                context,
                sendStatusEvent,
                sendAmplitudeEvent,
                sendVoiceActivityEvent,
                sendChunkEvent,
                appContext // Pass AppContext for SharedObject
            )
        }
        return audioRecorderProvider ?: throw IllegalStateException("Failed to initialize AudioRecorderProvider")
    }

    private fun getAudioPlayerProvider(): AudioPlayerProvider {
        if (audioPlayerProvider == null) audioPlayerProvider = MediaPlayerProvider(context)
        return audioPlayerProvider ?: throw IllegalStateException("Failed to initialize AudioPlayerProvider")
    }

    private fun getUtilProvider(): UtilProvider {
        if (utilProvider == null) utilProvider = AndroidUtilProvider()
        return utilProvider ?: throw IllegalStateException("Failed to initialize UtilProvider")
    }
    
    private fun removeLifecycleObserver() {
        try {
            val observer = lifecycleObserver
            val activity = observerActivity
            
            if (observer != null && activity != null) {
                Log.d("ExpoAudioStudioModule", "Removing lifecycle observer")
                activity.lifecycle.removeObserver(observer)
            }
            
            // Clear references
            lifecycleObserver = null
            observerActivity = null
        } catch (e: Exception) {
            Log.e("ExpoAudioStudioModule", "Error removing lifecycle observer: ${e.message}")
        }
    }

    override fun definition() = ModuleDefinition {
        Name("ExpoAudioStudio")
        Events("onPlayerStatusChange", "onRecorderStatusChange", "onRecorderAmplitude", "onVoiceActivityDetected","onAudioChunk")

        OnCreate {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            // Register lifecycle observer to prevent crashes when app goes to background
            try {
                val activity = appContext.currentActivity
                if (activity != null && activity is LifecycleOwner) {
                    lifecycleObserver = LifecycleEventObserver { _, event ->
                        val recorderProvider = audioRecorderProvider
                        when (event) {
                            Lifecycle.Event.ON_PAUSE -> {
                                // Only access provider if it's not null
                                recorderProvider?.let { recorder ->
                                    if (recorder.isRecording() && !recorder.isPaused()) {
                                        recorder.pauseRecording()
                                    }
                                }
                            }
                            Lifecycle.Event.ON_STOP -> {
                                // Only access provider if it's not null
                                recorderProvider?.stopVoiceActivityDetection()
                            }
                            else -> {}
                        }
                    }
                    // Store reference to activity for cleanup
                    observerActivity = activity
                    // Add the observer on the main thread to avoid crashes
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        activity.lifecycle.addObserver(lifecycleObserver!!)
                    } else {
                        mainHandler.post {
                            lifecycleObserver?.let { observer ->
                                observerActivity?.lifecycle?.addObserver(observer)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ExpoAudioStudioModule", "Failed to register lifecycle observer: ${e.message}")
            }
        }

        OnDestroy {
            try {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    removeLifecycleObserver()
                } else {
                    mainHandler.post { removeLifecycleObserver() }
                }
                audioPlayerProvider?.let {
                    try { it.releasePlayer() } catch (_: Exception) {}
                }
                audioPlayerProvider = null
                
                audioRecorderProvider?.let { 
                    try { it.stopRecording() } catch (_: Exception) {}
                    try { it.releaseRecorder() } catch (_: Exception) {}
                }
                audioRecorderProvider = null
                
                utilProvider = null
                audioManager = null
                isVADEnabledFromJS = false
                lastRecordingOutput = ""
            } catch (e: Exception) {
                Log.e("ExpoAudioStudioModule", "Cleanup error: ${e.message}")
            }
        }

        Function("preparePlayer") { url: String ->
            val ok = getAudioPlayerProvider().preparePlayer(url) { result ->
                sendEvent(
                    "onPlayerStatusChange",
                    bundleOf(
                        "isPlaying" to (result["isPlaying"] ?: false),
                        "didJustFinish" to (result["didJustFinish"] ?: false)
                    )
                )
            }
            if (ok) "prepared" else "PlaybackFailedException: Failed to prepare player"
        }

        Function("startPlaying") { url: String ->
            val ok = getAudioPlayerProvider().startPlaying(url) { result ->
                sendEvent(
                    "onPlayerStatusChange",
                    bundleOf(
                        "isPlaying" to (result["isPlaying"] ?: false),
                        "didJustFinish" to (result["didJustFinish"] ?: false)
                    )
                )
            }
            if (ok) {
                sendEvent("onPlayerStatusChange", bundleOf("isPlaying" to true, "didJustFinish" to false))
                "playing"
            } else "PlaybackFailedException: Failed to start playback"
        }

        Function("stopPlayer") {
            val ok = getAudioPlayerProvider().stopPlaying()
            if (ok) {
                sendEvent("onPlayerStatusChange", bundleOf("isPlaying" to false, "didJustFinish" to false))
                "stopped"
            } else "NoPlayerException"
        }

        Function("pausePlayer") {
            if (getAudioPlayerProvider().pausePlaying()) {
                sendEvent("onPlayerStatusChange", bundleOf("isPlaying" to false, "didJustFinish" to false))
                "paused"
            } else "NoPlayerException"
        }

        Function("resumePlayer") {
            if (getAudioPlayerProvider().resumePlaying()) {
                sendEvent("onPlayerStatusChange", bundleOf("isPlaying" to true, "didJustFinish" to false))
                "playing"
            } else "PlaybackFailedException: Failed to resume playback"
        }

        Function("seekTo") { position: Double ->
            val success = getAudioPlayerProvider().seekTo(position.toInt())
            if (success) {
                return@Function "success"
            }
            return@Function "SeekException: Failed to seek"
        }

        Function("setPlaybackSpeed") { speed: String ->
            val success = getAudioPlayerProvider().setPlaybackSpeed(speed)
            if (success) {
                return@Function "success"
            }
            return@Function "PlaybackFailedException: Failed to set playback speed"
        }

        Function("setAmplitudeUpdateFrequency") { hz: Double ->
            try {
                getAudioRecorderProvider().setAmplitudeUpdateFrequency(hz)
                "Amplitude frequency set to $hz Hz"
            } catch (e: Exception) { "Error: ${e.message}" }
        }

        Function("setListenToChunks"){ enable: Boolean? ->
            getAudioRecorderProvider().setListenToChunks(enable == true)
        }

        Function("startRecording") { directoryPath: String? ->
            audioPlayerProvider?.stopPlaying()
            val ts = System.currentTimeMillis()
            val fileName = "recording_${ts}.wav"

            lastRecordingOutput = if (!directoryPath.isNullOrEmpty()) {
                val clean = directoryPath.replace("file://", "")
                val dir = File(clean).apply { if (!exists()) mkdirs() }
                File(dir, fileName).absolutePath
            } else {
                getUtilProvider().fileCacheLocationFullPath(context, fileName)
            }

            val ok = getAudioRecorderProvider().startRecording(context, RecordArgument(outputFile = lastRecordingOutput))
            if (!ok) return@Function "RecordingFailedException: Failed to start recording"

            if (isVADEnabledFromJS) {
                getAudioRecorderProvider().requestAutoStartVAD()
            }
            lastRecordingOutput
        }

        Function("stopRecording") {
            try {
                if (getAudioRecorderProvider().isVoiceActivityDetectionActive()) {
                    getAudioRecorderProvider().stopVoiceActivityDetection()
                }
            } catch (e: Exception) {
                Log.e("ExpoAudioStudioModule", "VAD stop failed: ${e.message}")
            }
            val ok = getAudioRecorderProvider().stopRecording()
            if (ok) lastRecordingOutput else "NoRecorderException"
        }

        Function("pauseRecording") {
            try {
                if (getAudioRecorderProvider().pauseRecording()) "paused" else "NoRecorderException"
            } catch (e: Exception) { "Error: ${e.message}" }
        }

        Function("resumeRecording") {
            try {
                val ok = getAudioRecorderProvider().resumeRecording()
                if (ok && isVADEnabledFromJS && !getAudioRecorderProvider().isVoiceActivityDetectionActive()) {
                    getAudioRecorderProvider().requestAutoStartVAD()
                }
                if (ok) "resumed" else "NoRecorderException"
            } catch (e: Exception) { "Error: ${e.message}" }
        }

        Function("lastRecording") {
            if (lastRecordingOutput.isNotBlank() && File(lastRecordingOutput).exists()) lastRecordingOutput else null
        }

        AsyncFunction("listRecordings") { directoryPath: String?, promise: Promise ->
            try {
                val dir = if (!directoryPath.isNullOrEmpty()) File(directoryPath.replace("file://", "")) else context.cacheDir
                if (!dir.exists()) { promise.resolve(emptyList<Any>()); return@AsyncFunction }
                val results = dir.listFiles { f -> f.isFile && f.extension.lowercase() in listOf("wav", "mp3", "m4a", "aac") }
                    ?.map { f ->
                        mapOf(
                            "path" to f.absolutePath,
                            "name" to f.name,
                            "size" to f.length(),
                            "lastModified" to f.lastModified(),
                            "duration" to runCatching {
                                getAudioPlayerProvider().getAudioDuration(f.absolutePath).toDouble() / 1000.0
                            }.getOrElse { 0.0 }
                        )
                    } ?: emptyList()
                promise.resolve(results)
            } catch (e: Exception) {
                Log.e("ExpoAudioStudioModule", "listRecordings error: ${e.message}")
                promise.resolve(emptyList<Any>())
            }
        }

        AsyncFunction("joinAudioFiles") { filePaths: List<String>, outputPath: String, promise: Promise ->
            try {
                if (filePaths.size < 2) { promise.resolve("Error: At least 2 audio files are required"); return@AsyncFunction }
                val inputs = filePaths.map { File(it.replace("file://", "")) }.onEach {
                    if (!it.exists()) throw IllegalArgumentException("Input not found: ${it.absolutePath}")
                    if (!it.name.lowercase().endsWith(".wav")) throw IllegalArgumentException("Only WAV supported: ${it.name}")
                }

                val out = File(outputPath.replace("file://", "")).apply { parentFile?.mkdirs(); if (exists()) delete() }

                val headerBytes = ByteArray(44)
                java.io.BufferedInputStream(java.io.FileInputStream(inputs.first())).use { bis ->
                    val read = bis.read(headerBytes)
                    if (read < 44) { promise.resolve("Error: Invalid WAV header"); return@AsyncFunction }
                }
                val riff = String(headerBytes.sliceArray(0..3))
                val wave = String(headerBytes.sliceArray(8..11))
                if (riff != "RIFF" || wave != "WAVE") { promise.resolve("Error: Invalid WAV file"); return@AsyncFunction }

                var totalAudioBytes = 0L
                out.outputStream().buffered().use { os ->
                    os.write(headerBytes)
                    inputs.forEach { f ->
                        java.io.BufferedInputStream(java.io.FileInputStream(f)).use { bis ->
                            val dataOffset = findWavDataOffset(bis)
                            if (dataOffset < 0) return@forEach
                            val buf = ByteArray(8192)
                            var read: Int
                            while (bis.read(buf).also { read = it } > 0) {
                                os.write(buf, 0, read)
                                totalAudioBytes += read
                            }
                        }
                    }
                    os.flush()
                }
                updateWavHeader(out, totalAudioBytes)
                promise.resolve(out.absolutePath)
            } catch (e: Exception) {
                Log.e("ExpoAudioStudioModule", "joinAudioFiles error", e)
                promise.resolve("Error: ${e.message}")
            }
        }

        Function("setVoiceActivityThreshold") { threshold: Float ->
            runCatching { getAudioRecorderProvider().setVoiceActivityThreshold(threshold) }.getOrElse { "Error: ${it.message}" }
        }

        Function("setVADEnabled") { enabled: Boolean ->
            try {
                if (enabled) {
                    isVADEnabledFromJS = true
                    val p = getAudioRecorderProvider()
                    if (p.isRecording()) {
                        "VAD enabled and started: ${p.startVoiceActivityDetection()}"
                    } else {
                        "VAD enabled: Will auto-start with next recording"
                    }
                } else {
                    isVADEnabledFromJS = false
                    "VAD disabled: ${getAudioRecorderProvider().stopVoiceActivityDetection()}"
                }
            } catch (e: Exception) { "Error: ${e.message}" }
        }

        Property("isVADEnabled") { isVADEnabledFromJS }

        Function("setVADEventMode") { mode: String, throttleMs: Int? ->
           getAudioRecorderProvider().setVADEventMode(mode, throttleMs ?: 100)
        }

        Property("isVADActive") { getAudioRecorderProvider().isVoiceActivityDetectionActive() }

        Property("playerStatus") {
            val ps = getAudioPlayerProvider().playerStatus()
            if (ps != null) {
                mapOf(
                    "isPlaying" to getAudioPlayerProvider().isPlaying(),
                    "currentTime" to ps.currentSeconds,
                    "duration" to ps.duration,
                    "speed" to getAudioPlayerProvider().getPlaybackSpeed()
                )
            } else {
                mapOf("isPlaying" to false, "currentTime" to 0, "duration" to 0, "speed" to 1.0f)
            }
        }

        Property("currentPosition") {
            getAudioPlayerProvider().getCurrentPosition().toDouble() / 1000.0
        }

        Function("getDuration") { uri: String ->
            runCatching { getAudioPlayerProvider().getAudioDuration(uri).toDouble() / 1000.0 }.getOrElse { 0.0 }
        }

        AsyncFunction("getAudioAmplitudes") { fileUrl: String, barsCount: Int, promise: Promise ->
            try {
                val r = AudioAmplitudeAnalyzer.getAudioAmplitudes(context, fileUrl, barsCount)
                if (r.success) {
                    promise.resolve(mapOf(
                        "success" to true,
                        "amplitudes" to r.amplitudes.toList(),
                        "duration" to r.duration,
                        "sampleRate" to r.sampleRate,
                        "barsCount" to r.amplitudes.size
                    ))
                } else {
                    promise.resolve(mapOf(
                        "success" to false,
                        "error" to (r.error ?: "Unknown error"),
                        "amplitudes" to emptyList<Float>(),
                        "duration" to r.duration,
                        "sampleRate" to r.sampleRate
                    ))
                }
            } catch (e: Exception) {
                Log.e("ExpoAudioStudioModule", "getAudioAmplitudes error", e)
                promise.resolve(mapOf("success" to false, "error" to "Error: ${e.message}", "amplitudes" to emptyList<Float>(), "duration" to 0.0, "sampleRate" to 0.0))
            }
        }

        AsyncFunction("requestMicrophonePermission") { promise: Promise ->
            try {
                val permission = Manifest.permission.RECORD_AUDIO
                val activity = appContext.currentActivity
                if (activity == null) {
                    promise.resolve(mapOf("status" to "undetermined", "canAskAgain" to true, "granted" to false)); return@AsyncFunction
                }
                if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                    promise.resolve(mapOf("status" to "granted", "canAskAgain" to true, "granted" to true)); return@AsyncFunction
                }
                val prefs = context.getSharedPreferences("expo.modules.audiostudio.permissions", Context.MODE_PRIVATE)
                prefs.edit { putBoolean("has_asked_for_microphone", true) }
                ActivityCompat.requestPermissions(activity, arrayOf(permission), 123)

                permissionRunnable?.let { mainHandler.removeCallbacks(it) }

                var pollCount = 0
                val maxPolls = 60
                val pollIntervalMs = 500L
                val initialPermissionState = ContextCompat.checkSelfPermission(context, permission)
                var resolved = false

                val pollRunnable = object : Runnable {
                    override fun run() {
                        if (resolved) return
                        pollCount++
                        try {
                            val currentState = ContextCompat.checkSelfPermission(context, permission)
                            if (currentState == PackageManager.PERMISSION_GRANTED) {
                                resolved = true
                                promise.resolve(mapOf("status" to "granted", "canAskAgain" to true, "granted" to true))
                                return
                            }
                            if (currentState != initialPermissionState || pollCount >= maxPolls) {
                                resolved = true
                                val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
                                promise.resolve(mapOf("status" to "denied", "canAskAgain" to canAskAgain, "granted" to false))
                                return
                            }
                            mainHandler.postDelayed(this, pollIntervalMs)
                        } catch (e: Exception) {
                            if (!resolved) {
                                resolved = true
                                promise.reject("ERR_PERMISSION", "Failed to check permission result: ${e.message}", e)
                            }
                        }
                    }
                }
                permissionRunnable = pollRunnable
                mainHandler.postDelayed(pollRunnable, pollIntervalMs)
            } catch (e: Exception) {
                promise.reject("ERR_PERMISSION", "Failed to request permission: ${e.message}", e)
            }
        }

        AsyncFunction("getMicrophonePermissionStatus") { promise: Promise ->
            try {
                val permission = Manifest.permission.RECORD_AUDIO
                val activity = appContext.currentActivity
                if (activity == null) {
                    promise.resolve(mapOf("status" to "undetermined", "canAskAgain" to true, "granted" to false)); return@AsyncFunction
                }
                if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                    promise.resolve(mapOf("status" to "granted", "canAskAgain" to true, "granted" to true)); return@AsyncFunction
                }
                val prefs = context.getSharedPreferences("expo.modules.audiostudio.permissions", Context.MODE_PRIVATE)
                val hasAskedBefore = prefs.getBoolean("has_asked_for_microphone", false)
                val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission) || !hasAskedBefore
                val status = if (!hasAskedBefore) "undetermined" else "denied"
                promise.resolve(mapOf("status" to status, "canAskAgain" to canAskAgain, "granted" to false))
            } catch (e: Exception) {
                promise.reject("ERR_PERMISSION", "Failed to check permission status: ${e.message}", e)
            }
        }
    }

    /**
     * Scans a WAV input stream past RIFF/WAVE header chunks to find the "data" chunk,
     * leaving the stream positioned at the start of the audio data.
     * Returns the data chunk size, or -1 if not found.
     */
    private fun findWavDataOffset(input: java.io.InputStream): Int {
        val header = ByteArray(12)
        if (input.read(header) < 12) return -1
        if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") return -1

        val chunkHeader = ByteArray(8)
        while (true) {
            if (input.read(chunkHeader) < 8) return -1
            val chunkId = String(chunkHeader, 0, 4)
            val chunkSize = java.nio.ByteBuffer.wrap(chunkHeader, 4, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            if (chunkId == "data") return chunkSize
            var remaining = chunkSize.toLong()
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped <= 0) { input.read(); remaining -= 1 }
                else remaining -= skipped
            }
        }
    }

    private fun updateWavHeader(file: File, dataSize: Long) {
        var raf: java.io.RandomAccessFile? = null
        try {
            raf = java.io.RandomAccessFile(file, "rw")
            raf.seek(0)
            val riffBytes = ByteArray(4); raf.read(riffBytes)
            if (String(riffBytes) != "RIFF") return
            val totalFileSize = dataSize + 36
            raf.seek(4); raf.writeInt(Integer.reverseBytes(totalFileSize.toInt()))
            raf.seek(12)
            var dataPos = -1L
            while (raf.filePointer < raf.length() - 8) {
                val id = ByteArray(4); raf.read(id)
                val sizeBytes = ByteArray(4); raf.read(sizeBytes)
                val size = java.nio.ByteBuffer.wrap(sizeBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                if (String(id) == "data") { dataPos = raf.filePointer - 4; break }
                raf.seek(raf.filePointer + size)
            }
            if (dataPos != -1L) { raf.seek(dataPos); raf.writeInt(Integer.reverseBytes(dataSize.toInt())) }
        } catch (e: Exception) {
            Log.e("ExpoAudioStudioModule", "updateWavHeader error: ${e.message}", e)
        } finally {
            try { raf?.close() } catch (_: Exception) {}
        }
    }
}
