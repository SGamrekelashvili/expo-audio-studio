import Foundation
import AVFoundation

class AudioChunkCapture {
    
    private let sharedEngine = SharedAudioEngineManager.shared
    private let stateLock = NSLock()
    private var _isCapturing: Bool = false
    
    private var isCapturing: Bool {
        get {
            stateLock.lock()
            defer { stateLock.unlock() }
            return _isCapturing
        }
        set {
            stateLock.lock()
            _isCapturing = newValue
            stateLock.unlock()
        }
    }
    
    func startCapture(callback: @escaping ([String: Any]) -> Void) {
        guard !isCapturing else {
            print("[\(Date())] AudioChunkCapture: Already capturing")
            return
        }
        
        // Wrap the callback to match expected type (Any) -> Void
        let wrappedCallback: (Any) -> Void = { data in
            if let chunkData = data as? [String: Any] {
                callback(chunkData)
            } else {
                print("[\(Date())] AudioChunkCapture: Received unexpected data type: \(type(of: data))")
            }
        }
        
        sharedEngine.enableChunkCapture(callback: wrappedCallback)
        
        // Check if engine actually started
        if sharedEngine.isActive() {
            isCapturing = true
            print("[\(Date())] AudioChunkCapture: Started successfully")
        } else {
            print("[\(Date())] AudioChunkCapture: Failed to start - engine did not activate")
        }
    }
    
    func stopCapture() {
        guard isCapturing else { return }
        
        sharedEngine.disableChunkCapture()
        isCapturing = false
        print("[\(Date())] AudioChunkCapture: Stopped")
    }
    
    func isActive() -> Bool {
        return isCapturing
    }
}
