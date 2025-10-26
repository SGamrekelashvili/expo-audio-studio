<div align="center">
  <h1>🎙️ Expo Audio Studio</h1>
  <p><strong>Professional Audio Recording, Playback & Voice Activity Detection for Expo</strong></p>
  
  <p>
    <a href="https://www.npmjs.com/package/expo-audio-studio">
      <img src="https://img.shields.io/npm/v/expo-audio-studio.svg" alt="npm version" />
    </a>
    <a href="https://www.npmjs.com/package/expo-audio-studio">
      <img src="https://img.shields.io/npm/dm/expo-audio-studio.svg" alt="npm downloads" />
    </a>
    <a href="https://github.com/sgamrekelashvili/expo-audio-studio">
      <img src="https://img.shields.io/github/license/sgamrekelashvili/expo-audio-studio.svg" alt="license" />
    </a>
    <a href="https://github.com/sgamrekelashvili/expo-audio-studio">
      <img src="https://img.shields.io/github/stars/sgamrekelashvili/expo-audio-studio.svg?style=social" alt="github stars" />
    </a>
  </p>
</div>

---

## 🎬 Demo

<div align="center">
  <img src="https://github.com/SGamrekelashvili/expo-audio-studio/blob/main/example.gif" alt="Expo Audio Studio Demo" width="600" />
  <p><em>Voice Activity Detection Example</em></p>
</div>

## ✨ Features

- 🎙️ **High-Quality Recording** - WAV format on both platforms, multi-format
  support coming soon
- 🎵 **Advanced Playback** - Speed control, seeking, pause/resume
- 🧠 **Voice Activity Detection** - Real-time speech detection with confidence
  scoring
- 📊 **Audio Analysis** - Amplitude monitoring, duration calculation, waveform
  data
- 📁 **File Management** - List, join, and organize audio files
- 🔄 **Cross-Platform** - Identical API on iOS, Android, and Web (coming soon)
- ⚡ **Performance Optimized** - Native implementations for smooth operation
- 🛡️ **Type Safe** - Full TypeScript support with comprehensive types
- 🎯 **Production Ready** - Used in large scale application

## 📦 Installation

```bash
npm install expo-audio-studio
```

### Development Build

