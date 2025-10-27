import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  FlatList,
  TouchableOpacity,
  Alert,
} from 'react-native';
import Slider from '@react-native-community/slider';
import {
  startPlaying,
  stopPlaying,
  pausePlayer,
  resumePlayer,
  seekTo,
  getDuration,
  addPlayerStatusListener,
  listRecordings,
  configureAudioSession,
  activateAudioSession,
  type PlayerStatusChangeEvent,
  getCurrentPosition,
  getCurrentMeterLevel,
  getPlayerStatus,

} from 'expo-audio-studio';
import { AudioRecording } from './types';

const colors = {
  primary: '#6366F1',
  success: '#10B981',
  background: '#F8FAFC',
  surface: '#FFFFFF',
  text: '#1E293B',
  textSecondary: '#64748B',
  border: '#E2E8F0',
  error: '#EF4444',
};

export default function AudioPlayer() {
  const [recordings, setRecordings] = useState<AudioRecording[]>([]);
  const [selectedRecording, setSelectedRecording] = useState<AudioRecording | null>(null);
  const [isPlaying, setIsPlaying] = useState(false);
  const [isPaused, setIsPaused] = useState(false);
  const [playbackPosition, setPlaybackPosition] = useState(0);
  const [currentDuration, setCurrentDuration] = useState(0);

  useEffect(() => {
    loadRecordings();
  }, []);

  useEffect(() => {
    const subscription = addPlayerStatusListener((event: PlayerStatusChangeEvent) => {
      setIsPlaying(event.isPlaying);
      
      if (event.didJustFinish) {
        setIsPlaying(false);
        setIsPaused(false);
        setPlaybackPosition(0);
      }
    });

    return () => subscription.remove();
  }, []);

  useEffect(() => {
    let interval: NodeJS.Timeout | null = null;

    if (isPlaying && !isPaused) {
      interval = setInterval(() => {
        try {
          const position = getCurrentPosition();
          
          setPlaybackPosition(position);
        } catch (error) {
          console.error('Error getting position:', error);
        }
      }, 100); // Update every 100ms for smooth animation
    }

    return () => {
      if (interval) {
        clearInterval(interval);
      }
    };
  }, [isPlaying, isPaused]);

  const loadRecordings = useCallback(async () => {
    try {
      const files = listRecordings();
      
      const recordings: AudioRecording[] = files.map((file) => {
        return {
          path: file.path,
          name: file.name,
          duration: file.duration,
          createdAt: new Date(file.lastModified),
        };
      });

      setRecordings(recordings.reverse()); // Most recent first
    } catch (error) {
      console.error('Error loading recordings:', error);
      Alert.alert('Error', 'Failed to load recordings');
    }
  }, []);

  const handleSelectRecording = useCallback(async (recording: AudioRecording) => {
    try {
      // Stop current playback if any
      if (isPlaying || isPaused) {
        stopPlaying();
        setIsPlaying(false);
      }

      setSelectedRecording(recording);
      setPlaybackPosition(0);
      setIsPaused(false);

      // Get accurate duration
      const duration = getDuration(recording.path);
      setCurrentDuration(duration);
    } catch (error) {
      console.error('Error selecting recording:', error);
      Alert.alert('Error', 'Failed to load recording');
    }
  }, [isPlaying, isPaused]);

  const handlePlayPause = useCallback(async () => {
    if (!selectedRecording) {
      Alert.alert('No Recording', 'Please select a recording first');
      return;
    }

    try {
      if (!isPlaying && !isPaused) {
        // Start playback
        await configureAudioSession({
          category: 'playback',
          mode: 'default',
          options: {
            defaultToSpeaker: true,
            allowBluetooth: true,
            allowBluetoothA2DP: true,
          },
        });
        await activateAudioSession();
        
        startPlaying(selectedRecording.path);
        setIsPlaying(true);
      } else if (isPlaying) {
        // Pause
        pausePlayer();
        setIsPaused(true);
        setIsPlaying(false);
      } else if (isPaused) {
        // Resume
        resumePlayer();
        setIsPaused(false);
        setIsPlaying(true);
      }
    } catch (error) {
      console.error('Playback error:', error);
      Alert.alert('Error', 'Failed to control playback');
    }
  }, [selectedRecording, isPlaying, isPaused]);

  const handleStop = useCallback(() => {
    try {
      stopPlaying();
      setIsPlaying(false);
      setIsPaused(false);
      setPlaybackPosition(0);
    } catch (error) {
      console.error('Stop error:', error);
    }
  }, []);

  const handleSeek = useCallback((value: number) => {
    // Only allow seeking if we have a selected recording and valid duration
    if (!selectedRecording || currentDuration <= 0) {
      console.warn('Cannot seek: No audio loaded or invalid duration');
      return;
    }

    try {
      seekTo(value);
      setPlaybackPosition(value);
    } catch (error) {
      console.error('Seek error:', error);
      Alert.alert('Seek Error', 'Failed to seek in audio. Make sure audio is loaded.');
    }
  }, [selectedRecording, currentDuration]);

  const formatTime = (seconds: number): string => {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  const renderRecording = ({ item }: { item: AudioRecording }) => {
    const isSelected = selectedRecording?.path === item.path;
    
    return (
      <TouchableOpacity
        style={[styles.recordingItem, isSelected && styles.recordingItemSelected]}
        onPress={() => handleSelectRecording(item)}
      >
        <View style={styles.recordingInfo}>
          <Text style={styles.recordingName} numberOfLines={1}>
            {item.name}
          </Text>
          <Text style={styles.recordingDuration}>
            {formatTime(item.duration)}
          </Text>
        </View>
        {isSelected && (
          <View style={styles.playingIndicator}>
            <Text style={styles.playingText}>
              {isPlaying ? '▶️' : isPaused ? '⏸️' : '⏹️'}
            </Text>
          </View>
        )}
      </TouchableOpacity>
    );
  };

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>🎵 Audio Library</Text>
        <TouchableOpacity style={styles.refreshButton} onPress={loadRecordings}>
          <Text style={styles.refreshText}>🔄 Refresh</Text>
        </TouchableOpacity>
      </View>

      {recordings.length === 0 ? (
        <View style={styles.emptyState}>
          <Text style={styles.emptyIcon}>🎙️</Text>
          <Text style={styles.emptyText}>No recordings yet</Text>
          <Text style={styles.emptySubtext}>
            Go to the Recording tab to create your first audio
          </Text>
        </View>
      ) : (
        <>
          <FlatList
            data={recordings}
            renderItem={renderRecording}
            keyExtractor={(item) => item.path}
            style={styles.list}
            contentContainerStyle={styles.listContent}
          />

          {selectedRecording && (
            <View style={styles.playerControls}>
              <Text style={styles.nowPlaying} numberOfLines={1}>
                {selectedRecording.name}
              </Text>
              
              <View style={styles.seekContainer}>
                <Text style={styles.timeText}>{formatTime(playbackPosition)}</Text>
                <Slider
                  style={styles.seekBar}
                  minimumValue={0}
                  maximumValue={currentDuration}
                  value={playbackPosition}
                  onValueChange={handleSeek}
                  minimumTrackTintColor={colors.primary}
                  maximumTrackTintColor={colors.border}
                  thumbTintColor={colors.primary}
                  disabled={!selectedRecording || currentDuration <= 0}
                />
                <Text style={styles.timeText}>{formatTime(currentDuration)}</Text>
              </View>

              <View style={styles.controlButtons}>
                <TouchableOpacity
                  style={[styles.controlButton, styles.playButton]}
                  onPress={handlePlayPause}
                >
                  <Text style={styles.buttonText}>
                    {isPlaying ? '⏸️ Pause' : isPaused ? '▶️ Resume' : '▶️ Play'}
                  </Text>
                </TouchableOpacity>

                {(isPlaying || isPaused) && (
                  <TouchableOpacity
                    style={[styles.controlButton, styles.stopButton]}
                    onPress={handleStop}
                  >
                    <Text style={styles.buttonText}>⏹️ Stop</Text>
                  </TouchableOpacity>
                )}
              </View>
            </View>
          )}
        </>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 20,
    backgroundColor: colors.surface,
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    color: colors.text,
  },
  refreshButton: {
    paddingVertical: 8,
    paddingHorizontal: 12,
    backgroundColor: colors.primary,
    borderRadius: 8,
  },
  refreshText: {
    color: colors.surface,
    fontSize: 14,
    fontWeight: '600',
  },
  emptyState: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 40,
  },
  emptyIcon: {
    fontSize: 64,
    marginBottom: 16,
  },
  emptyText: {
    fontSize: 20,
    fontWeight: '600',
    color: colors.text,
    marginBottom: 8,
  },
  emptySubtext: {
    fontSize: 14,
    color: colors.textSecondary,
    textAlign: 'center',
  },
  list: {
    flex: 1,
  },
  listContent: {
    padding: 16,
  },
  recordingItem: {
    backgroundColor: colors.surface,
    padding: 16,
    borderRadius: 12,
    marginBottom: 12,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  recordingItemSelected: {
    borderWidth: 2,
    borderColor: colors.primary,
  },
  recordingInfo: {
    flex: 1,
  },
  recordingName: {
    fontSize: 16,
    fontWeight: '600',
    color: colors.text,
    marginBottom: 4,
  },
  recordingDuration: {
    fontSize: 14,
    color: colors.textSecondary,
  },
  playingIndicator: {
    marginLeft: 12,
  },
  playingText: {
    fontSize: 24,
  },
  playerControls: {
    backgroundColor: colors.surface,
    padding: 20,
    borderTopWidth: 1,
    borderTopColor: colors.border,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: -2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 8,
  },
  nowPlaying: {
    fontSize: 16,
    fontWeight: '600',
    color: colors.text,
    marginBottom: 16,
    textAlign: 'center',
  },
  seekContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 16,
  },
  seekBar: {
    flex: 1,
    marginHorizontal: 12,
    height: 40,
  },
  timeText: {
    fontSize: 12,
    color: colors.textSecondary,
    fontWeight: '500',
    minWidth: 40,
    textAlign: 'center',
  },
  controlButtons: {
    flexDirection: 'row',
    justifyContent: 'center',
    gap: 12,
  },
  controlButton: {
    flex: 1,
    paddingVertical: 14,
    borderRadius: 12,
    alignItems: 'center',
  },
  playButton: {
    backgroundColor: colors.success,
  },
  stopButton: {
    backgroundColor: colors.error,
  },
  buttonText: {
    color: colors.surface,
    fontSize: 16,
    fontWeight: '600',
  },
});
