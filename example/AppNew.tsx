import React, { useEffect, useState, useRef } from 'react';
import {
  StyleSheet,
  SafeAreaView,
  View,
  Text,
  TouchableOpacity,
  Alert,
  StatusBar,
} from 'react-native';
import Slider from '@react-native-community/slider';
import {
  // Recording functions
  startRecording,
  stopRecording,
  pauseRecording,
  resumeRecording,
  
  // Playback functions
  startPlaying,
  stopPlaying,
  pausePlayer,
  resumePlayer,
  setPlaybackSpeed,
  seekTo,
  getDuration,
  
  // VAD functions
  setVADEnabled,
  setVoiceActivityThreshold,
  
  // VAD properties
  isVADActive,
  isVADEnabled,
  
  // Audio session functions
  configureAudioSession,
  activateAudioSession,
  
  // Event listeners
  addRecorderStatusListener,
  addRecorderAmplitudeListener,
  addVoiceActivityListener,
  addPlayerStatusListener,
  
  // Permissions
  requestMicrophonePermission,
  
  // Types
  type AudioRecordingStateChangeEvent,
  type AudioMeteringEvent,
  type VoiceActivityEvent,
  type PlayerStatusChangeEvent,
} from 'expo-audio-studio';

// Modern color palette
const colors = {
  primary: '#6366F1',
  success: '#10B981',
  warning: '#F59E0B',
  error: '#EF4444',
  background: '#F8FAFC',
  surface: '#FFFFFF',
  text: '#1E293B',
  textSecondary: '#64748B',
  secondary: '#64748B',
  border: '#E2E8F0',
};

