package expo.modules.audiostudio.player

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Log
import android.content.Context
import java.io.File


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
                            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                            afd.close()
                        }
                        // Check if it's a relative asset path (no prefix)
                        !fileName.contains("/") && !fileName.contains("\\") -> {
                            
                            try {
                                val afd = context.assets.openFd(fileName)
                                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                                afd.close()
                            } catch (e: Exception) {
                                throw e
                            }
                        }
                        // Handle file URIs
                        fileName.startsWith("file://") -> {
                            val path = fileName.replace("file://", "")
                            val file = File(path)
                            if (file.exists()) {
                                setDataSource(path)
                            } else {
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
    

    override fun isPlaying(): Boolean {
        return _player?.isPlaying ?: false
    }
    

    override fun getPlaybackSpeed(): Float {
        return playbackSpeed
    }
    

    override fun getCurrentPosition(): Int {
        return try {
            _player?.currentPosition ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current position: ${e.message}")
            0
        }
    }
    

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

    override fun playerStatus(): PlayerProgress? {
        return _player?.let {
            PlayerProgress(
                duration = it.duration,
                currentSeconds= it.currentPosition,
                percentage = (it?.currentPosition?.toFloat()?.div(it?.duration?.toFloat()!!)) ?: 0f
            )
        }
    }

    override fun releasePlayer() {
        stopAndReleasePlayer()
    }

    companion object {
        private const val TAG = "MediaPlayerProvider"
    }
}
