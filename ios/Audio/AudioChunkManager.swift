import Foundation

public class AudioChunkManager {
    private let chunkQueue = DispatchQueue(label: "AudioChunkQueue", attributes: .concurrent)
    private var pendingChunks = [AudioChunk]()
    
    private let stateLock = NSLock()
    private var _isStreaming = false
    private var _lastVoiceDetectedTime: TimeInterval = 0
    private var _isVoiceActive = false
    
    private let maxBufferSize = 8192
    private let vadSilenceThresholdMs: TimeInterval = 0.3
    private let maxQueueSize = 10
    
    private var isStreaming: Bool {
        get {
            stateLock.lock()
            defer { stateLock.unlock() }
            return _isStreaming
        }
        set {
            stateLock.lock()
            _isStreaming = newValue
            stateLock.unlock()
        }
    }
    
    private var lastVoiceDetectedTime: TimeInterval {
        get {
            stateLock.lock()
            defer { stateLock.unlock() }
            return _lastVoiceDetectedTime
        }
        set {
            stateLock.lock()
            _lastVoiceDetectedTime = newValue
            stateLock.unlock()
        }
    }
    
    private var isVoiceActive: Bool {
        get {
            stateLock.lock()
            defer { stateLock.unlock() }
            return _isVoiceActive
        }
        set {
            stateLock.lock()
            _isVoiceActive = newValue
            stateLock.unlock()
        }
    }
    
    struct AudioChunk {
        let data: Data
        let timestamp: TimeInterval
        let hasVoice: Bool
    }
    
    public init() {
    }
    
    public func processChunk(_ audioData: Data, hasVoice: Bool) {
        guard isStreaming else { return }
        
        let now = Date().timeIntervalSince1970
        
        stateLock.lock()
        let shouldQueue: Bool
        if hasVoice {
            _lastVoiceDetectedTime = now
            _isVoiceActive = true
            shouldQueue = true
        } else {
            if now - _lastVoiceDetectedTime > vadSilenceThresholdMs {
                _isVoiceActive = false
            }
            shouldQueue = _isVoiceActive
        }
        let currentVoiceActive = _isVoiceActive
        stateLock.unlock()
        
        if shouldQueue {
            let chunkToQueue: Data
            if audioData.count > maxBufferSize {
                print("[AudioChunkManager] Chunk too large: \(audioData.count) bytes, truncating to \(maxBufferSize)")
                chunkToQueue = audioData.prefix(maxBufferSize)
            } else {
                chunkToQueue = audioData
            }

         
            chunkQueue.async(flags: .barrier) { [weak self] in
                guard let self = self else { return }
                guard self.isStreaming else { return }
                self.pendingChunks.append(AudioChunk(
                    data: chunkToQueue,
                    timestamp: now,
                    hasVoice: hasVoice
                ))
                
                while self.pendingChunks.count > self.maxQueueSize {
                    self.pendingChunks.removeFirst()
                }
            }
        } else {
            stateLock.lock()
            let silenceMs = Int((now - _lastVoiceDetectedTime) * 1000)
            stateLock.unlock()
            print("[AudioChunkManager] Skipping chunk - no voice for \(silenceMs)ms")
        }
    }
    
    @objc
    public func getNextChunk() -> [String: Any]? {
        var chunk: AudioChunk?
        
        chunkQueue.sync(flags: .barrier) {
            if !pendingChunks.isEmpty {
                chunk = pendingChunks.removeFirst()
            }
        }
        
        guard let audioChunk = chunk else { return nil }
        
        let byteArray = Array(audioChunk.data)
        
        return [
            "data": byteArray,
            "timestamp": audioChunk.timestamp,
            "hasVoice": audioChunk.hasVoice,
            "size": audioChunk.data.count
        ]
    }
    
    @objc
    public func getAllChunks() -> [[String: Any]] {
        var chunks = [[String: Any]]()
        
        while chunks.count < 10 {
            guard let chunk = getNextChunk() else { break }
            chunks.append(chunk)
        }
        
        return chunks
    }
    
    @objc
    public func startStreaming() {
        stateLock.lock()
        _isStreaming = true
        _lastVoiceDetectedTime = Date().timeIntervalSince1970
        _isVoiceActive = false
        stateLock.unlock()
        
        chunkQueue.async(flags: .barrier) { [weak self] in
            self?.pendingChunks.removeAll()
        }
        
        print("[AudioChunkManager] Started streaming")
    }
    
    @objc
    public func stopStreaming() {
        stateLock.lock()
        _isStreaming = false
        _isVoiceActive = false
        stateLock.unlock()
        
        chunkQueue.async(flags: .barrier) { [weak self] in
            self?.pendingChunks.removeAll()
        }
        
        print("[AudioChunkManager] Stopped streaming")
    }
    
    @objc
    public func isCurrentlyStreaming() -> Bool {
        return isStreaming
    }
    
    @objc
    public func getPendingChunkCount() -> Int {
        var count = 0
        chunkQueue.sync {
            count = pendingChunks.count
        }
        return count
    }
    
    @objc
    public func clearBuffer() {
        chunkQueue.async(flags: .barrier) { [weak self] in
            self?.pendingChunks.removeAll()
        }
    }
}
