package expo.modules.audiostudio.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.media.MediaMetadataRetriever
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MediaPlayerProvider(private val context: Context) : AudioPlayerProvider {

    private var playbackSpeed = 1f
    private var _player: ExoPlayer? = null
    private var cachedDuration: Long = 0L

    private val isPaused = AtomicBoolean(false)
    private val hasCompleted = AtomicBoolean(false)
    private val completionDispatched = AtomicBoolean(false)

    private var currentFileName: String? = null
    private var completionListener: Player.Listener? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Executes [block] on the main (player) thread synchronously.
     * ExoPlayer is bound to the main looper so all access must happen there.
     */
    private fun <T> runOnPlayerThread(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null
        var error: Throwable? = null
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) {
            Log.e(TAG, "Timed out waiting for player thread operation")
            @Suppress("UNCHECKED_CAST")
            return null as T
        }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun ensurePlayer(): ExoPlayer {
        _player?.let { return it }

        val player = ExoPlayer.Builder(context)
            .setLooper(Looper.getMainLooper())
            .setHandleAudioBecomingNoisy(true)
            .build()

        val attrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player.setAudioAttributes(attrs, true)
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.volume = 1f

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        cachedDuration = player.duration.coerceAtLeast(0L)
                    }
                    Player.STATE_ENDED -> {
                        hasCompleted.set(true)
                        isPaused.set(false)
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Player error: ${error.errorCodeName} - ${error.message}")
                isPaused.set(false)
                hasCompleted.set(false)
                completionDispatched.set(false)
            }
        })

        if (playbackSpeed != 1f) {
            try {
                player.playbackParameters = PlaybackParameters(playbackSpeed)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to apply playback speed on build: ${e.message}")
            }
        }

        _player = player
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
    ): Boolean = runOnPlayerThread {
        try {
            val player = ensurePlayer()

            completionListener?.let { player.removeListener(it) }

            hasCompleted.set(false)
            completionDispatched.set(false)
            isPaused.set(false)

            player.stop()
            player.clearMediaItems()

            val item = toMediaItem(fileName)
            player.setMediaItem(item)
            player.prepare()
            currentFileName = fileName

            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED && completionDispatched.compareAndSet(false, true)) {
                        AudioEndFunction(mapOf("isPlaying" to false, "didJustFinish" to true))
                    }
                }
            }
            completionListener = listener
            player.addListener(listener)
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
    ): Boolean = runOnPlayerThread {
        try {
            val existing = _player
            if (existing != null && currentFileName == fileName) {
                when (existing.playbackState) {
                    Player.STATE_ENDED -> {
                        hasCompleted.set(false)
                        completionDispatched.set(false)
                        isPaused.set(false)
                        existing.seekTo(0L)
                        existing.play()
                        true
                    }
                    else -> {
                        hasCompleted.set(false)
                        isPaused.set(false)
                        existing.play()
                        true
                    }
                }
            } else {
                if (!preparePlayer(fileName, AudioEndFunction)) return@runOnPlayerThread false
                _player?.let { p ->
                    hasCompleted.set(false)
                    isPaused.set(false)
                    p.play()
                    true
                } ?: false
            }
        } catch (e: Exception) {
            Log.w(TAG, "startPlaying failed, re-preparing: ${e.message}")
            try {
                if (!preparePlayer(fileName, AudioEndFunction)) return@runOnPlayerThread false
                _player?.let { p ->
                    hasCompleted.set(false)
                    isPaused.set(false)
                    p.play()
                    true
                } ?: false
            } catch (e2: Exception) {
                Log.e(TAG, "startPlaying retry failed: ${e2.message}")
                false
            }
        }
    }

    override fun pausePlaying(): Boolean = runOnPlayerThread {
        val p = _player ?: return@runOnPlayerThread false
        try {
            p.pause()
            isPaused.set(true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "pausePlaying: ${e.message}")
            false
        }
    }

    override fun resumePlaying(): Boolean = runOnPlayerThread {
        val p = _player ?: return@runOnPlayerThread false
        try {
            when (p.playbackState) {
                Player.STATE_ENDED -> {
                    hasCompleted.set(false)
                    completionDispatched.set(false)
                    isPaused.set(false)
                    p.seekTo(0L)
                    p.play()
                    true
                }
                else -> {
                    p.play()
                    isPaused.set(false)
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "resumePlaying: ${e.message}")
            false
        }
    }

    override fun stopPlaying(): Boolean = runOnPlayerThread {
        try {
            releasePlayerInternal()
            true
        } catch (e: Exception) {
            Log.e(TAG, "stopPlaying: ${e.message}")
            false
        }
    }

    override fun seekTo(position: Int): Boolean = runOnPlayerThread {
        try {
            _player?.seekTo(position.toLong() * 1000L)
            _player != null
        } catch (e: Exception) {
            Log.e(TAG, "seekTo: ${e.message}")
            false
        }
    }

    override fun isPlaying(): Boolean {
        return try {
            runOnPlayerThread { _player?.isPlaying ?: false }
        } catch (e: Exception) {
            false
        }
    }

    override fun getPlaybackSpeed(): Float = playbackSpeed

    override fun getCurrentPosition(): Int {
        return try {
            runOnPlayerThread {
                (_player?.currentPosition ?: 0L).coerceAtLeast(0L).toInt()
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCurrentPosition error: ${e.message}")
            0
        }
    }

    override fun getAudioDuration(uri: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            val cleanUri = uri.replace("file://", "")
            retriever.setDataSource(cleanUri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            (durationStr?.toLongOrNull() ?: 0L)
        } catch (_: Exception) {
            0L
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun releasePlayerInternal() {
        _player?.run {
            completionListener?.let { listener ->
                try { removeListener(listener) } catch (_: Exception) {}
            }
            try { stop() } catch (_: Exception) {}
            try { clearMediaItems() } catch (_: Exception) {}
            try { release() } catch (_: Exception) {}
        }
        _player = null
        completionListener = null
        currentFileName = null
        cachedDuration = 0L
        isPaused.set(false)
        hasCompleted.set(false)
        completionDispatched.set(false)
    }

    override fun setPlaybackSpeed(speed: String): Boolean {
        return try {
            playbackSpeed = speed.toFloat()
            runOnPlayerThread {
                _player?.let { p ->
                    try {
                        p.playbackParameters = PlaybackParameters(playbackSpeed)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to set speed: ${e.message}")
                    }
                }
            }
            true
        } catch (_: NumberFormatException) {
            false
        }
    }

    override fun playerStatus(): PlayerProgress {
        return try {
            runOnPlayerThread {
                val p = _player
                if (p == null || (p.duration <= 0 && !hasCompleted.get())) {
                    return@runOnPlayerThread PlayerProgress(duration = 0, currentSeconds = 0, percentage = 0f)
                }
                val duration = (if (p.duration > 0) p.duration else cachedDuration).coerceAtLeast(0L)
                val current = if (hasCompleted.get()) duration else p.currentPosition.coerceAtLeast(0L)
                val percentage = if (duration > 0) current.toFloat() / duration.toFloat() else 0f
                PlayerProgress(
                    duration = duration.toInt(),
                    currentSeconds = current.toInt(),
                    percentage = percentage
                )
            }
        } catch (e: Exception) {
            PlayerProgress(duration = 0, currentSeconds = 0, percentage = 0f)
        }
    }

    override fun releasePlayer() {
        try {
            runOnPlayerThread { releasePlayerInternal() }
        } catch (e: Exception) {
            Log.e(TAG, "Error during releasePlayer: ${e.message}")
            _player = null
            completionListener = null
        }
    }

    companion object {
        private const val TAG = "MediaPlayerProvider"
    }
}
