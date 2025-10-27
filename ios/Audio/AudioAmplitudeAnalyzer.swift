import Foundation
import AVFoundation

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

    static func getAudioAmplitudes(fileUrl: URL, barsCount: Int) -> AmplitudeResult {
        print("[\(Date())] AudioAmplitudeAnalyzer: Starting analysis for \(fileUrl.lastPathComponent)")
        print("[\(Date())] Requested bars: \(barsCount)")
        
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
        
        guard let audioTrack = asset.tracks(withMediaType: .audio).first else {
            return AmplitudeResult(
                amplitudes: [],
                duration: duration,
                sampleRate: 0.0,
                success: false,
                error: "No audio track found in file"
            )
        }
        
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
    

    private static func processAudioData(asset: AVAsset, barsCount: Int, duration: Double, sampleRate: Double) -> AmplitudeResult {
        do {
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
            
            var amplitudes = Array(repeating: Float(0.0), count: barsCount)
            var processedChunks = 0
            var totalSamples = 0
            
            let estimatedTotalSamples = Int(duration * 44100)
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
                    
                    if samplesInCurrentBar >= samplesPerBar {
                        let rmsAmplitude = sqrt(sumOfSquares / Double(samplesInCurrentBar))
                        
                        let dBValue: Float
                        if rmsAmplitude > 0 {
                            dBValue = 20.0 * log10(Float(rmsAmplitude))
                        } else {
                            dBValue = -160.0
                        }
                        amplitudes[currentBarIndex] = dBValue
                        
                        currentBarIndex += 1
                        samplesInCurrentBar = 0
                        sumOfSquares = 0.0
                    }
                }
                
                processedChunks += 1
                
                if processedChunks % 100 == 0 {
                    print("[\(Date())] Processed \(processedChunks) chunks, bar \(currentBarIndex)/\(barsCount)")
                }
                
                CMSampleBufferInvalidate(sampleBuffer)
            }
            
            if samplesInCurrentBar > 0 && currentBarIndex < barsCount {
                let rmsAmplitude = sqrt(sumOfSquares / Double(samplesInCurrentBar))
                
                let dBValue: Float
                if rmsAmplitude > 0 {
                    dBValue = 20.0 * log10(Float(rmsAmplitude))
                } else {
                    dBValue = -160.0
                }
                amplitudes[currentBarIndex] = dBValue
            }
            
            print("[\(Date())] Finished optimized processing. Total samples: \(totalSamples), bars: \(currentBarIndex)")
            
            if assetReader.status == .failed {
                return AmplitudeResult(
                    amplitudes: [],
                    duration: duration,
                    sampleRate: sampleRate,
                    success: false,
                    error: "Asset reader failed: \(assetReader.error?.localizedDescription ?? "Unknown error")"
                )
            }
            
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
                sampleRate: 44100,
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
    
    // MARK: - Memory Management
    

    static func forceMemoryCleanup() {
        autoreleasepool {
            print("[\(Date())] AudioAmplitudeAnalyzer: Forcing memory cleanup")
        }
    }
}
