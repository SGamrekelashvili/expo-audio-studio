package expo.modules.audiostudio.player

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Log
import android.content.Context
import java.io.File

/**
 * MediaPlayerProvider is responsible for managing audio playback.
 * It implements the AudioPlayerProvider interface.
 */
class MediaPlayerProvider(private val context: Context) : AudioPlayerProvider {

    private var playbackSpeed = 1f
    private var _player: MediaPlayer? = null
    private var cachedDuration: Int = 0
    private var isPaused: Boolean = false
    private val _playerStatusStateFlow =
        PlayerProgress(
            percentage = 0f,
            currentSeconds = 0,
            duration = 0
        )


    /**
     * Prepares the audio player without starting playback.
     * @param fileName The path of the audio file.
     * @param AudioEndFunction The callback to be invoked on completion.
     * @return True if player was prepared successfully, false otherwise.
     */
    override fun preparePlayer(
        fileName: String,
        AudioEndFunction: (result: Map<String, Boolean>) -> Unit
    ): Boolean {
        stopAndReleasePlayer()
        val playbackParams = PlaybackParams()
        playbackParams.setSpeed(playbackSpeed)
        playbackParams.setAudioFallbackMode(
            PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT
        )

        return try {
            _player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                        .build()
                )
                
                // Handle different URI schemes (same logic as startPlaying)
                Log.d(TAG, "Preparing audio from: $fileName")
                try {
                    when {
                        // Handle assets with asset:// prefix
                        fileName.startsWith("asset://") -> {
                            Log.d(TAG, "Preparing from assets folder with asset:// prefix")
                            val assetName = fileName.replace("asset://", "")
                            Log.d(TAG, "Asset name: $assetName")
                            
                            val afd = context.assets.openFd(assetName)
                            Log.d(TAG, "Asset file descriptor opened successfully")
                            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                            afd.close()
                            Log.d(TAG, "Asset data source set successfully")
                        }
                        // Check if it's a relative asset path (no prefix)
                        !fileName.contains("/") && !fileName.contains("\\") -> {
                            Log.d(TAG, "Preparing from assets folder with direct filename: $fileName")
                            
                            try {
                                val afd = context.assets.openFd(fileName)
                                Log.d(TAG, "Asset file descriptor opened successfully")
                                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                                afd.close()
                                Log.d(TAG, "Asset data source set successfully")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error loading asset file: $fileName", e)
                                throw e
                            }
                        }
                        // Handle file URIs
                        fileName.startsWith("file://") -> {
                            Log.d(TAG, "Preparing from file URI")
                            val path = fileName.replace("file://", "")
                            Log.d(TAG, "File path: $path")
                            val file = File(path)
                            if (file.exists()) {
                                Log.d(TAG, "File exists, size: ${file.length()} bytes")
                                setDataSource(path)
                            } else {
                                Log.e(TAG, "File does not exist: $path")
                                throw java.io.FileNotFoundException("File not found: $path")
                            }
                        }
                        // Default handling for regular paths
                        else -> {
                            Log.d(TAG, "Preparing from direct path")
                            setDataSource(fileName)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up data source: ${e.message}")
                    throw e
                }
                
                // PREPARE but DON'T START
                prepare()
                cachedDuration = duration
                setPlaybackParams(playbackParams)
                // Set volume to maximum
                setVolume(1.0f, 1.0f)
                
                // Set completion listener but don't start playing
                setOnCompletionListener {
                    val result = mapOf(
                        "isPlaying" to false,
                        "didJustFinish" to true
                    )
                    AudioEndFunction(result)
                }
                
                Log.d(TAG, "Player prepared successfully, ready for playback")
            }
            currentFileName = fileName  // Track the prepared file
            true
        } catch (exception: Exception) {
            Log.e(TAG, "preparePlayer: $exception")
            currentFileName = null  // Clear on error
            false
        }
    }

    /**
     * Starts playing the audio file.
     * @param fileName The path of the audio file.
     * @param onComplete The callback to be invoked on completion.
     * @return True if playback started successfully, false otherwise.
     */
    private var currentFileName: String? = null  // Track current prepared file
    
