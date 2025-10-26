//
//  PlayerDelegate.swift
//  Pods
//
//  Created by Sandro Gamrekelashvili on 20.05.25.
//

import Foundation
import AVFoundation

protocol PlayerDelegateProtocol: AnyObject {
    func playerDidFinishPlaying(successfully: Bool)
    func playerDecodeErrorDidOccur(error: Error?)
}

class PlayerDelegate: NSObject, AVAudioPlayerDelegate {
    weak var delegate: PlayerDelegateProtocol?
    
    init(delegate: PlayerDelegateProtocol?) {
        self.delegate = delegate
        super.init()
    }
    
    // MARK: - AVAudioPlayerDelegate methods
    public func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        print("[\(Date())] Audio player finished playing. Success: \(flag)")
        delegate?.playerDidFinishPlaying(successfully: flag)
    }
    
    public func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        print("[\(Date())] Audio player decode error: \(error?.localizedDescription ?? "unknown error")")
        delegate?.playerDecodeErrorDidOccur(error: error)
    }
}
