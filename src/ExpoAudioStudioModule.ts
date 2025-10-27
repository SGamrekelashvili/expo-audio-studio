import { NativeModule, requireNativeModule } from 'expo';

import type {
  ExpoAudioStudioModuleEvents,
  AudioAmplitudeResult,
  DurationResult,
  AudioSessionConfig,
  PermissionResponse,
} from './ExpoAudioStudio.types';

declare class ExpoAudioStudioModule extends NativeModule<ExpoAudioStudioModuleEvents> {
  // Recording Methods

  /**
   * Starts audio recording with optional custom directory
   *
   * @param directoryPath Optional custom directory path where recording will be saved
   * @returns File path where recording will be saved, or error message
   *
   * @example
   * ```typescript
   * // Record to default location
   * const result = ExpoAudioStudio.startRecording();
   *
   * // Record to custom directory
   * const customResult = ExpoAudioStudio.startRecording('/path/to/custom/dir/');
   * if (customResult.includes('recording_')) {
   *   console.log('Recording started, saving to:', customResult);
   * } else {
   *   console.error('Recording failed:', customResult);
   * }
   * ```
   */
  startRecording(_directoryPath?: string): string;

  /**
   * Stops the current recording session
   *
   * @returns File path of the completed recording, or error message
   *
   * @example
   * ```typescript
   * const filePath = ExpoAudioStudio.stopRecording();
   * if (filePath.includes('recording_')) {
   *   console.log('Recording saved to:', filePath);
   * }
   * ```
   */
  stopRecording(): string;

  /**
   * Pauses the current recording session
   *
   * @returns Success message or error description
   *
   * @example
   * ```typescript
   * const result = ExpoAudioStudio.pauseRecording();
   * console.log(result); // "paused" or error message
   * ```
   */
  pauseRecording(): string;

  /**
   * Resumes a paused recording session
   *
   * @returns Success message or error description
   *
   * @example
   * ```typescript
   * const result = ExpoAudioStudio.resumeRecording();
   * console.log(result); // "resumed" or error message
   * ```
   */
  resumeRecording(): string;

  /**
   * Gets the file path of the last recording
   *
   * @returns File path of the last recording, or null if no recording exists
   *
   * @example
   * ```typescript
   * const lastFile = ExpoAudioStudio.lastRecording();
   * if (lastFile) {
   *   console.log('Last recording:', lastFile);
   * }
   * ```
   */
  lastRecording(): string;

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
   * const recordings = ExpoAudioStudio.listRecordings();
   *
   * // List recordings in custom directory
   * const customRecordings = ExpoAudioStudio.listRecordings('/path/to/custom/dir/');
   *
   * recordings.forEach(file => {
   *   console.log(`File: ${file.name}, Size: ${file.size}, Duration: ${file.duration}s`);
   * });
   * ```
   */
  listRecordings(_directoryPath?: string): Array<{
    path: string;
    name: string;
    size: number;
    lastModified: number;
    duration: number;
  }>;

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
   * const result = ExpoAudioStudio.joinAudioFiles(files, outputPath);
   * if (!result.startsWith('Error:')) {
   *   console.log('Files joined successfully:', result);
   * } else {
   *   console.error('Join failed:', result);
   * }
   * ```
   */
  joinAudioFiles(_filePaths: string[], _outputPath: string): string;
  setAmplitudeUpdateFrequency(_frequencyHz: number): string;

  // Playback Methods

  /**
   * Prepares audio player without starting playback
   *
   * @param path - Absolute file path or URI to the audio file
   * @returns Success message or error description
   *
   * @example
   * ```typescript
   * const result = ExpoAudioStudio.preparePlayer('/path/to/audio.wav');
   * if (result === 'prepared') {
   *   console.log('Player prepared successfully');
   *   // Now you can pause, seek, then resume
   *   ExpoAudioStudio.pausePlayer();
   *   ExpoAudioStudio.seekTo(30.0);
   *   ExpoAudioStudio.resumePlayer();
   * }
   * ```
   */
  preparePlayer(_path: string): string;

  /**
   * Starts playing audio from the specified file path
   *
   * @param path - Absolute file path or URI to the audio file
   * @returns Success message or error description
   *
   * @example
   * ```typescript
   * const result = ExpoAudioStudio.startPlaying('/path/to/audio.wav');
   * if (result === 'playing') {
   *   console.log('Playback started successfully');
   * }
   * ```
   */
  startPlaying(_path: string): string;

