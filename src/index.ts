import { EventSubscription, Platform } from 'expo-modules-core';
import ExpoAudioStudioModule from './ExpoAudioStudioModule';
import {
  AudioMeteringEvent,
  AudioRecordingStateChangeEvent,
  PlayerStatusChangeEvent,
  PermissionResponse,
  VoiceActivityEvent,
  AudioSessionConfig,
  AudioAmplitudeResult,
  PlayerStatusResult,
} from './ExpoAudioStudio.types';

export type {
  // Event types
  AudioMeteringEvent,
  AudioRecordingStateChangeEvent,
  PlayerStatusChangeEvent,
  VoiceActivityEvent,
  ExpoAudioStudioModuleEvents,

  // Permission types
  PermissionResponse,
  PermissionStatus,

  // Playback types
  PlayerStatusResult,
  PlaybackSpeed,

  // Recording types
  RecordingOptions,
  RecordingState,
  RecordingStatus,

  // VAD types
  VADResult,
  VADInitResult,
  VADSensitivity,
  VADMode,

  // Audio Amplitude types
  AudioAmplitudeResult,

  // Audio Session types
  AudioSessionCategory,
  AudioSessionMode,
  AudioSessionOptions,
  AudioSessionConfig,

  // Utility types
  FilePathResult,
  DurationResult,
  OperationResult,
  ErrorResponse,
  AudioHandlerError,
} from './ExpoAudioStudio.types';

// Export the native module
export { default } from './ExpoAudioStudioModule';

// Event Listeners

/**
 * Adds a listener for player status changes (play, pause, stop, finish)
 *
 * @param listener - Callback function to handle player status events
 * @returns EventSubscription object to manage the listener
 *
 * @example
 * ```typescript
 * const subscription = addPlayerStatusListener((event) => {
 *   console.log('Player status:', event.isPlaying ? 'Playing' : 'Stopped');
 *   if (event.didJustFinish) {
 *     console.log('Playback completed');
 *   }
 * });
 *
 * // Don't forget to remove the listener when done
 * subscription.remove();
 * ```
 */
export function addPlayerStatusListener(
  listener: (_event: PlayerStatusChangeEvent) => void
): EventSubscription {
  return ExpoAudioStudioModule.addListener('onPlayerStatusChange', listener);
}

/**
 * Adds a listener for audio amplitude/metering events during recording
 *
 * @param listener - Callback function to handle amplitude events
 * @returns EventSubscription object to manage the listener
 *
 * @example
 * ```typescript
 * const subscription = addRecorderAmplitudeListener((event) => {
 *   console.log('Audio level:', event.amplitude, 'dB');
 *   // Update UI meter based on amplitude
 *   updateAudioMeter(event.amplitude);
 * });
 *
 * // Remove listener when recording stops
 * subscription.remove();
 * ```
 */
export function addRecorderAmplitudeListener(
  listener: (_event: AudioMeteringEvent) => void
): EventSubscription {
  return ExpoAudioStudioModule.addListener('onRecorderAmplitude', listener);
}

/**
 * Adds a listener for recording status changes (recording, paused, stopped, etc.)
 *
 * @param listener - Callback function to handle recording status events
 * @returns EventSubscription object to manage the listener
 *
 * @example
 * ```typescript
 * const subscription = addRecorderStatusListener((event) => {
 *   switch (event.status) {
 *     case 'recording':
 *       console.log('Recording started');
 *       break;
 *     case 'paused':
 *       console.log('Recording paused');
 *       break;
 *     case 'stopped':
 *       console.log('Recording stopped');
 *       break;
 *   }
 * });
 * ```
 */
export function addRecorderStatusListener(
  listener: (_event: AudioRecordingStateChangeEvent) => void
): EventSubscription {
  return ExpoAudioStudioModule.addListener('onRecorderStatusChange', listener);
}

