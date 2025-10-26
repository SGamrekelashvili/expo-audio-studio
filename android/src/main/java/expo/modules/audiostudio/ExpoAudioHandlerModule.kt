package expo.modules.audiostudio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import expo.modules.audiostudio.player.AudioPlayerProvider
import expo.modules.audiostudio.player.MediaPlayerProvider
import expo.modules.audiostudio.recorder.AudioRecorderProvider
import expo.modules.audiostudio.recorder.MediaRecorderProviderWithSileroVAD
import expo.modules.audiostudio.recorder.RecordArgument
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise

import java.io.File

class ExpoAudioStudioModule : Module() {
  // MEMORY FIX: Use lazy initialization to avoid creating heavy objects when idle
  private var audioRecorderProvider: AudioRecorderProvider? = null
  private var audioPlayerProvider: AudioPlayerProvider? = null
  private var utilProvider: UtilProvider? = null
  private var lastRecordingOutput=""
  private var wasPlayingBeforeInterruption: Boolean = false
  private var wasRecordingBeforeInterruption: Boolean = false
  private var audioManager: AudioManager? = null
  private var audioFocusRequest: AudioFocusRequest? = null
  private var isVADEnabledFromJS = false

  // Audio focus change listener - KOTLIN FIX: Use explicit object instead of lambda
  private val audioFocusChangeListener = object : AudioManager.OnAudioFocusChangeListener {
      override fun onAudioFocusChange(focusChange: Int) {
          when (focusChange) {
              AudioManager.AUDIOFOCUS_LOSS,
              AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                  // Save state and pause audio (only if providers exist)
                  wasPlayingBeforeInterruption = audioPlayerProvider?.isPlaying() ?: false
                  wasRecordingBeforeInterruption = audioRecorderProvider?.isRecording() ?: false

                  if (wasPlayingBeforeInterruption) {
                      audioPlayerProvider?.pausePlaying()
                      sendEvent(
                          "onPlayerStatusChange",
                          bundleOf(
                              "isPlaying" to false,
                              "didJustFinish" to false
                          )
                      )
                  }

                  if (wasRecordingBeforeInterruption) {
                      // Optional: handle pausing recording if needed
                  }
              }
              AudioManager.AUDIOFOCUS_GAIN -> {
                  // Resume audio if it was playing before
                  if (wasPlayingBeforeInterruption) {
                      audioPlayerProvider?.resumePlaying()
                      sendEvent(
                          "onPlayerStatusChange",
                          bundleOf(
                              "isPlaying" to true,
                              "didJustFinish" to false
                          )
                      )
                      wasPlayingBeforeInterruption = false
                  }

                  if (wasRecordingBeforeInterruption) {
                      // Optional: handle resuming recording if needed
                  }
              }
          }
      }
  }

  private val context
      get() = requireNotNull(appContext.reactContext)

  // MEMORY FIX: Lazy initialization methods to create objects only when needed
  private fun getAudioRecorderProvider(): AudioRecorderProvider {
    if (audioRecorderProvider == null) {
      val sendStatusEvent: (Map<String, Any>) -> Unit = { result ->
        val status = result["status"] ?: false
        this@ExpoAudioStudioModule.sendEvent(
          "onRecorderStatusChange",
          bundleOf("status" to status)
        )
      }

      val sendAmplitudeEvent: (Map<String, Any>) -> Unit = { result ->
        val amplitude = result["amplitude"] ?: false
        this@ExpoAudioStudioModule.sendEvent(
          "onRecorderAmplitude",
          bundleOf("amplitude" to amplitude)
        )
      }

      val sendVoiceActivityEvent: (Map<String, Any>) -> Unit = { result ->
        val isVoiceDetected = result["isVoiceDetected"] ?: false
        val timestamp = result["timestamp"] ?: System.currentTimeMillis()
        this@ExpoAudioStudioModule.sendEvent(
          "onVoiceActivityDetected",
          bundleOf(
            "isVoiceDetected" to isVoiceDetected,
            "timestamp" to timestamp,
          )
        )
      }

      audioRecorderProvider = MediaRecorderProviderWithSileroVAD(
        context,
        SendStatusEvent = sendStatusEvent,
        SendAmplitudeEvent = sendAmplitudeEvent,
        SendVoiceActivityEvent = sendVoiceActivityEvent
      )
    }
    return audioRecorderProvider!!
  }

  private fun getAudioPlayerProvider(): AudioPlayerProvider {
    if (audioPlayerProvider == null) {
      audioPlayerProvider = MediaPlayerProvider(context)
    }
    return audioPlayerProvider!!
  }

  private fun getUtilProvider(): UtilProvider {
    if (utilProvider == null) {
      utilProvider = AndroidUtilProvider()
    }
    return utilProvider!!
  }

  // Audio focus management functions
  private fun requestAudioFocus(): Boolean {
      if (audioManager == null) {
          Log.e("ExpoAudioStudioModule", "Audio manager is null")
          return false
      }

      val listener = audioFocusChangeListener
      if (listener == null) {
          Log.e("ExpoAudioStudioModule", "Audio focus listener not initialized")
          return false
      }

      val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          // For Android Oreo and above, use the new audio focus API
          val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
              .setOnAudioFocusChangeListener(listener)
              .build()

          audioFocusRequest = focusRequest
          audioManager!!.requestAudioFocus(focusRequest)
      } else {
          // For older Android versions
          @Suppress("DEPRECATION")
          audioManager!!.requestAudioFocus(
              listener,
              AudioManager.STREAM_MUSIC,
              AudioManager.AUDIOFOCUS_GAIN
          )
      }

      return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
  }

  private fun abandonAudioFocus() {
      if (audioManager == null) return

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          audioFocusRequest?.let { audioManager!!.abandonAudioFocusRequest(it) }
      } else {
          @Suppress("DEPRECATION")
          audioFocusChangeListener?.let { audioManager!!.abandonAudioFocus(it) }
      }
  }


  // Each module class must implement the definition function. The definition consists of components
  // that describes the module's functionality and behavior.
  // See https://docs.expo.dev/modules/module-api for more details about available components.
  override fun definition() = ModuleDefinition {
    // Sets the name of the module that JavaScript code will use to refer to the module. Takes a string as an argument.
    // Can be inferred from module's class name, but it's recommended to set it explicitly for clarity.
    // The module will be accessible from `requireNativeModule('ExpoAudioStudio')` in JavaScript.
    Name("ExpoAudioStudio")
    Events("onPlayerStatusChange","onRecorderStatusChange","onRecorderAmplitude","onVoiceActivityDetected")

    OnCreate {
        // MEMORY FIX: Only initialize lightweight objects, heavy objects created lazily
        // Initialize audio manager for handling audio focus
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    OnDestroy {
        // MEMORY FIX: Clean up resources only if they were created
        try {
            audioPlayerProvider?.let {
                it.stopPlaying()
                it.releasePlayer()
            }
            audioRecorderProvider?.let {
                it.stopRecording()
                it.releaseRecorder()
            }

            // Abandon audio focus if needed
            abandonAudioFocus()
        } catch (e: Exception) {
            Log.e("ExpoAudioStudioModule", "Error during cleanup: ${e.message}")
        }
    }



      Function("preparePlayer") { url: String ->
        val success = getAudioPlayerProvider().preparePlayer(url){ result ->
            val isPlaying = result["isPlaying"] ?: false
            val didJustFinish = result["didJustFinish"] ?: false

            Log.d("ExpoAudioStudioModule", "Prepare result - Is playing: $isPlaying, Ended by itself: $didJustFinish")

            this@ExpoAudioStudioModule.sendEvent(
                "onPlayerStatusChange",
                bundleOf(
                    "isPlaying" to isPlaying,
                    "didJustFinish" to didJustFinish
                ))
        }

        if(success){
            return@Function "prepared"
        }
        return@Function "PlaybackFailedException: Failed to prepare player"
      }

      Function("startPlaying") { url: String ->
        // Request audio focus before playing
        if (requestAudioFocus()) {
            val success = getAudioPlayerProvider().startPlaying(url){ result ->
                val isPlaying = result["isPlaying"] ?: false
                val didJustFinish = result["didJustFinish"] ?: false

                Log.d("ExpoAudioStudioModule", "Is playing: $isPlaying, Ended by itself: $didJustFinish")

                this@ExpoAudioStudioModule.sendEvent(
                    "onPlayerStatusChange",
                    bundleOf(
                        "isPlaying" to isPlaying,
                        "didJustFinish" to didJustFinish
                    ))
            }

            if(success){
                this@ExpoAudioStudioModule.sendEvent(
                    "onPlayerStatusChange",
                    bundleOf(
                        "isPlaying" to true,
                        "didJustFinish" to false
                    ))
                return@Function "playing"
            }
            return@Function "PlaybackFailedException: Failed to start playback"
        } else {
            return@Function "PlaybackFailedException: Could not gain audio focus"
        }
    }

    Function("stopPlayer"){
        val success = getAudioPlayerProvider().stopPlaying()
        abandonAudioFocus()

        if(success){
            this@ExpoAudioStudioModule.sendEvent(
                "onPlayerStatusChange",
                bundleOf(
                    "isPlaying" to false,
                    "didJustFinish" to false
                ))
            return@Function "stopped"
        }
        return@Function "NoPlayerException"
    }

    Function("pausePlayer") {
        val success = getAudioPlayerProvider().pausePlaying()

        if(success){
            this@ExpoAudioStudioModule.sendEvent(
                "onPlayerStatusChange",
                bundleOf(
                    "isPlaying" to false,
                    "didJustFinish" to false
                ))
            return@Function "paused"
        }
        return@Function "NoPlayerException"
    }

    Function("resumePlayer") {
        if (requestAudioFocus()) {
            val success = getAudioPlayerProvider().resumePlaying()

            if(success){
                this@ExpoAudioStudioModule.sendEvent(
                    "onPlayerStatusChange",
                    bundleOf(
                        "isPlaying" to true,
                        "didJustFinish" to false
                    ))
                return@Function "playing"
            }
            return@Function "PlaybackFailedException: Failed to resume playback"
        } else {
            return@Function "PlaybackFailedException: Could not gain audio focus"
        }
    }

      Property("isRecording") {
          audioRecorderProvider?.isRecording() ?: false
      }

      Property("isPaused") {
          audioRecorderProvider?.isPaused() ?: false
      }


      Function("setAmplitudeUpdateFrequency") { frequencyHz: Double ->
        try {
            Log.d("ExpoAudioStudioModule", "setAmplitudeUpdateFrequency called with $frequencyHz Hz")
            
            // Cast to the correct provider type and set frequency
            when (val provider = audioRecorderProvider) {
                is MediaRecorderProviderWithSileroVAD -> {
                    provider.setAmplitudeUpdateFrequency(frequencyHz)
                }
                else -> {
                    Log.w("ExpoAudioStudioModule", "Current recorder provider does not support configurable amplitude frequency")
                }
            }
            
            return@Function "Amplitude frequency set to $frequencyHz Hz"
        } catch (e: Exception) {
            Log.e("ExpoAudioStudioModule", "Error setting amplitude frequency: ${e.message}")
            return@Function "Error: ${e.message}"
        }
    }
    
    Function("startRecording") { directoryPath: String? ->
        // Stop any playback first
        audioPlayerProvider?.stopPlaying()

        // Generate a unique filename with timestamp
        val timestamp = System.currentTimeMillis()
        val fileName = "recording_${timestamp}.wav"
        
        // Use custom directory if provided, otherwise use default cache location
        lastRecordingOutput = if (!directoryPath.isNullOrEmpty()) {
            // Remove file:// prefix if present and ensure directory exists
            val cleanPath = directoryPath.replace("file://", "")
            val directory = File(cleanPath)
            if (!directory.exists()) {
                directory.mkdirs()
            }
            File(directory, fileName).absolutePath
        } else {
            getUtilProvider().fileCacheLocationFullPath(context, fileName)
        }

        // Request audio focus for recording
        if (requestAudioFocus()) {
            val success = getAudioRecorderProvider().startRecording(context, RecordArgument(
                outputFile = lastRecordingOutput,
            ))

            if (success) {
                wasRecordingBeforeInterruption = true
                
                // Auto-start VAD if enabled
                if (isVADEnabledFromJS) {
                    Log.d("ExpoAudioStudioModule", "Auto-starting VAD because recording started and VAD is enabled")
                    try {
                        val vadResult = getAudioRecorderProvider().startVoiceActivityDetection()
                        Log.d("ExpoAudioStudioModule", "VAD auto-start result: $vadResult")
                    } catch (e: Exception) {
                        Log.e("ExpoAudioStudioModule", "Error auto-starting VAD: ${e.message}")
                    }
                }
                
                return@Function lastRecordingOutput
            }
            return@Function "RecordingFailedException: Failed to start recording"
        } else {
            return@Function "RecordingFailedException: Could not gain audio focus"
        }
    }

    Function("stopRecording"){
        // Auto-stop VAD if it was running
        try {
            val vadActive = getAudioRecorderProvider().isVoiceActivityDetectionActive()
            if (vadActive) {
                Log.d("ExpoAudioStudioModule", "Auto-stopping VAD because recording stopped")
                val vadResult = getAudioRecorderProvider().stopVoiceActivityDetection()
                Log.d("ExpoAudioStudioModule", "VAD auto-stop result: $vadResult")
            }
        } catch (e: Exception) {
            Log.e("ExpoAudioStudioModule", "Error auto-stopping VAD: ${e.message}")
        }
        
        val success = getAudioRecorderProvider().stopRecording()
        abandonAudioFocus()
        wasRecordingBeforeInterruption = false

        if (success) {
            return@Function lastRecordingOutput
        }
        return@Function "NoRecorderException"
    }

    Function("pauseRecording"){
        try {
            val success = getAudioRecorderProvider().pauseRecording()
            
            if (success) {
                // Send status update on main thread
                Handler(Looper.getMainLooper()).post {
                    sendEvent(
                        "onRecorderStatusChange",
                        bundleOf("status" to "paused")
                    )
                }
                return@Function "paused"
            }
            return@Function "NoRecorderException"
        } catch (e: Exception) {
            Log.e("ExpoAudioStudioModule", "Error pausing recording: ${e.message}")
            return@Function "Error: ${e.message}"
        }
    }

    Function("resumeRecording"){
        try {
            val success = getAudioRecorderProvider().resumeRecording()
            
            if (success) {
                // Send status update on main thread
                Handler(Looper.getMainLooper()).post {
                    sendEvent(
                        "onRecorderStatusChange",
                        bundleOf("status" to "recording")
                    )
                }
                return@Function "resumed"
            }
            return@Function "NoRecorderException"
        } catch (e: Exception) {
            Log.e("ExpoAudioStudioModule", "Error resuming recording: ${e.message}")
            return@Function "Error: ${e.message}"
        }
    }

    Function("lastRecording") {
        if (lastRecordingOutput.isNotBlank() && File(lastRecordingOutput).exists()) {
            return@Function lastRecordingOutput
        }
        return@Function null
    }

    // MARK: - File Management Functions
    Function("listRecordings") { directoryPath: String? ->
        try {
            val directory = if (!directoryPath.isNullOrEmpty()) {
                // Remove file:// prefix if present
                val cleanPath = directoryPath.replace("file://", "")
                File(cleanPath)
            } else {
                // Use default cache directory
                context.cacheDir
            }

            if (!directory.exists()) {
                return@Function emptyList<Map<String, Any>>()
            }

            val audioFiles = directory.listFiles { file ->
                file.isFile && (file.extension.lowercase() in listOf("wav", "mp3", "m4a", "aac"))
            }?.map { file ->
                mapOf(
                    "path" to file.absolutePath,
                    "name" to file.name,
                    "size" to file.length(),
                    "lastModified" to file.lastModified(),
                    "duration" to try {
                        getAudioPlayerProvider().getAudioDuration(file.absolutePath, context).toDouble() / 1000.0
                    } catch (e: Exception) {
                        0.0
                    }
                )
            } ?: emptyList()

            return@Function audioFiles
        } catch (e: Exception) {
            Log.e("ExpoAudioStudioModule", "Error listing recordings: ${e.message}")
            return@Function emptyList<Map<String, Any>>()
        }
    }

    Function("joinAudioFiles") { filePaths: List<String>, outputPath: String ->
        try {
            Log.d("ExpoAudioStudioModule", "Starting joinAudioFiles with ${filePaths.size} files")
            
            if (filePaths.size < 2) {
                return@Function "Error: At least 2 audio files are required for joining"
            }

            // Validate input files exist and are WAV files
            val inputFiles = filePaths.mapIndexed { index, path ->
                val cleanPath = path.replace("file://", "")
                val file = File(cleanPath)
                
                Log.d("ExpoAudioStudioModule", "Checking file $index: $cleanPath")
                
                if (!file.exists()) {
                    return@Function "Error: Input file not found: $cleanPath"
                }
                
                if (!file.name.lowercase().endsWith(".wav")) {
                    return@Function "Error: Only WAV files are supported for joining. File: ${file.name}"
                }
                
                Log.d("ExpoAudioStudioModule", "File $index validated: ${file.length()} bytes")
                file
            }

            // Prepare output file
            val cleanOutputPath = outputPath.replace("file://", "")
            val outputFile = File(cleanOutputPath)
            outputFile.parentFile?.mkdirs()
            
            // Delete existing output file if it exists
            if (outputFile.exists()) {
                outputFile.delete()
                Log.d("ExpoAudioStudioModule", "Deleted existing output file")
            }

            Log.d("ExpoAudioStudioModule", "Starting WAV file concatenation")
            
            // Read first file to get WAV header information
            val firstFileBytes = inputFiles[0].readBytes()
            if (firstFileBytes.size < 44) {
                return@Function "Error: First file is too small to be a valid WAV file"
            }
            
            // Validate WAV header
            val riffHeader = String(firstFileBytes.sliceArray(0..3))
            val waveHeader = String(firstFileBytes.sliceArray(8..11))
            
            if (riffHeader != "RIFF" || waveHeader != "WAVE") {
                return@Function "Error: First file is not a valid WAV file"
            }
            
            Log.d("ExpoAudioStudioModule", "WAV header validated")

            // Create output stream and write combined audio
            val outputStream = outputFile.outputStream().buffered()
            var totalAudioDataSize = 0L
            
            try {
                // Write WAV header from first file (we'll update sizes later)
                outputStream.write(firstFileBytes, 0, 44)
                
                // Write audio data from all files
                for ((index, inputFile) in inputFiles.withIndex()) {
                    Log.d("ExpoAudioStudioModule", "Processing file $index: ${inputFile.name}")
                    
                    val fileBytes = inputFile.readBytes()
                    
                    if (fileBytes.size < 44) {
                        Log.w("ExpoAudioStudioModule", "Skipping file $index: too small")
                        continue
                    }
                    
                    // Extract audio data (skip 44-byte header)
                    val audioDataSize = fileBytes.size - 44
                    if (audioDataSize > 0) {
                        outputStream.write(fileBytes, 44, audioDataSize)
                        totalAudioDataSize += audioDataSize
                        Log.d("ExpoAudioStudioModule", "Added ${audioDataSize} bytes from file $index")
                    }
                }
                
                outputStream.flush()
            } finally {
                outputStream.close()
            }
            
            Log.d("ExpoAudioStudioModule", "Total audio data written: $totalAudioDataSize bytes")

            // Update WAV header with correct file sizes
            updateWavHeader(outputFile, totalAudioDataSize)
            
            val finalSize = outputFile.length()
            Log.d("ExpoAudioStudioModule", "Final joined file size: $finalSize bytes")

            return@Function outputFile.absolutePath
        } catch (e: Exception) {
            Log.e("ExpoAudioStudioModule", "Error joining audio files: ${e.message}", e)
            return@Function "Error: ${e.message}"
        }
    }

    // MARK: - Voice Activity Detection Functions


    Function("setVoiceActivityThreshold") { threshold: Float ->
        try {
            val result = getAudioRecorderProvider().setVoiceActivityThreshold(threshold)
            return@Function result
        } catch (e: Exception) {
            Log.e("ExpoAudioStudioModule", "Error setting VAD threshold: ${e.message}")
            return@Function "Error: ${e.message}"
        }
    }

    Function("setVADEnabled") { enabled: Boolean ->
        try {
            Log.d("ExpoAudioStudioModule", "setVADEnabled called with enabled: $enabled")
            
            if (enabled) {
                // Set VAD preference
                isVADEnabledFromJS = true
                val provider = getAudioRecorderProvider()
                
                // If recording is active, start VAD immediately
                if (provider.isRecording()) {
                    val result = provider.startVoiceActivityDetection()
                    return@Function "VAD enabled and started: $result"
                } else {
                    return@Function "VAD enabled: Will auto-start with next recording"
                }
            } else {
                // Disable VAD preference and stop if active
                isVADEnabledFromJS = false
                val result = getAudioRecorderProvider().stopVoiceActivityDetection()
                return@Function "VAD disabled: $result"
            }
        } catch (e: Exception) {
            Log.e("ExpoAudioStudioModule", "Error setting VAD enabled: ${e.message}")
            return@Function "Error: ${e.message}"
        }
    }

    Property("isVADActive") {
        try {
            return@Property getAudioRecorderProvider().isVoiceActivityDetectionActive()
        } catch (e: Exception) {
            Log.e("ExpoAudioStudioModule", "Error checking VAD active status: ${e.message}")
            return@Property false
        }
    }
    
    Property("isVADEnabled") {
        try {
            return@Property isVADEnabledFromJS
        } catch (e: Exception) {
            Log.e("ExpoAudioStudioModule", "Error checking VAD enabled status: ${e.message}")
            return@Property false
        }
    }

    Property("isVoiceActivityDetectionActive") {
        try {
            return@Property getAudioRecorderProvider().isVoiceActivityDetectionActive()
        } catch (e: Exception) {
            Log.e("ExpoAudioStudioModule", "Error checking VAD status: ${e.message}")
            return@Property false
        }
    }

      Property("playerStatus") {
        val playerStatus = getAudioPlayerProvider().playerStatus()
        Log.d("ExpoAudioStudioModule", "Player status: $playerStatus")

        if (playerStatus != null) {
            mapOf(
                "isPlaying" to getAudioPlayerProvider().isPlaying(),
                "currentTime" to playerStatus.currentSeconds,
                "duration" to playerStatus.duration,
                "speed" to getAudioPlayerProvider().getPlaybackSpeed()
            )
        } else {
            mapOf(
                "isPlaying" to false,
                "currentTime" to 0,
                "duration" to 0,
                "speed" to 1.0f
            )
        }
    }

    // Audio information properties and functions
      Property("currentPosition") {
        // Get the current position directly from the player for maximum accuracy
        val currentPosition = getAudioPlayerProvider().getCurrentPosition()
        currentPosition.toDouble() / 1000.0 // Convert ms to seconds
    }

    Function("getDuration") { uri: String ->
        try {
            val duration = getAudioPlayerProvider().getAudioDuration(uri, context)
            return@Function duration.toDouble() / 1000.0  // Convert ms to seconds
        } catch (e: Exception) {
            Log.e("ExpoAudioStudioModule", "Error getting duration: ${e.message}")
            return@Function 0.0
        }
    }

    Function("getAudioAmplitudes") { fileUrl: String, barsCount: Int ->
        try {
            Log.d("ExpoAudioStudioModule", "getAudioAmplitudes called for fileUrl: $fileUrl, barsCount: $barsCount")
            
            // MEMORY OPTIMIZED: Use object (singleton) instead of creating new instance
            val result = AudioAmplitudeAnalyzer.getAudioAmplitudes(context, fileUrl, barsCount)

            if (result.success) {
                Log.d("ExpoAudioStudioModule", "Successfully analyzed audio - ${result.amplitudes.size} bars, duration: ${result.duration}s")
                return@Function mapOf(
                    "success" to true,
                    "amplitudes" to result.amplitudes.toList(),
                    "duration" to result.duration,
                    "sampleRate" to result.sampleRate,
                    "barsCount" to result.amplitudes.size
                )
            } else {
                Log.e("ExpoAudioStudioModule", "Analysis failed - ${result.error}")
                return@Function mapOf(
                    "success" to false,
                    "error" to (result.error ?: "Unknown error"),
                    "amplitudes" to emptyList<Float>(),
                    "duration" to result.duration,
                    "sampleRate" to result.sampleRate
                )
            }
        } catch (e: Exception) {
            Log.e("ExpoAudioStudioModule", "Error in getAudioAmplitudes: ${e.message}", e)
            return@Function mapOf(
                "success" to false,
                "error" to "Error: ${e.message}",
                "amplitudes" to emptyList<Float>(),
                "duration" to 0.0,
                "sampleRate" to 0.0
            )
        }
    }

    Property("meterLevel") {
        // Get current amplitude level from recorder
        val amplitude = audioRecorderProvider?.getCurrentAmplitude() ?: -160.0f
        amplitude
    }

    // Set playback speed
    Function("setPlaybackSpeed") { speedString: String ->
        try {
            val speedFloat = speedString.toFloat()

            // Validate speed range
            if (speedFloat in 0.5f..2.0f) {
                val success = getAudioPlayerProvider().setPlaybackSpeed(speedString)
                if (success) {
                    return@Function "Playback speed set to $speedFloat"
                }
                return@Function "SetSpeedException: Failed to set speed"
            } else {
                Log.d("ExpoAudioStudioModule", "setPlaybackSpeed: Speed out of range.")
                return@Function "SetSpeedException: Speed out of range (0.5-2.0)"
            }
        } catch (e: NumberFormatException) {
            return@Function "SetSpeedException: Invalid speed format"
        }
    }

    // Seek to position
    Function("seekTo") { position: Double ->
        val success = getAudioPlayerProvider().seekTo(position.toInt())
        if (success) {
            return@Function "success"
        }
        return@Function "SeekException: Failed to seek"
    }

    // Permission Handling Functions
    AsyncFunction("requestMicrophonePermission") { promise: Promise ->
        try {
            // For pre-Marshmallow devices, permissions are granted at install time
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                val response = mapOf(
                    "status" to "granted",
                    "canAskAgain" to true,
                    "granted" to true
                )
                promise.resolve(response)
                return@AsyncFunction
            }

            val permission = Manifest.permission.RECORD_AUDIO
            val activity = appContext.currentActivity

            if (activity == null) {
                val response = mapOf(
                    "status" to "undetermined",
                    "canAskAgain" to true,
                    "granted" to false
                )
                promise.resolve(response)
                return@AsyncFunction
            }

            // Check if we already have permission
            val currentStatus = ContextCompat.checkSelfPermission(context, permission)
            if (currentStatus == PackageManager.PERMISSION_GRANTED) {
                val response = mapOf(
                    "status" to "granted",
                    "canAskAgain" to true,
                    "granted" to true
                )
                promise.resolve(response)
                return@AsyncFunction
            }

            // Mark that we're about to request permissions
            val prefs = context.getSharedPreferences("expo.modules.audiostudio.permissions", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("has_asked_for_microphone", true).apply()

            // Since we can't directly hook into the permission result callback in an Expo module,
            // we'll use a delay to check the result after the dialog should be dismissed
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(permission),
                123 // Request code
            )

            // On Android, shouldShowRequestPermissionRationale is complex:
            // - Returns false before first request
            // - Returns true if user denied without "never ask again"
            // - Returns false if user denied with "never ask again"
            //
            // We need a longer delay to ensure the permission dialog has been handled
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // Get the new permission status
                    val newStatus = ContextCompat.checkSelfPermission(context, permission)
                    val granted = newStatus == PackageManager.PERMISSION_GRANTED

                    // If granted, canAskAgain is always true
                    if (granted) {
                        val response = mapOf(
                            "status" to "granted",
                            "canAskAgain" to true,
                            "granted" to true
                        )
                        promise.resolve(response)
                        return@postDelayed
                    }

                    // If denied, we need to check if they selected "never ask again"
                    val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)

                    val response = mapOf(
                        "status" to "denied",
                        "canAskAgain" to canAskAgain,
                        "granted" to false
                    )
                    promise.resolve(response)
                } catch (e: Exception) {
                    promise.reject("ERR_PERMISSION", "Failed to check permission result: ${e.message}", e)
                }
            }, 2500) // Use 2.5 seconds for better reliability
        } catch (e: Exception) {
            promise.reject("ERR_PERMISSION", "Failed to request permission: ${e.message}", e)
        }
    }

    AsyncFunction("getMicrophonePermissionStatus") { promise: Promise ->
        try {
            // For pre-Marshmallow devices, permissions are granted at install time
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                val response = mapOf(
                    "status" to "granted",
                    "canAskAgain" to true,
                    "granted" to true
                )
                promise.resolve(response)
                return@AsyncFunction
            }

            val permission = Manifest.permission.RECORD_AUDIO
            val activity = appContext.currentActivity

            if (activity == null) {
                val response = mapOf(
                    "status" to "undetermined",
                    "canAskAgain" to true,
                    "granted" to false
                )
                promise.resolve(response)
                return@AsyncFunction
            }

            // Check current permission status
            val currentStatus = ContextCompat.checkSelfPermission(context, permission)

            if (currentStatus == PackageManager.PERMISSION_GRANTED) {
                // Permission is granted
                val response = mapOf(
                    "status" to "granted",
                    "canAskAgain" to true,
                    "granted" to true
                )
                promise.resolve(response)
                return@AsyncFunction
            }

            // Permission is not granted, but we need to determine if it's denied or undetermined
            // shouldShowRequestPermissionRationale returns:
            // - true if the user has denied the permission but not checked "never ask again"
            // - false if the user has denied and checked "never ask again" OR if the app has never requested it

            // To determine if we've ever asked for permission before, we check shared preferences
            val prefs = context.getSharedPreferences("expo.modules.audiostudio.permissions", Context.MODE_PRIVATE)
            val hasAskedBefore = prefs.getBoolean("has_asked_for_microphone", false)

            val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission) || !hasAskedBefore

            val status = if (!hasAskedBefore) {
                // If we've never asked before, it's undetermined
                "undetermined"
            } else {
                // If we've asked before, it's denied
                "denied"
            }

            val response = mapOf(
                "status" to status,
                "canAskAgain" to canAskAgain,
                "granted" to false
            )
            promise.resolve(response)
        } catch (e: Exception) {
            promise.reject("ERR_PERMISSION", "Failed to check permission status: ${e.message}", e)
        }
    }

    // ============================================================================
    // AUDIO SESSION FUNCTIONS
    // ============================================================================

    AsyncFunction("configureAudioSession") { config: Map<String, Any> ->
        configureAudioSessionAsync(config)
    }

    AsyncFunction("activateAudioSession") {
        activateAudioSessionAsync()
    }

    AsyncFunction("deactivateAudioSession") {
        deactivateAudioSessionAsync()
    }
  }

  // ============================================================================
  // AUDIO SESSION IMPLEMENTATION
  // ============================================================================

  private fun configureAudioSessionAsync(config: Map<String, Any>) {
    try {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // Parse category - Android doesn't have exact equivalents, but we can map some behaviors
        val category = config["category"] as? String ?: "playAndRecord"
        
        // Parse options
        val options = config["options"] as? Map<String, Boolean> ?: emptyMap()
        
        // Configure audio focus based on category and options
        when (category) {
            "ambient", "soloAmbient" -> {
                // For ambient categories, don't request audio focus
                Log.d("ExpoAudioStudioModule", "Audio session configured for ambient playback")
            }
            "playback" -> {
                // For playback, we'll handle audio focus in the player
                Log.d("ExpoAudioStudioModule", "Audio session configured for playback")
            }
            "record", "playAndRecord" -> {
                // For recording, ensure we can record
                Log.d("ExpoAudioStudioModule", "Audio session configured for recording")
            }
            else -> {
                Log.w("ExpoAudioStudioModule", "Unknown audio session category: $category")
            }
        }
        
        // Handle options
        if (options["defaultToSpeaker"] == true) {
            audioManager.isSpeakerphoneOn = true
            Log.d("ExpoAudioStudioModule", "Audio session configured to use speaker")
        }
        
        Log.d("ExpoAudioStudioModule", "Audio session configured successfully")
        
    } catch (e: Exception) {
        Log.e("ExpoAudioStudioModule", "Error configuring audio session: ${e.message}")
        throw Exception("Failed to configure audio session: ${e.message}")
    }
  }

  private fun activateAudioSessionAsync() {
    try {
        // On Android, audio session activation is handled automatically by the system
        // We can ensure audio focus is properly managed in our audio components
        Log.d("ExpoAudioStudioModule", "Audio session activated (Android handles this automatically)")
    } catch (e: Exception) {
        Log.e("ExpoAudioStudioModule", "Error activating audio session: ${e.message}")
        throw Exception("Failed to activate audio session: ${e.message}")
    }
  }

  private fun deactivateAudioSessionAsync() {
    try {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // Release any audio focus we might have
        // This will be handled by individual audio components
        
        // Reset speaker phone if it was enabled
        audioManager.isSpeakerphoneOn = false
        
        Log.d("ExpoAudioStudioModule", "Audio session deactivated")
    } catch (e: Exception) {
        Log.e("ExpoAudioStudioModule", "Error deactivating audio session: ${e.message}")
        throw Exception("Failed to deactivate audio session: ${e.message}")
    }
  }

  // Helper function to update WAV file header with correct file size
  private fun updateWavHeader(file: File, dataSize: Long) {
    try {
      val randomAccessFile = java.io.RandomAccessFile(file, "rw")
      
      Log.d("ExpoAudioStudioModule", "Updating WAV header with data size: $dataSize")
      
      // Read and verify RIFF header
      randomAccessFile.seek(0)
      val riffBytes = ByteArray(4)
      randomAccessFile.read(riffBytes)
      val riffHeader = String(riffBytes)
      
      if (riffHeader != "RIFF") {
        Log.e("ExpoAudioStudioModule", "Invalid RIFF header: $riffHeader")
        randomAccessFile.close()
        return
      }
      
      // Update file size in RIFF header (bytes 4-7)
      // Total file size = data size + header size - 8 (RIFF chunk size doesn't include first 8 bytes)
      val totalFileSize = dataSize + 36 // 44 byte header - 8 bytes for RIFF header
      randomAccessFile.seek(4)
      randomAccessFile.writeInt(Integer.reverseBytes(totalFileSize.toInt()))
      
      Log.d("ExpoAudioStudioModule", "Updated RIFF chunk size to: $totalFileSize")
      
      // Find the data chunk by searching through the file
      randomAccessFile.seek(12) // Skip RIFF header and WAVE identifier
      var dataChunkPosition = -1L
      
      while (randomAccessFile.filePointer < randomAccessFile.length() - 8) {
        val chunkId = ByteArray(4)
        randomAccessFile.read(chunkId)
        val chunkIdString = String(chunkId)
        
        val chunkSizeBytes = ByteArray(4)
        randomAccessFile.read(chunkSizeBytes)
        val chunkSize = java.nio.ByteBuffer.wrap(chunkSizeBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        
        Log.d("ExpoAudioStudioModule", "Found chunk: $chunkIdString, size: $chunkSize")
        
        if (chunkIdString == "data") {
          dataChunkPosition = randomAccessFile.filePointer - 4 // Position of chunk size
          Log.d("ExpoAudioStudioModule", "Found data chunk at position: $dataChunkPosition")
          break
        } else {
          // Skip this chunk
          randomAccessFile.seek(randomAccessFile.filePointer + chunkSize)
        }
      }
      
      if (dataChunkPosition != -1L) {
        // Update data chunk size
        randomAccessFile.seek(dataChunkPosition)
        randomAccessFile.writeInt(Integer.reverseBytes(dataSize.toInt()))
        Log.d("ExpoAudioStudioModule", "Updated data chunk size to: $dataSize")
      } else {
        Log.e("ExpoAudioStudioModule", "Could not find data chunk in WAV file")
      }
      
      randomAccessFile.close()
      Log.d("ExpoAudioStudioModule", "WAV header update completed")
      
    } catch (e: Exception) {
      Log.e("ExpoAudioStudioModule", "Error updating WAV header: ${e.message}", e)
    }
  }
}

