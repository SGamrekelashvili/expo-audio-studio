import Foundation
import AVFoundation

class AudioChunkCapture {
    
    private let sharedEngine = SharedAudioEngineManager.shared
    private var isCapturing: Bool = false
    
    func startCapture(callback: @escaping ([String: Any]) -> Void) {
        guard !isCapturing else {
            print("[\(Date())] AudioChunkCapture: Already capturing")
            return
        }
        
        sharedEngine.enableChunkCapture(callback: callback)
        
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