  /**
   * Stops the current audio playback
   *
   * @returns Success status as boolean
   *
   * @example
   * ```typescript
   * const stopped = ExpoAudioStudio.stopPlayer();
   * console.log('Playback stopped:', stopped);
   * ```
   */
  stopPlayer(): string;

  /**
   * Pauses the current audio playback
   *
   * @returns Success message or error description
   *
   * @example
   * ```typescript
   * const result = ExpoAudioStudio.pausePlayer();
   * console.log(result); // "paused" or error message
   * ```
   */
  pausePlayer(): string;

  /**
   * Resumes paused audio playback
   *
   * @returns Success message or error description
   *
   * @example
   * ```typescript
   * const result = ExpoAudioStudio.resumePlayer();
   * console.log(result); // "playing" or error message
   * ```
   */
  resumePlayer(): string;

  /**
   * Sets the playback speed multiplier
   *
   * @param speed - Speed multiplier as string (0.5 to 2.0)
   * @returns Success message or error description
   *
   * @example
   * ```typescript
   * const result = ExpoAudioStudio.setPlaybackSpeed('1.5');
   * console.log(result); // "Playback speed set to 1.5" or error
   * ```
   */
  setPlaybackSpeed(_speed: string): string;

  /**
   * Seeks to a specific position in the current audio
   *
   * @param position - Position in seconds
   * @returns Success message or error description
   *
   * @example
   * ```typescript
   * const result = ExpoAudioStudio.seekTo(30.5);
   * console.log(result); // "success" or error message
   * ```
   */
  seekTo(_position: number): string;

  // Audio Properties

  /**
   * Current playback position in seconds
   *
   * @example
   * ```typescript
   * const position = ExpoAudioStudio.currentPosition;
   * console.log(`Current position: ${position}`);
   * ```
   */
  readonly currentPosition: number;

  /**
   * Current audio level during recording (in dB)
   *
   * @example
   * ```typescript
   * const level = ExpoAudioStudio.meterLevel;
   * console.log(`Audio level: ${level} dB`);
   * ```
   */
  readonly meterLevel: number;

  /**
   * Detailed player status information
   *
   * @example
   * ```typescript
   * const status = ExpoAudioStudio.playerStatus;
   * console.log(`Playing: ${status.isPlaying}, Duration: ${status.duration}s`);
   * ```
   */
  readonly playerStatus: {
    isPlaying: boolean;
    currentTime: number;
    duration: number;
    speed: number;
  };

  /**
   * Whether Voice Activity Detection is currently active (actually running)
   *
   * @example
   * ```typescript
   * const isActive = ExpoAudioStudio.isVADActive;
   * console.log('VAD active:', isActive);
   * ```
   */
  readonly isVADActive: boolean;

  /**
   * Whether VAD is enabled by user preference (will auto-start with recording)
   *
   * @example
   * ```typescript
   * const isEnabled = ExpoAudioStudio.isVADEnabled;
   * console.log('VAD enabled preference:', isEnabled);
   * ```
   */
  readonly isVADEnabled: boolean;

  // Utility Methods

  /**
   * Gets the duration of an audio file
   *
   * @param uri - File path or URI to the audio file
   * @returns Duration in seconds, or 0 if file not found/invalid
   *
   * @example
   * ```typescript
   * const duration = ExpoAudioStudio.getDuration('/path/to/audio.wav');
   * console.log(`Audio duration: ${duration} seconds`);
   * ```
   */
  getDuration(_uri: string): DurationResult;

  /**
   * Analyzes audio file and returns amplitude data for visualization bars
   *
   * @param fileUrl - File path or URI to the audio file
   * @param barsCount - Number of amplitude bars to generate (1-2048)
   * @returns AudioAmplitudeResult with amplitude data and metadata
   *
   * @example
   * ```typescript
   * const result = ExpoAudioStudio.getAudioAmplitudes('/path/to/audio.wav', 64);
   * if (result.success) {
   *   console.log(`Generated ${result.barsCount} bars for ${result.duration}s audio`);
   *   // Use result.amplitudes array (dB values) for visualization
   *   result.amplitudes.forEach((dB, i) => console.log(`Bar ${i}: ${dB.toFixed(1)} dB`));
   * } else {
   *   console.error('Analysis failed:', result.error);
   * }
   * ```
   */
  getAudioAmplitudes(_fileUrl: string, _barsCount: number): AudioAmplitudeResult;

