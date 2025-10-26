//
//  RecorderDelegate.swift
//  Pods
//
//  Created by Sandro Gamrekelashvili on 20.05.25.
//

import Foundation
import AVFoundation

protocol RecorderDelegateProtocol: AnyObject {
    func recorderDidFinishRecording(successfully: Bool)
    func recorderEncodeErrorDidOccur(error: Error?)
}

class RecorderDelegate: NSObject, AVAudioRecorderDelegate {
    weak var delegate: RecorderDelegateProtocol?
    
    init(delegate: RecorderDelegateProtocol?) {
        self.delegate = delegate
        super.init()
    }
    
    // MARK: - AVAudioRecorderDelegate methods
    public func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        print("[\(Date())] Audio recorder finished recording. Success: \(flag)")
        delegate?.recorderDidFinishRecording(successfully: flag)
    }
    
    public func audioRecorderEncodeErrorDidOccur(_ recorder: AVAudioRecorder, error: Error?) {
        print("[\(Date())] Audio recorder encode error: \(error?.localizedDescription ?? "unknown error")")
        delegate?.recorderEncodeErrorDidOccur(error: error)
    }
}
