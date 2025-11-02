import Foundation
import AVFoundation

class SharedAudioEngineManager {
    
    static let shared = SharedAudioEngineManager()
    
    private var audioEngine: AVAudioEngine?
    private var isEngineRunning = false
    
    // Callbacks for different consumers
    private var chunkCallback: (([String: Any]) -> Void)?
    private var vadBufferCallback: ((AVAudioPCMBuffer, AVAudioTime) -> Void)?
    
    private var chunkEnabled = false
    private var vadEnabled = false
    
    private let lock = NSLock()
    
    // Target format: 16kHz mono to match recorder settings
    private let targetSampleRate: Double = 16000
    private var converter: AVAudioConverter?
    
    private init() {}
    
    // MARK: - Public API
    
    func enableChunkCapture(callback: @escaping ([String: Any]) -> Void) {
        lock.lock()
        defer { lock.unlock() }
        
        chunkCallback = callback
        chunkEnabled = true
        startEngineIfNeeded()
        
        // If engine failed to start, reset state
        if !isEngineRunning {
            chunkCallback = nil
            chunkEnabled = false
        }
    }
    
    func disableChunkCapture() {
        lock.lock()
        defer { lock.unlock() }
        
        chunkEnabled = false
        chunkCallback = nil
        stopEngineIfNotNeeded()
    }
    
    func enableVADCapture(callback: @escaping (AVAudioPCMBuffer, AVAudioTime) -> Void) {
        lock.lock()
        defer { lock.unlock() }
        
        vadBufferCallback = callback
        vadEnabled = true
        startEngineIfNeeded()
        
        // If engine failed to start, reset state
        if !isEngineRunning {
            vadBufferCallback = nil
            vadEnabled = false
        }
    }
    
    func disableVADCapture() {
        lock.lock()
        defer { lock.unlock() }
        
        vadEnabled = false
        vadBufferCallback = nil
        stopEngineIfNotNeeded()
    }
    
