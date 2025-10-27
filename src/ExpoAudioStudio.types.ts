// Audio Session Types

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

// Event Types

/**
 * Event payload for module initialization
 */
export type OnLoadEventPayload = {
  url: string;
};

/**
 * Module event listeners interface
 */
export type ExpoAudioStudioModuleEvents = {
  onPlayerStatusChange: (_params: PlayerStatusChangeEvent) => void;
  onRecorderAmplitude: (_params: AudioMeteringEvent) => void;
  onRecorderStatusChange: (_params: AudioRecordingStateChangeEvent) => void;
  onVoiceActivityDetected: (_params: VoiceActivityEvent) => void;
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
};

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

  /** Current audio level in dB */
  audioLevel?: number;

  /** Whether this represents a state change */
  isStateChange: boolean;

  /** Previous detection state (for state changes) */
  previousState?: boolean;

  /** Event type for better handling */
  eventType:
    | 'speech_start'
    | 'speech_continue'
    | 'speech_end'
    | 'silence_start'
    | 'silence_continue';
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
 * Recording configuration options
 */
export type RecordingOptions = {
  /** Output file format */
  format?: 'wav' | 'm4a' | 'mp3';
  /** Sample rate in Hz */
  sampleRate?: 16000 | 44100 | 48000;
  /** Number of audio channels */
  channels?: 1 | 2;
  /** Bit depth */
  bitDepth?: 16 | 24;
  /** Enable noise suppression */
  noiseSuppression?: boolean;
};

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

// Voice Activity Detection Types

/**
 * VAD sensitivity levels for easy configuration
 */
export type VADSensitivity = 'low' | 'medium' | 'high' | 'very_high';

/**
 * VAD detection mode
 */
export type VADMode = 'normal' | 'aggressive' | 'very_aggressive';

/**
 * VAD detection result with comprehensive information
 */
export type VADResult = {
  /** Whether voice is detected */
  isVoiceDetected: boolean;

  /** Confidence score (0.0-1.0) */
  confidence: number;

  /** Detection timestamp */
  timestamp: number;

  /** Current audio level (dB) */
  audioLevel?: number;

  /** Whether this is a state change event */
  isStateChange: boolean;

  /** Previous state (for state change events) */
  previousState?: boolean;

  /** Platform-specific debug info */
  debugInfo?: {
    frameSize?: number;
    sampleRate?: number;
    processingTime?: number;
    bufferHealth?: 'good' | 'warning' | 'critical';
  };
};

/**
 * VAD initialization result
 */
export type VADInitResult = {
  /** Whether initialization was successful */
  success: boolean;

  /** Error message if failed */
  error?: string;

  /** Available features */
  features?: {
    realTimeProcessing: boolean;
    confidenceScoring: boolean;
    sessionStats: boolean;
    debugMode: boolean;
  };
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
 * Standard function result for operations that can succeed or fail
 */
export type OperationResult = {
  /** Whether operation succeeded */
  success: boolean;
  /** Result message or error description */
  message: string;
  /** Additional data if applicable */
  data?: any;
};

/**
 * File path result type
 */
export type FilePathResult = string | null;

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
