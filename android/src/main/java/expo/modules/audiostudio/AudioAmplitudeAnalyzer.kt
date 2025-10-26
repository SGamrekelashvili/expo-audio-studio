package expo.modules.audiostudio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * High-performance audio amplitude analyzer for visualization bars
 * Uses Android MediaExtractor for efficient audio processing
 * 
 * MEMORY OPTIMIZED: Use object for static access to avoid object creation
 */
object AudioAmplitudeAnalyzer {
    
    private const val TAG = "AudioAmplitudeAnalyzer"
    private const val MAX_FILE_SIZE = 100L * 1024 * 1024 // 100MB limit
    private const val CHUNK_SIZE = 4096 // Process in 4KB chunks
    private const val MAX_BARS_COUNT = 2048 // Maximum bars for performance
    
    /**
     * Data class for amplitude analysis results
     */
    data class AmplitudeResult(
        val amplitudes: FloatArray,
        val duration: Double,
        val sampleRate: Double,
        val success: Boolean,
        val error: String? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            
            other as AmplitudeResult
            
            if (!amplitudes.contentEquals(other.amplitudes)) return false
            if (duration != other.duration) return false
            if (sampleRate != other.sampleRate) return false
            if (success != other.success) return false
            if (error != other.error) return false
            
            return true
        }
        