    func isActive() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return isEngineRunning
    }
    
    func forceStop() {
        lock.lock()
        defer { lock.unlock() }
        
        stopEngine()
        chunkEnabled = false
        vadEnabled = false
        chunkCallback = nil
        vadBufferCallback = nil
    }
    
    // MARK: - Private Engine Management
    
    private func startEngineIfNeeded() {
        // Note: lock should already be held by caller
        guard !isEngineRunning else {
            print("[\(Date())] SharedAudioEngine: Already running, callbacks updated")
            return
        }
        
        guard chunkEnabled || vadEnabled else {
            print("[\(Date())] SharedAudioEngine: No consumers enabled")
            return
        }
        
        var engine: AVAudioEngine?
        
        do {
            engine = AVAudioEngine()
            guard let audioEngine = engine else {
                print("[\(Date())] SharedAudioEngine: Failed to create engine")
                return
            }
            let inputNode = audioEngine.inputNode
            let inputFormat = inputNode.outputFormat(forBus: 0)
            
            // Create target format: 16kHz mono
            guard let targetFormat = AVAudioFormat(
                commonFormat: .pcmFormatFloat32,
                sampleRate: targetSampleRate,
                channels: 1,
                interleaved: false
            ) else {
                print("[\(Date())] SharedAudioEngine: Failed to create target format")
                return
            }
            
            // Create converter from input format to 16kHz
            guard let audioConverter = AVAudioConverter(from: inputFormat, to: targetFormat) else {
                print("[\(Date())] SharedAudioEngine: Failed to create converter")
                return
            }
            self.converter = audioConverter
            
            // Use buffer size appropriate for conversion
            let bufferSize: AVAudioFrameCount = 8192
            
            inputNode.installTap(onBus: 0, bufferSize: bufferSize, format: inputFormat) { [weak self] buffer, time in
                guard let self = self else { return }
                
                // Convert to 16kHz format
                guard let convertedBuffer = self.convertBuffer(buffer, converter: audioConverter, targetFormat: targetFormat) else {
                    return
                }
                
                // Note: Reading closure references is atomic on modern architectures
                // These callbacks can be safely read without locks from the audio thread
                
                // Send to VAD consumer if available
                self.vadBufferCallback?(convertedBuffer, time)
                
                // Send to chunk consumer if available
                if let chunkCallback = self.chunkCallback {
                    self.processChunk(buffer: convertedBuffer, callback: chunkCallback)
                }
            }
            
            try audioEngine.start()
            self.audioEngine = audioEngine
            self.isEngineRunning = true
            
            print("[\(Date())] SharedAudioEngine: Started (VAD: \(vadEnabled), Chunks: \(chunkEnabled))")
            print("[\(Date())] SharedAudioEngine: Input format: \(inputFormat.sampleRate)Hz -> Target: \(targetSampleRate)Hz")
        } catch {
            print("[\(Date())] SharedAudioEngine: Failed to start - \(error.localizedDescription)")
            
            // Cleanup on failure (engine might be partially initialized)
            if let engineInstance = engine {
                do {
                    try engineInstance.inputNode.removeTap(onBus: 0)
                } catch {
                    // Tap might not have been installed, safe to ignore
                    print("[\(Date())] SharedAudioEngine: No tap to remove during cleanup")
                }
            }
            self.converter = nil
            self.audioEngine = nil
            self.isEngineRunning = false
        }
    }
    
    private func stopEngineIfNotNeeded() {
        guard !chunkEnabled && !vadEnabled else {
            print("[\(Date())] SharedAudioEngine: Still has active consumers")
            return
        }
        
        stopEngine()
    }
    
    private func stopEngine() {
        guard isEngineRunning else { return }
        
        if let engine = audioEngine {
            do {
                try engine.inputNode.removeTap(onBus: 0)
            } catch {
                // Tap might not exist, safe to ignore
                print("[\(Date())] SharedAudioEngine: No tap to remove")
            }
            engine.stop()
        }
        audioEngine = nil
        converter = nil
        isEngineRunning = false
        
        print("[\(Date())] SharedAudioEngine: Stopped")
    }
    
    // MARK: - Audio Processing
    
    private func convertBuffer(_ inputBuffer: AVAudioPCMBuffer, converter: AVAudioConverter, targetFormat: AVAudioFormat) -> AVAudioPCMBuffer? {
        let inputFrameCount = inputBuffer.frameLength
        let ratio = targetFormat.sampleRate / inputBuffer.format.sampleRate
        let outputFrameCapacity = AVAudioFrameCount(Double(inputFrameCount) * ratio)
        
        guard let outputBuffer = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: outputFrameCapacity) else {
            return nil
        }
        
        var error: NSError?
        let status = converter.convert(to: outputBuffer, error: &error) { inNumPackets, outStatus in
            outStatus.pointee = .haveData
            return inputBuffer
        }
        
        if status == .error {
            print("[\(Date())] SharedAudioEngine: Conversion error - \(error?.localizedDescription ?? "unknown")")
            return nil
        }
        
        return outputBuffer
    }
    
    private func processChunk(buffer: AVAudioPCMBuffer, callback: @escaping ([String: Any]) -> Void) {
        // Convert audio buffer to PCM data
        guard let channelData = buffer.floatChannelData else { return }
        let channelDataValue = channelData.pointee
        let frameLength = Int(buffer.frameLength)
        
        // Convert float samples to 16-bit PCM
        var int16Data = [Int16](repeating: 0, count: frameLength)
        for i in 0..<frameLength {
            let sample = channelDataValue[i]
            let clampedSample = max(-1.0, min(1.0, sample))
            int16Data[i] = Int16(clampedSample * 32767.0)
        }
        
        // Convert to Data
        let data = Data(bytes: &int16Data, count: int16Data.count * MemoryLayout<Int16>.size)
        
        // Convert to base64
        let base64String = data.base64EncodedString()
        
        // Send chunk event
        DispatchQueue.main.async {
            callback(["base64": base64String])
        }
    }
}
