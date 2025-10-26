import Foundation
import AVFoundation
import SoundAnalysis

@available(iOS 13.0, *)
class EnhancedSoundClassificationManager: NSObject {
    
    private var audioEngine: AVAudioEngine?
    private var streamAnalyzer: SNAudioStreamAnalyzer?
    private var soundClassifier: SNClassifySoundRequest?
    
    private var isAnalyzing = false
    private var voiceActivityCallback: ((Bool, Float) -> Void)?
    
    // Configuration
    public var voiceConfidenceThreshold: Float = 0.5
    public var windowDuration: Double = 1.5
    public var overlapFactor: Float = 0.9
    
    // Voice-related sound identifiers
    private let voiceSoundIdentifiers: Set<String> = [
        "speech", "conversation", "narration", "monologue", "singing",
        "human_voice", "male_speech", "female_speech", "child_speech",
        "speech_synthesizer", "voice", "talk", "speaking"
    ]
    
    func startVoiceActivityDetection(callback: @escaping (Bool, Float) -> Void) -> String {
        guard !isAnalyzing else {
            return "AlreadyAnalyzing"
        }
        
        voiceActivityCallback = callback
        
        if #available(iOS 14.0, *) {
            return startSoundClassificationDetection()
        } else {
            return "UnsupportedIOSVersion: Sound classification requires iOS 14.0 or later"
        }
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
            print("⚠️ Threshold must be between 0.0 and 1.0")
            return
        }
        voiceConfidenceThreshold = threshold
        print("🎚️ Threshold updated to \(threshold)")
    }
    
    // MARK: - Standalone Detection Implementation
    
    @available(iOS 14.0, *)
    private func startSoundClassificationDetection() -> String {
        do {
            // Create audio engine
            audioEngine = AVAudioEngine()
            guard let audioEngine = audioEngine else { return "SetupError" }
            
            // Setup audio engine and analyzer
            let inputNode = audioEngine.inputNode
            let recordingFormat = inputNode.outputFormat(forBus: 0)
            
            streamAnalyzer = SNAudioStreamAnalyzer(format: recordingFormat)
            soundClassifier = try SNClassifySoundRequest(classifierIdentifier: .version1)
            soundClassifier?.windowDuration = CMTimeMakeWithSeconds(windowDuration, preferredTimescale: 48000)
            soundClassifier?.overlapFactor = Double(overlapFactor)
            
            guard let classifier = soundClassifier else {
                return "SetupError: Failed to create sound classifier"
            }
            try streamAnalyzer?.add(classifier, withObserver: self)
            
            inputNode.installTap(onBus: 0, bufferSize: 8192, format: recordingFormat) { [weak self] buffer, time in
                guard let self = self else { return }
                let framePosition = time.sampleTime
                self.streamAnalyzer?.analyze(buffer, atAudioFramePosition: framePosition)
            }
            
            try audioEngine.start()
            isAnalyzing = true
            
            print("✅ Standalone voice activity detection started")
            return "Success"
            
        } catch {
            print("❌ Failed to start voice activity detection: \(error)")
            return "StartError: \(error.localizedDescription)"
        }
    }
    
    private func stopStandaloneDetection() -> String {
        // Stop audio engine
        audioEngine?.stop()
        audioEngine?.inputNode.removeTap(onBus: 0)
        streamAnalyzer?.removeAllRequests()
        
        
        // Clean up
        audioEngine = nil
        streamAnalyzer = nil
        soundClassifier = nil
        isAnalyzing = false
        voiceActivityCallback = nil
        
        print("🛑 Voice activity detection stopped")
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
        
        DispatchQueue.main.async {
            callback(hasVoiceActivity, maxVoiceConfidence)
        }
    }
}

// MARK: - SNResultsObserving
@available(iOS 13.0, *)
extension EnhancedSoundClassificationManager: SNResultsObserving {
    func request(_ request: SNRequest, didProduce result: SNResult) {
        guard let classificationResult = result as? SNClassificationResult else { return }
        processClassificationResults(classificationResult)
    }
    
    func request(_ request: SNRequest, didFailWithError error: Error) {
        print("❌ Sound classification request failed: \(error)")
        DispatchQueue.main.async { [weak self] in
            self?.voiceActivityCallback?(false, 0.0)
        }
    }
    
    func requestDidComplete(_ request: SNRequest) {
        print("✅ Sound classification request completed")
    }
}
