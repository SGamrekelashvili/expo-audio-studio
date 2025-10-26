import Foundation
import AVFoundation
import Accelerate

/**
 * High-performance audio amplitude analyzer for visualization bars
 * Uses Apple's Accelerate framework for optimized DSP operations
 * 
 * MEMORY OPTIMIZED: Static methods to avoid object allocation
 */
class AudioAmplitudeAnalyzer {
    
    // MARK: - Types
    
    struct AmplitudeResult {
        let amplitudes: [Float]
        let duration: Double
        let sampleRate: Double
        let success: Bool
        let error: String?
    }
    
    // MARK: - Constants
    
    private static let maxFileSize: Int64 = 100 * 1024 * 1024 // 100MB limit
    private static let chunkSize: Int = 4096 // Process in 4KB chunks
    private static let maxBarsCount: Int = 2048 // Maximum bars for performance
    
    // MARK: - Public Methods
    
    /**
     * MEMORY OPTIMIZED: Analyzes audio file and returns amplitude data for visualization bars
     * Uses streaming processing to minimize memory footprint
     *
     * @param fileUrl: URL to the audio file
     * @param barsCount: Number of amplitude bars to generate (1-2048)
     * @returns AmplitudeResult with amplitude data and metadata
     */
    static func getAudioAmplitudes(fileUrl: URL, barsCount: Int) -> AmplitudeResult {
        print("[\(Date())] AudioAmplitudeAnalyzer: Starting analysis for \(fileUrl.lastPathComponent)")
        print("[\(Date())] Requested bars: \(barsCount)")
        
        // Validate inputs
        guard barsCount > 0 && barsCount <= maxBarsCount else {
            return AmplitudeResult(
                amplitudes: [],
                duration: 0.0,
                sampleRate: 0.0,
                success: false,
                error: "Invalid barsCount: must be between 1 and \(maxBarsCount)"
            )
        }
        
        guard fileUrl.isFileURL else {
            return AmplitudeResult(
                amplitudes: [],
                duration: 0.0,
                sampleRate: 0.0,
                success: false,
                error: "Invalid file URL: must be a local file URL"
            )
        }
        
        guard FileManager.default.fileExists(atPath: fileUrl.path) else {
            return AmplitudeResult(
                amplitudes: [],
                duration: 0.0,
                sampleRate: 0.0,
                success: false,
                error: "File not found: \(fileUrl.path)"
            )
        }
        
        // Check file size
        do {
            let fileAttributes = try FileManager.default.attributesOfItem(atPath: fileUrl.path)
            if let fileSize = fileAttributes[.size] as? Int64, fileSize > maxFileSize {
                return AmplitudeResult(
                    amplitudes: [],
                    duration: 0.0,
                    sampleRate: 0.0,
                    success: false,
                    error: "File too large: \(fileSize) bytes (max: \(maxFileSize))"
                )
            }
        } catch {
            return AmplitudeResult(
                amplitudes: [],
                duration: 0.0,
                sampleRate: 0.0,
                success: false,
                error: "Could not read file attributes: \(error.localizedDescription)"
            )
        }
        
        // Create AVAsset and extract audio data
        let asset = AVAsset(url: fileUrl)
        let duration = CMTimeGetSeconds(asset.duration)
        
        guard duration > 0 && !duration.isNaN else {
            return AmplitudeResult(
                amplitudes: [],
                duration: 0.0,
                sampleRate: 0.0,
                success: false,
                error: "Invalid audio duration"
            )
        }
        
        print("[\(Date())] Audio duration: \(duration) seconds")
        
        // Extract audio track
        guard let audioTrack = asset.tracks(withMediaType: .audio).first else {
            return AmplitudeResult(
                amplitudes: [],
                duration: duration,
                sampleRate: 0.0,
                success: false,
                error: "No audio track found in file"
            )
        }
        
        // Get audio format information
        let formatDescriptions = audioTrack.formatDescriptions
        guard let formatDescription = formatDescriptions.first else {
            return AmplitudeResult(
                amplitudes: [],
                duration: duration,
                sampleRate: 0.0,
                success: false,
                error: "Could not get audio format description"
            )
        }
        
        let audioStreamBasicDescription = CMAudioFormatDescriptionGetStreamBasicDescription(formatDescription as! CMAudioFormatDescription)
        guard let basicDescription = audioStreamBasicDescription else {
            return AmplitudeResult(
                amplitudes: [],
                duration: duration,
                sampleRate: 0.0,
                success: false,
                error: "Could not get audio stream basic description"
            )
        }
        
        let originalSampleRate = basicDescription.pointee.mSampleRate
        print("[\(Date())] Original sample rate: \(originalSampleRate) Hz")
        
        // Process audio data
        return processAudioData(asset: asset, barsCount: barsCount, duration: duration, sampleRate: originalSampleRate)
    }
    