/**
 * Adds a listener for voice activity detection events
 *
 * @param listener - Callback function to handle voice activity events
 * @returns EventSubscription object to manage the listener
 *
 * @example
 * ```typescript
 * const subscription = addVoiceActivityListener((event) => {
 *   if (event.isVoiceDetected) {
 *     console.log('Voice detected with confidence:', event.confidence);
 *   } else {
 *     console.log('Silence detected');
 *   }
 * });
 * ```
 */
export function addVoiceActivityListener(
  listener: (_event: VoiceActivityEvent) => void
): EventSubscription {
  return ExpoAudioStudioModule.addListener('onVoiceActivityDetected', listener);
}

// Playback Functions

/**
 * Prepares audio player without starting playback
 *
 * @param url - File path or URL to the audio file
 * @returns Success message or error description
 *
 * @example
 * ```typescript
 * const result = preparePlayer('/path/to/audio.wav');
 * if (result === 'prepared') {
 *   console.log('Player prepared successfully');
 *   // Now you can pause, seek, then resume
 *   pausePlayer();
 *   seekTo(30.0);
 *   resumePlayer();
 * } else {
 *   console.error('Prepare failed:', result);
 * }
 * ```
 */
export function preparePlayer(url: string): string {
  return ExpoAudioStudioModule.preparePlayer(url);
}

/**
 * Starts playing audio from the specified file path or URL
 *
 * @param url - File path or URL to the audio file
 * @returns Success message or error description
 *
 * @example
 * ```typescript
 * const result = startPlaying('/path/to/audio.wav');
 * if (result === 'playing') {
 *   console.log('Playback started successfully');
 * } else {
 *   console.error('Playback failed:', result);
 * }
 * ```
 */
export function startPlaying(url: string): string {
  return ExpoAudioStudioModule.startPlaying(url);
}

/**
 * Stops the current audio playback
 *
 * @returns Success message or error description
 *
 * @example
 * ```typescript
 * const result = stopPlaying();
 * console.log('Stop result:', result);
 * ```
 */
export function stopPlaying(): string {
  return ExpoAudioStudioModule.stopPlayer();
}

// Recording Functions

/**
 * Starts audio recording with optional custom directory
 *
 * @param directoryPath Optional custom directory path where recording will be saved
 * @returns File path where recording will be saved, or error message
 *
 * @example
 * ```typescript
 * // Record to default location
 * const result = startRecording();
 *
 * // Record to custom directory
 * const customResult = startRecording('/path/to/custom/dir/');
 * if (customResult.includes('recording_')) {
 *   console.log('Recording started, saving to:', customResult);
 * } else {
 *   console.error('Recording failed:', customResult);
 * }
 * ```
 */
export function startRecording(directoryPath?: string): string {
  return ExpoAudioStudioModule.startRecording(directoryPath);
}

/**
 * Stops the current recording session
 *
 * @returns File path of the completed recording, or error message
 *
 * @example
 * ```typescript
 * const filePath = stopRecording();
 * if (filePath.includes('recording_')) {
 *   console.log('Recording saved to:', filePath);
 *   // Now you can play it back
 *   startPlaying(filePath);
 * }
 * ```
 */
export function stopRecording(): string {
  return ExpoAudioStudioModule.stopRecording();
}

/**
 * Pauses the current recording session
 *
 * @returns Success message or error description
 *
 * @example
 * ```typescript
 * const result = pauseRecording();
 * if (result === 'paused') {
 *   console.log('Recording paused successfully');
 * } else {
 *   console.error('Failed to pause:', result);
 * }
 * ```
 */
export function pauseRecording(): string {
  return ExpoAudioStudioModule.pauseRecording();
}

/**
 * Resumes a paused recording session
 *
 * @returns Success message or error description
 *
 * @example
 * ```typescript
 * const result = resumeRecording();
 * if (result === 'resumed') {
 *   console.log('Recording resumed successfully');
 * } else {
 *   console.error('Failed to resume:', result);
 * }
 * ```
 */
export function resumeRecording(): string {
  return ExpoAudioStudioModule.resumeRecording();
}

// Playback Control

/**
 * Pauses the current audio playback
 *
 * @returns Success message or error description
 *
 * @example
 * ```typescript
 * const result = pausePlayer();
 * console.log('Pause result:', result);
 * ```
 */
