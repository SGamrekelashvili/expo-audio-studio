package expo.modules.audiostudio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object AudioAmplitudeAnalyzer {

    private const val TAG = "AudioAmplitudeAnalyzer"
    private const val MAX_FILE_SIZE = 100L * 1024 * 1024 // 100MB limit
    private const val CHUNK_SIZE = 4096 // bytes (must be multiple of 2 for 16-bit)
    private const val MAX_BARS_COUNT = 2048 // Maximum bars for performance
    private const val MIN_DB = -160f

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

    fun getAudioAmplitudes(context: Context, fileUrl: String, barsCount: Int): AmplitudeResult {
        Log.d(TAG, "Starting amplitude analysis for: $fileUrl")
        Log.d(TAG, "Requested bars: $barsCount")

        if (barsCount <= 0 || barsCount > MAX_BARS_COUNT) {
            return AmplitudeResult(floatArrayOf(), 0.0, 0.0, false,
                "Invalid barsCount: must be between 1 and $MAX_BARS_COUNT")
        }

        val cleanPath = fileUrl.replace("file://", "")
        val file = File(cleanPath)
        if (!file.exists()) {
            return AmplitudeResult(floatArrayOf(), 0.0, 0.0, false, "File not found: $cleanPath")
        }
        if (file.length() > MAX_FILE_SIZE) {
            return AmplitudeResult(floatArrayOf(), 0.0, 0.0, false,
                "File too large: ${file.length()} bytes (max: $MAX_FILE_SIZE)")
        }

        // Duration (best-effort)
        val metadataRetriever = MediaMetadataRetriever()
        val durationSec: Double = try {
            metadataRetriever.setDataSource(cleanPath)
            val durationStr = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ((durationStr?.toDoubleOrNull() ?: 0.0) / 1000.0).coerceAtLeast(0.0)
        } catch (_: Exception) {
            0.0
        } finally {
            try { metadataRetriever.release() } catch (_: Exception) {}
        }

        val looksLikeWav = cleanPath.lowercase().endsWith(".wav")
        return if (looksLikeWav) {
            parseWavPcmAndAnalyze(cleanPath, barsCount, durationSec)
        } else {
            val res = processAudioDataViaExtractor(cleanPath, barsCount, durationSec)
            if (!res.success) parseWavPcmAndAnalyze(cleanPath, barsCount, durationSec) else res
        }
    }

    /** Extractor path (works for some compressed formats; may fail on PCM/WAV) */
    private fun processAudioDataViaExtractor(
        filePath: String,
        barsCount: Int,
        duration: Double
    ): AmplitudeResult {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(filePath)
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
                return AmplitudeResult(floatArrayOf(), duration, 0.0, false, "No audio track found in file")
            }

            extractor.selectTrack(audioTrackIndex)
            val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            Log.d(TAG, "SampleRate=$sampleRate, Channels=$channelCount, Duration=$duration")

            val amplitudes = extractAmplitudesFromExtractor(extractor, barsCount)
            if (amplitudes.isEmpty()) {
                return AmplitudeResult(floatArrayOf(), duration, sampleRate.toDouble(), false, "No audio samples extracted")
            }
            AmplitudeResult(amplitudes, if (duration > 0.0) duration else 0.0, sampleRate.toDouble(), true, null)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Extractor IAE, likely WAV/PCM path: ${e.message}")
            AmplitudeResult(floatArrayOf(), duration, 0.0, false, "Extractor failed: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio via extractor", e)
            AmplitudeResult(floatArrayOf(), duration, 0.0, false, "Extractor error: ${e.message}")
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun extractAmplitudesFromExtractor(
        extractor: MediaExtractor,
        barsCount: Int
    ): FloatArray {
        val rmsPerChunk = ArrayList<Float>()
        val buffer = ByteBuffer.allocate(CHUNK_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break

            val shortCount = size / 2 // 16-bit samples
            var sumSquares = 0.0
            var samples = 0

            buffer.rewind()
            for (i in 0 until shortCount) {
                if (buffer.remaining() < 2) break
                val s = buffer.short.toFloat() / Short.MAX_VALUE
                sumSquares += s * s
                samples++
            }

            if (samples > 0) {
                val rms = sqrt(sumSquares / samples).toFloat()
                val db = if (rms > 0f) 20f * log10(rms) else MIN_DB
                rmsPerChunk.add(db.coerceIn(MIN_DB, 0f))
            }

            buffer.clear()
            extractor.advance()
        }

        if (rmsPerChunk.isEmpty()) return floatArrayOf()
        return downsampleToBars(rmsPerChunk, barsCount)
    }

    private fun parseWavPcmAndAnalyze(
        filePath: String,
        barsCount: Int,
        durationFromMeta: Double
    ): AmplitudeResult {
        var input: BufferedInputStream? = null
        try {
            input = BufferedInputStream(FileInputStream(filePath))

            fun readU32LE(): Long {
                val b0 = input.read(); val b1 = input.read(); val b2 = input.read(); val b3 = input.read()
                if ((b0 or b1 or b2 or b3) < 0) throw EOFException()
                return (b0.toLong() and 0xFF) or
                        ((b1.toLong() and 0xFF) shl 8) or
                        ((b2.toLong() and 0xFF) shl 16) or
                        ((b3.toLong() and 0xFF) shl 24)
            }
            fun readU16LE(): Int {
                val b0 = input.read(); val b1 = input.read()
                if ((b0 or b1) < 0) throw EOFException()
                return (b0 and 0xFF) or ((b1 and 0xFF) shl 8)
            }
            fun readFourCC(): String {
                val bytes = ByteArray(4)
                val r = input.read(bytes)
                if (r != 4) throw EOFException()
                return String(bytes, Charsets.US_ASCII)
            }
            fun safeSkip(total: Long) {
                var remain = total
                while (remain > 0) {
                    val skipped = input.skip(remain)
                    if (skipped <= 0) {
                        if (input.read() == -1) throw EOFException()
                        remain -= 1
                    } else {
                        remain -= skipped
                    }
                }
            }

            // ---- Parse RIFF/WAVE ----
            val riff = readFourCC()
            if (riff != "RIFF") return AmplitudeResult(floatArrayOf(), 0.0, 0.0, false, "Not a RIFF file")
            /* fileSize */ readU32LE()
            val wave = readFourCC()
            if (wave != "WAVE") return AmplitudeResult(floatArrayOf(), 0.0, 0.0, false, "Not a WAVE file")

            var numChannels = 1
            var sampleRate = 16000
            var bitsPerSample = 16
            var dataSize = 0
            var foundFmt = false
            var foundData = false

            // ---- Chunks ----
            while (true) {
                val chunkId = try { readFourCC() } catch (_: EOFException) { break }
                val chunkSize = readU32LE().toInt()

                when (chunkId) {
                    "fmt " -> {
                        val audioFormat = readU16LE()   // 1 = PCM
                        numChannels = readU16LE()
                        sampleRate = readU32LE().toInt()
                        /* byteRate */ readU32LE()
                        /* blockAlign */ readU16LE()
                        bitsPerSample = readU16LE()
                        val remaining = chunkSize - 16
                        if (remaining > 0) safeSkip(remaining.toLong())
                        foundFmt = true
                        Log.d(TAG, "WAV fmt: format=$audioFormat, ch=$numChannels, sr=$sampleRate, bps=$bitsPerSample")
                    }
                    "data" -> {
                        dataSize = chunkSize
                        foundData = true
                        break // proceed to read PCM data next
                    }
                    else -> {
                        safeSkip(chunkSize.toLong())
                    }
                }
            }

            if (!foundFmt || !foundData || bitsPerSample != 16) {
                return AmplitudeResult(floatArrayOf(), 0.0, 0.0, false, "Unsupported WAV (expect PCM 16-bit)")
            }

            val totalSamples = dataSize / (bitsPerSample / 8) / max(1, numChannels)
            val durationSec = if (durationFromMeta > 0) durationFromMeta else
                if (sampleRate > 0) totalSamples.toDouble() / sampleRate else 0.0

            // ---- Stream PCM → RMS per chunk ----
            val bytesPerSample = bitsPerSample / 8 // =2
            val frameBuffer = ByteArray(CHUNK_SIZE.coerceAtLeast(2048))
            val rmsPerChunk = ArrayList<Float>()

            var bytesRemaining = dataSize
            while (bytesRemaining > 0) {
                val toRead = min(bytesRemaining, frameBuffer.size)
                val read = input.read(frameBuffer, 0, toRead)
                if (read <= 0) break
                bytesRemaining -= read

                var sumSq = 0.0
                var samples = 0
                val bb = ByteBuffer.wrap(frameBuffer, 0, read).order(ByteOrder.LITTLE_ENDIAN)
                val shortCount = read / bytesPerSample

                if (numChannels == 1) {
                    for (i in 0 until shortCount) {
                        if (bb.remaining() < 2) break
                        val v = bb.short.toFloat() / Short.MAX_VALUE
                        sumSq += v * v
                        samples++
                    }
                } else {
                    var i = 0
                    while (i + 1 < shortCount && bb.remaining() >= 4) {
                        val l = bb.short.toFloat() / Short.MAX_VALUE
                        val r = bb.short.toFloat() / Short.MAX_VALUE
                        val m = (l + r) * 0.5f
                        sumSq += m * m
                        samples++
                        i += 2
                    }
                }

                if (samples > 0) {
                    val rms = sqrt(sumSq / samples).toFloat()
                    val db = if (rms > 0f) 20f * log10(rms) else MIN_DB
                    rmsPerChunk.add(db.coerceIn(MIN_DB, 0f))
                }
            }

            if (rmsPerChunk.isEmpty()) {
                return AmplitudeResult(floatArrayOf(), durationSec, sampleRate.toDouble(), false, "No PCM samples")
            }

            val bars = downsampleToBars(rmsPerChunk, barsCount)
            Log.d(TAG, "WAV analyzed: sr=$sampleRate, ch=$numChannels, dur=$durationSec, bars=${bars.size}")

            return AmplitudeResult(
                amplitudes = bars,
                duration = durationSec,
                sampleRate = sampleRate.toDouble(),
                success = true,
                error = null
            )
        } catch (e: Exception) {
            return AmplitudeResult(floatArrayOf(), durationFromMeta, 0.0, false, "WAV analyze error: ${e.message}")
        } finally {
            try { input?.close() } catch (_: Exception) {}
        }
    }

    private fun downsampleToBars(values: List<Float>, barsCount: Int): FloatArray {
        if (barsCount <= 0) return floatArrayOf()
        if (values.isEmpty()) return FloatArray(barsCount) { MIN_DB }
        val step = values.size.toFloat() / barsCount
        val out = FloatArray(barsCount)
        for (i in 0 until barsCount) {
            val start = (i * step).toInt().coerceAtLeast(0)
            val end = ((i + 1) * step).toInt().coerceAtMost(values.size).coerceAtLeast(start + 1)
            val avg = values.subList(start, end).average().toFloat()
            out[i] = avg.coerceIn(MIN_DB, 0f)
        }
        return out
    }
}