This library requires a
[development build](https://docs.expo.dev/develop/development-builds/introduction/)
as it includes native code.

```bash
# Create development build
npx expo run:ios
npx expo run:android
```

## 🚀 Quick Start

### Basic Recording

```typescript
import {
  startRecording,
  stopRecording,
  addRecorderStatusListener,
  requestMicrophonePermission,
} from 'expo-audio-studio';

// Request permission first
const permission = await requestMicrophonePermission();
if (!permission.granted) {
  console.log('Microphone permission denied');
  return;
}

// Listen to recording events
const subscription = addRecorderStatusListener(event => {
  console.log('Recording status:', event.status);
});

// Start recording
const filePath = startRecording();
console.log('Recording to:', filePath);

// Stop recording
const finalPath = stopRecording();
console.log('Recording saved to:', finalPath);

// Cleanup
subscription.remove();
```

### Voice Activity Detection

```typescript
import {
  setVADEnabled,
  addVoiceActivityListener,
  startRecording,
} from 'expo-audio-studio';

// Enable VAD
setVADEnabled(true);

// Listen to voice activity
const vadSubscription = addVoiceActivityListener(event => {
  console.log('Voice detected:', event.isVoiceDetected);
  console.log('Confidence:', event.confidence);
});

// Start recording - VAD will automatically start
startRecording();
```

### Audio Playback

```typescript
import {
  startPlaying,
  setPlaybackSpeed,
  addPlayerStatusListener,
} from 'expo-audio-studio';

// Listen to playback events
const playerSubscription = addPlayerStatusListener(event => {
  console.log('Playing:', event.isPlaying);
});

// Start playback
startPlaying('/path/to/audio/file.wav');

// Control playback speed
setPlaybackSpeed('1.5'); // 1.5x speed

// Cleanup
playerSubscription.remove();
```

## 📚 API Reference

### Recording Functions

| Function                         | Description             | Returns                    |
| -------------------------------- | ----------------------- | -------------------------- |
| `startRecording(directoryPath?)` | Start audio recording   | `string` - File path       |
| `stopRecording()`                | Stop recording          | `string` - Final file path |
| `pauseRecording()`               | Pause recording         | `string` - Status message  |
| `resumeRecording()`              | Resume recording        | `string` - Status message  |
| `lastRecording()`                | Get last recording path | `string \| null`           |

### Playback Functions

| Function                  | Description                  | Returns           |
| ------------------------- | ---------------------------- | ----------------- |
| `startPlaying(path)`      | Start audio playback         | `string` - Status |
| `stopPlayer()`            | Stop playback                | `string` - Status |
| `pausePlayer()`           | Pause playback               | `string` - Status |
| `resumePlayer()`          | Resume playback              | `string` - Status |
| `setPlaybackSpeed(speed)` | Set playback speed (0.5-2.0) | `string` - Status |
| `seekTo(position)`        | Seek to position in seconds  | `string` - Status |

### Voice Activity Detection

| Function                               | Description                       | Returns           |
| -------------------------------------- | --------------------------------- | ----------------- |
| `setVADEnabled(enabled)`               | Enable/disable VAD                | `string` - Status |
| `setVoiceActivityThreshold(threshold)` | Set detection threshold (0.0-1.0) | `string` - Status |

### Audio Analysis

| Function                                 | Description                   | Returns                        |
| ---------------------------------------- | ----------------------------- | ------------------------------ |
| `getDuration(uri)`                       | Get audio file duration       | `number` - Duration in seconds |
| `getAudioAmplitudes(fileUrl, barsCount)` | Get waveform data (dB values) | `object` - Amplitude data      |
| `setAmplitudeUpdateFrequency(hz)`        | Set amplitude update rate     | `string` - Status              |

### File Management

| Function                                | Description             | Returns                |
| --------------------------------------- | ----------------------- | ---------------------- |
| `listRecordings(directoryPath?)`        | List audio files        | `array` - File list    |
| `joinAudioFiles(filePaths, outputPath)` | Concatenate audio files | `string` - Output path |

### Properties

| Property          | Type      | Description                         |
| ----------------- | --------- | ----------------------------------- |
| `isVADActive`     | `boolean` | Whether VAD is currently processing |
| `isVADEnabled`    | `boolean` | Whether VAD is enabled              |
| `isPaused`        | `boolean` | Whether recording is paused         |
| `meterLevel`      | `number`  | Current audio level                 |
| `currentPosition` | `number`  | Current playback position           |

### Event Listeners

#### Recording Events

```typescript
addRecorderStatusListener((event: AudioRecordingStateChangeEvent) => {
  // event.status: 'recording' | 'stopped' | 'paused' | 'resumed' | 'error'
});

addRecorderAmplitudeListener((event: AudioMeteringEvent) => {
  // event.amplitude: number (dB level)
});
```

#### Voice Activity Events

```typescript
addVoiceActivityListener((event: VoiceActivityEvent) => {
  // event.isVoiceDetected: boolean - Whether voice is detected
  // event.confidence: number (0.0-1.0) - Confidence score
  //   iOS: Real ML confidence (varies 0.0-1.0)
  //   Android: Fixed (0.85 for voice, 0.15 for silence)
  // event.eventType: string - Event type
  //   Android: 'speech_start' | 'silence_start'
  //   iOS: Continuous events
  // event.timestamp: number - Event timestamp
  // event.audioLevel: number - Audio amplitude in dB (optional)
  // event.isStateChange: boolean - Whether this is a state change
  // event.platformData: object - Platform-specific metadata
});
```

**Platform Differences:**

- **iOS**: Events fire continuously (~60-100ms intervals) with real-time ML
  confidence
- **Android**: Events fire only on state changes with fixed confidence values

#### Playback Events

```typescript
addPlayerStatusListener((event: PlayerStatusChangeEvent) => {
  // event.isPlaying: boolean
  // event.didJustFinish: boolean
});
```

## 🎯 Advanced Usage

### Custom Recording Directory

```typescript
// Record to custom directory
const customPath = '/path/to/custom/directory';
const filePath = startRecording(customPath);
```

### Audio Session Configuration (iOS)

> [!IMPORTANT]  
> You can call `configureAudioSession()` again after `activateAudioSession()` to
> change options (e.g. switching between playback and recording).  
> However, **do not reconfigure the session while recording or playing audio** —
> this may cause the app to **freeze** on iOS.

```typescript
import { configureAudioSession, activateAudioSession } from 'expo-audio-studio';

// Configure for recording
await configureAudioSession({
  category: 'playAndRecord',
  mode: 'default',
  options: {
    defaultToSpeaker: true,
    allowBluetooth: true,
    allowBluetoothA2DP: true,
  },
});

// Activate session
await activateAudioSession();
```

> [!IMPORTANT]  
> You can call `configureAudioSession()` again after `activateAudioSession()` to
> change options (e.g. switching between playback and recording).  
> However, **do not reconfigure the session while recording or playing audio** —
> this may cause the app to **freeze** on iOS.

### Voice Activity Detection with Custom Threshold

```typescript
// Set sensitive threshold for quiet environments
setVoiceActivityThreshold(0.3);

// Set less sensitive threshold for noisy environments
setVoiceActivityThreshold(0.7);

// Enable VAD
setVADEnabled(true);
```

### Audio File Analysis

```typescript
// Get detailed waveform data (dB values)
const waveformData = getAudioAmplitudes('/path/to/file.wav', 100);
console.log('Waveform bars (dB):', waveformData.amplitudes);
console.log('Duration:', waveformData.duration);

// Convert dB to normalized values for visualization
const normalizedAmplitudes = waveformData.amplitudes.map(dB => {
  // Convert dB to 0-1 range (assuming -60dB to 0dB range)
  return Math.max(0, (dB + 60) / 60);
});

// Get file duration
const duration = getDuration('/path/to/file.wav');
console.log('Duration:', duration, 'seconds');
```

### Joining Audio Files

```typescript
const inputFiles = [
  '/path/to/file1.wav',
  '/path/to/file2.wav',
  '/path/to/file3.wav',
];

const outputPath = '/path/to/joined_audio.wav';
const result = joinAudioFiles(inputFiles, outputPath);
console.log('Joined file created:', result);
```

## 🔧 Configuration

### Audio Formats

**Current Implementation:**

- **iOS**: WAV (Linear PCM, 16kHz, 16-bit, mono) - High-quality format
- **Android**: WAV (PCM, 16kHz, 16-bit, mono) - High-quality format using
  [AndroidWaveRecorder](https://github.com/squti/Android-Wave-Recorder)

**Note**: Both platforms currently use WAV format for optimal quality
compatibility. Multi-format recording support is planned for iOS to provide
format options when needed.

### Voice Activity Detection

**Platform Implementations:**

- **iOS**: Core ML Sound Classification (requires iOS 13.0+)
  - Continuous event stream (~1.5 second analysis window with 90% overlap)
  - Real-time confidence scoring (0.0-1.0)
  - Events sent continuously during analysis
- **Android**: [Silero VAD](https://github.com/gkonovalov/android-vad) (Deep
  Neural Network-based)
  - State-change events only (voice detected / silence detected)
  - Frame Size: 512 samples (32ms) at 16kHz
  - Fixed confidence values (0.85 for voice, 0.15 for silence)
  - Optimized for efficiency - only fires on state transitions

**Event Behavior:**

- **iOS**: Receives events continuously during VAD operation (~60-100ms
  intervals)
- **Android**: Receives events only when voice activity state changes
- **Confidence**: iOS provides real ML confidence scores; Android uses fixed
  values

**Configuration:**

- **Detection Threshold**: 0.0-1.0 range (iOS only - affects ML classification
  threshold)
- **Silence Duration**: 300ms (Android)
- **Speech Duration**: 50ms (Android)

## 📱 Platform Support

| Platform | Minimum Version | Notes                                        |
| -------- | --------------- | -------------------------------------------- |
| iOS      | 13.0+           | Core ML required for VAD                     |
| Android  | API 21+ (5.0)   | Full feature support                         |
| Web      | Coming Soon 🚀  | In development - full feature parity planned |

## 📦 Dependencies

### Android Native Dependencies

| Library                                                               | Version | License | Purpose                    |
| --------------------------------------------------------------------- | ------- | ------- | -------------------------- |
| [AndroidWaveRecorder](https://github.com/squti/Android-Wave-Recorder) | 2.1.0   | MIT     | High-quality WAV recording |
| [Silero VAD](https://github.com/gkonovalov/android-vad)               | 2.0.10  | MIT     | Voice Activity Detection   |

### iOS Native Dependencies

- **Core ML** - Built-in Apple framework for Voice Activity Detection
- **AVFoundation** - Built-in Apple framework for audio recording/playback

**License Compatibility**: All dependencies use MIT License, making this package
safe for commercial and open-source use.

## 🛠️ Development

### Running the Example

```bash
cd example
npm install
npx expo run:ios
# or
npx expo run:android
# Web support coming soon!
```

### Building from Source

```bash
# Install dependencies
npm install

# Build TypeScript
npm run build

# Run tests
npm test
```

## 🗺️ Roadmap

### 🚀 Coming Soon

- **🌐 Web Platform Support** - Full feature parity with iOS and Android
  - WebRTC-based recording and playback
  - Browser-native Voice Activity Detection
  - File management with Web APIs
  - Same TypeScript API across all platforms

- **🎵 Multi-Format Recording** - Configurable audio formats (iOS priority)
  - WAV, M4A, MP3, FLAC support
  - Quality and compression settings
  - Format selection per recording session
  - Both platforms currently use WAV (excellent format), additional formats for
    specific use cases

- **📊 Enhanced Analytics** - Advanced audio analysis features
- **🎙️ Multi-channel Recording** - Stereo and multi-microphone support
- **🎵 Audio Effects** - Real-time audio processing and filters

### 💬 Feedback Welcome

Have ideas for new features?
[Open a discussion](https://github.com/sgamrekelashvili/expo-audio-studio/discussions)
and let us know!

## 🤝 Contributing

Contributions are welcome! Please read our [Contributing Guide](CONTRIBUTING.md)
for details on our code of conduct and the process for submitting pull requests.

### Development Setup

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file
for details.

## 🙏 Acknowledgments

- Built with [Expo Modules API](https://docs.expo.dev/modules/overview/)
- Android WAV recording powered by
  [AndroidWaveRecorder](https://github.com/squti/Android-Wave-Recorder) by
  @squti
- Android Voice Activity Detection powered by
  [Silero VAD](https://github.com/gkonovalov/android-vad) by @gkonovalov
- iOS Voice Activity Detection using Apple's Core ML Sound Classification
- Inspired by the need

## 📞 Support

- 📧 Email: [sgamrekelashvili@gmail.com](mailto:sgamrekelashvili@gmail.com)
- 🐛 Issues:
  [GitHub Issues](https://github.com/sgamrekelashvili/expo-audio-studio/issues)
- 💬 Discussions:
  [GitHub Discussions](https://github.com/sgamrekelashvili/expo-audio-studio/discussions)

---

<div align="center">
  <p><strong>Made with ❤️ for the React Native community</strong></p>
</div>