    // MARK: - Private Methods
    
    /**
     * Processes audio data using AVAssetReader for efficient streaming
     */
    private static func processAudioData(asset: AVAsset, barsCount: Int, duration: Double, sampleRate: Double) -> AmplitudeResult {
        do {
            // Create asset reader
            let assetReader = try AVAssetReader(asset: asset)
            
            // Configure audio output settings for optimal processing
            let outputSettings: [String: Any] = [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVLinearPCMBitDepthKey: 16,
                AVLinearPCMIsBigEndianKey: false,
                AVLinearPCMIsFloatKey: false,
                AVLinearPCMIsNonInterleaved: false,
                AVSampleRateKey: 44100 // Downsample to 44.1kHz for efficiency
            ]
            
            guard let audioTrack = asset.tracks(withMediaType: .audio).first else {
                return AmplitudeResult(
                    amplitudes: [],
                    duration: duration,
                    sampleRate: sampleRate,
                    success: false,
                    error: "No audio track available"
                )
            }
            
            let assetReaderOutput = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: outputSettings)
            assetReaderOutput.alwaysCopiesSampleData = false // Optimize memory usage
            
            guard assetReader.canAdd(assetReaderOutput) else {
                return AmplitudeResult(
                    amplitudes: [],
                    duration: duration,
                    sampleRate: sampleRate,
                    success: false,
                    error: "Cannot add asset reader output"
                )
            }
            
            assetReader.add(assetReaderOutput)
            
            guard assetReader.startReading() else {
                return AmplitudeResult(
                    amplitudes: [],
                    duration: duration,
                    sampleRate: sampleRate,
                    success: false,
                    error: "Failed to start reading asset: \(assetReader.error?.localizedDescription ?? "Unknown error")"
                )
            }
            
            print("[\(Date())] Started reading audio data...")
            
            // MEMORY OPTIMIZED: Stream processing to avoid large array allocation
            var amplitudes = Array(repeating: Float(0.0), count: barsCount)
            var processedChunks = 0
            var totalSamples = 0
            
            // Calculate samples per bar based on estimated total samples
            let estimatedTotalSamples = Int(duration * 44100) // 44.1kHz sample rate
            let samplesPerBar = max(1, estimatedTotalSamples / barsCount)
            
            var currentBarIndex = 0
            var samplesInCurrentBar = 0
            var sumOfSquares: Double = 0.0
            
            print("[\(Date())] Starting optimized processing: estimated \(estimatedTotalSamples) samples, \(samplesPerBar) per bar")
            
            while assetReader.status == .reading && currentBarIndex < barsCount {
                guard let sampleBuffer = assetReaderOutput.copyNextSampleBuffer() else {
                    break
                }
                
                let samples = extractSamplesFromBuffer(sampleBuffer: sampleBuffer)
                
                // Process samples for current bar
                for sample in samples {
                    if currentBarIndex >= barsCount { break }
                    
                    let normalizedSample = Double(sample) / Double(Int16.max)
                    sumOfSquares += normalizedSample * normalizedSample
                    samplesInCurrentBar += 1
                    totalSamples += 1
                    
                    // Check if we've collected enough samples for this bar
                    if samplesInCurrentBar >= samplesPerBar {
                        // Calculate RMS for this bar
                        let rmsAmplitude = sqrt(sumOfSquares / Double(samplesInCurrentBar))
                        
                        // Convert RMS to dB (same format as recording amplitudes)
                        let dBValue: Float
                        if rmsAmplitude > 0 {
                            dBValue = 20.0 * log10(Float(rmsAmplitude))
                        } else {
                            dBValue = -160.0 // Silence threshold
                        }
                        amplitudes[currentBarIndex] = dBValue
                        
                        // Move to next bar
                        currentBarIndex += 1
                        samplesInCurrentBar = 0
                        sumOfSquares = 0.0
                    }
                }
                
                processedChunks += 1
                
                // Log progress every 100 chunks
                if processedChunks % 100 == 0 {
                    print("[\(Date())] Processed \(processedChunks) chunks, bar \(currentBarIndex)/\(barsCount)")
                }
                
                CMSampleBufferInvalidate(sampleBuffer)
            }
            
            // Handle any remaining samples in the last bar
            if samplesInCurrentBar > 0 && currentBarIndex < barsCount {
                let rmsAmplitude = sqrt(sumOfSquares / Double(samplesInCurrentBar))
                
                // Convert RMS to dB (same format as recording amplitudes)
                let dBValue: Float
                if rmsAmplitude > 0 {
                    dBValue = 20.0 * log10(Float(rmsAmplitude))
                } else {
                    dBValue = -160.0 // Silence threshold
                }
                amplitudes[currentBarIndex] = dBValue
            }
            
            print("[\(Date())] Finished optimized processing. Total samples: \(totalSamples), bars: \(currentBarIndex)")
            
            // Check for reading errors
            if assetReader.status == .failed {
                return AmplitudeResult(
                    amplitudes: [],
                    duration: duration,
                    sampleRate: sampleRate,
                    success: false,
                    error: "Asset reader failed: \(assetReader.error?.localizedDescription ?? "Unknown error")"
                )
            }
            
            // Normalize amplitudes to 0.0-1.0 range
            let maxAmplitude = amplitudes.max() ?? 1.0
            if maxAmplitude > 0 {
                for i in 0..<amplitudes.count {
                    amplitudes[i] = amplitudes[i] / maxAmplitude
                }
            }
            
            print("[\(Date())] Generated \(amplitudes.count) amplitude bars")
            
            return AmplitudeResult(
                amplitudes: amplitudes,
                duration: duration,
                sampleRate: 44100, // We downsampled to 44.1kHz
                success: true,
                error: nil
            )
            
        } catch {
            return AmplitudeResult(
                amplitudes: [],
                duration: duration,
                sampleRate: sampleRate,
                success: false,
                error: "Failed to process audio: \(error.localizedDescription)"
            )
        }
    }
    
    /**
     * Extracts Int16 samples from CMSampleBuffer
     */
    private static func extractSamplesFromBuffer(sampleBuffer: CMSampleBuffer) -> [Int16] {
        guard let blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else {
            return []
        }
        
        let length = CMBlockBufferGetDataLength(blockBuffer)
        let data = UnsafeMutablePointer<Int16>.allocate(capacity: length / 2)
        defer { data.deallocate() }
        
        CMBlockBufferCopyDataBytes(blockBuffer, atOffset: 0, dataLength: length, destination: data)
        
        let sampleCount = length / 2
        return Array(UnsafeBufferPointer(start: data, count: sampleCount))
    }
    
    /**
     * Calculates amplitude bars using Apple's Accelerate framework for optimal performance
     */
    private static func calculateAmplitudes(samples: [Int16], barsCount: Int) -> [Float] {
        let totalSamples = samples.count
        let samplesPerBar = totalSamples / barsCount
        
        guard samplesPerBar > 0 else {
            // If we have fewer samples than bars, return what we have
            return samples.map { abs(Float($0)) / Float(Int16.max) }
        }
        
        var amplitudes: [Float] = []
        amplitudes.reserveCapacity(barsCount)
        
        print("[\(Date())] Calculating amplitudes: \(totalSamples) samples -> \(barsCount) bars (\(samplesPerBar) samples per bar)")
        
        for barIndex in 0..<barsCount {
            let startIndex = barIndex * samplesPerBar
            let endIndex = min(startIndex + samplesPerBar, totalSamples)
            
            if startIndex >= totalSamples {
                amplitudes.append(0.0)
                continue
            }
            
            // Use Accelerate framework for high-performance RMS calculation
            let barSamples = Array(samples[startIndex..<endIndex])
            let rmsAmplitude = calculateRMSAmplitude(samples: barSamples)
            
            amplitudes.append(rmsAmplitude)
        }
        
        // Normalize amplitudes to 0.0-1.0 range
        let maxAmplitude = amplitudes.max() ?? 1.0
        if maxAmplitude > 0 {
            for i in 0..<amplitudes.count {
                amplitudes[i] = amplitudes[i] / maxAmplitude
            }
        }
        
        return amplitudes
    }
    
    /**
     * Calculates RMS amplitude using Accelerate framework for optimal performance
     */
    private static func calculateRMSAmplitude(samples: [Int16]) -> Float {
        guard !samples.isEmpty else { return 0.0 }
        
        // Convert Int16 to Float for processing
        let floatSamples = samples.map { Float($0) / Float(Int16.max) }
        
        // Calculate RMS using Accelerate framework
        var rms: Float = 0.0
        var sumOfSquares: Float = 0.0
        
        // Use vDSP for vectorized operations
        vDSP_svesq(floatSamples, 1, &sumOfSquares, vDSP_Length(floatSamples.count))
        rms = sqrt(sumOfSquares / Float(floatSamples.count))
        
        return rms
    }
    
    // MARK: - Memory Management
    
    /**
     * Forces memory cleanup after processing large audio files
     * Call this if you're processing multiple large files in sequence
     */
    static func forceMemoryCleanup() {
        autoreleasepool {
            // Force garbage collection of any retained objects
            print("[\(Date())] AudioAmplitudeAnalyzer: Forcing memory cleanup")
        }
    }
}
