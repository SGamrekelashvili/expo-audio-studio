package expo.modules.audiostudio.recorder

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

interface AudioRecorderProvider {
    // MARK: - Core Recording Methods
    fun startRecording(context: Context, argument: RecordArgument): Boolean
    fun stopRecording(): Boolean
    fun pauseRecording(): Boolean
    fun resumeRecording(): Boolean
    fun isRecording(): Boolean
    fun isPaused(): Boolean
    fun getCurrentAmplitude(): Float?
    fun recorderStatus(): StateFlow<RecorderInnerState>
    fun recorderTimeElapsed(): StateFlow<RecorderProgress>
    fun recorderMetrics(): StateFlow<RecorderMetrics>
    fun releaseRecorder()
    
    // MARK: - Voice Activity Detection Methods
    fun startVoiceActivityDetection(): String
    fun stopVoiceActivityDetection(): String
    fun setVoiceActivityThreshold(threshold: Float): String
    fun isVoiceActivityDetectionActive(): Boolean
}