    override fun startPlaying(
        fileName: String,
        AudioEndFunction: (result: Map<String, Boolean>) -> Unit
    ): Boolean {
        // Check if we already have a prepared player for THIS SPECIFIC file
        _player?.let { existingPlayer ->
            try {
                // Only use existing player if it's the SAME file and not playing
                if (currentFileName == fileName && !existingPlayer.isPlaying) {
                    Log.d(TAG, "Using already prepared player for same file: $fileName")
                    existingPlayer.start()
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking existing player, will prepare new one: ${e.message}")
            }
        }
        
        // If no prepared player or error, prepare and start
        val prepareResult = preparePlayer(fileName, AudioEndFunction)
        if (!prepareResult) {
            return false
        }
        
        // Now start the prepared player
        return try {
            _player?.let { player ->
                player.start()
                Log.d(TAG, "Started prepared player successfully")
                true
            } ?: false
        } catch (exception: Exception) {
            Log.e(TAG, "Error starting prepared player: $exception")
            false
        }
    }




    /**
     * Stops playing the audio.
     * @return True if playback stopped successfully, false otherwise.
     */
    override fun stopPlaying(): Boolean {
        return try {
            stopAndReleasePlayer()
            isPaused = false
            true
        } catch (exception: Exception) {
            Log.e(TAG, "stopPlaying: $exception")
            false
        }
    }
    
    /**
     * Pauses audio playback.
     * @return True if playback was paused successfully, false otherwise.
     */
    override fun pausePlaying(): Boolean {
        return try {
            _player?.let {
                if (it.isPlaying) {
                    it.pause()
                    isPaused = true
                    return true
                }
            }
            false
        } catch (exception: Exception) {
            Log.e(TAG, "pausePlaying: $exception")
            false
        }
    }
    
    /**
     * Resumes audio playback if it was paused.
     * @return True if playback was resumed successfully, false otherwise.
     */
    override fun resumePlaying(): Boolean {
        return try {
            _player?.let {
                if (!it.isPlaying && isPaused) {
                    it.start()
                    isPaused = false
                    return true
                }
            }
            false
        } catch (exception: Exception) {
            Log.e(TAG, "resumePlaying: $exception")
            false
        }
    }
    
    /**
     * Seeks to a specific position in the audio file.
     * @param position The position to seek to, in milliseconds.
     * @return True if seeking was successful, false otherwise.
     */
    override fun seekTo(position: Int): Boolean {
        return try {
            _player?.let {
                it.seekTo(position)
                return true
            }
            false
        } catch (exception: Exception) {
            Log.e(TAG, "seekTo: $exception")
            false
        }
    }
    
    /**
     * Checks if audio is currently playing.
     * @return True if audio is playing, false otherwise.
     */
    override fun isPlaying(): Boolean {
        return _player?.isPlaying ?: false
    }
    
    /**
     * Gets the current playback speed.
     * @return The current playback speed.
     */
    override fun getPlaybackSpeed(): Float {
        return playbackSpeed
    }
    
    /**
     * Gets the current playback position in milliseconds.
     * @return The current position in milliseconds, or 0 if no player exists.
     */
    override fun getCurrentPosition(): Int {
        return try {
            _player?.currentPosition ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current position: ${e.message}")
            0
        }
    }
    
    /**
     * Gets the duration of an audio file without creating a permanent player.
     * @param uri The URI of the audio file.
     * @param context The application context.
     * @return The duration of the audio file in milliseconds, or 0 if unavailable.
     */
    override fun getAudioDuration(uri: String, context: Context): Int {
        // Create a temporary MediaPlayer to get the duration
        val tempPlayer = MediaPlayer()
        var duration = 0
        
        try {
            // Handle different URI schemes
            when {
                uri.startsWith("asset://") -> {
                    val assetName = uri.replace("asset://", "")
                    val afd = context.assets.openFd(assetName)
                    tempPlayer.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                }
                !uri.contains("/") && !uri.contains("\\") -> {
                    try {
                        val afd = context.assets.openFd(uri)
                        tempPlayer.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        afd.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading asset file for duration: $uri", e)
                        throw e
                    }
                }
                uri.startsWith("file://") -> {
                    val path = uri.replace("file://", "")
                    if (File(path).exists()) {
                        tempPlayer.setDataSource(path)
                    } else {
                        throw java.io.FileNotFoundException("File not found: $path")
                    }
                }
                else -> {
                    tempPlayer.setDataSource(uri)
                }
            }
            
            tempPlayer.prepare()
            duration = tempPlayer.duration
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting audio duration: ${e.message}")
        } finally {
            try {
                tempPlayer.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing temp player: ${e.message}")
            }
        }
        
        return duration
    }

    /**
     * Stops and releases the MediaPlayer.
     */
    private fun stopAndReleasePlayer() {
        _player?.run {
            stop()
            reset()
            release()
        }
        _player = null
        currentFileName = null  // Clear tracked filename
        cachedDuration = 0
    }

    override fun setPlaybackSpeed(speed: String): Boolean {
        return try {
            playbackSpeed = speed.toFloat()
            true
        } catch (e: NumberFormatException) {
            false
        }
    }

    /**
     * Returns the player status as a StateFlow.
     * @return StateFlow of PlayerProgress.
     */
    override fun playerStatus(): PlayerProgress? {
        return _player?.let {
            PlayerProgress(
                duration = it.duration,
                currentSeconds= it.currentPosition,
                percentage = (it?.currentPosition?.toFloat()?.div(it?.duration?.toFloat()!!)) ?: 0f
            )
        }
    }

    /**
     * Releases the player resources.
     */
    override fun releasePlayer() {
//        coroutineScope.cancel()
    }

    companion object {
        private const val TAG = "MediaPlayerProvider"
    }
}
