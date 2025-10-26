import React, { useEffect, useState, useCallback } from 'react';
import {
  StyleSheet,
  SafeAreaView,
  View,
  Text,
  TouchableOpacity,
  Alert,
  StatusBar,
  ScrollView,
  Animated,
  Dimensions,
  Pressable,
} from 'react-native';
import Slider from '@react-native-community/slider';
import {
  startRecording,
  stopRecording,
  pauseRecording,
  resumeRecording,
  startPlaying,
  stopPlaying,
  pausePlayer,
  resumePlayer,
  setPlaybackSpeed,
  seekTo,
  getDuration,
  setVADEnabled,
  setVoiceActivityThreshold,
  isVADActive,
  isVADEnabled,
  isPaused,
  meterLevel,
  currentPosition,
  addRecorderStatusListener,
  addRecorderAmplitudeListener,
  addVoiceActivityListener,
  addPlayerStatusListener,
  requestMicrophonePermission,
  configureAudioSession,
  activateAudioSession,
  type AudioRecordingStateChangeEvent,
  type AudioMeteringEvent,
  type VoiceActivityEvent,
  type PlayerStatusChangeEvent,
} from 'expo-audio-studio';

const { width: screenWidth } = Dimensions.get('window');

const colors = {
  primary: '#6366F1',
  success: '#10B981',
  warning: '#F59E0B',
  error: '#EF4444',
  background: '#F8FAFC',
  surface: '#FFFFFF',
  text: '#1E293B',
  textSecondary: '#64748B',
  border: '#E2E8F0',
  accent: '#EC4899',
};

