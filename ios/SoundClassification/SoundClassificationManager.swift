import Foundation
import AVFoundation
import SoundAnalysis

@available(iOS 14.0, *)
class EnhancedSoundClassificationManager: NSObject {
    
    private let sharedEngine = SharedAudioEngineManager.shared
    private var streamAnalyzer: SNAudioStreamAnalyzer?
    private var soundClassifier: SNClassifySoundRequest?
    
    private let stateLock = NSRecursiveLock()
    private var _isAnalyzing = false
    private var _voiceActivityCallback: (([String: Any]) -> Void)?
    private var _lastVoiceState: Bool = false
    
    // "onEveryFrame" | "onChange" | "throttled"
    private var _vadEventMode: String = "onEveryFrame"
    public var vadEventMode: String {
        get {
            stateLock.lock()
            defer { stateLock.unlock() }
            return _vadEventMode
        }
        set {
            stateLock.lock()
            _vadEventMode = newValue
            stateLock.unlock()
        }
    }
    
    private var _vadThrottleMs: Int = 100
    public var vadThrottleMs: Int {
        get {
            stateLock.lock()
            defer { stateLock.unlock() }
            return _vadThrottleMs
        }
        set {
            stateLock.lock()
            _vadThrottleMs = newValue
            stateLock.unlock()
        }
    }
    
    private var _lastEventTime: TimeInterval = 0
    private var lastEventTime: TimeInterval {
        get {
            stateLock.lock()
            defer { stateLock.unlock() }
            return _lastEventTime
        }
        set {
            stateLock.lock()
            _lastEventTime = newValue
            stateLock.unlock()
        }
    }
    
    private var _voiceConfidenceThreshold: Float = 0.5
    public var voiceConfidenceThreshold: Float {
        get {
            stateLock.lock()
            defer { stateLock.unlock() }
            return _voiceConfidenceThreshold
        }
        set {
            stateLock.lock()
            _voiceConfidenceThreshold = newValue
            stateLock.unlock()
        }
    }
    public var windowDuration: Double = 1.5
    public var overlapFactor: Float = 0.9
    
    private let voiceSoundIdentifiers: Set<String> = [
        "speech", "conversation", "narration", "monologue", "singing",
        "human_voice", "male_speech", "female_speech", "child_speech",
        "speech_synthesizer", "voice", "talk", "speaking",
        "speaking", "conversation", "dialog", "dialogue",
          "narration", "monologue",
          "male speech", "man speaking",
          "female speech", "woman speaking",
          "child speech", "kid speaking",
          "public speaking", "lecture", "debate", "interview", "podcast"
    ]
    
    func startVoiceActivityDetection(callback: @escaping ([String: Any]) -> Void) -> String {
        stateLock.lock()
        guard !_isAnalyzing else {
            stateLock.unlock()
            return "AlreadyAnalyzing"
        }
        
        _voiceActivityCallback = callback
        stateLock.unlock()
        
      
        return startSoundClassificationDetection()
   
    }
    
    func stopVoiceActivityDetection() -> String {
        stateLock.lock()
        guard _isAnalyzing else {
            stateLock.unlock()
            return "NotAnalyzing"
        }
        stateLock.unlock()
        
        return stopStandaloneDetection()
    }
    
