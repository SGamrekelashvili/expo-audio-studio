import Foundation
import AVFoundation

class RecorderManager: NSObject, RecorderDelegateProtocol {
    
    private var audioRecorder: AVAudioRecorder?
    private var recordTimer: Timer?
    var lastRecordingOutput: URL? // To store the URL of the last successful recording
    
    private var recorderDelegate: RecorderDelegate?
    private var statusCallback: ((String) -> Void)?
    private var recordingStoppedCallback: (() -> Void)?  // VAD OPTIMIZATION: Callback for recording stop
    
    // State management
    private var isRecording: Bool = false
    private let stateLock = NSLock()
    
    // Cleanup flag to prevent multiple cleanup calls
    private var isCleaningUp: Bool = false
    
    
    // Gets the full output file path
    private func getOutputFilePath(customDirectory: String? = nil) -> URL {
        // Always generate a fresh path with timestamp to avoid conflicts
        let timestamp = Int(Date().timeIntervalSince1970)
        let fileName = "recording_\(timestamp).wav"
        
        if let customDir = customDirectory, !customDir.isEmpty {
            // Remove file:// prefix if present
            let cleanPath = customDir.replacingOccurrences(of: "file://", with: "")
            let customURL = URL(fileURLWithPath: cleanPath)
            
            // Create directory if it doesn't exist
            try? FileManager.default.createDirectory(at: customURL, withIntermediateDirectories: true, attributes: nil)
            
            return customURL.appendingPathComponent(fileName)
        } else {
            // Use default documents directory
            let documentsDirectory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            return documentsDirectory.appendingPathComponent(fileName)
        }
    }
    
    // Timer function to update recording meters
    @objc private func updateRecorderMeters() {
        stateLock.lock()
        defer { stateLock.unlock() }
        
        // Check if recorder is still valid and recording before updating meters
        guard let recorder = audioRecorder, recorder.isRecording, isRecording else {
            // If not recording, invalidate the timer
            print("[\(Date())] updateRecorderMeters: Recorder not valid or not recording. Invalidating timer.")
            cleanupTimer()
            return
        }
        
        recorder.updateMeters()
        let amplitude = recorder.averagePower(forChannel: 0) // Get amplitude for channel 0
        
        // Store amplitude callback to avoid capturing self strongly
        if let amplitudeCallback = self.amplitudeCallback {
            DispatchQueue.main.async {
                amplitudeCallback(amplitude)
            }
        }
    }
    
    // Store amplitude callback to avoid strong references in timer
    private var amplitudeCallback: ((Float) -> Void)?
    
    // Configurable amplitude update frequency (default: 60 FPS = 16.67ms)
    private var amplitudeUpdateInterval: TimeInterval = 1.0 / 60.0 // 60 Hz for smooth 60 FPS animations
    
    // Function to set amplitude update frequency from JavaScript
    func setAmplitudeUpdateFrequency(_ frequencyHz: Double) {
        stateLock.lock()
        defer { stateLock.unlock() }
        
        // Clamp frequency between 1 Hz and 120 Hz for reasonable performance
        let clampedFrequency = max(1.0, min(120.0, frequencyHz))
        amplitudeUpdateInterval = 1.0 / clampedFrequency
        
        print("[\(Date())] Amplitude frequency set to \(clampedFrequency) Hz (\(amplitudeUpdateInterval * 1000) ms interval)")
    }
    
