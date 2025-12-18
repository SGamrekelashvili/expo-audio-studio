import Foundation
import AVFoundation

class AudioManager: NSObject, PlayerDelegateProtocol {
    
    private let stateLock = NSRecursiveLock()
    
    private var audioPlayer: AVAudioPlayer?
    
    private var currentPlaybackSpeed: Float = 1.0
    private var onPlayerStatusChange: ((Bool, Bool) -> Void)?
    
    private var playerDelegate: PlayerDelegate?
    

    
    func preparePlayer(path: String, sendPlayerStatusEvent: @escaping (Bool, Bool) -> Void) -> String? {
        self.onPlayerStatusChange = { [weak self] isPlaying, isFinished in
            sendPlayerStatusEvent(isPlaying, isFinished)
        }

        do {
            print("[\(Date())] preparePlayer: Preparing audio player for path: \(path)")
            
            guard let audioFileURL = URL(string: path) else {
                return "InvalidUrlException: Malformed URL"
            }
            
            if audioFileURL.isFileURL {
                if !FileManager.default.fileExists(atPath: audioFileURL.path) {
                    print("[\(Date())] preparePlayer: File not found at path: \(audioFileURL.path)")
                    return "InvalidUrlException: File not found at \(path)"
                }
            }
            
            if let existingPlayer = self.audioPlayer {
                print("[\(Date())] preparePlayer: Properly cleaning up existing player")
                existingPlayer.stop()
            }
            
            audioPlayer = try AVAudioPlayer(contentsOf: audioFileURL)
            
            guard let player = self.audioPlayer else {
                sendPlayerStatusEvent(false, false)
                return "PlaybackFailedException: Player is nil"
            }
            
            self.playerDelegate = PlayerDelegate(delegate: self)
            player.delegate = self.playerDelegate
            player.enableRate = true
            player.rate = currentPlaybackSpeed
            player.volume = 1.0
            player.currentTime = 0.0
            
            // PREPARE but DON'T START
            if !player.prepareToPlay() {
                print("[\(Date())] preparePlayer: prepareToPlay failed.")
                sendPlayerStatusEvent(false, false)
                self.audioPlayer = nil
                return "PlaybackFailedException: prepareToPlay failed"
            }
            
            print("[\(Date())] preparePlayer: Player prepared successfully, ready for playback")
            sendPlayerStatusEvent(false, false) // Not playing, not finished
            return "prepared"
            
        } catch {
            sendPlayerStatusEvent(false, false)
            self.audioPlayer = nil
            return "PLAYBACK_PREPARE_ERROR: \(error.localizedDescription)"
        }
    }

    func startPlayingAudio(path: String, sendPlayerStatusEvent: @escaping (Bool, Bool) -> Void) -> String? {
        if let existingPlayer = self.audioPlayer, existingPlayer.url?.path == path {
            print("[\(Date())] startPlayingAudio: Using already prepared player")
            self.onPlayerStatusChange = { [weak self] isPlaying, isFinished in
                sendPlayerStatusEvent(isPlaying, isFinished)
            }
            
            if existingPlayer.play() {
                sendPlayerStatusEvent(true, false)
                return "playing"
            } else {
                sendPlayerStatusEvent(false, false)
                return "PlaybackFailedException: play() failed on prepared player"
            }
        }
        
        let prepareResult = preparePlayer(path: path, sendPlayerStatusEvent: sendPlayerStatusEvent)
        if prepareResult != "prepared" {
            return prepareResult // Return prepare error
        }
        
        guard let player = self.audioPlayer else {
            sendPlayerStatusEvent(false, false)
            return "PlaybackFailedException: Player is nil after prepare"
        }
        
        if player.play() {
            sendPlayerStatusEvent(true, false)
            return "playing"
        } else {
            sendPlayerStatusEvent(false, false)
            return "PlaybackFailedException: play() returned false"
        }
        
        
    }
    
    func stopPlayingAudio() -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        
        guard let player = self.audioPlayer else {
            return false
        }

        player.stop()
        self.audioPlayer = nil
        self.playerDelegate = nil
        self.onPlayerStatusChange = nil
        return true
    }
    
    func pausePlayingAudio() -> String {
        stateLock.lock()
        defer { stateLock.unlock() }
        
        guard let player = self.audioPlayer else {
            return "NoPlayerException"
        }
        
        player.pause()
        onPlayerStatusChange?(false, false)
        return "paused"
    }
    
    func resumePlayingAudio() -> String {
        stateLock.lock()
        defer { stateLock.unlock() }
        
        guard let player = self.audioPlayer else {
            return "NoPlayerException"
        }
        
        if player.play() {
            onPlayerStatusChange?(true, false)
            return "playing"
        } else {
            onPlayerStatusChange?(false, false)
            return "PlaybackFailedException: resume failed"
        }
    }
    
    func setPlaybackSpeed(speed: Float) -> String {
        stateLock.lock()
        defer { stateLock.unlock() }
        
        guard let player = self.audioPlayer else {
            currentPlaybackSpeed = speed // Store for next playback
            return "NoPlayerException"
        }
        
        if player.enableRate {
            player.rate = speed
            currentPlaybackSpeed = speed
            return "success"
        } else {
            return "PlaybackFailedException: rate change not supported"
        }
    }
    
    func seekToTime(position: TimeInterval) -> String {
        stateLock.lock()
        defer { stateLock.unlock() }
        
        guard let player = self.audioPlayer else {
            return "NoPlayerException"
        }
        
        player.currentTime = min(max(0, position), player.duration)
        return "success"
    }
    
    func getPlayer() -> AVAudioPlayer? {
        stateLock.lock()
        defer { stateLock.unlock() }
        return audioPlayer
    }
    
    // MARK: - PlayerDelegateProtocol
    
    func playerDidFinishPlaying(successfully: Bool) {
        stateLock.lock()
        audioPlayer = nil
        let callback = self.onPlayerStatusChange
        self.onPlayerStatusChange = nil
        stateLock.unlock()
        
        if let cb = callback {
            DispatchQueue.main.async {
                cb(false, true)
            }
        }
    }
    
    func playerDecodeErrorDidOccur(error: Error?) {
        stateLock.lock()
        audioPlayer = nil
        let callback = self.onPlayerStatusChange
        self.onPlayerStatusChange = nil
        stateLock.unlock()
        
        if let cb = callback {
            DispatchQueue.main.async {
                cb(false, false)
            }
        }
        
        print("[\(Date())] Player decode error cleanup complete. Error: \(String(describing: error))")
    }
}
