import ExpoModulesCore
import AVFoundation

// Enum for managing audio session setup types (activate or deactivate)
enum SessionSetupType {
    case stop
    case play
}

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
    private let audioManager = AudioManager()
    private let recorderManager = RecorderManager()
    
    @available(iOS 14.0, *)
    private var soundClassificationManager: EnhancedSoundClassificationManager?
    
    private var isVADEnabledFromJS: Bool = false
    
    private var wasPlayingBeforeInterruption: Bool = false
    private var wasRecordingBeforeInterruption: Bool = false
    
    
    
    
    @available(iOS 14.0, *)
    private func getSoundClassificationManager() -> EnhancedSoundClassificationManager {
        if soundClassificationManager == nil {
            soundClassificationManager = EnhancedSoundClassificationManager()
        }
        return soundClassificationManager!
    }
    
    private func shouldVADBeActive() -> Bool {
        let isRecording = recorderManager.getRecorder()?.isRecording ?? false
        return isRecording && isVADEnabledFromJS
    }

    private func getFileCacheLocation() -> URL {
        return recorderManager.getFileCacheLocation()
    }

    func sendPlayerStatusEvent(isPlaying: Bool, didJustFinish: Bool) {
        print("[\(Date())] sendPlayerStatusEvent: isPlaying=\(isPlaying), didJustFinish=\(didJustFinish)")
        sendEvent("onPlayerStatusChange", [
            "isPlaying": isPlaying,
            "didJustFinish": didJustFinish
        ])
    }

    func sendRecorderStatusEvent(status: String) {
        print("[\(Date())] sendRecorderStatusEvent: status=\(status)")
        sendEvent("onRecorderStatusChange", [
            "status": status 
        ])
    }

    private func sendAmplitudeEvent(amplitude: Float) {
        sendEvent("onRecorderAmplitude", [
            "amplitude": amplitude
        ])
    }
    
    private func sendVoiceActivityEvent(_ event: [String: Any]) {
        print("[\(Date())] sendVoiceActivityEvent: \(event)")
        sendEvent("onVoiceActivityDetected", event)
    }
    
    private func sendAudioChunkEvent(_ chunk: [String: Any]) {
        sendEvent("onAudioChunk", chunk)
    }

    private func setupNotificationObservers() {
        print("[\(Date())] Setting up notification observers...")
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAudioSessionInterruption),
            name: AVAudioSession.interruptionNotification,
            object: nil
        )
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleRouteChange),
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )
        
        print("[\(Date())] Notification observers setup completed")
    }
    
    private func removeNotificationObservers() {
        print("[\(Date())] Removing notification observers...")
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.interruptionNotification, object: nil)
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.routeChangeNotification, object: nil)
        print("[\(Date())] Notification observers removed")
    }


    public func definition() -> ModuleDefinition {
        Name("ExpoAudioStudio")
        
        OnCreate {
            print("[\(Date())] ExpoAudioStudioModule: OnCreate called")
            setupNotificationObservers() 
            
            recorderManager.setRecordingStoppedCallback { [weak self] in
                if #available(iOS 14.0, *) {
                    if let strongSelf = self, strongSelf.isVADEnabledFromJS {
                        print("[\(Date())] Auto-stopping VAD due to recording stop")
                        _ = strongSelf.soundClassificationManager?.stopVoiceActivityDetection()
                    }
                }
            }
        }
        
        OnDestroy {
            removeNotificationObservers()
            
            _ = audioManager.stopPlayingAudio()
            _ = recorderManager.stopRecording()
            
            SharedAudioEngineManager.shared.forceStop()
            
            if #available(iOS 14.0, *) {
                  if let manager = self.soundClassificationManager, manager.isVoiceActivityDetectionActive() {
                      _ = manager.stopVoiceActivityDetection()
                  }
                  self.soundClassificationManager = nil
                  self.isVADEnabledFromJS = false
              }
            
            print("[\(Date())] Cleanup complete.")
        }
        
        Events(
            "onPlayerStatusChange",
            "onRecorderStatusChange",
            "onRecorderAmplitude",
            "onVoiceActivityDetected",
            "onAudioChunk"
        )
        
        // MARK: - Recording Functions
        
        Function("lastRecording") { () -> String? in
            return recorderManager.getRecordedFilePath()
        }
        
        // MARK: - File Management Functions
        
        Function("listRecordings") { (directoryPath: String?) -> [[String: Any]] in
            let directory: URL
            
            if let customDir = directoryPath, !customDir.isEmpty {
                // Remove file:// prefix if present
                let cleanPath = customDir.replacingOccurrences(of: "file://", with: "")
                directory = URL(fileURLWithPath: cleanPath)
            } else {
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
        
        Function("joinAudioFiles") { (filePaths: [String], outputPath: String) -> String in
            print("[\(Date())] Starting audio join with \(filePaths.count) files")
            
            guard filePaths.count >= 2 else {
                return "Error: At least 2 audio files are required for joining"
            }
            
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
            
            let cleanOutputPath = outputPath.replacingOccurrences(of: "file://", with: "")
            let outputURL = URL(fileURLWithPath: cleanOutputPath)
            
            try? FileManager.default.createDirectory(at: outputURL.deletingLastPathComponent(), withIntermediateDirectories: true, attributes: nil)
            
            if FileManager.default.fileExists(atPath: outputURL.path) {
                try? FileManager.default.removeItem(at: outputURL)
                print("[\(Date())] Deleted existing output file")
            }
            
            let composition = AVMutableComposition()
            guard let compositionAudioTrack = composition.addMutableTrack(
                withMediaType: .audio,
                preferredTrackID: kCMPersistentTrackID_Invalid
            ) else {
                return "Error: Could not create composition track"
            }
            
            print("[\(Date())] Created AVMutableComposition")
            
            for (index, inputURL) in inputURLs.enumerated() {
                print("[\(Date())] Adding file \(index): \(inputURL.lastPathComponent)")
                
                let asset = AVURLAsset(url: inputURL)
                guard let assetTrack = asset.tracks(withMediaType: .audio).first else {
                    print("[\(Date())] Warning: No audio track found in file index \(index)")
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
            
            guard let exportSession = AVAssetExportSession(
                asset: composition,
                presetName: AVAssetExportPresetPassthrough
            ) else {
                return "Error: Could not create export session"
            }
            
            exportSession.outputFileType = .wav
            exportSession.outputURL = outputURL
            
            print("[\(Date())] Starting export session...")
            
            let semaphore = DispatchSemaphore(value: 0)
            var exportResult: String = ""
            
            exportSession.exportAsynchronously {
                switch exportSession.status {
                case .completed:
                    print("[\(Date())] Export completed successfully!")
                    
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
            
            let timeoutResult = semaphore.wait(timeout: .now() + 30.0) // 30 second timeout
            
            if timeoutResult == .timedOut {
                exportSession.cancelExport()
                return "Error: Export timed out after 30 seconds"
            }
            
            return exportResult
        }
        
        Function("setAmplitudeUpdateFrequency") { (frequencyHz: Double) -> String in
            print("[\(Date())] setAmplitudeUpdateFrequency called with \(frequencyHz) Hz")
            
            self.recorderManager.setAmplitudeUpdateFrequency(frequencyHz)
            
            return "Amplitude frequency set to \(frequencyHz) Hz"
        }
        
        Function("setListenToChunks") { (enable: Bool) -> Bool in
            print("[\(Date())] setListenToChunks called with enable: \(enable)")
            return self.recorderManager.setListenToChunks(enable)
        }
        
        Function("startRecording") { (directoryPath: String?) -> String in
            print("[\(Date())] startRecording function called with directory: \(directoryPath ?? "default")")
            let _ = self.audioManager.stopPlayingAudio()
          
            let result = self.recorderManager.startRecording(
                directoryPath: directoryPath,
                sendRecorderStatusEvent: { status in
                    self.sendRecorderStatusEvent(status: status)
                    
                    // Auto-start VAD when recording starts if enabled
                    if status == "recording" && self.isVADEnabledFromJS {
                        if #available(iOS 14.0, *) {
                            print("[\(Date())] Auto-starting VAD because recording started and VAD is enabled")
                            let vadResult = self.getSoundClassificationManager().startVoiceActivityDetection { [weak self] event in
                                self?.sendVoiceActivityEvent(event)
                            }
                            print("[\(Date())] VAD auto-start result: \(vadResult)")
                        }
                    }
                },
                sendAmplitudeEvent: { amplitude in
                    self.sendAmplitudeEvent(amplitude: amplitude)
                },
                sendChunkEvent: { chunk in
                    self.sendAudioChunkEvent(chunk)
                }
            )
            
            return result
        }
        
        Function("stopRecording") { () -> String in
            print("[\(Date())] stopRecording function called")
            
     
                let vadActive = self.soundClassificationManager?.isVoiceActivityDetectionActive() ?? false
                if vadActive {
                    print("[\(Date())] Auto-stopping VAD because recording stopped")
                    let vadResult = self.soundClassificationManager?.stopVoiceActivityDetection() ?? "NotActive"
                    print("[\(Date())] VAD auto-stop result: \(vadResult)")
                }
            
            let result = self.recorderManager.stopRecording()
            return result
        }
        
        Function("pauseRecording") { () -> String in
            print("[\(Date())] pauseRecording function called.")
            return self.recorderManager.pauseRecording()
        }
        
        Function("resumeRecording") { () -> String in
            print("[\(Date())] resumeRecording function called.")
            return self.recorderManager.resumeRecording()
        }
        
        // MARK: - Playback Functions
        
        Function("preparePlayer") { (path: String) -> String in
            print("[\(Date())] preparePlayer function called with path: \(path)")
            
            if let result = self.audioManager.preparePlayer(path: path, sendPlayerStatusEvent: { isPlaying, didJustFinish in
                self.sendPlayerStatusEvent(isPlaying: isPlaying, didJustFinish: didJustFinish)
            }) {
                return result
            } else {
                return AudioError.playbackFailed.rawValue
            }
        }
        
        Function("startPlaying") { (path: String) -> String in
            print("[\(Date())] startPlaying function called with path: \(path)")
            
            if let result = self.audioManager.startPlayingAudio(path: path, sendPlayerStatusEvent: { isPlaying, didJustFinish in
                self.sendPlayerStatusEvent(isPlaying: isPlaying, didJustFinish: didJustFinish)
            }) {
                return result
            } else {
                return AudioError.playbackFailed.rawValue
            }
        }
        
        Function("stopPlayer") { () -> String in
            print("[\(Date())] stopPlayer function called.")
            
            let result = self.audioManager.stopPlayingAudio()
            DispatchQueue.main.async {
                self.sendPlayerStatusEvent(isPlaying: false, didJustFinish: false)
            }
            return result ? "stopped" : "NoPlayerException"
        }
        
        Function("setPlaybackSpeed") { (speedString: String) -> String in
            guard let speedFloat = Float(speedString) else {
                return AudioError.invalidSpeedFormat.rawValue
            }
            
            if speedFloat >= 0.5 && speedFloat <= 2.0 {
                _ = audioManager.setPlaybackSpeed(speed: speedFloat)
                return "Playback speed set to \(speedFloat)"
            } else {
                print("[\(Date())] setPlaybackSpeed: Speed out of range.")
                return AudioError.speedOutOfRange.rawValue
            }
        }
        
        Function("seekTo") { (position: Double) -> String in
            return audioManager.seekToTime(position: position)
        }
        
        Function("pausePlayer") { () -> String in
            print("[\(Date())] pausePlayer function called.")
            
            let result = self.audioManager.pausePlayingAudio()
            if result != AudioError.noPlayer.rawValue {
                self.sendPlayerStatusEvent(isPlaying: false, didJustFinish: false)
            }
            return result
        }
        
        Function("resumePlayer") { () -> String in
            print("[\(Date())] resumePlayer function called.")
            
            
            let result = self.audioManager.resumePlayingAudio()
            if result != AudioError.noPlayer.rawValue {
                self.sendPlayerStatusEvent(isPlaying: true, didJustFinish: false)
            }
            return result
        }
        
        // MARK: - Audio Information Properties
        
        Property("currentPosition") {
            return audioManager.getPlayer()?.currentTime ?? 0.0
        }
        
        // MARK: - Permission Request Functions
        
        AsyncFunction("requestMicrophonePermission") { (promise: Promise) in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                let status: String = granted ? "granted" : "denied"
                
                let permissionResponse: [String: Any] = [
                    "status": status,
                    "canAskAgain": true,
                    "granted": granted
                ]
                
                promise.resolve(permissionResponse)
            }
        }
        
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
                statusString = "undetermined"
            }
            
            let permissionResponse: [String: Any] = [
                "status": statusString,
                "canAskAgain": true,
                "granted": isGranted
            ]
            
            promise.resolve(permissionResponse)
        }
        
        Function("getDuration") { (uri: String) -> Double in
            print("[\(Date())] getDuration called for uri: \(uri)")
            guard let audioURL = URL(string: uri) else {
                print("[\(Date())] getDuration: Invalid URL for getDuration.")
                return 0.0
            }
            
            if audioURL.isFileURL && !FileManager.default.fileExists(atPath: audioURL.path) {
                print("[\(Date())] getDuration: File not found for getDuration at path: \(audioURL.path)")
                return 0.0
            }
            
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
            
            let result = autoreleasepool { () -> AudioAmplitudeAnalyzer.AmplitudeResult in
                return AudioAmplitudeAnalyzer.getAudioAmplitudes(fileUrl: audioURL, barsCount: barsCount)
            }
            
            if result.success {
                print("[\(Date())] getAudioAmplitudes: Successfully analyzed audio - \(result.amplitudes.count) bars, duration: \(result.duration)s")
                
                if result.duration > 60.0 {
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
        
        Property("meterLevel") {
            guard let recorder = recorderManager.getRecorder(), recorder.isRecording else {
                return -160.0
            }
            recorder.updateMeters()
            return Double(recorder.averagePower(forChannel: 0))
        }
        
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
            
            if #available(iOS 14.0, *) {
                if threshold >= 0.0 && threshold <= 1.0 {
                    self.getSoundClassificationManager().updateThreshold(threshold)
                    return "Success: Threshold set to \(threshold)"
                } else {
                    return "InvalidThreshold: Threshold must be between 0.0 and 1.0"
                }
            } else {
                return "UnsupportedIOSVersion: Voice activity detection requires iOS 14.0 or later"
            }
        }

        Function("setVADEnabled") { (enabled: Bool) -> String in
            print("[\(Date())] setVADEnabled function called with enabled: \(enabled)")
            
                if enabled {
                    self.isVADEnabledFromJS = true
                    
                    let isRecording = self.recorderManager.getRecorder()?.isRecording ?? false
                    if isRecording {
                        let result = self.getSoundClassificationManager().startVoiceActivityDetection { [weak self] event in
                            self?.sendVoiceActivityEvent(event)
                        }
                        return "VAD enabled and started: \(result)"
                    } else {
                        return "VAD enabled: Will auto-start with next recording"
                    }
                } else {
                    self.isVADEnabledFromJS = false
                    let result = self.soundClassificationManager?.stopVoiceActivityDetection() ?? "NotActive"
                    return "VAD disabled: \(result)"
                }
          
        }

        Property("isVADActive") {
                return self.soundClassificationManager?.isVoiceActivityDetectionActive() ?? false
        }
        
        Property("isVADEnabled") {
            return self.isVADEnabledFromJS
        }
        
        //"onChange", "onEveryFrame", "throttled"
        Function("setVADEventMode") { (mode: String, throttleMs: Int?) -> String in
          
                let manager = self.getSoundClassificationManager()
                manager.vadEventMode = mode
                
                if let throttle = throttleMs, mode == "throttled" {
                    manager.vadThrottleMs = throttle
                }
                
                return "VAD event mode set to: \(mode)" + (throttleMs != nil ? " with \(throttleMs!)ms throttle" : "")
          
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
    

    private func configureAudioSessionAsync(config: [String: Any]) throws {
        let session = AVAudioSession.sharedInstance()
        
        guard let categoryString = config["category"] as? String else {
            throw AudioSessionError.categorySetupFailed(NSError(domain: "AudioSession", code: 1, userInfo: [NSLocalizedDescriptionKey: "Missing category"]))
        }
        
        let category = try parseAudioSessionCategory(categoryString)
        
        let mode: AVAudioSession.Mode
        if let modeString = config["mode"] as? String {
            mode = try parseAudioSessionMode(modeString)
        } else {
            mode = .default
        }
        
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
    

    private func activateAudioSessionAsync() throws {
        do {
            try AVAudioSession.sharedInstance().setActive(true, options: [.notifyOthersOnDeactivation])
            print("[\(Date())] Audio session activated successfully")
        } catch {
            throw AudioSessionError.categorySetupFailed(error)
        }
    }

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
            categoryOptions.insert(.allowBluetoothHFP)
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
    private func hasBluetoothOutput() -> Bool {
        let currentRoute = AVAudioSession.sharedInstance().currentRoute
        return currentRoute.outputs.contains { output in
            output.portType == .bluetoothA2DP ||
            output.portType == .bluetoothHFP ||
            output.portType == .bluetoothLE
        }
    }
    
    private func hasWiredHeadphones() -> Bool {
        let currentRoute = AVAudioSession.sharedInstance().currentRoute
        return currentRoute.outputs.contains { output in
            output.portType == .headphones ||
            output.portType == .headsetMic
        }
    }
    
    // MARK: - Audio Session Interruption Handler
        

    @objc func handleAudioSessionInterruption(notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            return
        }

        switch type {
        case .began:
            print("[\(Date())] Audio session interruption BEGAN")
            
            wasPlayingBeforeInterruption = audioManager.getPlayer()?.isPlaying == true
            wasRecordingBeforeInterruption = recorderManager.getRecorder()?.isRecording == true

            if wasPlayingBeforeInterruption {
                print("[\(Date())] Pausing playback due to interruption")
                _ = audioManager.pausePlayingAudio()
                sendPlayerStatusEvent(isPlaying: false, didJustFinish: false)
            }
            if wasRecordingBeforeInterruption {
                print("[\(Date())] Stopping recording due to interruption")
                _ = recorderManager.stopRecording()
                sendRecorderStatusEvent(status: "interrupted")
            }
            
            if SharedAudioEngineManager.shared.isActive() {
                print("[\(Date())] Stopping SharedAudioEngine due to interruption")
                SharedAudioEngineManager.shared.forceStop()
            }

        case .ended:
            print("[\(Date())] Audio session interruption ENDED")
            guard let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt else { return }
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
            
        
            if options.contains(.shouldResume) {
                print("[\(Date())] Should resume after interruption")
                if wasPlayingBeforeInterruption {
                    _ = audioManager.resumePlayingAudio()
                    print("[\(Date())] Resumed playback after interruption")
                    sendPlayerStatusEvent(isPlaying: true, didJustFinish: false)
                }
            } else {
                print("[\(Date())] Should NOT resume after interruption")
            }

        @unknown default:
            print("[\(Date())] Unknown audio session interruption type")
        }
    }
        
    // MARK: - Audio Route Change Handler

    @objc func handleRouteChange(notification: Notification) {
        guard let userInfo = notification.userInfo,
              let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else {
            return
        }

        print("[\(Date())] Audio route changed. Reason: \(reason.rawValue)")
        
        switch reason {
        case .newDeviceAvailable:
            print("[\(Date())] New audio device available")
            
            if hasBluetoothOutput() {
                print("[\(Date())] Bluetooth device connected")
            }
            if hasWiredHeadphones() {
                print("[\(Date())] Wired headphones connected")
            }
            
        case .oldDeviceUnavailable:
            print("[\(Date())] Audio device disconnected")
            
            if audioManager.getPlayer()?.isPlaying == true {
                print("[\(Date())] Pausing playback due to device disconnection")
                _ = audioManager.pausePlayingAudio()
                sendPlayerStatusEvent(isPlaying: false, didJustFinish: false)
            }
            
            if recorderManager.getRecorder()?.isRecording == true {
                print("[\(Date())] Stopping recording due to device disconnection")
                _ = recorderManager.stopRecording()
                sendRecorderStatusEvent(status: "device_disconnected")
            }
            
        case .categoryChange:
            print("[\(Date())] Audio session category changed")
            
        case .override:
            print("[\(Date())] Audio session output overridden")
            
        case .wakeFromSleep:
            print("[\(Date())] Audio session woke from sleep")
            
        case .noSuitableRouteForCategory:
            print("[\(Date())] No suitable route for current category")
            
        case .routeConfigurationChange:
            print("[\(Date())] Audio route configuration changed")
            
        case .unknown:
            print("[\(Date())] Unknown audio route change reason")
            
        @unknown default:
            print("[\(Date())] Unhandled audio route change reason")
        }
    }
    
    // MARK: - Helper Functions
    
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
}