        override fun hashCode(): Int {
            var result = amplitudes.contentHashCode()
            result = 31 * result + duration.hashCode()
            result = 31 * result + sampleRate.hashCode()
            result = 31 * result + success.hashCode()
            result = 31 * result + (error?.hashCode() ?: 0)
            return result
        }
    }
    
    /**
     * Analyzes audio file and returns amplitude data for visualization bars
     *
     * @param context Android context
     * @param fileUrl Path to the audio file
     * @param barsCount Number of amplitude bars to generate (1-2048)
     * @return AmplitudeResult with amplitude data and metadata
     */
    fun getAudioAmplitudes(context: Context, fileUrl: String, barsCount: Int): AmplitudeResult {
        Log.d(TAG, "Starting amplitude analysis for: $fileUrl")
        Log.d(TAG, "Requested bars: $barsCount")
        
        // Validate inputs
        if (barsCount <= 0 || barsCount > MAX_BARS_COUNT) {
            return AmplitudeResult(
                amplitudes = floatArrayOf(),
                duration = 0.0,
                sampleRate = 0.0,
                success = false,
                error = "Invalid barsCount: must be between 1 and $MAX_BARS_COUNT"
            )
        }
        
        // Clean file path
        val cleanPath = fileUrl.replace("file://", "")
        val file = File(cleanPath)
        
        if (!file.exists()) {
            return AmplitudeResult(
                amplitudes = floatArrayOf(),
                duration = 0.0,
                sampleRate = 0.0,
                success = false,
                error = "File not found: $cleanPath"
            )
        }
        
        // Check file size
        if (file.length() > MAX_FILE_SIZE) {
            return AmplitudeResult(
                amplitudes = floatArrayOf(),
                duration = 0.0,
                sampleRate = 0.0,
                success = false,
                error = "File too large: ${file.length()} bytes (max: $MAX_FILE_SIZE)"
            )
        }
        
        // Get audio metadata
        val metadataRetriever = MediaMetadataRetriever()
        val duration: Double
        
        try {
            metadataRetriever.setDataSource(cleanPath)
            val durationStr = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            duration = (durationStr?.toDoubleOrNull() ?: 0.0) / 1000.0 // Convert ms to seconds
            
            if (duration <= 0) {
                return AmplitudeResult(
                    amplitudes = floatArrayOf(),
                    duration = 0.0,
                    sampleRate = 0.0,
                    success = false,
                    error = "Invalid audio duration"
                )
            }
            
            Log.d(TAG, "Audio duration: $duration seconds")
        } catch (e: Exception) {
            return AmplitudeResult(
                amplitudes = floatArrayOf(),
                duration = 0.0,
                sampleRate = 0.0,
                success = false,
                error = "Failed to read audio metadata: ${e.message}"
            )
        } finally {
            try {
                metadataRetriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing metadata retriever: ${e.message}")
            }
        }
        
        // Process audio data
        return processAudioData(cleanPath, barsCount, duration)
    }
    
    /**
     * Processes audio data using MediaExtractor for efficient streaming
     */
    private fun processAudioData(filePath: String, barsCount: Int, duration: Double): AmplitudeResult {
        val extractor = MediaExtractor()
        
        try {
            extractor.setDataSource(filePath)
            
            // Find audio track
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                
                if (mime?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
            
            if (audioTrackIndex == -1 || audioFormat == null) {
                return AmplitudeResult(
                    amplitudes = floatArrayOf(),
                    duration = duration,
                    sampleRate = 0.0,
                    success = false,
                    error = "No audio track found in file"
                )
            }
            
            extractor.selectTrack(audioTrackIndex)
            
            // Get audio format information
            val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            
            Log.d(TAG, "Audio format - Sample rate: $sampleRate Hz, Channels: $channelCount")
            
            // MEMORY OPTIMIZED: Use streaming approach to avoid large array allocation
            val amplitudes = extractAudioSamplesOptimized(extractor, sampleRate, channelCount, barsCount)
            
            if (amplitudes.isEmpty()) {
                return AmplitudeResult(
                    amplitudes = floatArrayOf(),
                    duration = duration,
                    sampleRate = sampleRate.toDouble(),
                    success = false,
                    error = "No audio samples extracted"
                )
            }
            
            Log.d(TAG, "Generated ${amplitudes.size} dB amplitude bars")
            
            return AmplitudeResult(
                amplitudes = amplitudes,
                duration = duration,
                sampleRate = sampleRate.toDouble(),
                success = true,
                error = null
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio data", e)
            return AmplitudeResult(
                amplitudes = floatArrayOf(),
                duration = duration,
                sampleRate = 0.0,
                success = false,
                error = "Failed to process audio: ${e.message}"
            )
        } finally {
            try {
                extractor.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing media extractor: ${e.message}")
            }
        }
    }
    
    /**
     * MEMORY OPTIMIZED: Extracts audio samples with streaming amplitude calculation
     * Processes data in chunks to avoid large memory allocation
     */
    private fun extractAudioSamplesOptimized(extractor: MediaExtractor, sampleRate: Int, channelCount: Int, barsCount: Int): FloatArray {
        val amplitudes = FloatArray(barsCount)
        val buffer = ByteBuffer.allocate(CHUNK_SIZE)
        var processedChunks = 0
        var totalSamples = 0
        
        // Calculate approximate samples per bar based on duration and sample rate
        val durationMs = extractor.sampleTime / 1000 // Convert to milliseconds
        val totalExpectedSamples = (durationMs * sampleRate / 1000).toInt()
        val samplesPerBar = maxOf(1, totalExpectedSamples / barsCount)
        
        var currentBarIndex = 0
        var samplesInCurrentBar = 0
        var sumOfSquares = 0.0
        
        Log.d(TAG, "Starting optimized extraction: expected $totalExpectedSamples samples, $samplesPerBar per bar")
        
        // Reset extractor to beginning
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        
        while (currentBarIndex < barsCount) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            
            if (sampleSize < 0) {
                break // End of stream
            }
            
            // Convert bytes to 16-bit samples
            buffer.rewind()
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            
            val sampleCount = sampleSize / 2 // 16-bit samples = 2 bytes per sample
            
            for (i in 0 until sampleCount) {
                if (buffer.remaining() >= 2 && currentBarIndex < barsCount) {
                    val sample = buffer.short
                    val normalizedSample = sample.toFloat() / Short.MAX_VALUE
                    
                    // Accumulate for RMS calculation
                    sumOfSquares += normalizedSample * normalizedSample
                    samplesInCurrentBar++
                    totalSamples++
                    
                    // Check if we've collected enough samples for this bar
                    if (samplesInCurrentBar >= samplesPerBar) {
                        // Calculate RMS for this bar
                        val rmsAmplitude = sqrt(sumOfSquares / samplesInCurrentBar).toFloat()
                        
                        // Convert RMS to dB (same format as recording amplitudes)
                        val dBValue = if (rmsAmplitude > 0) {
                            20.0f * log10(rmsAmplitude)
                        } else {
                            -160.0f // Silence threshold
                        }
                        amplitudes[currentBarIndex] = dBValue
                        
                        // Move to next bar
                        currentBarIndex++
                        samplesInCurrentBar = 0
                        sumOfSquares = 0.0
                    }
                }
            }
            
            extractor.advance()
            processedChunks++
            
            // Log progress every 100 chunks
            if (processedChunks % 100 == 0) {
                Log.d(TAG, "Processed $processedChunks chunks, bar $currentBarIndex/$barsCount")
            }
            
            buffer.clear()
        }
        
        // Handle any remaining samples in the last bar
        if (samplesInCurrentBar > 0 && currentBarIndex < barsCount) {
            val rmsAmplitude = sqrt(sumOfSquares / samplesInCurrentBar).toFloat()
            
            // Convert RMS to dB (same format as recording amplitudes)
            val dBValue = if (rmsAmplitude > 0) {
                20.0f * log10(rmsAmplitude)
            } else {
                -160.0f // Silence threshold
            }
            amplitudes[currentBarIndex] = dBValue
        }
        
        Log.d(TAG, "Finished optimized extraction. Total samples: $totalSamples, bars: $currentBarIndex")
        
        return amplitudes
    }
    
    /**
     * Calculates amplitude bars using RMS (Root Mean Square) for optimal visualization
     */
    private fun calculateAmplitudes(samples: ShortArray, barsCount: Int): FloatArray {
        val totalSamples = samples.size
        val samplesPerBar = totalSamples / barsCount
        
        if (samplesPerBar <= 0) {
            // If we have fewer samples than bars, return what we have
            return samples.map { abs(it.toFloat()) / Short.MAX_VALUE }.toFloatArray()
        }
        
        val amplitudes = FloatArray(barsCount)
        
        Log.d(TAG, "Calculating amplitudes: $totalSamples samples -> $barsCount bars ($samplesPerBar samples per bar)")
        
        for (barIndex in 0 until barsCount) {
            val startIndex = barIndex * samplesPerBar
            val endIndex = min(startIndex + samplesPerBar, totalSamples)
            
            if (startIndex >= totalSamples) {
                amplitudes[barIndex] = 0.0f
                continue
            }
            
            // Calculate RMS amplitude for this bar
            var sumOfSquares = 0.0
            var sampleCount = 0
            
            for (i in startIndex until endIndex) {
                val normalizedSample = samples[i].toFloat() / Short.MAX_VALUE
                sumOfSquares += normalizedSample * normalizedSample
                sampleCount++
            }
            
            val rmsAmplitude = if (sampleCount > 0) {
                sqrt(sumOfSquares / sampleCount).toFloat()
            } else {
                0.0f
            }
            
            // Convert RMS to dB (same format as recording amplitudes)
            val dBValue = if (rmsAmplitude > 0) {
                20.0f * log10(rmsAmplitude)
            } else {
                -160.0f // Silence threshold
            }
            
            amplitudes[barIndex] = dBValue
        }
        
        // Normalize amplitudes to 0.0-1.0 range
        val maxAmplitude = amplitudes.maxOrNull() ?: 1.0f
        if (maxAmplitude > 0) {
            for (i in amplitudes.indices) {
                amplitudes[i] = amplitudes[i] / maxAmplitude
            }
        }
        
        return amplitudes
    }
    
    /**
     * Alternative method for WAV files - direct PCM data extraction for maximum performance
     */
    private fun extractWavSamples(filePath: String): ShortArray? {
        return try {
            val file = File(filePath)
            if (!file.name.lowercase().endsWith(".wav")) {
                return null // Not a WAV file
            }
            
            val inputStream = FileInputStream(file)
            val samples = mutableListOf<Short>()
            
            // Skip WAV header (44 bytes)
            inputStream.skip(44)
            
            val buffer = ByteArray(CHUNK_SIZE)
            var bytesRead: Int
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                val byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead)
                byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                
                while (byteBuffer.remaining() >= 2) {
                    samples.add(byteBuffer.short)
                }
            }
            
            inputStream.close()
            samples.toShortArray()
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract WAV samples directly: ${e.message}")
            null
        }
    }
}
