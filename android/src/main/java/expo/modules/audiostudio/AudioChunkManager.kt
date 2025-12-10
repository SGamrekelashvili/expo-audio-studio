package expo.modules.audiostudio

import expo.modules.kotlin.sharedobjects.SharedObject
import expo.modules.kotlin.AppContext
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AudioChunkManager(appContext: AppContext) : SharedObject(appContext) {
    companion object {
        private const val TAG = "AudioChunkManager"
        private const val MAX_BUFFER_SIZE = 8192
        private const val VAD_SILENCE_THRESHOLD_MS = 300
    }
    
    private val chunkQueue = ConcurrentLinkedQueue<AudioChunk>()
    private val isStreaming = AtomicBoolean(false)
    private val lastVoiceDetectedTime = AtomicLong(0L)
    private val isVoiceActive = AtomicBoolean(false)
    
    data class AudioChunk(
        val data: ByteArray,
        val timestamp: Long,
        val hasVoice: Boolean
    )
    
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
            val chunkToQueue = if (audioData.size > MAX_BUFFER_SIZE) {
                Log.w(TAG, "Chunk too large: ${audioData.size}, truncating to $MAX_BUFFER_SIZE")
                audioData.sliceArray(0 until MAX_BUFFER_SIZE)
            } else {
                audioData
            }

            chunkQueue.offer(AudioChunk(chunkToQueue, now, hasVoice))

            while (chunkQueue.size > 10) {
                chunkQueue.poll() // Remove oldest
            }
        } else {
            Log.d(TAG, "Skipping chunk - no voice detected for ${now - lastVoiceDetectedTime.get()}ms")
        }
    }

    fun getNextChunk(): Map<String, Any>? {
        val chunk = chunkQueue.poll() ?: return null

        val intArray = chunk.data.map { it.toInt() and 0xFF }

        return mapOf(
            "data" to intArray,
            "timestamp" to chunk.timestamp,
            "hasVoice" to chunk.hasVoice,
            "size" to chunk.data.size
        )
    }

    fun getAllChunks(): List<Map<String, Any>> {
        val chunks = mutableListOf<Map<String, Any>>()
        while (chunks.size < 10) {
            val chunk = getNextChunk() ?: break
            chunks.add(chunk)
        }
        return chunks
    }

    fun startStreaming() {
        isStreaming.set(true)
        chunkQueue.clear()
        lastVoiceDetectedTime.set(System.currentTimeMillis())
        isVoiceActive.set(false)
        Log.d(TAG, "Started streaming")
    }
    
    fun stopStreaming() {
        isStreaming.set(false)
        chunkQueue.clear()
        isVoiceActive.set(false)
        Log.d(TAG, "Stopped streaming")
    }
    
    fun isCurrentlyStreaming(): Boolean = isStreaming.get()
    
    fun getPendingChunkCount(): Int = chunkQueue.size
    
    fun clearBuffer() {
        chunkQueue.clear()
    }
}
