import Foundation
import AVFoundation
import SoundAnalysis

@available(iOS 14.0, *)
class EnhancedSoundClassificationManager: NSObject {
    
    private let sharedEngine = SharedAudioEngineManager.shared
    private var streamAnalyzer: SNAudioStreamAnalyzer?
    private var soundClassifier: SNClassifySoundRequest?
    
    private var isAnalyzing = false
    private var voiceActivityCallback: (([String: Any]) -> Void)?
    
    private var lastVoiceState: Bool = false
    
    // "onEveryFrame" | "onChange" | "throttled"
    public var vadEventMode: String = "onEveryFrame"
    public var vadThrottleMs: Int = 100
    private var lastEventTime: TimeInterval = 0
    
    public var voiceConfidenceThreshold: Float = 0.5
    public var windowDuration: Double = 1.5
    public var overlapFactor: Float = 0.9
    
    // Voice-related sound identifiers
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
        guard !isAnalyzing else {
            return "AlreadyAnalyzing"
        }
        
        voiceActivityCallback = callback
        
      
        return startSoundClassificationDetection()
   
    }
    
    func stopVoiceActivityDetection() -> String {
        guard isAnalyzing else {
            return "NotAnalyzing"
        }
        
        return stopStandaloneDetection()
    }
    
    func isVoiceActivityDetectionActive() -> Bool {
        return isAnalyzing
    }
    
    func updateThreshold(_ threshold: Float) {
        guard threshold >= 0.0 && threshold <= 1.0 else {
            print("Threshold must be between 0.0 and 1.0")
            return
        }
        voiceConfidenceThreshold = threshold
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
            
            isAnalyzing = true
            
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
        isAnalyzing = false
        voiceActivityCallback = nil
        
        print("Voice activity detection stopped")
        return "Success"
    }
    
    // MARK: - Sound Classification Result Processing
    
    private func processClassificationResults(_ classificationResult: SNClassificationResult) {
        guard let callback = voiceActivityCallback else { return }
        
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
        let isStateChange = hasVoiceActivity != lastVoiceState
        
        let shouldSendEvent: Bool
        switch vadEventMode {
        case "onChange":
            shouldSendEvent = isStateChange
        case "throttled":
            let timeSinceLastEvent = (currentTime - lastEventTime) * 1000
            shouldSendEvent = isStateChange || timeSinceLastEvent >= Double(vadThrottleMs)
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
            
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                
                let event: [String: Any] = [
                    "isVoiceDetected": hasVoiceActivity,
                    "confidence": maxVoiceConfidence,
                    "timestamp": currentTime * 1000,
                    "isStateChange": isStateChange,
                    "previousState": self.lastVoiceState,
                    "eventType": eventType
                ]
                
                callback(event)
                
                if isStateChange {
                    self.lastVoiceState = hasVoiceActivity
                }
                self.lastEventTime = currentTime
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
        DispatchQueue.main.async { [weak self] in
            let event: [String: Any] = [
                "isVoiceDetected": false,
                "confidence": 0.0,
                "timestamp": Date().timeIntervalSince1970 * 1000,
                "isStateChange": false,
                "eventType": "silence_continue"
            ]
            self?.voiceActivityCallback?(event)
        }
    }
    
    func requestDidComplete(_ request: SNRequest) {
        print("Sound classification request completed")
    }
}
