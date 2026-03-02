// Audio Session Typess

/**
 * Audio session category for iOS
 */
export type AudioSessionCategory =
  | 'ambient'
  | 'soloAmbient'
  | 'playback'
  | 'record'
  | 'playAndRecord'
  | 'multiRoute';

/**
 * Audio session mode for iOS
 */
export type AudioSessionMode =
  | 'default'
  | 'voiceChat'
  | 'gameChat'
  | 'videoRecording'
  | 'measurement'
  | 'moviePlayback'
  | 'videoChat'
  | 'spokenAudio';

/**
 * Audio session options for iOS
 */
export type AudioSessionOptions = {
  /** Mix with other audio apps */
  mixWithOthers?: boolean;
  /** Duck other audio when this app plays */
  duckOthers?: boolean;
  /** Allow Bluetooth audio */
  allowBluetooth?: boolean;
  /** Allow Bluetooth A2DP */
  allowBluetoothA2DP?: boolean;
  /** Default to speaker instead of receiver */
  defaultToSpeaker?: boolean;
  /** Allow AirPlay audio */
  allowAirPlay?: boolean;
};

/**
 *
 * Audio session configuration
 */
export type AudioSessionConfig = {
  /** Audio session category */
  category: AudioSessionCategory;
  /** Audio session mode */
  mode?: AudioSessionMode;
  /** Audio session options */
  options?: AudioSessionOptions;
};

export type InterruptionEndedEvent = {
  /** Whether the app can resume audio playback */
  canResume: boolean;
  /** Whether audio was playing before the interruption */
  wasPlayingBeforeInterruption: boolean;
  /** Whether recording was active before the interruption */
  wasRecordingBeforeInterruption: boolean;
};

/**
 * Route change event - fired when audio devices connect/disconnect
 * (iOS only)
 */
export type RouteChangeEvent = {
  /** Reason for the route change */
  reason: 'deviceDisconnected' | 'deviceConnected' | 'categoryChange' | 'override' | 'unknown';
  /** Whether recording is currently active */
  isRecording: boolean;
  /** Human-readable message about the change */
  message: string;
};

/**
 * App state change event - fired on background/foreground transitions
 * (iOS only)
 */
export type AppStateChangeEvent = {
  /** Current app state */
  state: 'background' | 'foreground';
  /** Whether recording is currently active */
  isRecording: boolean;
  /** Whether playback is currently active */
  isPlaying: boolean;
};

/**
 * Module event listeners interface
 */
export type ExpoAudioStudioModuleEvents = {
  onPlayerStatusChange: (_params: PlayerStatusChangeEvent) => void;
  onRecorderAmplitude: (_params: AudioMeteringEvent) => void;
  onRecorderStatusChange: (_params: AudioRecordingStateChangeEvent) => void;
  onVoiceActivityDetected: (_params: VoiceActivityEvent) => void;
  /**
   * Returns audio chunks as binary data
   */
  onAudioChunk: (_params: AudioChunkEvent) => void;
  /**
   * Called when audio session interruption ends
   * (iOS only)
   */
  onInterruptionEnded: (_params: InterruptionEndedEvent) => void;
  /**
   * Called when audio route changes (device connect/disconnect)
   * Recording continues automatically on built-in mic when Bluetooth disconnects
   * (iOS only)
   */
  onRouteChange: (_params: RouteChangeEvent) => void;
  /**
   * Called when app transitions between background and foreground
   * Use this to handle recording state during app lifecycle
   * (iOS only)
   */
  onAppStateChange: (_params: AppStateChangeEvent) => void;
};

/**
 * Audio metering event containing amplitude information
 */
export type AudioMeteringEvent = {
  /** Audio amplitude in decibels (dB) */
  amplitude: number;
};

/**
 * Player status change event
 */
export type PlayerStatusChangeEvent = {
  /** Whether audio is currently playing */
  isPlaying: boolean;
  /** Whether playback just finished naturally */
  didJustFinish: boolean;
};

/**
 * Recording state change event
 */
export type AudioRecordingStateChangeEvent = {
  /** Current recording status */
  status: RecordingStatus;
  /** Error code when status is 'error' */
  errorCode?: RecordingErrorCode;
  /** Human-readable error message when status is 'error' */
  errorMessage?: string;
};

/**
 * Recording error codes for specific error identification
 */
export type RecordingErrorCode =
  | 'RECORDER_STATE_ERROR' // Unexpected recorder state during operation
  | 'RECORDER_CREATE_FAILED' // Failed to create AVAudioRecorder
  | 'RECORDER_START_FAILED' // recorder.record() returned false
  | 'RECORDER_SETUP_ERROR' // Exception during recording setup
  | 'RECORDER_ENCODE_ERROR'; // Encode error during recording