    func startRecording(
        directoryPath: String? = nil,
        sendRecorderStatusEvent: @escaping (String) -> Void,
        sendAmplitudeEvent: @escaping (Float) -> Void
    ) -> String {
        stateLock.lock()
        defer { stateLock.unlock() }
        
        // Stop any existing recording first
        if isRecording {
            print("[\(Date())] startRecording: Recording already in progress, stopping previous recording")
            _ = stopRecordingInternal()
        }
        
        // Ensure clean state
        cleanupRecorderInternal()
        
        // Set callbacks after cleanup to prevent them from being cleared
        self.statusCallback = sendRecorderStatusEvent
        self.amplitudeCallback = sendAmplitudeEvent

        do {
            let outputURL = getOutputFilePath(customDirectory: directoryPath)

            // Remove existing file if necessary
            if FileManager.default.fileExists(atPath: outputURL.path) {
                try? FileManager.default.removeItem(at: outputURL)
            }

            // WAV (Linear PCM) settings - proven GPT compatibility with optimized size
            let settings: [String: Any] = [
                        AVFormatIDKey: Int(kAudioFormatLinearPCM),
                        AVSampleRateKey: 16000.0,          // 16kHz - AI standard
                        AVNumberOfChannelsKey: 1,          // Mono
                        AVLinearPCMBitDepthKey: 16,        // 16-bit signed integer
                        AVLinearPCMIsBigEndianKey: false,  // Little-endian
                        AVLinearPCMIsFloatKey: false,      // Integer, not float
                        AVLinearPCMIsNonInterleaved: false // Interleaved
            ]

            // Init AVAudioRecorder
            guard let recorder = try? AVAudioRecorder(url: outputURL, settings: settings) else {
                DispatchQueue.main.async {
                    sendRecorderStatusEvent("error")
                }
                return "Failed to create recorder."
            }

            recorder.isMeteringEnabled = true
            let delegate = RecorderDelegate(delegate: self)
            recorder.delegate = delegate
            self.recorderDelegate = delegate
            self.audioRecorder = recorder

            guard recorder.record() else {
                DispatchQueue.main.async {
                    sendRecorderStatusEvent("error")
                }
                cleanupRecorderInternal()
                return "Failed to start recording."
            }

            // Update state
            isRecording = true
            self.lastRecordingOutput = outputURL

            // Success: Setup metering timer with weak self to prevent retain cycles
            DispatchQueue.main.async { [weak self] in
                sendRecorderStatusEvent("recording")
                
                guard let self = self else { return }
                let timer = Timer(timeInterval: self.amplitudeUpdateInterval, repeats: true) { [weak self] _ in
                    self?.updateRecorderMeters()
                }
                RunLoop.main.add(timer, forMode: .common)
                self.recordTimer = timer
            }

            return outputURL.absoluteString
        } catch {
            DispatchQueue.main.async {
                sendRecorderStatusEvent("error")
            }
            return "RECORDING_SETUP_ERROR: \(error.localizedDescription)"
        }
    }

    
    func stopRecording() -> String {
        stateLock.lock()
        defer { stateLock.unlock() }
        
        return stopRecordingInternal()
    }
    
    func pauseRecording() -> String {
        stateLock.lock()
        defer { stateLock.unlock() }
        
        guard let recorder = self.audioRecorder, recorder.isRecording else {
            return "NoRecorderException"
        }
        
        // Pause recording first
        recorder.pause()
        
        // Clean up timer while paused - do this asynchronously to avoid blocking
        DispatchQueue.main.async { [weak self] in
            self?.cleanupTimer()
            self?.statusCallback?("paused")
        }
        
        return "paused"
    }
    
