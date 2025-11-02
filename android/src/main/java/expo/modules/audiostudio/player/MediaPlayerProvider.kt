package expo.modules.audiostudio.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

class MediaPlayerProvider(private val context: Context) : AudioPlayerProvider {

    private var playbackSpeed = 1f
    private var _player: ExoPlayer? = null
    private var cachedDuration: Long = 0L
    private var isPaused: Boolean = false
    private var hasCompleted: Boolean = false
    private var currentFileName: String? = null
    private var completionDispatched = false
    private var sharedListener: Player.Listener? = null

    private fun buildPlayer(): ExoPlayer {
        val player = ExoPlayer.Builder(context)
            .setHandleAudioBecomingNoisy(true)
            .build()

        val attrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player.setAudioAttributes(attrs, /* handleAudioFocus = */ true)
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.volume = 1f

        sharedListener?.let { player.removeListener(it) }
        sharedListener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        cachedDuration = player.duration.coerceAtLeast(0L)
                    }
                    Player.STATE_ENDED -> {
                        hasCompleted = true
                        isPaused = false
                    }
                    else -> Unit
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Player error: ${error.errorCodeName} - ${error.message}")
                isPaused = false
                hasCompleted = false
                completionDispatched = false
            }
        }
        player.addListener(sharedListener!!)

        if (playbackSpeed != 1f) {
            try {
                player.playbackParameters = PlaybackParameters(playbackSpeed)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to apply playback speed on build: ${e.message}")
            }
        }

        return player
    }

    private fun toMediaItem(fileName: String): MediaItem {
        return when {
            fileName.startsWith("asset://") -> {
                val name = fileName.removePrefix("asset://").trimStart('/')
                MediaItem.fromUri("asset:///$name".toUri())
            }
            !fileName.contains("/") && !fileName.contains("\\") -> {
                MediaItem.fromUri("asset:///$fileName".toUri())
            }
            fileName.startsWith("file://") -> {
                val path = fileName.removePrefix("file://")
                val f = File(path)
                if (!f.exists()) throw java.io.FileNotFoundException("File not found: $path")
                MediaItem.fromUri(Uri.fromFile(f))
            }
            else -> MediaItem.fromUri(fileName.toUri())
        }
    }

    override fun preparePlayer(
        fileName: String,
        AudioEndFunction: (result: Map<String, Boolean>) -> Unit
    ): Boolean {
        stopAndReleasePlayer()
        hasCompleted = false
        completionDispatched = false
        isPaused = false

        return try {
            val player = buildPlayer().also { _player = it }
            val item = toMediaItem(fileName)
            player.setMediaItem(item)
            player.prepare()
            currentFileName = fileName

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED && !completionDispatched) {
                        completionDispatched = true
                        AudioEndFunction(
                            mapOf("isPlaying" to false, "didJustFinish" to true)
                        )
                    }
                }
            })
            true
        } catch (e: Exception) {
            Log.e(TAG, "preparePlayer failed: ${e.message}")
            currentFileName = null
            false
        }
    }

    override fun startPlaying(
        fileName: String,
        AudioEndFunction: (result: Map<String, Boolean>) -> Unit
    ): Boolean {
        val existing = _player
        return try {
            if (existing != null && currentFileName == fileName) {
                when (existing.playbackState) {
                    Player.STATE_ENDED -> {
                        hasCompleted = false
                        completionDispatched = false
                        isPaused = false
                        existing.seekTo(0L)
                        existing.play()
                        true
                    }
                    Player.STATE_READY, Player.STATE_BUFFERING, Player.STATE_IDLE -> {
                        hasCompleted = false
                        existing.play()
                        true
                    }
                    else -> {
                        hasCompleted = false
                        existing.play()
                        true
                    }
                }
            } else {
                if (!preparePlayer(fileName, AudioEndFunction)) return false
                _player?.let { p ->
                    hasCompleted = false
                    p.play()
                    true
                } ?: false
            }
        } catch (e: Exception) {
            Log.w(TAG, "startPlaying failed, re-preparing: ${e.message}")
            if (!preparePlayer(fileName, AudioEndFunction)) return false
            _player?.let { p ->
                hasCompleted = false
                p.play()
                true
            } ?: false
        }
    }

    override fun pausePlaying(): Boolean {
        val p = _player ?: return false
        return try {
            if (p.isPlaying) {
                p.pause()
                isPaused = true
                true
            } else {
                isPaused = true
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "pausePlaying: ${e.message}")
            false
        }
    }

    override fun resumePlaying(): Boolean {
        val p = _player ?: return false
        return try {
            when (p.playbackState) {
                Player.STATE_ENDED -> {
                    hasCompleted = false
                    completionDispatched = false
                    isPaused = false
                    p.seekTo(0L)
                    p.play()
                    true
                }
                else -> {
                    p.play()
                    isPaused = false
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "resumePlaying: ${e.message}")
            false
        }
    }

    override fun stopPlaying(): Boolean {
        return try {
            stopAndReleasePlayer()
            true
        } catch (e: Exception) {
            Log.e(TAG, "stopPlaying: ${e.message}")
            false
        }
    }

    override fun seekTo(position: Int): Boolean {
        return try {
            _player?.seekTo(position.toLong() * 1000L)
            _player != null
        } catch (e: Exception) {
            Log.e(TAG, "seekTo: ${e.message}")
            false
        }
    }

    override fun isPlaying(): Boolean = _player?.isPlaying ?: false

    override fun getPlaybackSpeed(): Float = playbackSpeed

    override fun getCurrentPosition(): Int {
        return try {
            (_player?.currentPosition ?: 0L).coerceAtLeast(0L).toInt()
        } catch (e: Exception) {
            Log.e(TAG, "getCurrentPosition error: ${e.message}")
            0
        }
    }

    override fun getAudioDuration(uri: String): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(uri)
            val format = extractor.getTrackFormat(0)
            (format.getLong(MediaFormat.KEY_DURATION) / 1000)
        } catch (_: Exception) {
            0L
        } finally {
            extractor.release()
        }
    }

    private fun stopAndReleasePlayer() {
        _player?.run {
            try { stop() } catch (_: Exception) {}
            try { clearMediaItems() } catch (_: Exception) {}
            try {
                sharedListener?.let { removeListener(it) }
            } catch (_: Exception) {}
            try { release() } catch (_: Exception) {}
        }
        _player = null
        currentFileName = null
        cachedDuration = 0L
        isPaused = false
        hasCompleted = false
        completionDispatched = false
        sharedListener = null
    }

    override fun setPlaybackSpeed(speed: String): Boolean {
        return try {
            playbackSpeed = speed.toFloat()
            _player?.let { p ->
                try { p.playbackParameters = PlaybackParameters(playbackSpeed) }
                catch (e: Exception) { Log.w(TAG, "Failed to set speed: ${e.message}") }
            }
            true
        } catch (_: NumberFormatException) {
            false
        }
    }

    override fun playerStatus(): PlayerProgress {
        val p = _player
        if (p == null || (p.duration <= 0 && !hasCompleted)) {
            return PlayerProgress(duration = 0, currentSeconds = 0, percentage = 0f)
        }
        val duration = (if (p.duration > 0) p.duration else cachedDuration).coerceAtLeast(0L)
        val current = if (hasCompleted) duration else p.currentPosition.coerceAtLeast(0L)
        val percentage = if (duration > 0) current.toFloat() / duration.toFloat() else 0f
        return PlayerProgress(
            duration = duration.toInt(),
            currentSeconds = current.toInt(),
            percentage = percentage
        )
    }

    override fun releasePlayer() {
        stopAndReleasePlayer()
    }

    companion object {
        private const val TAG = "MediaPlayerProvider"
    }
}