export function pausePlayer(): string {
  return ExpoAudioStudioModule.pausePlayer();
}

/**
 * Resumes paused audio playback
 *
 * @returns Success message or error description
 *
 * @example
 * ```typescript
 * const result = resumePlayer();
 * console.log('Resume result:', result);
 * ```
 */
export function resumePlayer(): string {
  return ExpoAudioStudioModule.resumePlayer();
}

/**
 * Seeks to a specific position in the current audio
 *
 * @param position - Position in seconds to seek to
 * @returns Success message or error description
 *
 * @example
 * ```typescript
 * const result = seekTo(30.5); // Seek to 30.5 seconds
 * console.log('Seek result:', result);
 * ```
 */
export function seekTo(position: number): string {
  return ExpoAudioStudioModule.seekTo(position);
}

/**
 * Sets the playback speed multiplier
 *
 * @param speed - Speed multiplier (0.5 to 2.0)
 * @returns Success message or error description
 *
 * @example
 * ```typescript
 * const result = setPlaybackSpeed(1.5); // 1.5x speed
 * console.log('Speed change result:', result);
 * ```
 */
export function setPlaybackSpeed(speed: number): string {
  return ExpoAudioStudioModule.setPlaybackSpeed(speed.toString());
}

// Utility Functions

/**
 * Gets the file path of the last completed recording
 *
 * @returns File path of last recording, or null if none exists
 *
 * @example
 * ```typescript
 * const lastFile = lastRecording();
 * if (lastFile) {
 *   console.log('Last recording:', lastFile);
 *   startPlaying(lastFile);
 * }
 * ```
 */
export function lastRecording(): string | null {
  return ExpoAudioStudioModule.lastRecording();
}

// File Management

/**
 * Lists all audio recordings in a directory
 *
 * @param directoryPath Optional directory path to search. If not provided, uses default cache directory
 * @returns Array of recording file information objects
 *
 * @example
 * ```typescript
 * // List recordings in default directory
 * const recordings = listRecordings();
 *
 * // List recordings in custom directory
 * const customRecordings = listRecordings('/path/to/custom/dir/');
 *
 * recordings.forEach(file => {
 *   console.log(`File: ${file.name}, Size: ${file.size}, Duration: ${file.duration}s`);
 * });
 * ```
 */
export function listRecordings(directoryPath?: string): Array<{
  path: string;
  name: string;
  size: number;
  lastModified: number;
  duration: number;
}> {
  return ExpoAudioStudioModule.listRecordings(directoryPath);
}

/**
 * Joins multiple audio files into a single file
 *
 * @param filePaths Array of file paths to join (minimum 2 files required)
 * @param outputPath Output file path for the joined audio
 * @returns Path to the joined audio file, or error message
 *
 * @example
 * ```typescript
 * const files = ['/path/to/recording1.wav', '/path/to/recording2.wav'];
 * const outputPath = '/path/to/joined_audio.wav';
 *
 * const result = joinAudioFiles(files, outputPath);
 * if (!result.startsWith('Error:')) {
 *   console.log('Files joined successfully:', result);
 * } else {
 *   console.error('Join failed:', result);
 * }
 * ```
 */
export function joinAudioFiles(filePaths: string[], outputPath: string): string {
  return ExpoAudioStudioModule.joinAudioFiles(filePaths, outputPath);
}

/**
 * Sets the amplitude update frequency for smooth animations
 *
 * @param frequencyHz Frequency in Hz (1-120 Hz). For 60 FPS animations, use 60 Hz
 * @returns Success message or error description
 *
 * @example
 * ```typescript
 * // Set to 60 Hz for smooth 60 FPS animations
 * const result = setAmplitudeUpdateFrequency(60);
 * console.log(result); // "Amplitude frequency set to 60 Hz"
 *
 * // Set to 30 Hz for battery-friendly updates
 * setAmplitudeUpdateFrequency(30);
 *
 * // Set to 120 Hz for ultra-smooth animations (higher CPU usage)
 * setAmplitudeUpdateFrequency(120);
 * ```
 */
