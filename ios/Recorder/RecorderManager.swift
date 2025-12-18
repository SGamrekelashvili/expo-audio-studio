import Foundation
import AVFoundation

class RecorderManager: NSObject, RecorderDelegateProtocol {
    
    private let stateLock = NSRecursiveLock()
    
    private var audioRecorder: AVAudioRecorder?
    private weak var recordTimer: Timer? 
    var lastRecordingOutput: URL?
    
    private var recorderDelegate: RecorderDelegate?
    private var statusCallback: ((String, String?, String?) -> Void)?
    private var recordingStoppedCallback: (() -> Void)?
    
    private var isRecording: Bool = false
    private var isCleaningUp: Bool = false
    
    // Audio chunks
    private var enableListenToChunks: Bool = false
    private var audioChunkCapture: AudioChunkCapture?
    private var chunkCallback: (([String: Any]) -> Void)?
    
    
    private func getOutputFilePath(customDirectory: String? = nil) -> URL {
        // Always generate a fresh path with timestamp to avoid conflicts
        let timestamp = Int(Date().timeIntervalSince1970)
        let fileName = "recording_\(timestamp).wav"
        
        if let customDir = customDirectory, !customDir.isEmpty {
            let cleanPath = customDir.replacingOccurrences(of: "file://", with: "")
            let customURL = URL(fileURLWithPath: cleanPath)
            
            try? FileManager.default.createDirectory(at: customURL, withIntermediateDirectories: true, attributes: nil)
            
            return customURL.appendingPathComponent(fileName)
        } else {
            let documentsDirectory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            return documentsDirectory.appendingPathComponent(fileName)
        }
    }
    
    @objc private func updateRecorderMeters() {
        stateLock.lock()
        defer { stateLock.unlock() }
        
        let callback = statusCallback
        
        guard let recorder = audioRecorder else {
            print("[\(Date())] updateRecorderMeters: Recorder is nil, cleaning up timer.")
            cleanupTimer()
            return
        }
        
        // Check if recording is paused (isRecording=true but recorder.isRecording=false)
        if !recorder.isRecording && isRecording {
            print("[\(Date())] updateRecorderMeters: Recording is paused, skipping meter update.")
            return
        }
        
        // Check if recording was stopped intentionally
        if !isRecording {
            print("[\(Date())] updateRecorderMeters: Recording stopped, cleaning up timer.")
            cleanupTimer()
            return
        }
        
        // At this point: isRecording=true AND recorder.isRecording should be true
        guard recorder.isRecording else {
            print("[\(Date())] updateRecorderMeters: Unexpected recorder state - isRecording=true but recorder.isRecording=false. Sending error.")
            isRecording = false
            cleanupTimer()
            DispatchQueue.main.async {
                callback?("error", "RECORDER_STATE_ERROR", "Unexpected recorder state during meter update")
            }
            return
        }
        
        recorder.updateMeters()
        let amplitude = recorder.averagePower(forChannel: 0)
        
        // Send amplitude update
        if let amplitudeCallback = self.amplitudeCallback {
            DispatchQueue.main.async {
                amplitudeCallback(amplitude)
            }
        }
    }
    
    private var amplitudeCallback: ((Float) -> Void)?
    private var amplitudeUpdateInterval: TimeInterval = 1.0 / 60.0
    