export default function AppImproved() {
  // Permission state
  const [hasPermission, setHasPermission] = useState<boolean>(false);

  // Recording states
  const [isRecording, setIsRecording] = useState(false);
  const [recordingPaused, setRecordingPaused] = useState(false);
  const [recordingPath, setRecordingPath] = useState<string>('');
  const [amplitude, setAmplitude] = useState<number>(-160);

  // Playback states
  const [isPlaying, setIsPlaying] = useState(false);
  const [playbackSpeed, setPlaybackSpeedState] = useState(1.0);
  const [playbackPosition, setPlaybackPosition] = useState(0);
  const [audioDuration, setAudioDuration] = useState(0);

  // VAD states
  const [vadEnabled, setVadEnabledState] = useState(false);
  const [vadActive, setVadActiveState] = useState(false);
  const [voiceDetected, setVoiceDetected] = useState(false);
  const [voiceConfidence, setVoiceConfidence] = useState(0);
  const [vadThreshold, setVadThreshold] = useState<number>(0.5);

  // UI state
  const [activeTab, setActiveTab] = useState<'record' | 'vad'>('record');
  const [pulseAnim] = useState(new Animated.Value(1));

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
    console.log('🎧 Setting up audio event listeners...');

    const statusSubscription = addRecorderStatusListener(
      (event: AudioRecordingStateChangeEvent) => {
        console.log('📊 Recording Status Changed:', event.status);

        // Update states based on status
        const newIsRecording = event.status === 'recording';
        const newIsPaused = event.status === 'paused';

        console.log('📊 State Update:', {
          status: event.status,
          newIsRecording,
          newIsPaused,
          previousIsRecording: isRecording,
          previousIsPaused: recordingPaused,
        });

        setIsRecording(newIsRecording);
        setRecordingPaused(newIsPaused);

        // Handle VAD state changes
        if (event.status === 'stopped') {
          console.log('🧠 Recording stopped - updating VAD active state');
          setVadActiveState(false);
        } else if (event.status === 'recording' && vadEnabled) {
          console.log('🧠 Recording started with VAD enabled - setting VAD active');
          setVadActiveState(true);
        }

        if (event.status === 'error') {
          console.error('❌ Recording Error:', event);
          Alert.alert('Recording Error', 'Recording failed. Please try again.');
          setIsRecording(false);
          setRecordingPaused(false);
          setVadActiveState(false);
        }
      }
    );

    const amplitudeSubscription = addRecorderAmplitudeListener((event: AudioMeteringEvent) => {
      setAmplitude(event.amplitude);
      // Only log every 10th amplitude to avoid spam
      if (Math.random() < 0.1) {
        console.log('🔊 Amplitude:', event.amplitude.toFixed(1), 'dB');
      }
    });

    const vadSubscription = addVoiceActivityListener((event: VoiceActivityEvent) => {
      console.log({ event });
      console.log('🧠 VAD Event:', {
        isVoiceDetected: event.isVoiceDetected,
        confidence: event.confidence?.toFixed(3),
        eventType: event.eventType,
        timestamp: new Date().toISOString(),
        stateDuration: event.stateDuration,
        audioLevel: event.audioLevel?.toFixed(1),
        isStateChange: event.isStateChange,
        previousState: event.previousState,
        platformData: event.platformData,
      });

      setVoiceDetected(event.isVoiceDetected);
      setVoiceConfidence(event.confidence || 0);

      // Update VAD active state based on actual events
      setVadActiveState(true);
    });

    const playerSubscription = addPlayerStatusListener((event: PlayerStatusChangeEvent) => {
      console.log('🎵 Player Status:', event.isPlaying ? 'Playing' : 'Stopped');
      setIsPlaying(event.isPlaying);
    });

    return () => {
      statusSubscription.remove();
      amplitudeSubscription.remove();
      vadSubscription.remove();
      playerSubscription.remove();
    };
  }, []);

  // Pulse animation for recording
  useEffect(() => {
    if (isRecording && !recordingPaused) {
      const pulse = Animated.loop(
        Animated.sequence([
          Animated.timing(pulseAnim, {
            toValue: 1.2,
            duration: 500,
            useNativeDriver: true,
          }),
          Animated.timing(pulseAnim, {
            toValue: 1,
            duration: 500,
            useNativeDriver: true,
          }),
        ])
      );
      pulse.start();
      return () => pulse.stop();
    }
  }, [isRecording, recordingPaused, pulseAnim]);

  // Set VAD threshold when permission is granted
  useEffect(() => {
    if (hasPermission) {
      try {
        setVoiceActivityThreshold(vadThreshold);
      } catch (error) {
        console.error('VAD threshold error:', error);
      }
    }
  }, [hasPermission, vadThreshold]);

  const handleStartRecording = useCallback(async () => {
    if (!hasPermission) {
      console.warn('🚫 Recording: No microphone permission');
      Alert.alert('Permission Required', 'Please grant microphone permission first.');
      return;
    }

    try {
      console.log('🎤️ Starting recording...');
      console.log('🧠 VAD State before recording:', { vadEnabled, vadActive });

      // Configure audio session for recording
      console.log('Configuring audio session for recording...');
      const sessionConfig = configureAudioSession({
        category: 'playAndRecord',
        mode: 'default',
        options: {
          defaultToSpeaker: true,
          allowBluetooth: true,
          allowBluetoothA2DP: true,
        },
      });
      console.log('Audio session config result:', sessionConfig);

      // Activate audio session
      console.log('Activating audio session...');
      const activationResult = activateAudioSession();
      console.log('Audio session activation result:', activationResult);

      const result = startRecording();
      console.log('🎤️ Recording started:', result);
      setRecordingPath(result);

      // Check if VAD should be active
      if (vadEnabled) {
        console.log('🧠 VAD is enabled - should start automatically with recording');
      }
    } catch (error) {
      console.error('❌ Start recording error:', error);
      Alert.alert('Error', 'Failed to start recording');
    }
  }, [hasPermission, vadEnabled, vadActive]);

  const handleStopRecording = useCallback(() => {
    try {
      console.log('🛑 Stopping recording...');
      console.log('🧠 VAD State before stopping:', { vadEnabled, vadActive });

      const result = stopRecording();
      console.log('🛑 Recording stopped:', result);

      setRecordingPath(result);
      setIsRecording(false);
      setRecordingPaused(false);

      // VAD should auto-stop with recording
      if (vadEnabled) {
        console.log('🧠 VAD should auto-stop with recording');
        setVadActiveState(false);
      }
    } catch (error) {
      console.error('❌ Stop recording error:', error);
      Alert.alert('Error', 'Failed to stop recording');
    }
  }, [vadEnabled, vadActive]);

  const handleToggleVAD = useCallback(() => {
    if (!hasPermission) {
      console.warn('🚫 VAD Toggle: No microphone permission');
      Alert.alert('Permission Required', 'Please grant microphone permission first.');
      return;
    }

    try {
      const newVadState = !vadEnabled;
      console.log('🧠 Toggling VAD:', { from: vadEnabled, to: newVadState });

      const result = setVADEnabled(newVadState);
      console.log('🧠 VAD Toggle Result:', result);

      setVadEnabledState(newVadState);

      if (!newVadState) {
        console.log('🧠 VAD Disabled - Clearing voice state');
        setVoiceDetected(false);
        setVoiceConfidence(0);
        setVadActiveState(false);
      } else {
        console.log('🧠 VAD Enabled - Waiting for voice events...');
      }
    } catch (error) {
      console.error('❌ Toggle VAD error:', error);
      Alert.alert('Error', 'Failed to toggle voice activity detection');
    }
  }, [hasPermission, vadEnabled]);

  const handlePlayRecording = useCallback(async () => {
    if (!recordingPath) {
      Alert.alert('No Recording', 'Please record something first.');
      return;
    }

    try {
      // Configure audio session for playback
      console.log('Configuring audio session for playback...');
      const sessionConfig = configureAudioSession({
        category: 'playback',
        mode: 'default',
        options: {
          defaultToSpeaker: true,
          allowBluetooth: true,
          allowBluetoothA2DP: true,
        },
      });
      console.log('Audio session config result:', sessionConfig);

      // Activate audio session
      const activationResult = await activateAudioSession();
      console.log('Audio session activation result:', activationResult);

      const duration = getDuration(recordingPath);
      setAudioDuration(duration);
      startPlaying(recordingPath);
    } catch (error) {
      console.error('Playback error:', error);
      Alert.alert('Error', 'Failed to start playback');
    }
  }, [recordingPath]);

  const getAmplitudeHeight = () => {
    const normalizedAmplitude = Math.max(0, (amplitude + 160) / 160);
    return Math.max(4, normalizedAmplitude * 100);
  };

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle='dark-content' backgroundColor={colors.background} />

      <View style={styles.header}>
        <Text style={styles.title}>🎙️ Audio Studio Pro</Text>
        <Text style={styles.subtitle}>Professional Audio Recording & VAD</Text>

        <View style={styles.tabContainer}>
          <TouchableOpacity
            style={[styles.tab, activeTab === 'record' && styles.activeTab]}
            onPress={() => setActiveTab('record')}
          >
            <Text style={[styles.tabText, activeTab === 'record' && styles.activeTabText]}>
              🎙️ Recording
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.tab, activeTab === 'vad' && styles.activeTab]}
            onPress={() => setActiveTab('vad')}
          >
            <Text style={[styles.tabText, activeTab === 'vad' && styles.activeTabText]}>
              🧠 Voice Detection
            </Text>
          </TouchableOpacity>
        </View>
      </View>

      <ScrollView style={styles.content} showsVerticalScrollIndicator={false}>
        {/* Status Cards */}
        <View style={styles.statusGrid}>
          <View style={styles.statusCard}>
            <Text style={styles.statusLabel}>Permission</Text>
            <View
              style={[
                styles.statusBadge,
                { backgroundColor: hasPermission ? colors.success : colors.error },
              ]}
            >
              <Text style={styles.statusText}>{hasPermission ? '✓' : '✗'}</Text>
            </View>
          </View>

          <View style={styles.statusCard}>
            <Text style={styles.statusLabel}>Recording</Text>
            <View
              style={[
                styles.statusBadge,
                {
                  backgroundColor: isRecording
                    ? recordingPaused
                      ? colors.warning
                      : colors.success
                    : colors.textSecondary,
                },
              ]}
            >
              <Text style={styles.statusText}>
                {isRecording ? (recordingPaused ? '⏸️' : '🔴') : '⏹️'}
              </Text>
            </View>
          </View>
        </View>

        {activeTab === 'record' ? (
          <View style={styles.tabContent}>
            {/* Recording Controls */}
            <View style={styles.controlSection}>
              <Text
                onPress={() => {
                  if (isRecording) {
                    handleStopRecording();
                  } else {
                    handleStartRecording();
                  }
                }}
                style={styles.recordButtonText}
              >
                {isRecording ? 'Recording' : recordingPaused ? 'Paused' : 'Record'}
              </Text>
              {/* Amplitude Visualization */}
              {isRecording && !recordingPaused && (
                <View style={styles.amplitudeContainer}>
                  <Text style={styles.sectionTitle}>Audio Level</Text>
                  <View style={styles.amplitudeBar}>
                    <View style={[styles.amplitudeFill, { height: getAmplitudeHeight() }]} />
                  </View>
                  <Text style={styles.amplitudeValue}>{amplitude.toFixed(1)} dB</Text>
                </View>
              )}
              {isRecording && (
                <View style={styles.recordingActions}>
                  <TouchableOpacity
                    style={[styles.actionButton, styles.pauseButton]}
                    onPress={() => (recordingPaused ? resumeRecording() : pauseRecording())}
                  >
                    <Text style={styles.actionButtonText}>
                      {recordingPaused ? '▶️ Resume' : '⏸️ Pause'}
                    </Text>
                  </TouchableOpacity>
                </View>
              )}
            </View>

            {/* Playback Section */}
            {recordingPath && (
              <View style={styles.playbackSection}>
                <Text style={styles.sectionTitle}>🎵 Playback</Text>

                <TouchableOpacity
                  style={[styles.button, styles.playButton]}
                  onPress={isPlaying ? stopPlaying : handlePlayRecording}
                >
                  <Text style={styles.buttonText}>{isPlaying ? '⏹️ Stop' : '▶️ Play'}</Text>
                </TouchableOpacity>

                {audioDuration > 0 && (
                  <View style={styles.seekContainer}>
                    <Text style={styles.timeText}>
                      {Math.floor(playbackPosition / 60)}:
                      {(playbackPosition % 60).toFixed(0).padStart(2, '0')}
                    </Text>
                    <Slider
                      style={styles.seekBar}
                      minimumValue={0}
                      maximumValue={audioDuration}
                      value={playbackPosition}
                      onValueChange={value => {
                        seekTo(value);
                        setPlaybackPosition(value);
                      }}
                      minimumTrackTintColor={colors.primary}
                      maximumTrackTintColor={colors.border}
                      thumbTintColor={colors.primary}
                    />
                    <Text style={styles.timeText}>
                      {Math.floor(audioDuration / 60)}:
                      {(audioDuration % 60).toFixed(0).padStart(2, '0')}
                    </Text>
                  </View>
                )}
              </View>
            )}
          </View>
        ) : (
          /* VAD Tab */
          <View style={styles.tabContent}>
            {/* Debug Panel */}
            <View style={styles.debugPanel}>
              <Text style={styles.debugTitle}>🔍 Debug Info</Text>
              <View style={styles.debugRow}>
                <Text style={styles.debugLabel}>VAD Enabled:</Text>
                <Text
                  style={[styles.debugValue, { color: vadEnabled ? colors.success : colors.error }]}
                >
                  {vadEnabled ? '✅ YES' : '❌ NO'}
                </Text>
              </View>
              <View style={styles.debugRow}>
                <Text style={styles.debugLabel}>VAD Active:</Text>
                <Text
                  style={[
                    styles.debugValue,
                    { color: vadActive ? colors.success : colors.warning },
                  ]}
                >
                  {vadActive ? '🟢 ACTIVE' : '🟡 INACTIVE'}
                </Text>
              </View>
              <View style={styles.debugRow}>
                <Text style={styles.debugLabel}>Recording:</Text>
                <Text
                  style={[
                    styles.debugValue,
                    { color: isRecording ? colors.success : colors.textSecondary },
                  ]}
                >
                  {isRecording ? '🎙️ YES' : '⏹️ NO'}
                </Text>
              </View>
              <View style={styles.debugRow}>
                <Text style={styles.debugLabel}>Voice Detected:</Text>
                <Text
                  style={[
                    styles.debugValue,
                    { color: voiceDetected ? colors.success : colors.textSecondary },
                  ]}
                >
                  {voiceDetected ? '🗣️ YES' : '🤫 NO'}
                </Text>
              </View>
              <View style={styles.debugRow}>
                <Text style={styles.debugLabel}>Confidence:</Text>
                <Text style={styles.debugValue}>{(voiceConfidence * 100).toFixed(1)}%</Text>
              </View>
            </View>

            <View style={styles.vadSection}>
              <Text style={styles.sectionTitle}>🧠 Voice Activity Detection</Text>

              {vadActive && (
                <View style={styles.voiceIndicator}>
                  <View
                    style={[
                      styles.voiceStatus,
                      {
                        backgroundColor: voiceDetected ? colors.success : colors.border,
                      },
                    ]}
                  >
                    <Text style={styles.voiceStatusText}>
                      {voiceDetected ? '🗣️ Voice Detected' : '🤫 Silence'}
                    </Text>
                  </View>

                  <View style={styles.confidenceContainer}>
                    <Text style={styles.confidenceLabel}>
                      Confidence: {(voiceConfidence * 100).toFixed(1)}%
                    </Text>
                    <View style={styles.confidenceBar}>
                      <View
                        style={[
                          styles.confidenceFill,
                          {
                            width: `${voiceConfidence * 100}%`,
                            backgroundColor:
                              voiceConfidence > 0.7
                                ? colors.success
                                : voiceConfidence > 0.4
                                  ? colors.warning
                                  : colors.error,
                          },
                        ]}
                      />
                    </View>
                  </View>
                </View>
              )}

              <TouchableOpacity
                style={[styles.button, vadEnabled ? styles.stopButton : styles.startButton]}
                onPress={handleToggleVAD}
                disabled={!hasPermission}
              >
                <Text style={styles.buttonText}>
                  {vadEnabled ? '⏹️ Disable VAD' : '🧠 Enable VAD'}
                </Text>
              </TouchableOpacity>

              <View style={styles.thresholdContainer}>
                <Text style={styles.thresholdLabel}>
                  Detection Threshold: {vadThreshold.toFixed(2)}
                </Text>
                <Slider
                  style={styles.thresholdSlider}
                  minimumValue={0.1}
                  maximumValue={0.9}
                  value={vadThreshold}
                  onValueChange={setVadThreshold}
                  minimumTrackTintColor={colors.primary}
                  maximumTrackTintColor={colors.border}
                  thumbTintColor={colors.primary}
                />
              </View>

              {/* Instructions */}
              <View style={styles.instructionPanel}>
                <Text style={styles.instructionTitle}>💡 How to Test VAD:</Text>
                <Text style={styles.instructionText}>
                  1. Enable VAD above{'\n'}
                  2. Start recording{'\n'}
                  3. Speak into microphone{'\n'}
                  4. Check console logs for VAD events{'\n'}
                  5. Watch debug panel for real-time status
                </Text>
              </View>
            </View>
          </View>
        )}
      </ScrollView>
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
    backgroundColor: colors.surface,
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
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
    marginBottom: 20,
  },
  tabContainer: {
    flexDirection: 'row',
    backgroundColor: colors.border,
    borderRadius: 12,
    padding: 4,
  },
  tab: {
    flex: 1,
    paddingVertical: 12,
    paddingHorizontal: 20,
    borderRadius: 8,
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
  statusGrid: {
    flexDirection: 'row',
    gap: 12,
    marginBottom: 20,
  },
  statusCard: {
    flex: 1,
    backgroundColor: colors.surface,
    padding: 16,
    borderRadius: 12,
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
    fontSize: 14,
    fontWeight: '600',
    color: colors.text,
  },
  statusBadge: {
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 12,
  },
  statusText: {
    color: colors.surface,
    fontWeight: '600',
    fontSize: 12,
  },
  amplitudeContainer: {
    backgroundColor: colors.surface,
    padding: 20,
    borderRadius: 16,
    marginBottom: 20,
    alignItems: 'center',
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
    backgroundColor: colors.primary,
    borderRadius: 8,
    minHeight: 4,
  },
  amplitudeValue: {
    fontSize: 14,
    color: colors.textSecondary,
    fontWeight: '500',
  },
  tabContent: {
    flex: 1,
  },
  controlSection: {
    alignItems: 'center',
    marginBottom: 30,
  },
  recordButton: {
    width: 80,
    height: 80,
    borderRadius: 40,
    justifyContent: 'center',
    alignItems: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.2,
    shadowRadius: 8,
    elevation: 8,
  },
  recordButtonText: {
    fontSize: 32,
  },
  recordingActions: {
    marginTop: 20,
  },
  actionButton: {
    paddingVertical: 12,
    paddingHorizontal: 24,
    borderRadius: 8,
    alignItems: 'center',
  },
  pauseButton: {
    backgroundColor: colors.warning,
  },
  actionButtonText: {
    color: colors.surface,
    fontSize: 16,
    fontWeight: '600',
  },
  playbackSection: {
    backgroundColor: colors.surface,
    padding: 20,
    borderRadius: 16,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  button: {
    paddingVertical: 16,
    paddingHorizontal: 24,
    borderRadius: 12,
    alignItems: 'center',
    marginBottom: 16,
  },
  startButton: {
    backgroundColor: colors.primary,
  },
  stopButton: {
    backgroundColor: colors.error,
  },
  playButton: {
    backgroundColor: colors.success,
  },
  buttonText: {
    color: colors.surface,
    fontSize: 16,
    fontWeight: '600',
  },
  seekContainer: {
    flexDirection: 'row',
    alignItems: 'center',
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
  vadSection: {
    backgroundColor: colors.surface,
    padding: 20,
    borderRadius: 16,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  voiceIndicator: {
    marginBottom: 20,
  },
  voiceStatus: {
    padding: 16,
    borderRadius: 12,
    alignItems: 'center',
    marginBottom: 16,
  },
  voiceStatusText: {
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
  thresholdContainer: {
    marginTop: 20,
  },
  thresholdLabel: {
    fontSize: 14,
    fontWeight: '600',
    color: colors.text,
    marginBottom: 8,
    textAlign: 'center',
  },
  thresholdSlider: {
    width: '100%',
    height: 40,
  },
  // Debug panel styles
  debugPanel: {
    backgroundColor: colors.surface,
    padding: 16,
    borderRadius: 12,
    marginBottom: 16,
    borderLeftWidth: 4,
    borderLeftColor: colors.accent,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  debugTitle: {
    fontSize: 16,
    fontWeight: '700',
    color: colors.text,
    marginBottom: 12,
  },
  debugRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  debugLabel: {
    fontSize: 14,
    color: colors.textSecondary,
    fontWeight: '500',
  },
  debugValue: {
    fontSize: 14,
    fontWeight: '600',
    color: colors.text,
  },
  instructionPanel: {
    backgroundColor: colors.background,
    padding: 16,
    borderRadius: 12,
    marginTop: 16,
    borderWidth: 1,
    borderColor: colors.border,
  },
  instructionTitle: {
    fontSize: 14,
    fontWeight: '700',
    color: colors.text,
    marginBottom: 8,
  },
  instructionText: {
    fontSize: 13,
    color: colors.textSecondary,
    lineHeight: 18,
  },
});