export function setAmplitudeUpdateFrequency(frequencyHz: number): string {
  return ExpoAudioStudioModule.setAmplitudeUpdateFrequency(frequencyHz);
}

/**
 * Gets the duration of an audio file
 *
 * @param uri - File path or URI to the audio file
 * @returns Duration in seconds, or 0 if file not found/invalid
 *
 * @example
 * ```typescript
 * const duration = getDuration('/path/to/audio.wav');
 * console.log(`Audio duration: ${duration} seconds`);
 * ```
 */
export function getDuration(uri: string): number {
  return ExpoAudioStudioModule.getDuration(uri);
}

/**
 * Analyzes audio file and returns amplitude data for visualization bars
 *
 * @param fileUrl - File path or URI to the audio file
 * @param barsCount - Number of amplitude bars to generate (1-2048)
 * @returns AudioAmplitudeResult with amplitude data and metadata
 *
 * @example
 * ```typescript
 * const result = getAudioAmplitudes('/path/to/audio.wav', 64);
 * if (result.success) {
 *   console.log(`Generated ${result.barsCount} bars for ${result.duration}s audio`);
 *
 *   // Use amplitude data for visualization (dB values)
 *   result.amplitudes.forEach((amplitude, index) => {
 *     console.log(`Bar ${index}: ${amplitude.toFixed(1)} dB`);
 *   });
 *
 *   // Create waveform visualization (convert dB to height)
 *   const maxHeight = 100;
 *   const waveformBars = result.amplitudes.map(dB => {
 *     // Convert dB to normalized value (assuming -60dB to 0dB range)
 *     const normalized = Math.max(0, (dB + 60) / 60);
 *     return Math.round(normalized * maxHeight);
 *   });
 * } else {
 *   console.error('Analysis failed:', result.error);
 * }
 * ```
 */
export function getAudioAmplitudes(fileUrl: string, barsCount: number): AudioAmplitudeResult {
  return ExpoAudioStudioModule.getAudioAmplitudes(fileUrl, barsCount);
}

// Permissions

/**
 * Requests microphone permission from the user
 *
 * @returns Promise resolving to permission response
 *
 * @example
 * ```typescript
 * const permission = await requestMicrophonePermission();
 * if (permission.granted) {
 *   console.log('Microphone permission granted');
 *   // Now you can start recording
 *   startRecording();
 * } else {
 *   console.log('Permission denied:', permission.status);
 * }
 * ```
 */
export function requestMicrophonePermission(): Promise<PermissionResponse> {
  return ExpoAudioStudioModule.requestMicrophonePermission();
}

/**
 * Gets the current microphone permission status
 *
 * @returns Promise resolving to current permission status
 *
 * @example
 * ```typescript
 * const status = await getMicrophonePermissionStatus();
 * console.log('Permission status:', status.status);
 * console.log('Can ask again:', status.canAskAgain);
 * ```
 */
export function getMicrophonePermissionStatus(): Promise<PermissionResponse> {
  return ExpoAudioStudioModule.getMicrophonePermissionStatus();
}

// Voice Activity Detection

/**
 * Sets VAD enabled state - manages VAD lifecycle automatically
 *
 * @param enabled - Whether to enable VAD for current and future recordings
 * @returns Success message or error description
 *
 * @example
 * ```typescript
 * // Enable VAD - will start with recording or wait if not recording
 * const result = setVADEnabled(true);
 *
 * // Start recording - VAD will auto-start
 * startRecording();
 *
 * // Stop recording - VAD auto-stops but preference is kept
 * stopRecording();
 *
 * // Next recording - VAD will auto-start again
 * startRecording();
 *
 * // Disable VAD completely
 * setVADEnabled(false);
 * ```
 */
export function setVADEnabled(enabled: boolean): string {
  return ExpoAudioStudioModule.setVADEnabled(enabled);
}

