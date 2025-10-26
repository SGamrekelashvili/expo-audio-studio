import ExpoModulesCore
import AVFoundation

// Enum for managing audio session setup types (activate or deactivate)
enum SessionSetupType {
    case stop
    case play
}

// Enum for standardized error messages
enum AudioError: String, Error {
    case noPlayer = "NoPlayerException"
    case playbackFailed = "PlaybackFailedException: Unknown error"
    case invalidSpeedFormat = "SetSpeedException: Invalid speed format"
    case speedOutOfRange = "SetSpeedException: Speed out of range (0.5-2.0)"
    case recordingFailed = "RecordingFailedException: Unknown error"
    case audioSessionConfigurationFailed = "AudioSessionConfigurationFailedException: Failed to configure audio session"
    case audioSessionActivationFailed = "AudioSessionActivationFailedException: Failed to activate audio session"
    case audioSessionDeactivationFailed = "AudioSessionDeactivationFailedException: Failed to deactivate audio session"
    case fileNotFound = "FileNotFoundException: Audio file not found"
    case invalidURI = "InvalidURIException: Provided URI is not valid"
}

// Custom errors for audio session operations
enum AudioSessionError: Error, LocalizedError {
    case activationFailed(Error)
    case categorySetupFailed(Error)
    case deactivationFailed(Error)

    var errorDescription: String? {
        switch self {
        case .activationFailed(let error):
            return "Failed to activate audio session: \(error.localizedDescription)"
        case .categorySetupFailed(let error):
            return "Failed to set audio session category: \(error.localizedDescription)"
        case .deactivationFailed(let error):
            return "Failed to deactivate audio session: \(error.localizedDescription)"
        }
    }
}

public class ExpoAudioStudioModule: Module {
    // Audio manager instances (assuming these classes are defined elsewhere)
    private let audioManager = AudioManager()
    private let recorderManager = RecorderManager()
    
    // MEMORY FIX: Sound classification manager for voice activity detection - lazy loaded
    @available(iOS 13.0, *)
    private var soundClassificationManager: EnhancedSoundClassificationManager?
    
    // VAD OPTIMIZATION: Track if VAD is enabled from JavaScript
    private var isVADEnabledFromJS: Bool = false
    
    // Flags to remember if we were playing/recording before an interruption
    private var wasPlayingBeforeInterruption: Bool = false
    private var wasRecordingBeforeInterruption: Bool = false
    
    
    
    
    // MEMORY FIX: Lazy getter for sound classification manager
    @available(iOS 13.0, *)
    private func getSoundClassificationManager() -> EnhancedSoundClassificationManager {
        if soundClassificationManager == nil {
            soundClassificationManager = EnhancedSoundClassificationManager()
        }
        return soundClassificationManager!
    }
    
    // VAD OPTIMIZATION: Helper to check if VAD should be active
    private func shouldVADBeActive() -> Bool {
        let isRecording = recorderManager.getRecorder()?.isRecording ?? false
        return isRecording && isVADEnabledFromJS
    }

    // Helper function to get directory for storing audio files (delegated to RecorderManager)
    private func getFileCacheLocation() -> URL {
        return recorderManager.getFileCacheLocation()
    }

    // Helper function to send player status events to JavaScript
    func sendPlayerStatusEvent(isPlaying: Bool, didJustFinish: Bool) {
        print("[\(Date())] sendPlayerStatusEvent: isPlaying=\(isPlaying), didJustFinish=\(didJustFinish)")
        sendEvent("onPlayerStatusChange", [
            "isPlaying": isPlaying,
            "didJustFinish": didJustFinish
        ])
    }

    // Helper function to send recorder status events to JavaScript
    func sendRecorderStatusEvent(status: String) {
        print("[\(Date())] sendRecorderStatusEvent: status=\(status)")
        sendEvent("onRecorderStatusChange", [
            "status": status // e.g., "recording", "stopped", "failed", "error"
        ])
    }

    // Helper function to send amplitude events to JavaScript (logging is commented to avoid verbosity)
    private func sendAmplitudeEvent(amplitude: Float) {
        // print("[\(Date())] sendAmplitudeEvent: amplitude=\(amplitude)") // Avoid excessive logging
        sendEvent("onRecorderAmplitude", [
            "amplitude": amplitude
        ])
    }
    
    // Helper function to send voice activity events to JavaScript
    private func sendVoiceActivityEvent(isVoiceDetected: Bool, confidence: Float) {
        print("[\(Date())] sendVoiceActivityEvent: isVoiceDetected=\(isVoiceDetected), confidence=\(confidence)")
        sendEvent("onVoiceActivityDetected", [
            "isVoiceDetected": isVoiceDetected,
            "confidence": confidence,
            "timestamp": Date().timeIntervalSince1970 * 1000 // Convert to milliseconds
        ])
    }

    // MARK: - Setup and Teardown Observers

