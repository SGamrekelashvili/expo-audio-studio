package expo.modules.audiostudio

import android.util.Base64
import expo.modules.kotlin.sharedobjects.SharedObject
import expo.modules.kotlin.AppContext
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AudioChunkManager(appContext: AppContext) : SharedObject(appContext) {
    companion object {
        private const val TAG = "AudioChunkManager"
        private const val MAX_BUFFER_SIZE = 8192
        private const val VAD_SILENCE_THRESHOLD_MS = 300
        private const val BATCH_MAX_BYTES = 32768
    }

    private val accumulatorLock = Any()
    private val accumulator = ByteArrayOutputStream(BATCH_MAX_BYTES)
    private var oldestTimestamp = 0L
    private var latestTimestamp = 0L
    private var accumulatorHasVoice = false

    private val isStreaming = AtomicBoolean(false)
    private val lastVoiceDetectedTime = AtomicLong(0L)
    private val isVoiceActive = AtomicBoolean(false)

    fun processChunk(audioData: ByteArray, hasVoice: Boolean) {
        if (!isStreaming.get()) return

        val now = System.currentTimeMillis()

        if (hasVoice) {
            lastVoiceDetectedTime.set(now)
            isVoiceActive.set(true)
        } else {
            if (now - lastVoiceDetectedTime.get() > VAD_SILENCE_THRESHOLD_MS) {
                isVoiceActive.set(false)
            }
        }

        if (isVoiceActive.get()) {
            val data = if (audioData.size > MAX_BUFFER_SIZE) {
                audioData.copyOfRange(0, MAX_BUFFER_SIZE)
            } else {
                audioData
            }

            synchronized(accumulatorLock) {
                if (accumulator.size() == 0) {
                    oldestTimestamp = now
                }
                accumulator.write(data)
                latestTimestamp = now
                if (hasVoice) accumulatorHasVoice = true
            }
        }
    }

    /**
     * Drains accumulated audio data into a single batch map using Base64 encoding
     * to avoid per-byte boxing overhead.
     */
    fun drainBatch(): Map<String, Any>? {
        synchronized(accumulatorLock) {
            if (accumulator.size() == 0) return null

            val bytes = accumulator.toByteArray()
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val result = mapOf<String, Any>(
                "data" to encoded,
                "timestamp" to oldestTimestamp,
                "endTimestamp" to latestTimestamp,
                "hasVoice" to accumulatorHasVoice,
                "size" to bytes.size,
                "encoding" to "base64"
            )

            accumulator.reset()
            accumulatorHasVoice = false
            return result
        }
    }

    fun startStreaming() {
        isStreaming.set(true)
        synchronized(accumulatorLock) { accumulator.reset() }
        lastVoiceDetectedTime.set(System.currentTimeMillis())
        isVoiceActive.set(false)
        Log.d(TAG, "Started streaming")
    }

    fun stopStreaming() {
        isStreaming.set(false)
        synchronized(accumulatorLock) { accumulator.reset() }
        isVoiceActive.set(false)
        Log.d(TAG, "Stopped streaming")
    }

    fun isCurrentlyStreaming(): Boolean = isStreaming.get()

    fun clearBuffer() {
        synchronized(accumulatorLock) { accumulator.reset() }
    }
}