export default function App() {
  // Permission state
  const [hasPermission, setHasPermission] = useState<boolean>(false);
  
  // Recording states
  const [isRecording, setIsRecording] = useState(false);
  const [isPaused, setIsPaused] = useState(false);
  const [recordingPath, setRecordingPath] = useState<string>('');
  const [amplitude, setAmplitude] = useState<number>(-160);
  
  // Playback states
  const [isPlaying, setIsPlaying] = useState(false);
  const [playbackSpeed, setPlaybackSpeed] = useState(1.0);
  const [playbackPosition, setPlaybackPosition] = useState(0);
  const [audioDuration, setAudioDuration] = useState(0);
  const [isPrepared, setIsPrepared] = useState(false);
  
  // VAD states
  const [vadEnabled, setVadEnabledState] = useState(false);
  const [vadActive, setVadActiveState] = useState(false);
  const [voiceDetected, setVoiceDetected] = useState(false);
  const [voiceConfidence, setVoiceConfidence] = useState(0);
  const [vadThreshold, setVadThreshold] = useState<number>(0.3); // Lower threshold for normal conversation
  
  // UI state
  const [activeTab, setActiveTab] = useState<'record' | 'vad'>('record');

  // Request microphone permission
  useEffect(() => {
    const requestPermission = async () => {
      try {
        const result = await requestMicrophonePermission();
        setHasPermission(result.granted);
        if (!result.granted) {
          Alert.alert('Permission Required', 'Microphone permission is required for recording.');
        }
      } catch (error) {
        console.error('Permission error:', error);
        Alert.alert('Error', 'Failed to request microphone permission');
      }
    };

    requestPermission();
  }, []);

  // Setup event listeners
  useEffect(() => {
    const statusSubscription = addRecorderStatusListener((event: AudioRecordingStateChangeEvent) => {
      console.log('Recording status changed:', event.status);
      setIsRecording(event.status === 'recording');
      setIsPaused(event.status === 'paused');
      
      // Update VAD active state based on recording status
      if (event.status === 'recording' && vadEnabled) {
        setVadActiveState(true);
      } else if (event.status === 'stopped') {
        setVadActiveState(false);
      }
      
      // Handle recording errors
      if (event.status === 'error') {
        Alert.alert('Recording Error', 'Recording failed. Please try again.');
        setIsRecording(false);
        setIsPaused(false);
        setVadActiveState(false);
      }
    });

    const amplitudeSubscription = addRecorderAmplitudeListener((event: AudioMeteringEvent) => {
      setAmplitude(event.amplitude);
    });

    const vadSubscription = addVoiceActivityListener((event: VoiceActivityEvent) => {
      console.log('🧠 VAD Event:', {
        type: event.eventType,
        voiceDetected: event.isVoiceDetected,
        confidence: event.confidence,
        timestamp: new Date().toISOString()
      });
      
      setVoiceDetected(event.isVoiceDetected);
      setVoiceConfidence(event.confidence);
    });

    const playerSubscription = addPlayerStatusListener((event: PlayerStatusChangeEvent) => {
      setIsPlaying(event.isPlaying);
      console.log('Player status:', event.isPlaying ? 'playing' : 'stopped');
    });

    return () => {
      statusSubscription.remove();
      amplitudeSubscription.remove();
      vadSubscription.remove();
      playerSubscription.remove();
    };
  }, []);

  // Set VAD threshold when permission is granted
  useEffect(() => {
    if (hasPermission) {
      try {
        const result = setVoiceActivityThreshold(vadThreshold);
        console.log('VAD threshold set to', vadThreshold, ':', result);
      } catch (error) {
        console.error('VAD threshold error:', error);
      }
    }
  }, [hasPermission, vadThreshold]);

  // Recording functions
  const handleStartRecording = async () => {
    if (!hasPermission) {
      Alert.alert('Permission Required', 'Please grant microphone permission first.');
      return;
    }

    try {
      // Configure audio session for recording (iOS fix)
      console.log('Configuring audio session for recording...');
      
      await configureAudioSession({
        category: 'playAndRecord',
        mode: 'default',
        options: {
          allowBluetooth: true,
          defaultToSpeaker: true,
        }
      });
      
      console.log('Activating audio session...');
      await activateAudioSession();
      
      // Small delay to ensure audio session is ready
      await new Promise(resolve => setTimeout(resolve, 200));
      
      const result = startRecording();
      console.log('Recording started:', result);
      setRecordingPath(result);
    } catch (error) {
      console.error('Start recording error:', error);
      Alert.alert('Error', 'Failed to start recording: ' + error);
    }
  };

  const handleStopRecording = () => {
    try {
      const result = stopRecording();
      console.log('Recording stopped:', result);
      setRecordingPath(result);
      setIsRecording(false);
      setIsPaused(false);
    } catch (error) {
      console.error('Stop recording error:', error);
      Alert.alert('Error', 'Failed to stop recording');
    }
  };

  const handlePauseRecording = () => {
    try {
      const result = pauseRecording();
      console.log('Recording paused:', result);
    } catch (error) {
      console.error('Pause recording error:', error);
      Alert.alert('Error', 'Failed to pause recording');
    }
  };

  const handleResumeRecording = () => {
    try {
      const result = resumeRecording();
      console.log('Recording resumed:', result);
    } catch (error) {
      console.error('Resume recording error:', error);
      Alert.alert('Error', 'Failed to resume recording');
    }
  };

  // VAD functions
  const handleToggleVAD = () => {
    if (!hasPermission) {
      Alert.alert('Permission Required', 'Please grant microphone permission first.');
      return;
    }

    try {
      const newVadState = !vadEnabled;
      console.log('🧠 Setting VAD enabled to:', newVadState);
      
      const result = setVADEnabled(newVadState);
      console.log('🧠 VAD result:', result);
      
      // Update local state
      setVadEnabledState(newVadState);
      
      if (!newVadState) {
        setVoiceDetected(false);
        setVoiceConfidence(0);
        setVadActiveState(false);
      }
      
      console.log('🧠 VAD toggled successfully to:', newVadState);
    } catch (error) {
      console.error('Toggle VAD error:', error);
      Alert.alert('Error', 'Failed to toggle voice activity detection');
    }
  };

  // Playback functions
  const handlePlayRecording = async () => {
    if (!recordingPath) {
      Alert.alert('No Recording', 'Please record something first.');
      return;
    }

    try {
      console.log('Preparing player for:', recordingPath);
      
      // Configure audio session for playback
      await configureAudioSession({
        category: 'playAndRecord', // Keep playAndRecord as requested
        mode: 'default',
        options: {
          allowBluetooth: true,
          defaultToSpeaker: true,
        }
      });
      
      // Get duration first
      const duration = getDuration(recordingPath);
      console.log('Audio duration:', duration);
      setAudioDuration(duration);
      
      // Start playing
      const result = startPlaying(recordingPath);
      console.log('Playback started:', result);
      
      if (result.includes('Error') || result.includes('Exception')) {
        throw new Error(result);
      }
    } catch (error) {
      console.error('Playback error:', error);
      Alert.alert('Error', 'Failed to start playback: ' + error);
    }
  };

  const handleStopPlayback = () => {
    try {
      const result = stopPlaying();
      console.log('Playback stopped:', result);
      setPlaybackPosition(0);
    } catch (error) {
      console.error('Stop playback error:', error);
      Alert.alert('Error', 'Failed to stop playback');
    }
  };

  const handlePausePlayback = () => {
    try {
      const result = pausePlayer();
      console.log('Playback paused:', result);
    } catch (error) {
      console.error('Pause playback error:', error);
      Alert.alert('Error', 'Failed to pause playback');
    }
  };

  const handleResumePlayback = () => {
    try {
      const result = resumePlayer();
      console.log('Playback resumed:', result);
    } catch (error) {
      console.error('Resume playback error:', error);
      Alert.alert('Error', 'Failed to resume playback');
    }
  };

  const handleSeek = (position: number) => {
    try {
      const result = seekTo(position);
      console.log('Seek result:', result);
      setPlaybackPosition(position);
    } catch (error) {
      console.error('Seek error:', error);
    }
  };

  // Get amplitude bar height for visualization
  const getAmplitudeHeight = () => {
    const normalizedAmplitude = Math.max(0, (amplitude + 160) / 160);
    return Math.max(4, normalizedAmplitude * 100);
  };

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="dark-content" backgroundColor={colors.background} />
      
      <View style={styles.header}>
        <Text style={styles.title}>🎙️ Audio Studio</Text>
        <Text style={styles.subtitle}>Recording + Voice Activity Detection</Text>
        
        {/* Tab Navigation */}
        <View style={styles.tabContainer}>
          <TouchableOpacity 
            style={[styles.tab, activeTab === 'record' && styles.activeTab]} 
            onPress={() => setActiveTab('record')}
          >
            <Text style={[styles.tabText, activeTab === 'record' && styles.activeTabText]}>🎙️ Recording</Text>
          </TouchableOpacity>
          <TouchableOpacity 
            style={[styles.tab, activeTab === 'vad' && styles.activeTab]} 
            onPress={() => setActiveTab('vad')}
          >
            <Text style={[styles.tabText, activeTab === 'vad' && styles.activeTabText]}>🧠 Voice Detection</Text>
          </TouchableOpacity>
        </View>
      </View>

      <View style={styles.content}>
        {/* Permission Status */}
        <View style={styles.statusCard}>
          <Text style={styles.statusLabel}>Microphone Permission</Text>
          <View style={[styles.statusIndicator, { backgroundColor: hasPermission ? colors.success : colors.error }]}>
            <Text style={styles.statusText}>{hasPermission ? '✓ Granted' : '✗ Denied'}</Text>
          </View>
        </View>

        {/* Recording Status */}
        <View style={styles.statusCard}>
          <Text style={styles.statusLabel}>Recording Status</Text>
          <View style={[
            styles.statusIndicator, 
            { backgroundColor: isRecording ? (isPaused ? colors.warning : colors.success) : colors.textSecondary }
          ]}>
            <Text style={styles.statusText}>
              {isRecording ? (isPaused ? '⏸️ Paused' : '🔴 Recording') : '⏹️ Stopped'}
            </Text>
          </View>
        </View>

        {/* Amplitude Visualization */}
        {isRecording && !isPaused && (
          <View style={styles.amplitudeContainer}>
            <Text style={styles.amplitudeLabel}>Audio Level</Text>
            <View style={styles.amplitudeBar}>
              <View 
                style={[
                  styles.amplitudeFill, 
                  { 
                    height: getAmplitudeHeight(),
                    backgroundColor: colors.primary 
                  }
                ]} 
              />
            </View>
            <Text style={styles.amplitudeValue}>{amplitude.toFixed(1)} dB</Text>
          </View>
        )}

        {/* Recording Path */}
        {recordingPath && (
          <View style={styles.pathCard}>
            <Text style={styles.pathLabel}>Last Recording</Text>
            <Text style={styles.pathText} numberOfLines={2}>
              {recordingPath.split('/').pop()}
            </Text>
          </View>
        )}

        {/* Tab Content */}
        {activeTab === 'record' ? (
          <>
            {/* Control Buttons */}
            <View style={styles.buttonContainer}>
              {!isRecording ? (
                <TouchableOpacity 
                  style={[styles.button, styles.startButton]} 
                  onPress={handleStartRecording}
                  disabled={!hasPermission}
                >
                  <Text style={styles.buttonText}>🎙️ Start Recording</Text>
                </TouchableOpacity>
              ) : (
                <View style={styles.recordingControls}>
                  <TouchableOpacity 
                    style={[styles.button, styles.stopButton]} 
                    onPress={handleStopRecording}
                  >
                    <Text style={styles.buttonText}>⏹️ Stop</Text>
                  </TouchableOpacity>
                  
                  <TouchableOpacity 
                    style={[styles.button, isPaused ? styles.resumeButton : styles.pauseButton]} 
                    onPress={isPaused ? handleResumeRecording : handlePauseRecording}
                  >
                    <Text style={styles.buttonText}>
                      {isPaused ? '▶️ Resume' : '⏸️ Pause'}
                    </Text>
                  </TouchableOpacity>
                </View>
              )}
            </View>

            {/* Playback Controls */}
            {recordingPath && (
              <View style={styles.playbackSection}>
                <Text style={styles.sectionTitle}>🎵 Playback Controls</Text>
                
                <View style={styles.playbackControls}>
                  {!isPlaying ? (
                    <TouchableOpacity 
                      style={[styles.button, styles.playButton]} 
                      onPress={handlePlayRecording}
                    >
                      <Text style={styles.buttonText}>▶️ Play Recording</Text>
                    </TouchableOpacity>
                  ) : (
                    <View style={styles.recordingControls}>
                      <TouchableOpacity 
                        style={[styles.button, styles.stopButton]} 
                        onPress={handleStopPlayback}
                      >
                        <Text style={styles.buttonText}>⏹️ Stop</Text>
                      </TouchableOpacity>
                      
                      <TouchableOpacity 
                        style={[styles.button, styles.pauseButton]} 
                        onPress={handlePausePlayback}
                      >
                        <Text style={styles.buttonText}>⏸️ Pause</Text>
                      </TouchableOpacity>
                    </View>
                  )}
                </View>

                {/* Seek Bar */}
                {audioDuration > 0 && (
                  <View style={styles.seekContainer}>
                    <Text style={styles.timeText}>
                      {Math.floor(playbackPosition / 60)}:{(playbackPosition % 60).toFixed(0).padStart(2, '0')}
                    </Text>
                    <Slider
                      style={styles.seekBar}
                      minimumValue={0}
                      maximumValue={audioDuration}
                      value={playbackPosition}
                      onValueChange={handleSeek}
                      minimumTrackTintColor={colors.primary}
                      maximumTrackTintColor={colors.border}
                      thumbTintColor={colors.primary}
                    />
                    <Text style={styles.timeText}>
                      {Math.floor(audioDuration / 60)}:{(audioDuration % 60).toFixed(0).padStart(2, '0')}
                    </Text>
                  </View>
                )}
              </View>
            )}
          </>
        ) : (
          /* VAD Tab Content */
          <View style={styles.vadContainer}>
            <Text style={styles.sectionTitle}>🧠 Voice Activity Detection</Text>
            
            {/* VAD Status */}
            <View style={styles.statusCard}>
              <Text style={styles.statusLabel}>VAD Status</Text>
              <View style={[styles.statusIndicator, { backgroundColor: vadActive ? colors.success : colors.textSecondary }]}>
                <Text style={styles.statusText}>{vadActive ? '🟢 Active' : '⚫ Inactive'}</Text>
              </View>
            </View>

            {/* Voice Detection Indicator */}
            {vadActive && (
              <View style={styles.voiceIndicatorContainer}>
                <View style={[styles.voiceIndicator, { backgroundColor: voiceDetected ? colors.success : colors.border }]}>
                  <Text style={styles.voiceIndicatorText}>
                    {voiceDetected ? '🗣️ Voice Detected' : '🤫 Silence'}
                  </Text>
                </View>
                
                {/* Confidence Bar */}
                <View style={styles.confidenceContainer}>
                  <Text style={styles.confidenceLabel}>Confidence: {(voiceConfidence * 100).toFixed(1)}%</Text>
                  <View style={styles.confidenceBar}>
                    <View 
                      style={[styles.confidenceFill, { 
                        width: `${voiceConfidence * 100}%`,
                        backgroundColor: voiceConfidence > 0.7 ? colors.success : voiceConfidence > 0.4 ? colors.warning : colors.error
                      }]} 
                    />
                  </View>
                </View>
              </View>
            )}

            {/* VAD Controls */}
            <View style={styles.buttonContainer}>
              <TouchableOpacity 
                style={[styles.button, vadEnabled ? styles.stopButton : styles.startButton]} 
                onPress={handleToggleVAD}
                disabled={!hasPermission}
              >
                <Text style={styles.buttonText}>
                  {vadEnabled ? '⏹️ Disable VAD' : '🧠 Enable VAD'}
                </Text>
              </TouchableOpacity>
            </View>
            
            {/* VAD Status Display */}
            {vadEnabled && (
              <View style={styles.statusCard}>
                <Text style={styles.statusTitle}>📊 VAD Status</Text>
                <View style={styles.statusRow}>
                  <Text style={styles.statusRowLabel}>Preference:</Text>
                  <Text style={[styles.statusValue, { color: vadEnabled ? colors.success : colors.error }]}>
                    {vadEnabled ? '✅ Enabled' : '❌ Disabled'}
                  </Text>
                </View>
                <View style={styles.statusRow}>
                  <Text style={styles.statusRowLabel}>Currently Active:</Text>
                  <Text style={[styles.statusValue, { color: vadActive ? colors.success : colors.warning }]}>
                    {vadActive ? '🟢 Processing' : '🟡 Waiting'}
                  </Text>
                </View>
                <View style={styles.statusRow}>
                  <Text style={styles.statusRowLabel}>Recording:</Text>
                  <Text style={[styles.statusValue, { color: isRecording ? colors.success : colors.secondary }]}>
                    {isRecording ? '🎤 Active' : '⏸️ Inactive'}
                  </Text>
                </View>
              </View>
            )}

            {/* VAD Instructions */}
            <View style={styles.instructionCard}>
              <Text style={styles.instructionText}>
                💡 Enable VAD to automatically detect voice activity during recordings.
                {vadEnabled ? (
                  isRecording ? ' Currently monitoring voice activity.' : ' Will auto-start with next recording.'
                ) : ' Toggle above to enable automatic voice detection.'}
              </Text>
            </View>
          </View>
        )}
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  header: {
    padding: 20,
    alignItems: 'center',
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
    backgroundColor: colors.surface,
  },
  title: {
    fontSize: 28,
    fontWeight: 'bold',
    color: colors.text,
    marginBottom: 4,
  },
  subtitle: {
    fontSize: 16,
    color: colors.textSecondary,
    marginBottom: 16,
  },
  tabContainer: {
    flexDirection: 'row',
    backgroundColor: colors.border,
    borderRadius: 8,
    padding: 4,
  },
  tab: {
    flex: 1,
    paddingVertical: 8,
    paddingHorizontal: 16,
    borderRadius: 6,
    alignItems: 'center',
  },
  activeTab: {
    backgroundColor: colors.primary,
  },
  tabText: {
    fontSize: 14,
    fontWeight: '600',
    color: colors.textSecondary,
  },
  activeTabText: {
    color: colors.surface,
  },
  content: {
    flex: 1,
    padding: 20,
  },
  statusCard: {
    backgroundColor: colors.surface,
    padding: 16,
    borderRadius: 12,
    marginBottom: 16,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  statusLabel: {
    fontSize: 16,
    fontWeight: '600',
    color: colors.text,
  },
  statusIndicator: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 20,
  },
  statusText: {
    color: colors.surface,
    fontWeight: '600',
    fontSize: 14,
  },
  amplitudeContainer: {
    backgroundColor: colors.surface,
    padding: 20,
    borderRadius: 12,
    marginBottom: 16,
    alignItems: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  amplitudeLabel: {
    fontSize: 16,
    fontWeight: '600',
    color: colors.text,
    marginBottom: 12,
  },
  amplitudeBar: {
    width: 200,
    height: 100,
    backgroundColor: colors.border,
    borderRadius: 8,
    justifyContent: 'flex-end',
    overflow: 'hidden',
    marginBottom: 8,
  },
  amplitudeFill: {
    width: '100%',
    borderRadius: 8,
    minHeight: 4,
  },
  amplitudeValue: {
    fontSize: 14,
    color: colors.textSecondary,
    fontWeight: '500',
  },
  pathCard: {
    backgroundColor: colors.surface,
    padding: 16,
    borderRadius: 12,
    marginBottom: 20,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  pathLabel: {
    fontSize: 14,
    fontWeight: '600',
    color: colors.textSecondary,
    marginBottom: 8,
  },
  pathText: {
    fontSize: 16,
    color: colors.text,
    fontFamily: 'monospace',
  },
  buttonContainer: {
    marginTop: 'auto',
    paddingBottom: 20,
  },
  recordingControls: {
    flexDirection: 'row',
    gap: 12,
  },
  button: {
    flex: 1,
    paddingVertical: 16,
    paddingHorizontal: 24,
    borderRadius: 12,
    alignItems: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  startButton: {
    backgroundColor: colors.primary,
  },
  stopButton: {
    backgroundColor: colors.error,
  },
  pauseButton: {
    backgroundColor: colors.warning,
  },
  resumeButton: {
    backgroundColor: colors.success,
  },
  buttonText: {
    color: colors.surface,
    fontSize: 18,
    fontWeight: '600',
  },
  // Playback styles
  playbackSection: {
    marginTop: 20,
    backgroundColor: colors.surface,
    padding: 20,
    borderRadius: 12,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  sectionTitle: {
    fontSize: 18,
    fontWeight: '700',
    color: colors.text,
    marginBottom: 16,
    textAlign: 'center',
  },
  playbackControls: {
    marginBottom: 16,
  },
  playButton: {
    backgroundColor: colors.success,
  },
  seekContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 12,
  },
  seekBar: {
    flex: 1,
    marginHorizontal: 12,
    height: 40,
  },
  timeText: {
    fontSize: 14,
    color: colors.textSecondary,
    fontWeight: '500',
    minWidth: 40,
    textAlign: 'center',
  },
  // VAD styles
  vadContainer: {
    flex: 1,
  },
  voiceIndicatorContainer: {
    backgroundColor: colors.surface,
    padding: 20,
    borderRadius: 12,
    marginBottom: 16,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  voiceIndicator: {
    padding: 16,
    borderRadius: 8,
    alignItems: 'center',
    marginBottom: 16,
  },
  voiceIndicatorText: {
    fontSize: 16,
    fontWeight: '600',
    color: colors.text,
  },
  confidenceContainer: {
    alignItems: 'center',
  },
  confidenceLabel: {
    fontSize: 14,
    fontWeight: '600',
    color: colors.text,
    marginBottom: 8,
  },
  confidenceBar: {
    width: '100%',
    height: 8,
    backgroundColor: colors.border,
    borderRadius: 4,
    overflow: 'hidden',
  },
  confidenceFill: {
    height: '100%',
    borderRadius: 4,
  },
  instructionCard: {
    backgroundColor: colors.surface,
    padding: 16,
    borderRadius: 12,
    marginTop: 16,
    borderLeftWidth: 4,
    borderLeftColor: colors.primary,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  instructionText: {
    fontSize: 14,
    color: colors.textSecondary,
    lineHeight: 20,
  },
  statusTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: colors.text,
    marginBottom: 12,
  },
  statusRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  statusRowLabel: {
    fontSize: 14,
    color: colors.textSecondary,
    fontWeight: '500',
  },
  statusValue: {
    fontSize: 14,
    fontWeight: '600',
  },
});
