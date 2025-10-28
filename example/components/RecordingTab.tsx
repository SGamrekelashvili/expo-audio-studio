import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Alert,
  ScrollView,
  Switch,
} from 'react-native';
import Slider from '@react-native-community/slider';
import ExpoAudioStudio from 'expo-audio-studio';
import { AudioRecordingStateChangeEvent, VoiceActivityEvent, AudioMeteringEvent } from 'expo-audio-studio/types';

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
};

export default function RecordingTab() {
  const [hasPermission, setHasPermission] = useState(false);
  const [isRecording, setIsRecording] = useState(false);
  const [_, setRecordingPath] = useState<string>('');
  const [amplitude, setAmplitude] = useState<number>(-160);
  
  // VAD states
  const [vadEnabled, setVadEnabledState] = useState(false);
  const [vadThreshold, setVadThreshold] = useState(0.5);
  const [voiceDetected, setVoiceDetected] = useState(false);
  const [voiceConfidence, setVoiceConfidence] = useState(0);

  useEffect(() => {
    requestPermissions();
  }, []);

  useEffect(() => {
    const statusSub = ExpoAudioStudio.addRecorderStatusListener((event: AudioRecordingStateChangeEvent) => {
      setIsRecording(event.status === 'recording');
      
      if (event.status === 'error') {
        Alert.alert('Recording Error', 'Failed to record audio');
        setIsRecording(false);
      }
    });

    const amplitudeSub = ExpoAudioStudio.addRecorderAmplitudeListener((event: AudioMeteringEvent) => {
      setAmplitude(event.amplitude);
    });

    const vadSub = ExpoAudioStudio.addVoiceActivityListener((event: VoiceActivityEvent) => {
      setVoiceDetected(event.isVoiceDetected);
      setVoiceConfidence(event.confidence || 0);
    });

    return () => {
      statusSub.remove();
      amplitudeSub.remove();
      vadSub.remove();
    };
  }, []);

  const requestPermissions = async () => {
    try {
      const result = await ExpoAudioStudio.requestMicrophonePermission();
      setHasPermission(result.granted);
    } catch (error) {
      console.error('Permission error:', error);
    }
  };

  const handleStartRecording = useCallback(async () => {
    if (!hasPermission) {
      Alert.alert('Permission Required', 'Please grant microphone permission');
      return;
    }

    try {
      await ExpoAudioStudio.configureAudioSession({
        category: 'playAndRecord',
        mode: 'default',
        options: {
          defaultToSpeaker: true,
          allowBluetooth: true,
          allowBluetoothA2DP: true,
        },
      });
      await ExpoAudioStudio.activateAudioSession();

      const path = ExpoAudioStudio.startRecording();
      setRecordingPath(path);
      console.log('Recording started:', path);
    } catch (error) {
      console.error('Recording error:', error);
      Alert.alert('Error', 'Failed to start recording');
    }
  }, [hasPermission]);

  const handleStopRecording = useCallback(() => {
    try {
      const path = ExpoAudioStudio.stopRecording();
      setRecordingPath(path);
      setIsRecording(false);
      console.log('Recording stopped:', path);
      Alert.alert('Success', 'Recording saved successfully');
    } catch (error) {
      console.error('Stop error:', error);
      Alert.alert('Error', 'Failed to stop recording');
    }
  }, []);

  const handleToggleVAD = useCallback((value: boolean) => {
    try {
       ExpoAudioStudio.setVADEnabled(value);
      setVadEnabledState(value);
      
      if (!value) {
        setVoiceDetected(false);
        setVoiceConfidence(0);
      }
    } catch (error) {
      console.error('VAD toggle error:', error);
      Alert.alert('Error', 'Failed to toggle VAD');
    }
  }, []);

  const handleThresholdChange = useCallback((value: number) => {
    setVadThreshold(value);
    try {
       ExpoAudioStudio.setVoiceActivityThreshold(value);
    } catch (error) {
      console.error('Threshold error:', error);
    }
  }, []);


  const getAmplitudeHeight = () => {
    const normalized = Math.max(0, (amplitude + 160) / 160);
    return Math.max(4, normalized * 100);
  };

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <View style={styles.header}>
        <Text style={styles.title}>🎙️ Audio Recording</Text>
        <Text style={styles.subtitle}>
          Record audio with voice detection
        </Text>
      </View>

      {/* Recording Status */}
      <View style={styles.statusCard}>
        <View style={styles.statusRow}>
          <Text style={styles.statusLabel}>Permission:</Text>
          <Text style={[styles.statusValue, { color: hasPermission ? colors.success : colors.error }]}>
            {hasPermission ? '✅ Granted' : '❌ Denied'}
          </Text>
        </View>
        <View style={styles.statusRow}>
          <Text style={styles.statusLabel}>Recording:</Text>
          <Text style={[styles.statusValue, { color: isRecording ? colors.error : colors.textSecondary }]}>
            {isRecording ? '🔴 Active' : '⏹️ Stopped'}
          </Text>
        </View>
      </View>

      {/* Recording Control */}
      <View style={styles.recordingSection}>
        <TouchableOpacity
          style={[styles.recordButton, isRecording && styles.recordingActive]}
          onPress={isRecording ? handleStopRecording : handleStartRecording}
          disabled={!hasPermission}
        >
          <Text style={styles.recordButtonText}>
            {isRecording ? '⏹️ Stop Recording' : '🎙️ Start Recording'}
          </Text>
        </TouchableOpacity>

        {isRecording && (
          <View style={styles.amplitudeVisualization}>
            <Text style={styles.visualLabel}>Audio Level</Text>
            <View style={styles.amplitudeBar}>
              <View style={[styles.amplitudeFill, { height: getAmplitudeHeight() }]} />
            </View>
            <Text style={styles.amplitudeText}>{amplitude.toFixed(1)} dB</Text>
          </View>
        )}
      </View>

      {/* VAD Options */}
      <View style={styles.vadSection}>
        <Text style={styles.sectionTitle}>🧠 Voice Activity Detection</Text>
        
        <View style={styles.vadControl}>
          <Text style={styles.controlLabel}>Enable VAD</Text>
          <Switch
            value={vadEnabled}
            onValueChange={handleToggleVAD}
            trackColor={{ false: colors.border, true: colors.primary }}
            thumbColor={vadEnabled ? colors.success : colors.textSecondary}
          />
        </View>

        {vadEnabled && (
          <>
            <View style={styles.vadStatusCard}>
              <Text style={styles.vadStatusLabel}>Voice Status:</Text>
              <View style={[styles.vadStatusBadge, { backgroundColor: voiceDetected ? colors.success : colors.border }]}>
                <Text style={styles.vadStatusText}>
                  {voiceDetected ? '🗣️ Speaking' : '🤫 Silence'}
                </Text>
              </View>
              <Text style={styles.confidenceText}>
                Confidence: {(voiceConfidence * 100).toFixed(0)}%
              </Text>
            </View>

            <View style={styles.thresholdControl}>
              <Text style={styles.controlLabel}>
                Threshold: {vadThreshold.toFixed(2)}
              </Text>
              <Text style={styles.thresholdDescription}>
                Lower values detect quieter speech, higher values reduce false positives
              </Text>
              <Slider
                style={styles.slider}
                minimumValue={0.1}
                maximumValue={0.9}
                value={vadThreshold}
                onValueChange={handleThresholdChange}
                minimumTrackTintColor={colors.primary}
                maximumTrackTintColor={colors.border}
                thumbTintColor={colors.primary}
              />
            </View>
          </>
        )}
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  content: {
    padding: 20,
  },
  header: {
    alignItems: 'center',
    marginBottom: 24,
  },
  title: {
    fontSize: 28,
    fontWeight: 'bold',
    color: colors.text,
    marginBottom: 4,
  },
  subtitle: {
    fontSize: 14,
    color: colors.textSecondary,
  },
  statusCard: {
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
  statusRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  statusLabel: {
    fontSize: 14,
    fontWeight: '600',
    color: colors.text,
  },
  statusValue: {
    fontSize: 14,
    fontWeight: '600',
  },
  recordingSection: {
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
  recordButton: {
    paddingVertical: 16,
    paddingHorizontal: 32,
    borderRadius: 12,
    backgroundColor: colors.primary,
    width: '100%',
    alignItems: 'center',
  },
  recordingActive: {
    backgroundColor: colors.error,
  },
  recordButtonText: {
    color: colors.surface,
    fontSize: 18,
    fontWeight: '700',
  },
  amplitudeVisualization: {
    marginTop: 20,
    alignItems: 'center',
    width: '100%',
  },
  visualLabel: {
    fontSize: 14,
    fontWeight: '600',
    color: colors.text,
    marginBottom: 12,
  },
  amplitudeBar: {
    width: 200,
    height: 80,
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
  amplitudeText: {
    fontSize: 12,
    color: colors.textSecondary,
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
  sectionTitle: {
    fontSize: 20,
    fontWeight: '700',
    color: colors.text,
    marginBottom: 16,
  },
  vadControl: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 16,
    paddingBottom: 16,
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
  },
  controlLabel: {
    fontSize: 16,
    fontWeight: '600',
    color: colors.text,
  },
  vadStatusCard: {
    backgroundColor: colors.background,
    padding: 16,
    borderRadius: 12,
    marginBottom: 16,
    alignItems: 'center',
  },
  vadStatusLabel: {
    fontSize: 14,
    fontWeight: '600',
    color: colors.text,
    marginBottom: 8,
  },
  vadStatusBadge: {
    paddingVertical: 8,
    paddingHorizontal: 16,
    borderRadius: 20,
    marginBottom: 8,
  },
  vadStatusText: {
    fontSize: 16,
    fontWeight: '600',
    color: colors.text,
  },
  confidenceText: {
    fontSize: 12,
    color: colors.textSecondary,
  },
  thresholdControl: {
    marginBottom: 16,
  },
  thresholdDescription: {
    fontSize: 12,
    color: colors.textSecondary,
    marginTop: 4,
    marginBottom: 8,
  },
  slider: {
    width: '100%',
    height: 40,
    marginTop: 8,
  },
});
