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

class ExpoAudioStudioModule : Module() {
    private var audioRecorderProvider: AudioRecorderProvider? = null
    private var audioPlayerProvider: AudioPlayerProvider? = null
    private var utilProvider: UtilProvider? = null
    private var lastRecordingOutput = ""

    private var audioManager: AudioManager? = null
    private var isVADEnabledFromJS = false

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
                val amplitude = result["base64"] ?: -160f
                sendEvent("onAudioChunk", bundleOf("base64" to amplitude))
            }

            audioRecorderProvider = MediaRecorderProvider(
                context,
                sendStatusEvent,
                sendAmplitudeEvent,
                sendVoiceActivityEvent,
                sendChunkEvent
            )
        }
        return audioRecorderProvider!!
    }

    private fun getAudioPlayerProvider(): AudioPlayerProvider {
        if (audioPlayerProvider == null) audioPlayerProvider = MediaPlayerProvider(context)
        return audioPlayerProvider!!
    }

    private fun getUtilProvider(): UtilProvider {
        if (utilProvider == null) utilProvider = AndroidUtilProvider()
        return utilProvider!!
    }

    override fun definition() = ModuleDefinition {
        Name("ExpoAudioStudio")
        Events("onPlayerStatusChange", "onRecorderStatusChange", "onRecorderAmplitude", "onVoiceActivityDetected","onAudioChunk")

        OnCreate {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }

        OnDestroy {
            try {
                audioPlayerProvider?.let { it.stopPlaying(); it.releasePlayer() }
                audioRecorderProvider?.let { it.stopRecording(); it.releaseRecorder() }
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

        Function("listRecordings") { directoryPath: String? ->
            try {
                val dir = if (!directoryPath.isNullOrEmpty()) File(directoryPath.replace("file://", "")) else context.cacheDir
                if (!dir.exists()) return@Function emptyList()
                dir.listFiles { f -> f.isFile && f.extension.lowercase() in listOf("wav", "mp3", "m4a", "aac") }
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
            } catch (e: Exception) {
                Log.e("ExpoAudioStudioModule", "listRecordings error: ${e.message}")
                emptyList()
            }
        }

        Function("joinAudioFiles") { filePaths: List<String>, outputPath: String ->
            try {
                if (filePaths.size < 2) return@Function "Error: At least 2 audio files are required"
                val inputs = filePaths.map { File(it.replace("file://", "")) }.onEach {
                    if (!it.exists()) throw IllegalArgumentException("Input not found: ${it.absolutePath}")
                    if (!it.name.lowercase().endsWith(".wav")) throw IllegalArgumentException("Only WAV supported: ${it.name}")
                }

                val out = File(outputPath.replace("file://", "")).apply { parentFile?.mkdirs(); if (exists()) delete() }

                val first = inputs.first().readBytes()
                if (first.size < 44) return@Function "Error: Invalid WAV header"
                val riff = String(first.sliceArray(0..3))
                val wave = String(first.sliceArray(8..11))
                if (riff != "RIFF" || wave != "WAVE") return@Function "Error: Invalid WAV file"

                var totalAudioBytes = 0L
                out.outputStream().buffered().use { os ->
                    os.write(first, 0, 44)
                    inputs.forEach { f ->
                        val bytes = f.readBytes()
                        if (bytes.size >= 44) {
                            val size = bytes.size - 44
                            os.write(bytes, 44, size)
                            totalAudioBytes += size
                        }
                    }
                    os.flush()
                }
                updateWavHeader(out, totalAudioBytes)
                out.absolutePath
            } catch (e: Exception) {
                Log.e("ExpoAudioStudioModule", "joinAudioFiles error", e)
                "Error: ${e.message}"
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

        Function("getAudioAmplitudes") { fileUrl: String, barsCount: Int ->
            runCatching {
                val r = AudioAmplitudeAnalyzer.getAudioAmplitudes(context, fileUrl, barsCount)
                if (r.success) {
                    mapOf(
                        "success" to true,
                        "amplitudes" to r.amplitudes.toList(),
                        "duration" to r.duration,
                        "sampleRate" to r.sampleRate,
                        "barsCount" to r.amplitudes.size
                    )
                } else {
                    mapOf(
                        "success" to false,
                        "error" to (r.error ?: "Unknown error"),
                        "amplitudes" to emptyList<Float>(),
                        "duration" to r.duration,
                        "sampleRate" to r.sampleRate
                    )
                }
            }.getOrElse {
                Log.e("ExpoAudioStudioModule", "getAudioAmplitudes error", it)
                mapOf("success" to false, "error" to "Error: ${it.message}", "amplitudes" to emptyList<Float>(), "duration" to 0.0, "sampleRate" to 0.0)
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
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            promise.resolve(mapOf("status" to "granted", "canAskAgain" to true, "granted" to true))
                        } else {
                            val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
                            promise.resolve(mapOf("status" to "denied", "canAskAgain" to canAskAgain, "granted" to false))
                        }
                    } catch (e: Exception) {
                        promise.reject("ERR_PERMISSION", "Failed to check permission result: ${e.message}", e)
                    }
                }, 2500)
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

    private fun updateWavHeader(file: File, dataSize: Long) {
        try {
            val raf = java.io.RandomAccessFile(file, "rw")
            raf.seek(0)
            val riffBytes = ByteArray(4); raf.read(riffBytes)
            if (String(riffBytes) != "RIFF") { raf.close(); return }
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
            raf.close()
        } catch (e: Exception) {
            Log.e("ExpoAudioStudioModule", "updateWavHeader error: ${e.message}", e)
        }
    }
}