    /**
     Sets up notification observers for audio session interruptions and route changes.
     */
    private func setupNotificationObservers() {
        print("[\(Date())] Setting up notification observers...")
        
        // Audio Session Interruption Observer
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAudioSessionInterruption),
            name: AVAudioSession.interruptionNotification,
            object: nil
        )
        
        // Audio Route Change Observer
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleRouteChange),
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )
        
        print("[\(Date())] Notification observers setup completed")
    }
    
    /**
     Removes all previously set notification observers.
     */
    private func removeNotificationObservers() {
        print("[\(Date())] Removing notification observers...")
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.interruptionNotification, object: nil)
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.routeChangeNotification, object: nil)
        print("[\(Date())] Notification observers removed")
    }

    /**
     Defines the module's properties, functions, and lifecycle hooks for Expo.
     */
    public func definition() -> ModuleDefinition {
        Name("ExpoAudioStudio")
        
        // Called when the module is created
        OnCreate {
            print("[\(Date())] ExpoAudioStudioModule: OnCreate called")
            setupNotificationObservers() // Setup notification observers
            printCurrentAudioRoute() // Print initial audio route info
            
            // VAD OPTIMIZATION: Set up callback to auto-stop VAD when recording stops
            recorderManager.setRecordingStoppedCallback { [weak self] in
                if #available(iOS 13.0, *) {
                    if let strongSelf = self, strongSelf.isVADEnabledFromJS {
                        print("[\(Date())] Auto-stopping VAD due to recording stop")
                        _ = strongSelf.soundClassificationManager?.stopVoiceActivityDetection()
                    }
                }
            }
        }
        
        // Called when the module is destroyed (e.g., app closes)
        OnDestroy {
            print("[\(Date())] ExpoAudioStudioModule: OnDestroy called, cleaning up resources.")
            removeNotificationObservers() // Remove notification observers first
            
            // Stop and release any active audio players or recorders
            _ = audioManager.stopPlayingAudio()
            _ = recorderManager.stopRecording()
            
            if #available(iOS 13.0, *) {
                  if let manager = self.soundClassificationManager, manager.isVoiceActivityDetectionActive() {
                      _ = manager.stopVoiceActivityDetection()
                  }
                  self.soundClassificationManager = nil // MEMORY FIX: Release manager
                  self.isVADEnabledFromJS = false // VAD OPTIMIZATION: Clear JS enable state
              }
            
            print("[\(Date())] Cleanup complete.")
        }
        
        // Register event types that can be sent to JavaScript
        Events(
            "onPlayerStatusChange",
            "onRecorderStatusChange",
            "onRecorderAmplitude",
            "onVoiceActivityDetected"
        )
        
        // MARK: - Recording Functions
        
        // Function to get the path of the last recorded file
        Function("lastRecording") { () -> String? in
            return recorderManager.getRecordedFilePath()
        }
        
        // MARK: - File Management Functions
        
        // Function to list all recordings in a directory
        Function("listRecordings") { (directoryPath: String?) -> [[String: Any]] in
            let directory: URL
            
            if let customDir = directoryPath, !customDir.isEmpty {
                // Remove file:// prefix if present
                let cleanPath = customDir.replacingOccurrences(of: "file://", with: "")
                directory = URL(fileURLWithPath: cleanPath)
            } else {
                // Use default documents directory
                directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            }
            
            guard FileManager.default.fileExists(atPath: directory.path) else {
                return []
            }
            
            do {
                let fileURLs = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey], options: .skipsHiddenFiles)
                
                let audioExtensions = ["wav", "mp3", "m4a", "aac"]
                let audioFiles = fileURLs.filter { url in
                    audioExtensions.contains(url.pathExtension.lowercased())
                }
                
                return audioFiles.compactMap { url in
                    do {
                        let resourceValues = try url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
                        let duration = self.getAudioDuration(url: url)
                        
                        return [
                            "path": url.absoluteString,
                            "name": url.lastPathComponent,
                            "size": resourceValues.fileSize ?? 0,
                            "lastModified": Int64((resourceValues.contentModificationDate?.timeIntervalSince1970 ?? 0) * 1000),
                            "duration": duration
                        ]
                    } catch {
                        print("Error getting file attributes for \(url.path): \(error)")
                        return nil
                    }
                }
            } catch {
                print("Error listing directory contents: \(error)")
                return []
            }
        }
        
        // Function to join multiple audio files using AVMutableComposition (PROFESSIONAL APPROACH)
        Function("joinAudioFiles") { (filePaths: [String], outputPath: String) -> String in
            print("[\(Date())] Starting PROFESSIONAL audio joining with \(filePaths.count) files")
            
            guard filePaths.count >= 2 else {
                return "Error: At least 2 audio files are required for joining"
            }
            
            // Validate input files exist
            var inputURLs: [URL] = []
            for (index, path) in filePaths.enumerated() {
                let cleanPath = path.replacingOccurrences(of: "file://", with: "")
                let url = URL(fileURLWithPath: cleanPath)
                
                print("[\(Date())] Checking file \(index): \(cleanPath)")
                
                guard FileManager.default.fileExists(atPath: url.path) else {
                    return "Error: Input file not found: \(cleanPath)"
                }
                
                inputURLs.append(url)
            }
            
            // Prepare output file
            let cleanOutputPath = outputPath.replacingOccurrences(of: "file://", with: "")
            let outputURL = URL(fileURLWithPath: cleanOutputPath)
            
            // Create output directory if needed
            try? FileManager.default.createDirectory(at: outputURL.deletingLastPathComponent(), withIntermediateDirectories: true, attributes: nil)
            
            // Delete existing output file if it exists
            if FileManager.default.fileExists(atPath: outputURL.path) {
                try? FileManager.default.removeItem(at: outputURL)
                print("[\(Date())] Deleted existing output file")
            }
            
            // Use AVMutableComposition for professional audio joining
            let composition = AVMutableComposition()
            guard let compositionAudioTrack = composition.addMutableTrack(
                withMediaType: .audio,
                preferredTrackID: kCMPersistentTrackID_Invalid
            ) else {
                return "Error: Could not create composition track"
            }
            
            print("[\(Date())] Created AVMutableComposition")
            
            // Add each audio file to the composition
            for (index, inputURL) in inputURLs.enumerated() {
                print("[\(Date())] Adding file \(index): \(inputURL.lastPathComponent)")
                
                let asset = AVURLAsset(url: inputURL)
                guard let assetTrack = asset.tracks(withMediaType: .audio).first else {
                    print("[\(Date())] Warning: No audio track found in file \(index)")
                    continue
                }
                
                let timeRange = CMTimeRange(start: .zero, duration: asset.duration)
                let insertTime = compositionAudioTrack.timeRange.end
                
                do {
                    try compositionAudioTrack.insertTimeRange(timeRange, of: assetTrack, at: insertTime)
                    let durationSeconds = CMTimeGetSeconds(asset.duration)
                    print("[\(Date())] Added \(durationSeconds) seconds from file \(index)")
                } catch {
                    print("[\(Date())] Error adding file \(index): \(error.localizedDescription)")
                    return "Error: Failed to add file \(index) to composition"
                }
            }
            
            let totalDuration = CMTimeGetSeconds(composition.duration)
            print("[\(Date())] Total composition duration: \(totalDuration) seconds")
            
            // Export the composition to a file
            guard let exportSession = AVAssetExportSession(
                asset: composition,
                presetName: AVAssetExportPresetPassthrough
            ) else {
                return "Error: Could not create export session"
            }
            
            exportSession.outputFileType = .wav
            exportSession.outputURL = outputURL
            
            print("[\(Date())] Starting export session...")
            
            // Use a semaphore to wait for async export
            let semaphore = DispatchSemaphore(value: 0)
            var exportResult: String = ""
            
            exportSession.exportAsynchronously {
                switch exportSession.status {
                case .completed:
                    print("[\(Date())] Export completed successfully!")
                    
                    // Verify the output file
                    if FileManager.default.fileExists(atPath: outputURL.path) {
                        do {
                            let fileSize = try FileManager.default.attributesOfItem(atPath: outputURL.path)[.size] as? Int64 ?? 0
                            print("[\(Date())] Output file size: \(fileSize) bytes")
                            exportResult = outputURL.absoluteString
                        } catch {
                            print("[\(Date())] Error checking output file: \(error.localizedDescription)")
                            exportResult = "Error: Could not verify output file"
                        }
                    } else {
                        exportResult = "Error: Output file was not created"
                    }
                    
                case .failed:
                    let errorMsg = exportSession.error?.localizedDescription ?? "Unknown export error"
                    print("[\(Date())] Export failed: \(errorMsg)")
                    exportResult = "Error: Export failed - \(errorMsg)"
                    
                case .cancelled:
                    print("[\(Date())] Export cancelled")
                    exportResult = "Error: Export was cancelled"
                    
                default:
                    print("[\(Date())] Export status: \(exportSession.status.rawValue)")
                    exportResult = "Error: Unexpected export status"
                }
                
                semaphore.signal()
            }
            
            // Wait for export to complete (with timeout)
            let timeoutResult = semaphore.wait(timeout: .now() + 30.0) // 30 second timeout
            
            if timeoutResult == .timedOut {
                exportSession.cancelExport()
                return "Error: Export timed out after 30 seconds"
            }
            
            return exportResult
        }
        
        // Function to set amplitude update frequency (for 60 FPS animations)
        Function("setAmplitudeUpdateFrequency") { (frequencyHz: Double) -> String in
            print("[\(Date())] setAmplitudeUpdateFrequency called with \(frequencyHz) Hz")
            
            self.recorderManager.setAmplitudeUpdateFrequency(frequencyHz)
            
            return "Amplitude frequency set to \(frequencyHz) Hz"
        }
        
        // Function to start audio recording
        Function("startRecording") { (directoryPath: String?) -> String in
            print("[\(Date())] startRecording function called with directory: \(directoryPath ?? "default")")
            let _ = self.audioManager.stopPlayingAudio()
            // Audio session configuration now handled from JavaScript side
            
            // Stop any playing audio first to prevent conflicts
            
            // Start recording using the RecorderManager with custom directory
            let result = self.recorderManager.startRecording(
                directoryPath: directoryPath,
                sendRecorderStatusEvent: { status in
                    self.sendRecorderStatusEvent(status: status)
                    
                    // Auto-start VAD when recording starts if enabled
                    if status == "recording" && self.isVADEnabledFromJS {
                        if #available(iOS 13.0, *) {
                            print("[\(Date())] Auto-starting VAD because recording started and VAD is enabled")
                            let vadResult = self.getSoundClassificationManager().startVoiceActivityDetection { [weak self] isVoiceDetected, confidence in
                                self?.sendVoiceActivityEvent(isVoiceDetected: isVoiceDetected, confidence: confidence)
                            }
                            print("[\(Date())] VAD auto-start result: \(vadResult)")
                        }
                    }
                },
                sendAmplitudeEvent: { amplitude in
                    self.sendAmplitudeEvent(amplitude: amplitude)
                }
            )
            
            // Return potential error from startRecording, or success message
            return result
        }
        
        // Function to stop audio recording
        Function("stopRecording") { () -> String in
            print("[\(Date())] stopRecording function called")
            
            // Stop VAD when recording stops (if it was running)
            if #available(iOS 13.0, *) {
                let vadActive = self.soundClassificationManager?.isVoiceActivityDetectionActive() ?? false
                if vadActive {
                    print("[\(Date())] Auto-stopping VAD because recording stopped")
                    let vadResult = self.soundClassificationManager?.stopVoiceActivityDetection() ?? "NotActive"
                    print("[\(Date())] VAD auto-stop result: \(vadResult)")
                }
            }
            
            let result = self.recorderManager.stopRecording()
            return result
        }
        
        // Function to pause audio recording
        Function("pauseRecording") { () -> String in
            print("[\(Date())] pauseRecording function called.")
            return self.recorderManager.pauseRecording()
        }
        
        // Function to resume audio recording
        Function("resumeRecording") { () -> String in
            print("[\(Date())] resumeRecording function called.")
            return self.recorderManager.resumeRecording()
        }
        
        // MARK: - Playback Functions
        
        // Function to prepare audio player without starting playback
        Function("preparePlayer") { (path: String) -> String in
            print("[\(Date())] preparePlayer function called with path: \(path)")
            
            // Use AudioManager to prepare player
            if let result = self.audioManager.preparePlayer(path: path, sendPlayerStatusEvent: { isPlaying, didJustFinish in
                self.sendPlayerStatusEvent(isPlaying: isPlaying, didJustFinish: didJustFinish)
            }) {
                return result
            } else {
                return AudioError.playbackFailed.rawValue
            }
        }
        
        // Function to start playing audio from a given path (URI)
        Function("startPlaying") { (path: String) -> String in
            print("[\(Date())] startPlaying function called with path: \(path)")
            // Audio session configuration now handled from JavaScript side
            
            // Use AudioManager to start playback
            if let result = self.audioManager.startPlayingAudio(path: path, sendPlayerStatusEvent: { isPlaying, didJustFinish in
                // Audio session deactivation now handled from JavaScript side
                self.sendPlayerStatusEvent(isPlaying: isPlaying, didJustFinish: didJustFinish)
            }) {
                return result
            } else {
                return AudioError.playbackFailed.rawValue
            }
        }
        
        // Function to stop the current audio playback
        Function("stopPlayer") { () -> String in
            print("[\(Date())] stopPlayer function called.")
            
            let result = self.audioManager.stopPlayingAudio()
            DispatchQueue.main.async {
                self.sendPlayerStatusEvent(isPlaying: false, didJustFinish: false)
            }
            return result ? "stopped" : "NoPlayerException"
        }
        
        // Function to set the playback speed
        Function("setPlaybackSpeed") { (speedString: String) -> String in
            guard let speedFloat = Float(speedString) else {
                return AudioError.invalidSpeedFormat.rawValue
            }
            
            // Validate speed range (0.5 to 2.0)
            if speedFloat >= 0.5 && speedFloat <= 2.0 {
                _ = audioManager.setPlaybackSpeed(speed: speedFloat)
                return "Playback speed set to \(speedFloat)"
            } else {
                print("[\(Date())] setPlaybackSpeed: Speed out of range.")
                return AudioError.speedOutOfRange.rawValue
            }
        }
        
        // Function to seek to a specific position in the audio
        Function("seekTo") { (position: Double) -> String in
            return audioManager.seekToTime(position: position)
        }
        
        // Function to pause audio playback
        Function("pausePlayer") { () -> String in
            print("[\(Date())] pausePlayer function called.")
            
            let result = self.audioManager.pausePlayingAudio()
            if result != AudioError.noPlayer.rawValue {
                self.sendPlayerStatusEvent(isPlaying: false, didJustFinish: false)
                // Audio session management now handled from JavaScript side
            }
            return result
        }
        
        // Function to resume audio playback
        Function("resumePlayer") { () -> String in
            print("[\(Date())] resumePlayer function called.")
            
            // Audio session reactivation now handled from JavaScript side
            
            let result = self.audioManager.resumePlayingAudio()
            if result != AudioError.noPlayer.rawValue {
                // Send player status indicating it's playing again
                self.sendPlayerStatusEvent(isPlaying: true, didJustFinish: false)
            }
            return result
        }
        
        // MARK: - Audio Information Properties
        
        // Property to get the current playback position
        Property("currentPosition") {
            // Return current time if player exists, otherwise 0.0
            return audioManager.getPlayer()?.currentTime ?? 0.0
        }
        
        // MARK: - Permission Request Functions
        
        // Asynchronous function to request microphone permission
        AsyncFunction("requestMicrophonePermission") { (promise: Promise) in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                let status: String = granted ? "granted" : "denied"
                
                let permissionResponse: [String: Any] = [
                    "status": status,
                    "canAskAgain": true, // iOS always allows asking again via Settings
                    "granted": granted
                ]
                
                promise.resolve(permissionResponse)
            }
        }
        
        // Asynchronous function to get the current microphone permission status
        AsyncFunction("getMicrophonePermissionStatus") { (promise: Promise) in
            let status = AVAudioSession.sharedInstance().recordPermission
            var statusString = "undetermined"
            var isGranted = false
            
            switch status {
            case .granted:
                statusString = "granted"
                isGranted = true
            case .denied:
                statusString = "denied"
            case .undetermined:
                statusString = "undetermined"
            @unknown default:
                statusString = "undetermined" // Handle future unknown cases
            }
            
            let permissionResponse: [String: Any] = [
                "status": statusString,
                "canAskAgain": true, // iOS always allows changing in Settings
                "granted": isGranted
            ]
            
            promise.resolve(permissionResponse)
        }
        
        // Function to get the duration of an audio file from its URI
        Function("getDuration") { (uri: String) -> Double in
            print("[\(Date())] getDuration called for uri: \(uri)")
            guard let audioURL = URL(string: uri) else {
                print("[\(Date())] getDuration: Invalid URL for getDuration.")
                return 0.0
            }
            
            // Check if it's a file URL and if the file exists before attempting to get duration
            if audioURL.isFileURL && !FileManager.default.fileExists(atPath: audioURL.path) {
                print("[\(Date())] getDuration: File not found for getDuration at path: \(audioURL.path)")
                return 0.0
            }
            
            // Use AVURLAsset for efficient duration retrieval without loading the entire audio data
            let asset = AVURLAsset(url: audioURL)
            let duration = asset.duration
            let durationInSeconds = CMTimeGetSeconds(duration)
            
            if durationInSeconds.isNaN {
                print("[\(Date())] getDuration: Could not retrieve duration for \(uri).")
                return 0.0
            }
            print("[\(Date())] getDuration: Successfully got duration: \(durationInSeconds) seconds")
            return durationInSeconds
        }
        
        // Function to get audio amplitudes for visualization bars
        Function("getAudioAmplitudes") { (fileUrl: String, barsCount: Int) -> [String: Any] in
            print("[\(Date())] getAudioAmplitudes called for fileUrl: \(fileUrl), barsCount: \(barsCount)")
            
            guard let audioURL = URL(string: fileUrl) else {
                print("[\(Date())] getAudioAmplitudes: Invalid URL.")
                return [
                    "success": false,
                    "error": "Invalid file URL",
                    "amplitudes": [],
                    "duration": 0.0,
                    "sampleRate": 0.0
                ]
            }
            
            // MEMORY OPTIMIZED: Use autoreleasepool for large file processing
            let result = autoreleasepool { () -> AudioAmplitudeAnalyzer.AmplitudeResult in
                return AudioAmplitudeAnalyzer.getAudioAmplitudes(fileUrl: audioURL, barsCount: barsCount)
            }
            
            if result.success {
                print("[\(Date())] getAudioAmplitudes: Successfully analyzed audio - \(result.amplitudes.count) bars, duration: \(result.duration)s")
                
                // Force memory cleanup for large files
                if result.duration > 60.0 { // Files longer than 1 minute
                    AudioAmplitudeAnalyzer.forceMemoryCleanup()
                }
                
                return [
                    "success": true,
                    "amplitudes": Array(result.amplitudes),
                    "duration": result.duration,
                    "sampleRate": result.sampleRate,
                    "barsCount": result.amplitudes.count
                ]
            } else {
                print("[\(Date())] getAudioAmplitudes: Analysis failed - \(result.error ?? "Unknown error")")
                return [
                    "success": false,
                    "error": result.error ?? "Unknown error",
                    "amplitudes": [],
                    "duration": result.duration,
                    "sampleRate": result.sampleRate
                ]
            }
        }
        
        // Property to get the current audio meter level during recording
        Property("meterLevel") {
            // Return current meter level if recording, otherwise a low default value
            guard let recorder = recorderManager.getRecorder(), recorder.isRecording else {
                return -160.0 // Default low value when not recording (dB)
            }
            recorder.updateMeters()
            return Double(recorder.averagePower(forChannel: 0))
        }
        
        // Property to check if recording is paused
        Property("isPaused") {
            return self.recorderManager.isPaused()
        }
        
        // Property to provide detailed player status
        Property("playerStatus") {
            guard let player = self.audioManager.getPlayer() else {
                return [
                    "isPlaying": false,
                    "currentTime": 0.0,
                    "duration": 0.0,
                    "speed": 1.0
                ]
            }
            let duration = player.duration
            let currentTime = player.currentTime
            return [
                "isPlaying": player.isPlaying,
                "currentTime": Double(currentTime),
                "duration": Double(duration),
                "speed": player.rate
            ]
        }
        
        // MARK: - Voice Activity Detection Functions
        

        Function("setVoiceActivityThreshold") { (threshold: Float) -> String in
            print("[\(Date())] setVoiceActivityThreshold function called with threshold: \(threshold)")
            
            if #available(iOS 13.0, *) {
                if threshold >= 0.0 && threshold <= 1.0 {
                    self.getSoundClassificationManager().updateThreshold(threshold)
                    return "Success: Threshold set to \(threshold)"
                } else {
                    return "InvalidThreshold: Threshold must be between 0.0 and 1.0"
                }
            } else {
                return "UnsupportedIOSVersion: Voice activity detection requires iOS 13.0 or later"
            }
        }

        Function("setVADEnabled") { (enabled: Bool) -> String in
            print("[\(Date())] setVADEnabled function called with enabled: \(enabled)")
            
            if #available(iOS 13.0, *) {
                if enabled {
                    // Set VAD preference
                    self.isVADEnabledFromJS = true
                    
                    // If recording is active, start VAD immediately
                    let isRecording = self.recorderManager.getRecorder()?.isRecording ?? false
                    if isRecording {
                        let result = self.getSoundClassificationManager().startVoiceActivityDetection { [weak self] isVoiceDetected, confidence in
                            self?.sendVoiceActivityEvent(isVoiceDetected: isVoiceDetected, confidence: confidence)
                        }
                        return "VAD enabled and started: \(result)"
                    } else {
                        return "VAD enabled: Will auto-start with next recording"
                    }
                } else {
                    // Disable VAD preference and stop if active
                    self.isVADEnabledFromJS = false
                    let result = self.soundClassificationManager?.stopVoiceActivityDetection() ?? "NotActive"
                    return "VAD disabled: \(result)"
                }
            } else {
                return "UnsupportedIOSVersion: Voice activity detection requires iOS 13.0 or later"
            }
        }

        Property("isVADActive") {
            if #available(iOS 13.0, *) {
                return self.soundClassificationManager?.isVoiceActivityDetectionActive() ?? false
            } else {
                return false
            }
        }
        
        Property("isVADEnabled") {
            return self.isVADEnabledFromJS
        }
        
        Property("isVoiceActivityDetectionActive") {
            if #available(iOS 13.0, *) {
                return self.soundClassificationManager?.isVoiceActivityDetectionActive() ?? false
            } else {
                return false
            }
        }
        
        // MARK: - Audio Session Functions
        
        AsyncFunction("configureAudioSession") { (config: [String: Any]) in
            try self.configureAudioSessionAsync(config: config)
        }
        
        AsyncFunction("activateAudioSession") { () in
            try self.activateAudioSessionAsync()
        }
        
        AsyncFunction("deactivateAudioSession") { () in
            try self.deactivateAudioSessionAsync()
        }
    }
    
    // MARK: - Audio Session Implementation
    
    /**
     Configures the audio session with specified category, mode, and options
     */
    private func configureAudioSessionAsync(config: [String: Any]) throws {
        let session = AVAudioSession.sharedInstance()
        
        // Parse category
        guard let categoryString = config["category"] as? String else {
            throw AudioSessionError.categorySetupFailed(NSError(domain: "AudioSession", code: 1, userInfo: [NSLocalizedDescriptionKey: "Missing category"]))
        }
        
        let category = try parseAudioSessionCategory(categoryString)
        
        // Parse mode (optional, defaults to .default)
        let mode: AVAudioSession.Mode
        if let modeString = config["mode"] as? String {
            mode = try parseAudioSessionMode(modeString)
        } else {
            mode = .default
        }
        
        // Parse options (optional)
        var categoryOptions: AVAudioSession.CategoryOptions = []
        if let options = config["options"] as? [String: Bool] {
            categoryOptions = parseAudioSessionOptions(options)
        }
        
        do {
            try session.setCategory(category, mode: mode, options: categoryOptions)
            print("[\(Date())] Audio session configured: category=\(category), mode=\(mode), options=\(categoryOptions)")
        } catch {
            throw AudioSessionError.categorySetupFailed(error)
        }
    }
    
    /**
     Activates the audio session
     */
    private func activateAudioSessionAsync() throws {
        do {
            try AVAudioSession.sharedInstance().setActive(true, options: [.notifyOthersOnDeactivation])
            print("[\(Date())] Audio session activated successfully")
        } catch {
            throw AudioSessionError.categorySetupFailed(error)
        }
    }
    
    /**
     Deactivates the audio session
     */
    private func deactivateAudioSessionAsync() throws {
        do {
            try AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
            print("[\(Date())] Audio session deactivated successfully")
        } catch {
            throw AudioSessionError.deactivationFailed(error)
        }
    }
    
    // MARK: - Audio Session Parsing Helpers
    
    private func parseAudioSessionCategory(_ categoryString: String) throws -> AVAudioSession.Category {
        switch categoryString {
        case "ambient":
            return .ambient
        case "soloAmbient":
            return .soloAmbient
        case "playback":
            return .playback
        case "record":
            return .record
        case "playAndRecord":
            return .playAndRecord
        case "multiRoute":
            return .multiRoute
        default:
            throw AudioSessionError.categorySetupFailed(NSError(domain: "AudioSession", code: 2, userInfo: [NSLocalizedDescriptionKey: "Invalid category: \(categoryString)"]))
        }
    }
    
    private func parseAudioSessionMode(_ modeString: String) throws -> AVAudioSession.Mode {
        switch modeString {
        case "default":
            return .default
        case "voiceChat":
            return .voiceChat
        case "gameChat":
            return .gameChat
        case "videoRecording":
            return .videoRecording
        case "measurement":
            return .measurement
        case "moviePlayback":
            return .moviePlayback
        case "videoChat":
            return .videoChat
        case "spokenAudio":
            return .spokenAudio
        default:
            throw AudioSessionError.categorySetupFailed(NSError(domain: "AudioSession", code: 3, userInfo: [NSLocalizedDescriptionKey: "Invalid mode: \(modeString)"]))
        }
    }
    
    private func parseAudioSessionOptions(_ options: [String: Bool]) -> AVAudioSession.CategoryOptions {
        var categoryOptions: AVAudioSession.CategoryOptions = []
        
        if options["mixWithOthers"] == true {
            categoryOptions.insert(.mixWithOthers)
        }
        if options["duckOthers"] == true {
            categoryOptions.insert(.duckOthers)
        }
        if options["allowBluetooth"] == true {
            categoryOptions.insert(.allowBluetooth)
        }
        if options["allowBluetoothA2DP"] == true {
            categoryOptions.insert(.allowBluetoothA2DP)
        }
        if options["defaultToSpeaker"] == true {
            categoryOptions.insert(.defaultToSpeaker)
        }
        if options["allowAirPlay"] == true {
            categoryOptions.insert(.allowAirPlay)
        }
        
        return categoryOptions
    }
    
    
    // MARK: - Helper Functions
    
    /**
     Prints the current audio output and input routes to the console.
     */
    private func printCurrentAudioRoute() {
        let currentRoute = AVAudioSession.sharedInstance().currentRoute
        print("[\(Date())] === Current Audio Route ===")
        print("Inputs:")
        for input in currentRoute.inputs {
            print("  📍 \(input.portName) (\(input.portType.rawValue))")
        }
        print("Outputs:")
        for output in currentRoute.outputs {
            print("  🔊 \(output.portName) (\(output.portType.rawValue))")
        }
        print("========================")
    }
    
    /**
     Checks if a Bluetooth audio output device is currently connected.
     */
    private func hasBluetoothOutput() -> Bool {
        let currentRoute = AVAudioSession.sharedInstance().currentRoute
        return currentRoute.outputs.contains { output in
            output.portType == .bluetoothA2DP ||
            output.portType == .bluetoothHFP ||
            output.portType == .bluetoothLE
        }
    }
    
    /**
     Checks if wired headphones are currently connected.
     */
    private func hasWiredHeadphones() -> Bool {
        let currentRoute = AVAudioSession.sharedInstance().currentRoute
        return currentRoute.outputs.contains { output in
            output.portType == .headphones ||
            output.portType == .headsetMic
        }
    }
    
    // MARK: - Audio Session Interruption Handler
        
    /**
     Handles audio session interruptions (e.g., phone call, alarm).
     Pauses playback/recording when interruption begins and attempts to resume playback if applicable when it ends.
     */
    @objc func handleAudioSessionInterruption(notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            return
        }

        switch type {
        case .began:
            // Audio session interrupted. Pause playback/recording.
            print("[\(Date())] ⏸️ Audio session interruption BEGAN")
            
            wasPlayingBeforeInterruption = audioManager.getPlayer()?.isPlaying == true
            wasRecordingBeforeInterruption = recorderManager.getRecorder()?.isRecording == true

            if wasPlayingBeforeInterruption {
                print("[\(Date())] Pausing playback due to interruption")
                _ = audioManager.pausePlayingAudio()
                sendPlayerStatusEvent(isPlaying: false, didJustFinish: false)
            }
            if wasRecordingBeforeInterruption {
                print("[\(Date())] Stopping recording due to interruption")
                // For recording, it's often safer to stop entirely
                _ = recorderManager.stopRecording()
                sendRecorderStatusEvent(status: "interrupted")
            }

        case .ended:
            // Audio session interruption ended
            print("[\(Date())] ✅ Audio session interruption ENDED")
            guard let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt else { return }
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
            
            // Try to reactivate audio session (for playback, assuming the last active category was playback)
            // Note: If recording was interrupted, the session category might need to be re-set to playAndRecord before resuming recording.
            // Audio session reactivation after interruption now handled from JavaScript side

            if options.contains(.shouldResume) {
                print("[\(Date())] Should resume after interruption")
                if wasPlayingBeforeInterruption {
                    _ = audioManager.resumePlayingAudio()
                    print("[\(Date())] Resumed playback after interruption")
                    sendPlayerStatusEvent(isPlaying: true, didJustFinish: false)
                }
                // Note: Recording is NOT auto-resumed for user safety/privacy reasons.
                // The user must explicitly restart recording.
            } else {
                print("[\(Date())] Should NOT resume after interruption")
            }

        @unknown default:
            print("[\(Date())] ❓ Unknown audio session interruption type")
        }
    }
        
    // MARK: - Audio Route Change Handler
        
    /**
     Handles changes in the audio route (e.g., headphones plugged in/out, Bluetooth device connected/disconnected).
     Pauses playback or stops recording if the relevant output/input device becomes unavailable.
     */
    @objc func handleRouteChange(notification: Notification) {
        guard let userInfo = notification.userInfo,
              let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else {
            return
        }

        print("[\(Date())] 🎧 Audio route changed. Reason: \(reason.rawValue)")
        
        switch reason {
        case .newDeviceAvailable:
            print("[\(Date())] ✅ New audio device available")
            printCurrentAudioRoute()
            
            if hasBluetoothOutput() {
                print("[\(Date())] 🎧 Bluetooth device connected")
            }
            if hasWiredHeadphones() {
                print("[\(Date())] 🎧 Wired headphones connected")
            }
            
        case .oldDeviceUnavailable:
            print("[\(Date())] ❌ Audio device disconnected")
            printCurrentAudioRoute()
            
            // If playback is active and output device is unavailable, pause playback
            if audioManager.getPlayer()?.isPlaying == true {
                print("[\(Date())] Pausing playback due to device disconnection")
                _ = audioManager.pausePlayingAudio()
                sendPlayerStatusEvent(isPlaying: false, didJustFinish: false)
            }
            
            // If recording is active, stop it for privacy/safety
            if recorderManager.getRecorder()?.isRecording == true {
                print("[\(Date())] Stopping recording due to device disconnection")
                _ = recorderManager.stopRecording()
                sendRecorderStatusEvent(status: "device_disconnected")
            }
            
        case .categoryChange:
            print("[\(Date())] 📱 Audio session category changed")
            
        case .override:
            print("[\(Date())] 🔄 Audio session output overridden")
            
        case .wakeFromSleep:
            print("[\(Date())] 😴 Audio session woke from sleep")
            
        case .noSuitableRouteForCategory:
            print("[\(Date())] ❌ No suitable route for current category")
            
        case .routeConfigurationChange:
            print("[\(Date())] ⚙️ Audio route configuration changed")
            
        case .unknown:
            print("[\(Date())] ❓ Unknown audio route change reason")
            
        @unknown default:
            print("[\(Date())] ❓ Unhandled audio route change reason")
        }
    }
    
    // MARK: - Helper Functions
    
    // Helper function to get audio file duration
    private func getAudioDuration(url: URL) -> Double {
        do {
            let audioFile = try AVAudioFile(forReading: url)
            let sampleRate = audioFile.fileFormat.sampleRate
            let frameCount = audioFile.length
            let duration = Double(frameCount) / sampleRate
            return duration
        } catch {
            print("Error getting audio duration for \(url.path): \(error)")
            return 0.0
        }
    }
    
    // Helper function to update WAV file header with correct file size
    private func updateWavHeader(fileURL: URL, dataSize: Int64) {
        do {
            print("[\(Date())] === NEW WAV HEADER DEBUG v2.0 START ===")
            print("[\(Date())] File: \(fileURL.lastPathComponent)")
            print("[\(Date())] Expected data size: \(dataSize)")
            
            // Read the file
            let originalData = try Data(contentsOf: fileURL)
            print("[\(Date())] Original file size: \(originalData.count) bytes")
            
            guard originalData.count >= 44 else {
                print("[\(Date())] ERROR: File too small for WAV header")
                return
            }
            
            // Analyze the current WAV structure
            let riffHeader = String(data: originalData.subdata(in: 0..<4), encoding: .ascii) ?? "????"
            let currentFileSize = originalData.subdata(in: 4..<8).withUnsafeBytes { $0.load(as: UInt32.self).littleEndian }
            let waveHeader = String(data: originalData.subdata(in: 8..<12), encoding: .ascii) ?? "????"
            
            print("[\(Date())] Current RIFF header: '\(riffHeader)'")
            print("[\(Date())] Current file size in header: \(currentFileSize)")
            print("[\(Date())] Current WAVE header: '\(waveHeader)'")
            
            // Find all chunks
            var position = 12
            var dataChunkPosition = -1
            var currentDataSize: UInt32 = 0
            
            print("[\(Date())] Analyzing chunks:")
            while position < originalData.count - 8 {
                let chunkId = String(data: originalData.subdata(in: position..<position+4), encoding: .ascii) ?? "????"
                let chunkSize = originalData.subdata(in: position+4..<position+8).withUnsafeBytes { $0.load(as: UInt32.self).littleEndian }
                
                print("[\(Date())] - Chunk '\(chunkId)' at position \(position), size: \(chunkSize)")
                
                if chunkId == "data" {
                    dataChunkPosition = position + 4
                    currentDataSize = chunkSize
                    print("[\(Date())] Found DATA chunk! Position: \(dataChunkPosition), current size: \(currentDataSize)")
                    break
                }
                
                position += 8 + Int(chunkSize)
            }
            
            guard dataChunkPosition != -1 else {
                print("[\(Date())] ERROR: Could not find data chunk!")
                return
            }
            
            // Create modified data
            var modifiedData = originalData
            
            // Update RIFF chunk size (total file size - 8)
            let newTotalFileSize = UInt32(dataSize + Int64(dataChunkPosition) + 4) // data position + 4 bytes for size field
            let fileSizeBytes = withUnsafeBytes(of: newTotalFileSize.littleEndian) { Data($0) }
            modifiedData.replaceSubrange(4..<8, with: fileSizeBytes)
            
            // Update data chunk size
            let dataSizeBytes = withUnsafeBytes(of: UInt32(dataSize).littleEndian) { Data($0) }
            modifiedData.replaceSubrange(dataChunkPosition..<dataChunkPosition+4, with: dataSizeBytes)
            
            print("[\(Date())] Updated RIFF size: \(currentFileSize) -> \(newTotalFileSize)")
            print("[\(Date())] Updated DATA size: \(currentDataSize) -> \(UInt32(dataSize))")
            
            // Write back
            try modifiedData.write(to: fileURL)
            
            // Verify the update
            let verifyData = try Data(contentsOf: fileURL)
            let verifyFileSize = verifyData.subdata(in: 4..<8).withUnsafeBytes { $0.load(as: UInt32.self).littleEndian }
            let verifyDataSize = verifyData.subdata(in: dataChunkPosition..<dataChunkPosition+4).withUnsafeBytes { $0.load(as: UInt32.self).littleEndian }
            
            print("[\(Date())] Verification - File size: \(verifyFileSize), Data size: \(verifyDataSize)")
            print("[\(Date())] Final file size on disk: \(verifyData.count) bytes")
            print("[\(Date())] === WAV HEADER DEBUG END ===")
            
        } catch {
            print("[\(Date())] ERROR updating WAV header: \(error.localizedDescription)")
        }
    }
}