    func resumeRecording() -> String {
        stateLock.lock()
        defer { stateLock.unlock() }
        
        guard let recorder = self.audioRecorder else {
            return "NoRecorderException"
        }
        
        guard recorder.record() else {
            return "Failed to resume recording"
        }
        
        // Restart metering timer asynchronously to avoid blocking
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            // Ensure any existing timer is cleaned up first
            self.cleanupTimer()
            
            // Create new timer with a slight delay to ensure clean state
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
                guard let self = self else { return }
                
                let timer = Timer(timeInterval: 0.1, repeats: true) { [weak self] _ in
                    self?.updateRecorderMeters()
                }
                RunLoop.main.add(timer, forMode: .common)
                self.recordTimer = timer
                
                self.statusCallback?("recording")
            }
        }
        
        return "resumed"
    }
    
    // Internal stop recording method (assumes lock is already held)
    private func stopRecordingInternal() -> String {
        // Check if a recorder exists and is currently recording
        guard let recorder = self.audioRecorder else {
            return "NoRecorderException"
        }
        
        let recordingURL = recorder.url
        
        // Stop the recording and let the delegate handle the completion
        if recorder.isRecording {
            recorder.stop() // This will trigger audioRecorderDidFinishRecording
            
            // PERFORMANCE FIX: Use async delay instead of blocking main thread
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                // File finalization happens automatically via delegate
                print("[\(Date())] Recording file finalization delay completed")
            }
        }
        
        // Update state immediately
        isRecording = false
        self.lastRecordingOutput = recordingURL
        
        // VAD OPTIMIZATION: Notify that recording stopped
        recordingStoppedCallback?()
        
        // Clean up timer and recorder
        cleanupTimer()
        cleanupRecorderInternal()
        
        return recordingURL.absoluteString
    }
    
    func getRecordedFilePath() -> String? {
        return lastRecordingOutput?.absoluteString
    }
    
    func getFileCacheLocation() -> URL {
        return FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }
    
    // Provide access to recorder for direct status checks
    func getRecorder() -> AVAudioRecorder? {
        stateLock.lock()
        defer { stateLock.unlock() }
        return audioRecorder
    }
    
    // Check if currently recording
    func isCurrentlyRecording() -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return isRecording && audioRecorder?.isRecording == true
    }
    
    // Check if currently paused
    func isPaused() -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return audioRecorder != nil && !audioRecorder!.isRecording && isRecording
    }
    
    // VAD OPTIMIZATION: Set callback for when recording stops
    func setRecordingStoppedCallback(_ callback: @escaping () -> Void) {
        stateLock.lock()
        defer { stateLock.unlock() }
        recordingStoppedCallback = callback
    }
    
    // MARK: - Private Helper Methods
    
    private func cleanupTimer() {
        // CRITICAL FIX: Use async dispatch to prevent deadlock
        // Never use sync dispatch while holding stateLock
        if Thread.isMainThread {
            if let timer = self.recordTimer {
                print("[\(Date())] cleanupTimer: Invalidating timer.")
                timer.invalidate()
                self.recordTimer = nil
            }
        } else {
            // Use async to prevent deadlock - timer cleanup can happen asynchronously
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                if let timer = self.recordTimer {
                    print("[\(Date())] cleanupTimer: Invalidating timer.")
                    timer.invalidate()
                    self.recordTimer = nil
                }
            }
        }
    }
    
    private func cleanupRecorderInternal() {
        guard !isCleaningUp else { return }
        isCleaningUp = true
        
        self.audioRecorder = nil
        self.recorderDelegate = nil
        self.amplitudeCallback = nil
        
        isCleaningUp = false
    }
    
    // Public cleanup method
    func cleanup() {
        stateLock.lock()
        defer { stateLock.unlock() }
        
        if isRecording {
            _ = stopRecordingInternal()
        }
        
        cleanupTimer()
        cleanupRecorderInternal()
        
        isRecording = false
        statusCallback = nil
    }
    
    // MARK: - RecorderDelegateProtocol
    
    func recorderDidFinishRecording(successfully: Bool) {
        // CRITICAL FIX: Minimize lock scope to prevent deadlock
        var callback: ((String) -> Void)?
        
        stateLock.lock()
        // Update internal state
        isRecording = false
        callback = statusCallback
        stateLock.unlock()
        
        // Clean up timer outside of lock to prevent deadlock
        cleanupTimer()
        
        // Notify status changes through callback
        DispatchQueue.main.async {
            callback?(successfully ? "stopped" : "failed")
        }
    }
    
    func recorderEncodeErrorDidOccur(error: Error?) {
        // CRITICAL FIX: Minimize lock scope to prevent deadlock
        var callback: ((String) -> Void)?
        
        stateLock.lock()
        // Update internal state
        isRecording = false
        callback = statusCallback
        stateLock.unlock()
        
        // Clean up timer and recorder outside of lock
        cleanupTimer()
        cleanupRecorderInternal()
        
        // Notify error status
        DispatchQueue.main.async {
            callback?("error")
        }
        
        print("[\(Date())] Recorder encode error: \(error?.localizedDescription ?? "unknown error")")
    }
}