/**
 * Sets the voice activity detection threshold
 *
 * @param threshold - Detection threshold (0.0 to 1.0)
 * @returns Success message or error description
 *
 * @example
 * ```typescript
 * const result = setVoiceActivityThreshold(0.7);
 * console.log(result); // "Success: Threshold set to 0.7"
 * ```
 */
export function setVoiceActivityThreshold(threshold: number): string {
  return ExpoAudioStudioModule.setVoiceActivityThreshold(threshold);
}

// Audio Session

/**
 * Configures the audio session with specified category, mode, and options
 *
 * @param config - Audio session configuration object
 * @returns Promise that resolves when configuration is complete
 *
 * @example
 * ```typescript
 * import { configureAudioSession } from 'expo-audio-handler';
 *
 * await configureAudioSession({
 *   category: 'playAndRecord',
 *   mode: 'default',
 *   options: {
 *     allowBluetooth: true,
 *     defaultToSpeaker: true
 *   }
 * });
 * console.log('Audio session configured');
 * ```
 */
export function configureAudioSession(config: AudioSessionConfig): Promise<void> {
  if (Platform.OS === 'ios') {
    return ExpoAudioStudioModule.configureAudioSession(config);
  }
  return Promise.resolve();
}

/**
 * Activates the audio session
 *
 * @returns Promise that resolves when activation is complete
 *
 * @example
 * ```typescript
 * import { activateAudioSession } from 'expo-audio-handler';
 *
 * await activateAudioSession();
 * console.log('Audio session activated');
 * ```
 */
export function activateAudioSession(): Promise<void> {
  if (Platform.OS === 'ios') {
    return ExpoAudioStudioModule.activateAudioSession();
  }
  return Promise.resolve();
}

/**
 * Deactivates the audio session
 *
 * @returns Promise that resolves when deactivation is complete
 *
 * @example
 * ```typescript
 * import { deactivateAudioSession } from 'expo-audio-handler';
 *
 * await deactivateAudioSession();
 * console.log('Audio session deactivated');
 * ```
 */
export function deactivateAudioSession(): Promise<void> {
  if (Platform.OS === 'ios') {
    return ExpoAudioStudioModule.deactivateAudioSession();
  }
  return Promise.resolve();
}

/**
 * Detailed player status information
 *
 * @example
 * ```typescript
 * const status = getPlayerStatus();
 * console.log(`Playing: ${status.isPlaying}, Duration: ${status.duration}s`);
 * ```
 */
export function getPlayerStatus(): PlayerStatusResult {
  return ExpoAudioStudioModule.playerStatus;
}

/**
 * Gets the current playback position in seconds
 *
 * @returns Current playback position in seconds, or 0.0 if no player is active
 *
 * @example
 * ```typescript
 * const position = getCurrentPosition();
 * console.log(`Current position: ${position}s`);
 *
 * // Use in an interval to track progress
 * const interval = setInterval(() => {
 *   const pos = getCurrentPosition();
 *   console.log(`Playing at ${pos}s`);
 * }, 1000);
 * ```
 */
export function getCurrentPosition(): number {
  return ExpoAudioStudioModule.currentPosition;
}

/**
 * Current audio level during recording (in dB)
 *
 * @example
 * ```typescript
 * const level = getCurrentMeterLevel();
 * console.log(`Audio level: ${level} dB`);
 * ```
 */
export function getCurrentMeterLevel(): number {
  return ExpoAudioStudioModule.meterLevel;
}

/**
 * Whether Voice Activity Detection is currently active (processing audio)
 *
 * @example
 * ```typescript
 * const isActive = getIsVADActive();
 * console.log('VAD currently active:', isActive);
 * ```
 */
export function getIsVADActive(): boolean {
  return ExpoAudioStudioModule.isVADActive;
}

/**
 * Whether VAD is enabled by user preference (will auto-start with recordings)
 *
 * @example
 * ```typescript
 * const isEnabled = getIsVADEnabled();
 * console.log('VAD preference enabled:', isEnabled);
 * ```
 */
export function getIsVADEnabled(): boolean {
  return ExpoAudioStudioModule.isVADEnabled;
}
