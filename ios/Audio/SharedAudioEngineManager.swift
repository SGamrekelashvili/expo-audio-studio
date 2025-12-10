import Foundation
import AVFoundation
import ExpoModulesCore
import CoreAudio

typealias AudioChunkCallback = (Any) -> Void

class SharedAudioEngineManager {
    
    static let shared = SharedAudioEngineManager()
    
    private let lock = NSRecursiveLock()
    
    private var audioEngine: AVAudioEngine?
    private var isEngineRunning = false
    
    private var chunkCallback: AudioChunkCallback?
    private var vadBufferCallback: ((AVAudioPCMBuffer, AVAudioTime) -> Void)?
    
    private var chunkEnabled = false
    private var vadEnabled = false
    
    private var converter: AVAudioConverter?
    
    private var audioChunkManager: AudioChunkManager?
    private var currentVoiceState = false
    
    private var engineUsers = 0
    
    func updateVoiceState(_ hasVoice: Bool) {
        lock.lock()
        defer { lock.unlock() }
        currentVoiceState = hasVoice
    }
    
    private let targetSampleRate: Double = 16000
    
    private init() {}
    
    
    func enableChunkCapture(callback: @escaping AudioChunkCallback) {
        lock.lock()
        defer { lock.unlock() }
        
        chunkCallback = callback
        chunkEnabled = true
        engineUsers += 1
        
        if audioChunkManager == nil {
            audioChunkManager = AudioChunkManager()
            audioChunkManager?.startStreaming()
        }
        
        startEngineIfNeeded()
        
        if !isEngineRunning {
            chunkCallback = nil
            chunkEnabled = false
            engineUsers = max(0, engineUsers - 1)
            audioChunkManager?.stopStreaming()
            audioChunkManager = nil
        }
    }
    
    func disableChunkCapture() {
        lock.lock()
        defer { lock.unlock() }
        
        chunkCallback = nil
        chunkEnabled = false
        engineUsers = max(0, engineUsers - 1)
        
        let manager = audioChunkManager
        audioChunkManager = nil
        
        manager?.stopStreaming()
        
        if engineUsers == 0 && !vadEnabled {
            stopEngineUnsafe()
        }
    }
    
    func enableVADCapture(callback: @escaping (AVAudioPCMBuffer, AVAudioTime) -> Void) {
        lock.lock()
        defer { lock.unlock() }
        
        vadBufferCallback = callback
        vadEnabled = true
        engineUsers += 1
        
        startEngineIfNeeded()
        
        if !isEngineRunning {
            vadBufferCallback = nil
            vadEnabled = false
            engineUsers = max(0, engineUsers - 1)
        }
    }
    
    func disableVADCapture() {
        lock.lock()
        defer { lock.unlock() }
        
        vadEnabled = false
        vadBufferCallback = nil
        engineUsers = max(0, engineUsers - 1)
        
        if engineUsers == 0 && !chunkEnabled {
            stopEngineUnsafe()
        }
    }
    
    func isActive() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return isEngineRunning
    }
    
    func forceStop() {
        lock.lock()
        defer { lock.unlock() }
        
        chunkEnabled = false
        vadEnabled = false
        chunkCallback = nil
        vadBufferCallback = nil
        engineUsers = 0
        
        let manager = audioChunkManager
        audioChunkManager = nil
        
        manager?.stopStreaming()
        stopEngineUnsafe()
    }
    
    
    private func startEngineIfNeeded() {
        guard !isEngineRunning else {
            print("[\(Date())] SharedAudioEngine: Already running, callbacks updated")
            return
        }
        
        guard engineUsers > 0 else {
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
            
            guard let targetFormat = AVAudioFormat(
                commonFormat: .pcmFormatFloat32,
                sampleRate: targetSampleRate,
                channels: 1,
                interleaved: false
            ) else {
                print("[\(Date())] SharedAudioEngine: Failed to create target format")
                return
            }
            

            guard let audioConverter = AVAudioConverter(from: inputFormat, to: targetFormat) else {
                print("[\(Date())] SharedAudioEngine: Failed to create converter")
                return
            }
            self.converter = audioConverter
            
            let bufferSize: AVAudioFrameCount = 8192
            
            inputNode.installTap(onBus: 0, bufferSize: bufferSize, format: inputFormat) { [weak self] buffer, time in
                guard let self = self else { return }
                
                self.lock.lock()
                let vadCallback = self.vadBufferCallback
                let chunkCallback = self.chunkCallback
                let shouldProcess = self.isEngineRunning
                self.lock.unlock()
                
                guard shouldProcess else { return }
                
                guard let convertedBuffer = self.convertBuffer(buffer, converter: audioConverter, targetFormat: targetFormat) else {
                    return
                }
                
                vadCallback?(convertedBuffer, time)
                
                if let callback = chunkCallback {
                    self.processChunk(buffer: convertedBuffer, callback: callback)
                }
            }
            
            try audioEngine.start()
            self.audioEngine = audioEngine
            self.isEngineRunning = true
            print("[\(Date())] SharedAudioEngine: Started successfully")
            
        } catch {
            print("[\(Date())] SharedAudioEngine: Failed to start - \(error)")
            if let engineInstance = engine {
                engineInstance.inputNode.removeTap(onBus: 0)
            }
            self.converter = nil
            self.audioEngine = nil
            self.isEngineRunning = false
        }
    }
    
    private func stopEngineIfNotNeeded() {
        lock.lock()
        defer { lock.unlock() }
        
        guard engineUsers == 0 else {
            print("[\(Date())] SharedAudioEngine: Still has active consumers")
            return
        }
        
        stopEngineUnsafe()
    }
    
    private func stopEngine() {
        lock.lock()
        defer { lock.unlock() }
        stopEngineUnsafe()
    }
    
    private func stopEngineUnsafe() {
        guard isEngineRunning else { return }
        
        isEngineRunning = false
        
        if let engine = audioEngine {
            engine.inputNode.removeTap(onBus: 0)
           
            if engine.isRunning {
                engine.stop()
            }
        }
        
        audioEngine = nil
        converter = nil
        audioChunkManager?.stopStreaming()
        audioChunkManager = nil
        print("[\(Date())] SharedAudioEngine: Stopped")
    }
    
    
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
    
    private func processChunk(buffer: AVAudioPCMBuffer, callback: AudioChunkCallback) {
        guard let channelData = buffer.floatChannelData?[0] else { return }
        
        let frameLength = Int(buffer.frameLength)
        
        autoreleasepool {
            var pcmData = Data(capacity: frameLength * 2)
            for i in 0..<frameLength {
                let sample = channelData[i]
                let int16Sample = Int16(max(-32768, min(32767, sample * 32768)))
                withUnsafeBytes(of: int16Sample.littleEndian) { bytes in
                    pcmData.append(contentsOf: bytes)
                }
            }
            
            lock.lock()
            let voiceState = currentVoiceState
            let manager = audioChunkManager
            lock.unlock()
            
            if let chunkManager = manager {
                chunkManager.processChunk(pcmData, hasVoice: voiceState)
                
                let chunks = chunkManager.getAllChunks()
                if !chunks.isEmpty {
                    callback([
                        "chunks": chunks,
                        "type": "batch",
                        "format": "uint8array"
                    ] as [String: Any])
                }
            } else {
                let byteArray = Array(pcmData)
                callback([
                    "data": byteArray,
                    "type": "direct",
                    "format": "uint8array"
                ] as [String: Any])
            }
        }
    }
}