    func setListenToChunks(_ enable: Bool) -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        enableListenToChunks = enable
        return enable
    }
    
    private func startChunkCapture() {
        stateLock.lock()
        let shouldCapture = enableListenToChunks
        stateLock.unlock()
        
        guard shouldCapture else { return }
        
        SharedAudioEngineManager.shared.enableChunkCapture(
            callback: { [weak self] chunkData in
                guard let self = self else { return }
                self.stateLock.lock()
                let callback = self.chunkCallback
                self.stateLock.unlock()
                callback?(chunkData as? [String: Any] ?? [:])
            }
        )
    }
    
    private func stopChunkCapture() {
        SharedAudioEngineManager.shared.disableChunkCapture()
    }
    
    func setAmplitudeUpdateFrequency(_ frequencyHz: Double) {
        stateLock.lock()
        defer { stateLock.unlock() }
        
        let clampedFrequency = max(1.0, min(120.0, frequencyHz))
        amplitudeUpdateInterval = 1.0 / clampedFrequency
        
        print("[\(Date())] Amplitude frequency set to \(clampedFrequency) Hz (\(amplitudeUpdateInterval * 1000) ms interval)")
    }
    
    func startRecording(
        directoryPath: String? = nil,
        sendRecorderStatusEvent: @escaping (String, String?, String?) -> Void,
        sendAmplitudeEvent: @escaping (Float) -> Void,
        sendChunkEvent: @escaping ([String: Any]) -> Void
    ) -> String {
        stateLock.lock()
        defer { stateLock.unlock() }
        
        if isRecording {
            print("[\(Date())] startRecording: Recording already in progress, stopping previous recording")
            _ = stopRecordingInternal()
        }
        
        cleanupRecorderInternal()
        
        self.statusCallback = sendRecorderStatusEvent
        self.amplitudeCallback = sendAmplitudeEvent
        self.chunkCallback = sendChunkEvent

        do {
            let outputURL = getOutputFilePath(customDirectory: directoryPath)

            if FileManager.default.fileExists(atPath: outputURL.path) {
                try? FileManager.default.removeItem(at: outputURL)
            }

            let settings: [String: Any] = [
                        AVFormatIDKey: Int(kAudioFormatLinearPCM),
                        AVSampleRateKey: 16000.0,
                        AVNumberOfChannelsKey: 1,
                        AVLinearPCMBitDepthKey: 16,
                        AVLinearPCMIsBigEndianKey: false,
                        AVLinearPCMIsFloatKey: false,
                        AVLinearPCMIsNonInterleaved: false
            ]

            guard let recorder = try? AVAudioRecorder(url: outputURL, settings: settings) else{
                DispatchQueue.main.async {
                    sendRecorderStatusEvent("error", "RECORDER_CREATE_FAILED", "Failed to create AVAudioRecorder")
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
                    sendRecorderStatusEvent("error", "RECORDER_START_FAILED", "recorder.record() returned false")
                }
                cleanupRecorderInternal()
                return "Failed to start recording."
            }

            isRecording = true
            self.lastRecordingOutput = outputURL
            let shouldCapture = enableListenToChunks
            
            // Start audio engine for chunk capture if enabled
            if shouldCapture {
                startChunkCapture()
            }

            DispatchQueue.main.async { [weak self] in
                sendRecorderStatusEvent("recording", nil, nil)
                
                guard let self = self else { return }
                
                self.stateLock.lock()
                // Make sure any old timer is invalidated first
                self.recordTimer?.invalidate()
                self.recordTimer = nil
                
                let interval = self.amplitudeUpdateInterval
                self.stateLock.unlock()
                
                let timer = Timer(timeInterval: interval, repeats: true) { [weak self] _ in
                    guard let self = self else {
                        return
                    }
                    self.updateRecorderMeters()
                }
                RunLoop.main.add(timer, forMode: .common)
                
                self.stateLock.lock()
                self.recordTimer = timer
                self.stateLock.unlock()
                
                print("[\(Date())] Amplitude timer started with interval: \(interval)")
            }

            return outputURL.absoluteString
        } catch {
            DispatchQueue.main.async {
                sendRecorderStatusEvent("error", "RECORDER_SETUP_ERROR", "Recording setup failed: \(error.localizedDescription)")
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
        
        cleanupTimer()
        
        recorder.pause()
        
        stopChunkCapture()
        
        DispatchQueue.main.async { [weak self] in
            self?.statusCallback?("paused", nil, nil)
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
        
        if enableListenToChunks {
            startChunkCapture()
        }
        
        let interval = self.amplitudeUpdateInterval
        
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            self.stateLock.lock()
            self.recordTimer?.invalidate()
            self.recordTimer = nil
            self.stateLock.unlock()
            
            let timer = Timer(timeInterval: interval, repeats: true) { [weak self] _ in
                guard let self = self else {
                    return
                }
                self.updateRecorderMeters()
            }
            RunLoop.main.add(timer, forMode: .common)
            
            self.stateLock.lock()
            self.recordTimer = timer
            self.stateLock.unlock()
            
            self.statusCallback?("recording", nil, nil)
            print("[\(Date())] Amplitude timer restarted after resume")
        }
        
        return "resumed"
    }
    
    private func stopRecordingInternal() -> String {
        guard let recorder = self.audioRecorder else {
            return "NoRecorderException"
        }
        
        let recordingURL = recorder.url
        
        isRecording = false
        
        cleanupTimer()
        
        if recorder.isRecording {
            recorder.stop() 
        }
        
        self.lastRecordingOutput = recordingURL
        
        stopChunkCapture()
        
        recordingStoppedCallback?()
        
        cleanupRecorderInternal()
        
        return recordingURL.absoluteString
    }
    
    func getRecordedFilePath() -> String? {
        return lastRecordingOutput?.absoluteString
    }
    
    func getFileCacheLocation() -> URL {
        return FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }
    
    func getRecorder() -> AVAudioRecorder? {
        stateLock.lock()
        defer { stateLock.unlock() }
        return audioRecorder
    }
    
    func isCurrentlyRecording() -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return isRecording && audioRecorder?.isRecording == true
    }
    
    func isPaused() -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        guard let recorder = audioRecorder else { return false }
        return !recorder.isRecording && isRecording
    }
    
    func setRecordingStoppedCallback(_ callback: @escaping () -> Void) {
        stateLock.lock()
        defer { stateLock.unlock() }
        recordingStoppedCallback = callback
    }
    
    
    private func cleanupTimer() {
        // Timer must be invalidated on main thread where it was created
        // Use async to avoid deadlock when called from background thread while holding stateLock
        // The improved updateRecorderMeters() now handles all states correctly, so even if
        // the timer fires once more before cleanup, it won't send false errors
        if Thread.isMainThread {
            stateLock.lock()
            recordTimer?.invalidate()
            recordTimer = nil
            stateLock.unlock()
        } else {
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.stateLock.lock()
                self.recordTimer?.invalidate()
                self.recordTimer = nil
                self.stateLock.unlock()
            }
        }
    }
    
    private func cleanupRecorderInternal() {
        guard !isCleaningUp else { return }
        isCleaningUp = true
        
        audioChunkCapture = nil
        self.audioRecorder = nil
        self.recorderDelegate = nil
        self.amplitudeCallback = nil
        self.chunkCallback = nil
        self.statusCallback = nil
        
        isCleaningUp = false
    }
    
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
        var callback: ((String, String?, String?) -> Void)?
        
        stateLock.lock()
        // Update internal state
        isRecording = false
        callback = statusCallback
        stateLock.unlock()
        
        stopChunkCapture()
        cleanupTimer()
        
        DispatchQueue.main.async {
            callback?(successfully ? "stopped" : "failed", nil, nil)
        }
    }
    
    func recorderEncodeErrorDidOccur(error: Error?) {
        var callback: ((String, String?, String?) -> Void)?
        let errorMessage = error?.localizedDescription ?? "Unknown encode error"
        
        stateLock.lock()

        isRecording = false
        callback = statusCallback
        stateLock.unlock()
        
        cleanupTimer()
        cleanupRecorderInternal()
        
        DispatchQueue.main.async {
            callback?("error", "RECORDER_ENCODE_ERROR", errorMessage)
        }
        
        print("[\(Date())] Recorder encode error: \(errorMessage)")
    }
}