/**
 * Recording status enumeration
 */
export type RecordingStatus =
  | 'recording' // Currently recording
  | 'stopped' // Recording stopped
  | 'paused' // Recording paused
  | 'resumed' // Recording resumed (alias for "recording")
  | 'failed' // Recording failed to start
  | 'error' // Recording encountered an error
  | 'interrupted'; // Recording interrupted by system

/**
 * Enhanced voice activity detection event with comprehensive data
 */
export type VoiceActivityEvent = {
  /** Whether voice/speech is currently detected */
  isVoiceDetected: boolean;

  /** Confidence level (0.0-1.0) */
  confidence: number;

  /** Timestamp of detection */
  timestamp: number;

  /** Whether this represents a state change */
  isStateChange: boolean;

  /** Previous detection state (for state changes) */
  previousState?: boolean;

  /** Event type for better handling */
  eventType: 'speech_start' | 'speech_continue' | 'silence_start' | 'silence_continue';
};

/**
 * Audio chunk event containing binary audio data.
 * On Android, data is Base64-encoded PCM for efficiency.
 * On iOS, data may use the legacy chunks array format.
 */
export type AudioChunkEvent = {
  /** Base64-encoded PCM audio data (Android) */
  data?: string;
  /** Timestamp of the first sample in the batch */
  timestamp?: number;
  /** Timestamp of the last sample in the batch */
  endTimestamp?: number;
  /** Whether VAD detected voice in this batch */
  hasVoice?: boolean;
  /** Size of decoded audio data in bytes */
  size?: number;
  /** Encoding format of the data field */
  encoding?: 'base64';
  /** Legacy: Array of audio chunks (iOS) */
  chunks?: Array<{
    /** PCM audio data as integers (0-255) */
    data: number[];
    /** Timestamp when chunk was captured */
    timestamp: number;
    /** Whether VAD detected voice in this chunk */
    hasVoice: boolean;
    /** Size of chunk in bytes */
    size: number;
  }>;
  /** Event type - 'batch' for batch processing */
  type?: 'batch';
  /** Data format - 'uint8array' for binary data (iOS legacy) */
  format?: 'uint8array';
};

// Permission Types

/**
 * Permission status enumeration
 */
export type PermissionStatus =
  | 'granted' // Permission granted
  | 'denied' // Permission denied
  | 'undetermined' // Permission not yet requested
  | 'restricted'; // Permission restricted by system

/**
 * Permission response object
 */
export type PermissionResponse = {
  /** Current permission status */
  status: PermissionStatus;
  /** Whether permission can be requested again */
  canAskAgain: boolean;
  /** Whether permission is currently granted */
  granted: boolean;
};

// Playback Types

/**
 * Detailed player status information
 */
export type PlayerStatusResult = {
  /** Whether audio is currently playing */
  isPlaying: boolean;
  /** Current playback position in seconds */
  currentTime: number;
  /** Total duration in seconds */
  duration: number;
  /** Current playback speed multiplier */
  speed: number;
};

/**
 * Playback speed range (0.5x to 2.0x)
 */
export type PlaybackSpeed = number;

// Recording Types

/**
 * Recording state information
 */
export type RecordingState = {
  /** Whether currently recording */
  isRecording: boolean;
  /** Whether recording is paused */
  isPaused: boolean;
  /** Current recording status */
  status: RecordingStatus;
  /** Current amplitude level in dB */
  amplitude: number;
  /** Recording duration in seconds */
  duration: number;
};

// Audio Amplitude Types

/**
 * Audio amplitude analysis result
 */
export type AudioAmplitudeResult = {
  /** Whether the analysis was successful */
  success: boolean;
  /** Array of amplitude values in decibels (dB) for each bar */
  amplitudes: number[];
  /** Audio duration in seconds */
  duration: number;
  /** Audio sample rate in Hz */
  sampleRate: number;
  /** Number of amplitude bars generated */
  barsCount: number;
  /** Error message if analysis failed */
  error?: string;
};

// Function Return Types

/**
 * Duration result in seconds
 */
export type DurationResult = number;

// Error Types

/**
 * Audio handler error types
 */
export type AudioHandlerError =
  | 'NoPlayerException'
  | 'NoRecorderException'
  | 'PlaybackFailedException'
  | 'RecordingFailedException'
  | 'PermissionDeniedException'
  | 'FileNotFoundException'
  | 'InvalidParameterException'
  | 'AudioSessionException'
  | 'UnsupportedOperationException';

/**
 * Error response object
 */
export type ErrorResponse = {
  /** Error type */
  type: AudioHandlerError;
  /** Human-readable error message */
  message: string;
  /** Additional error details */
  details?: any;
};
