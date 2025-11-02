import Foundation
import AVFoundation

class SharedAudioEngineManager {
    
    static let shared = SharedAudioEngineManager()
    
    private var audioEngine: AVAudioEngine?
    private var isEngineRunning = false
    
    private var chunkCallback: (([String: Any]) -> Void)?
    private var vadBufferCallback: ((AVAudioPCMBuffer, AVAudioTime) -> Void)?
    
    private var chunkEnabled = false
    private var vadEnabled = false
    
    private let lock = NSLock()
    
    private let targetSampleRate: Double = 16000
    private var converter: AVAudioConverter?
    
    private init() {}
    
    
    func enableChunkCapture(callback: @escaping ([String: Any]) -> Void) {
        lock.lock()
        defer { lock.unlock() }
        
        chunkCallback = callback
        chunkEnabled = true
        startEngineIfNeeded()
        
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
    
    
    private func startEngineIfNeeded() {
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
                
                guard let convertedBuffer = self.convertBuffer(buffer, converter: audioConverter, targetFormat: targetFormat) else {
                    return
                }
        
                self.vadBufferCallback?(convertedBuffer, time)
                
                if let chunkCallback = self.chunkCallback {
                    self.processChunk(buffer: convertedBuffer, callback: chunkCallback)
                }
            }
            
            try audioEngine.start()
            self.audioEngine = audioEngine
            self.isEngineRunning = true
            
        } catch {
            
            if let engineInstance = engine {
                engineInstance.inputNode.removeTap(onBus: 0)
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
      
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
        }
        audioEngine = nil
        converter = nil
        isEngineRunning = false
        
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
    
    private func processChunk(buffer: AVAudioPCMBuffer, callback: @escaping ([String: Any]) -> Void) {
        guard let channelData = buffer.floatChannelData else { return }
        let channelDataValue = channelData.pointee
        let frameLength = Int(buffer.frameLength)
        
        var int16Data = [Int16](repeating: 0, count: frameLength)
        for i in 0..<frameLength {
            let sample = channelDataValue[i]
            let clampedSample = max(-1.0, min(1.0, sample))
            int16Data[i] = Int16(clampedSample * 32767.0)
        }
        
        let data = Data(bytes: &int16Data, count: int16Data.count * MemoryLayout<Int16>.size)
        
        let base64String = data.base64EncodedString()
        
        DispatchQueue.main.async {
            callback(["base64": base64String])
        }
    }
}