  // Voice Activity Detection

  /**
   * Sets VAD enabled state - manages VAD lifecycle automatically
   *
   * @param mode - VAD event mode ('onEveryFrame' | 'throttled' | 'onChange')
   * @param throttleMs - Optional throttle time in milliseconds (for 'throttled' mode)
   * @returns Success message or error description
   *
   * @example
   * ```typescript
   * // Enable VAD - will start with next recording or immediately if recording
   * const result = ExpoAudioStudio.setVADEventMode('onEveryFrame');
   * console.log(result); // "Success: VAD event mode set to onEveryFrame" or error
   * ```
   *
   * @example
   * ```typescript
   * // Enable VAD - will start with next recording or immediately if recording
   * const result = ExpoAudioStudio.setVADEventMode('throttled', 200);
   * console.log(result); // "Success: VAD event mode set to throttled with 200ms throttle" or error
   * ```
   *
   * @example
   * ```typescript
   * // Enable VAD - will start with next recording or immediately if recording
   * const result = ExpoAudioStudio.setVADEventMode('onChange');
   * console.log(result); // "Success: VAD event mode set to onChange" or error
   * ```
   */
  setVADEventMode(_mode: 'onEveryFrame' | 'throttled' | 'onChange', _throttleMs?: number): string;

  /**
   * Sets VAD enabled state - manages VAD lifecycle automatically
   *
   * @param enabled - Whether to enable VAD for current and future recordings
   * @returns Success message or error description
   *
   * @example
   * ```typescript
   * // Enable VAD - will start with next recording or immediately if recording
   * const result = ExpoAudioStudio.setVADEnabled(true);
   *
   * // Disable VAD - stops immediately and won't auto-start
   * const result2 = ExpoAudioStudio.setVADEnabled(false);
   * ```
   */
  setVADEnabled(_enabled: boolean): string;

  /**
   * Sets the voice activity detection threshold
   *
   * @param threshold - Detection threshold (0.0 to 1.0)
   * @returns Success message or error description
   *
   * @example
   * ```typescript
   * const result = ExpoAudioStudio.setVoiceActivityThreshold(0.7);
   * console.log(result); // "Success: Threshold set to 0.7" or error
   * ```
   */
  setVoiceActivityThreshold(_threshold: number): string;

  // Permissions

  /**
   * Requests microphone permission from the user
   *
   * @returns Promise resolving to permission response
   *
   * @example
   * ```typescript
   * const permission = await ExpoAudioStudio.requestMicrophonePermission();
   * if (permission.granted) {
   *   console.log('Microphone permission granted');
   * } else {
   *   console.log('Permission denied:', permission.status);
   * }
   * ```
   */
  requestMicrophonePermission(): Promise<PermissionResponse>;

  /**
   * Gets the current microphone permission status
   *
   * @returns Promise resolving to current permission status
   *
   * @example
   * ```typescript
   * const status = await ExpoAudioStudio.getMicrophonePermissionStatus();
   * console.log('Permission status:', status.status);
   * console.log('Can ask again:', status.canAskAgain);
   * ```
   */
  getMicrophonePermissionStatus(): Promise<PermissionResponse>;

  // Audio Session

  /**
   * Configures the audio session with specified category, mode, and options
   *
   * @param config - Audio session configuration object
   * @returns Promise that resolves when configuration is complete
   *
   * @example
   * ```typescript
   * await ExpoAudioStudio.configureAudioSession({
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
  configureAudioSession(_config: AudioSessionConfig): Promise<void>;

  /**
   * Activates the audio session
   *
   * @returns Promise that resolves when activation is complete
   *
   * @example
   * ```typescript
   * await ExpoAudioStudio.activateAudioSession();
   * console.log('Audio session activated');
   * ```
   */
  activateAudioSession(): Promise<void>;

  /**
   * Deactivates the audio session
   *
   * @returns Promise that resolves when deactivation is complete
   *
   * @example
   * ```typescript
   * await ExpoAudioStudio.deactivateAudioSession();
   * console.log('Audio session deactivated');
   * ```
   */
  deactivateAudioSession(): Promise<void>;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<ExpoAudioStudioModule>('ExpoAudioStudio');