    func isVoiceActivityDetectionActive() -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return _isAnalyzing
    }
    
    func updateThreshold(_ threshold: Float) {
        guard threshold >= 0.0 && threshold <= 1.0 else {
            print("Threshold must be between 0.0 and 1.0")
            return
        }
        
        stateLock.lock()
        _voiceConfidenceThreshold = threshold
        stateLock.unlock()
        
        print("Threshold updated to \(threshold)")
    }
    
    // MARK: - Standalone Detection Implementation
    
    @available(iOS 14.0, *)
    private func startSoundClassificationDetection() -> String {
        do {
            // SharedEngine provides 16kHz audio - create analyzer with matching format
            let audioFormat = AVAudioFormat(
                commonFormat: .pcmFormatFloat32,
                sampleRate: 16000,
                channels: 1,
                interleaved: false
            )
            
            guard let format = audioFormat else {
                return "SetupError: Failed to create audio format"
            }
            
            streamAnalyzer = SNAudioStreamAnalyzer(format: format)
            soundClassifier = try SNClassifySoundRequest(classifierIdentifier: .version1)
            soundClassifier?.windowDuration = CMTimeMakeWithSeconds(windowDuration, preferredTimescale: 48000)
            soundClassifier?.overlapFactor = Double(overlapFactor)
            
            guard let classifier = soundClassifier else {
                return "SetupError: Failed to create sound classifier"
            }
            try streamAnalyzer?.add(classifier, withObserver: self)
            
            // Enable VAD in shared engine (will receive pre-converted 16kHz buffers)
            sharedEngine.enableVADCapture { [weak self] buffer, time in
                guard let self = self, let analyzer = self.streamAnalyzer else { return }
                let framePosition = time.sampleTime
                analyzer.analyze(buffer, atAudioFramePosition: framePosition)
            }
            
            stateLock.lock()
            _isAnalyzing = true
            stateLock.unlock()
            
            return "Success"
            
        } catch {
            print("Failed to start voice activity detection: \(error)")
            return "StartError: \(error.localizedDescription)"
        }
    }
    
    private func stopStandaloneDetection() -> String {
        // Disable VAD in shared engine
        sharedEngine.disableVADCapture()
        
        // Clean up analyzer
        streamAnalyzer?.removeAllRequests()
        streamAnalyzer = nil
        soundClassifier = nil
        
        stateLock.lock()
        _isAnalyzing = false
        _voiceActivityCallback = nil
        stateLock.unlock()
        
        print("Voice activity detection stopped")
        return "Success"
    }
    
    // MARK: - Sound Classification Result Processing
    
    private func processClassificationResults(_ classificationResult: SNClassificationResult) {
        stateLock.lock()
        guard let callback = _voiceActivityCallback else {
            stateLock.unlock()
            return
        }
        stateLock.unlock()
        
        var maxVoiceConfidence: Float = 0.0
        var hasVoiceActivity = false
        
        for classification in classificationResult.classifications {
            let identifier = classification.identifier.lowercased()
            let confidence = Float(classification.confidence)
            
            let isVoiceSound = voiceSoundIdentifiers.contains { voiceId in
                identifier.contains(voiceId.lowercased())
            }
            
            if isVoiceSound {
                maxVoiceConfidence = max(maxVoiceConfidence, confidence)
                if confidence >= voiceConfidenceThreshold {
                    hasVoiceActivity = true
                }
            }
        }
        
        let currentTime = Date().timeIntervalSince1970
        stateLock.lock()
        let isStateChange = hasVoiceActivity != _lastVoiceState
        let currentEventMode = _vadEventMode
        let throttleTime = _vadThrottleMs
        let lastEventTimeValue = _lastEventTime
        stateLock.unlock()
        
        let shouldSendEvent: Bool
        switch currentEventMode {
        case "onChange":
            shouldSendEvent = isStateChange
        case "throttled":
            let timeSinceLastEvent = (currentTime - lastEventTimeValue) * 1000
            shouldSendEvent = isStateChange || timeSinceLastEvent >= Double(throttleTime)
        default:
            shouldSendEvent = true
        }
        
        if shouldSendEvent {
            let eventType: String
            if isStateChange {
                eventType = hasVoiceActivity ? "speech_start" : "silence_start"
            } else {
                eventType = hasVoiceActivity ? "speech_continue" : "silence_continue"
            }
            
            sharedEngine.updateVoiceState(hasVoiceActivity)
            
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                
                let event: [String: Any] = [
                    "isVoiceDetected": hasVoiceActivity,
                    "confidence": maxVoiceConfidence,
                    "timestamp": currentTime * 1000,
                    "isStateChange": isStateChange,
                    "previousState": { self.stateLock.lock(); let prevState = self._lastVoiceState; self.stateLock.unlock(); return prevState }(),
                    "eventType": eventType
                ]
                
                callback(event)
                
                if isStateChange {
                    self.stateLock.lock()
                    self._lastVoiceState = hasVoiceActivity
                    self.stateLock.unlock()
                }
                
                self.stateLock.lock()
                self._lastEventTime = currentTime
                self.stateLock.unlock()
            }
        }
    }
}

// MARK: - SNResultsObserving
@available(iOS 14.0, *)
extension EnhancedSoundClassificationManager: SNResultsObserving {
    func request(_ request: SNRequest, didProduce result: SNResult) {
        guard let classificationResult = result as? SNClassificationResult else { return }
        processClassificationResults(classificationResult)
    }
    
    func request(_ request: SNRequest, didFailWithError error: Error) {
        print("Sound classification request failed: \(error)")
        
        stateLock.lock()
        let callback = _voiceActivityCallback
        stateLock.unlock()
        
        DispatchQueue.main.async {
            let event: [String: Any] = [
                "isVoiceDetected": false,
                "confidence": 0.0,
                "timestamp": Date().timeIntervalSince1970 * 1000,
                "isStateChange": false,
                "eventType": "silence_continue"
            ]
            callback?(event)
        }
    }
    
    func requestDidComplete(_ request: SNRequest) {
        print("Sound classification request completed")
    }
}
