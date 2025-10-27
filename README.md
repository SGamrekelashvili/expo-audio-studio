<div align="center">
  <h1>Expo Audio Studio</h1>
  <p><strong>Audio recording and playback for Expo apps with built-in voice detection</strong></p>
  
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

## Demo

<div align="center">
  <img src="https://github.com/SGamrekelashvili/expo-audio-studio/blob/main/example.gif" alt="Expo Audio Studio Demo" width="600" />
  <p><em>Voice Activity Detection Example</em></p>
</div>

## What's included

- Record high-quality audio (WAV/PCM16 format, 16kHz, 16-bit mono)
- Play audio with speed control and seeking
- Detect when someone is speaking (voice activity detection)
- Get real-time amplitude data and waveform visualizations
- Join multiple audio files together
- Works the same on iOS and Android
- Full TypeScript support

## Installation

```bash
npm install expo-audio-studio
```

### Setup permissions

Add the plugin to your `app.config.ts` to automatically configure microphone
permissions:

```typescript
export default {
  plugins: [
    [
      'expo-audio-studio',
      {
        microphonePermission:
          'Allow $(PRODUCT_NAME) to access your microphone for audio recording',
      },
    ],
  ],
};
```

Or use it without options for default permissions:

```typescript
export default {
  plugins: ['expo-audio-studio'],
};
```

This adds:

- iOS: Microphone usage description
- Android: Audio recording permissions

### Build your app

Since this uses native code, you'll need a
[development build](https://docs.expo.dev/develop/development-builds/introduction/):

```bash
# Prebuild to apply the plugin
npx expo prebuild

# Create development build
npx expo run:ios
npx expo run:android
```

## Getting started

### Record audio

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

### Detect speech

```typescript
import {
  setVADEnabled,
  setVADEventMode,
  addVoiceActivityListener,
  startRecording,
} from 'expo-audio-studio';

// Choose how often you want events
setVADEventMode('onEveryFrame'); // Real-time (default)
// setVADEventMode('onChange');  // Only state changes
// setVADEventMode('throttled', 100); // Every 100ms

// Enable VAD
setVADEnabled(true);

// Listen to voice activity
const vadSubscription = addVoiceActivityListener(event => {
  if (event.isStateChange) {
    // State just changed - someone started or stopped talking
    console.log(event.isVoiceDetected ? 'Started talking!' : 'Stopped talking');
  }
  console.log('Confidence:', event.confidence);
  console.log('Event type:', event.eventType);
});

// Start recording - VAD will automatically start
startRecording();
```

### Play audio

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
setPlaybackSpeed(1.5); // 1.5x speed

// Cleanup
playerSubscription.remove();
```

## API Reference

### Recording Functions

| Function                         | Description             | Returns                    |
| -------------------------------- | ----------------------- | -------------------------- |
| `startRecording(directoryPath?)` | Start audio recording   | `string` - File path       |
| `stopRecording()`                | Stop recording          | `string` - Final file path |
| `pauseRecording()`               | Pause recording         | `string` - Status message  |
| `resumeRecording()`              | Resume recording        | `string` - Status message  |
| `lastRecording()`                | Get last recording path | `string` or `null`         |
| `getCurrentMeterLevel()`         | Get current audio level | `number`                   |

### Playback Functions

| Function                  | Description                   | Returns           |
| ------------------------- | ----------------------------- | ----------------- |
| `startPlaying(path)`      | Start audio playback          | `string` - Status |
| `stopPlaying()`           | Stop playback                 | `string` - Status |
| `pausePlayer()`           | Pause playback                | `string` - Status |
| `resumePlayer()`          | Resume playback               | `string` - Status |
| `setPlaybackSpeed(speed)` | Set playback speed (0.5-2.0)  | `string` - Status |
| `seekTo(position)`        | Seek to position in seconds   | `string` - Status |
| `getCurrentPosition()`    | Get current playback position | `number`          |

### Voice Activity Detection

| Function                               | Description                               | Returns           |
| -------------------------------------- | ----------------------------------------- | ----------------- |
| `setVADEnabled(enabled)`               | Enable/disable VAD                        | `string` - Status |
| `setVoiceActivityThreshold(threshold)` | Set detection threshold (0.0-1.0)         | `string` - Status |
| `setVADEventMode(mode, throttleMs?)`   | Control event frequency                   | `string` - Status |
| `getIsVADActive()`                     | Whether VAD is currently processing audio | `boolean`         |
| `getIsVADEnabled()`                    | Whether VAD is enabled by user preference | `boolean`         |

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
  // event.isVoiceDetected: boolean - Is someone speaking?
  // event.confidence: number - How confident is the detection (0.0-1.0)
  // event.timestamp: number - When this happened
  // event.isStateChange: boolean - Did the state just change?
  // event.previousState: boolean - What was the previous state
  // event.eventType: 'speech_start' | 'speech_continue' | 'silence_start' | 'silence_continue'
});
```

#### Playback Events

```typescript
addPlayerStatusListener((event: PlayerStatusChangeEvent) => {
  // event.isPlaying: boolean
  // event.didJustFinish: boolean
});
```

## More examples

### Save to a custom folder

```typescript
const filePath = startRecording('/path/to/custom/directory');
```

### iOS audio session setup

> **Note:** Don't reconfigure the audio session while recording or playing -
> this can freeze your app.

```typescript
import { configureAudioSession, activateAudioSession } from 'expo-audio-studio';

await configureAudioSession({
  category: 'playAndRecord',
  mode: 'default',
  options: {
    defaultToSpeaker: true,
    allowBluetooth: true,
  },
});

await activateAudioSession();
```

### Adjust voice detection sensitivity

```typescript
// More sensitive (for quiet rooms)
setVoiceActivityThreshold(0.3);

// Less sensitive (for noisy places)
setVoiceActivityThreshold(0.7);

setVADEnabled(true);
```

### Real-world example: Voice-activated recording

#### Example 1

```typescript
import {
  setVADEnabled,
  setVADEventMode,
  addVoiceActivityListener,
} from 'expo-audio-studio';

// Only notify on state changes for battery efficiency
setVADEventMode('onChange');
setVADEnabled(true);

const subscription = addVoiceActivityListener(event => {
  if (event.eventType === 'speech_start') {
    console.log('🎤 Voice detected');
  } else if (event.eventType === 'silence_start') {
    console.log('🔇 Silence detected');
  }
});
```

#### Example 2

```typescript
import {
  setVADEnabled,
  setVADEventMode,
  addVoiceActivityListener,
} from 'expo-audio-studio';

// Only notify in every 250ms
setVADEventMode('throttled', 250);
setVADEnabled(true);

const subscription = addVoiceActivityListener(event => {
  if (event.isVoiceDetected) {
    console.log('Voice detected');
  } else {
    console.log('Silence detected');
  }
});
```

### Get waveform data

```typescript
const waveformData = getAudioAmplitudes('/path/to/file.wav', 100);
console.log('Amplitude values:', waveformData.amplitudes);

// Normalize for UI visualization (dB to 0-1 range)
const normalized = waveformData.amplitudes.map(dB =>
  Math.max(0, (dB + 60) / 60)
);

const duration = getDuration('/path/to/file.wav');
```

### Merge audio files

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

## Audio format

Recordings use WAV format (PCM16, 16kHz, 16-bit mono) on both platforms. This
provides good quality while keeping file sizes reasonable.

- iOS: Linear PCM using AVFoundation
- Android: PCM using
  [AndroidWaveRecorder](https://github.com/squti/Android-Wave-Recorder)

Additional formats are on the roadmap.

## Voice detection details

Both iOS and Android now fire the same events, so you can expect identical
behavior.

### Controlling event frequency

You can choose how often you want to receive voice detection events:

```typescript
// Get events for every audio frame processed (~32ms, about 30 per second)
// Perfect for real-time visualizations or instant response
setVADEventMode('onEveryFrame');

// Only get notified when voice state changes (speech starts/stops)
// Battery-friendly and great for simple on/off detection
setVADEventMode('onChange');

// Get updates every X milliseconds, plus immediate state changes
// Nice balance between real-time and performance
setVADEventMode('throttled', 250); // every 250ms
```

**Which one should you use?**

- Building a live voice visualizer? Use `onEveryFrame`
- Just need to know when someone starts/stops talking? Use `onChange`
- Want periodic updates without overwhelming your app? Use `throttled` with
  100-250ms

You can change the mode anytime, even while recording is happening. The setting
sticks around until you change it again.

### Under the hood

**iOS** uses Apple's Core ML Sound Classification:

- Real confidence scores from machine learning (0.0-1.0)
- Analyzes audio in 1.5 second windows with smart overlap
- Works on iOS 14.0 and up

**Android** uses [Silero VAD](https://github.com/gkonovalov/android-vad):

- Compact neural network implementation
- Fixed confidence values (0.85 for voice, 0.15 for silence)
- Processes 32 ms chunks at 16 kHz

## Platform requirements

- **iOS**: 14.0 or higher (for voice detection)
- **Android**: API 21 (Android 5.0) or higher
- **Web**: Coming soon

## What's under the hood

**Android libraries:**

- [AndroidWaveRecorder](https://github.com/squti/Android-Wave-Recorder)
  (v2.1.0) - for WAV recording
- [Silero VAD](https://github.com/gkonovalov/android-vad) (v2.0.10) - for voice
  detection

**iOS frameworks:**

- AVFoundation - for recording/playback
- Core ML - for voice detection

Everything is MIT licensed.

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

## What's next

**Streaming & real-time processing**

- Stream audio chunks in real time with configurable chunk sizes
- Access raw PCM16 data during recording
- Reduce latency for live transcription and other low-latency pipelines
- Use the stream for speech-to-text or custom analysis

**More formats**

- M4A, MP3, and FLAC recording options
- Configurable quality settings

**Web support**

- WebRTC-based recording
- Browser-side voice detection
- Same API across native and web targets

**Other features**

- Stereo and multi-channel recording
- Real-time audio effects
- Additional analytics hooks

Got ideas?
[Open a discussion](https://github.com/sgamrekelashvili/expo-audio-studio/discussions)!

## Contributing

Pull requests are welcome! Check out the [Contributing Guide](CONTRIBUTING.md)
for details.

1. Fork the repo
2. Create a branch: `git checkout -b feature/my-feature`
3. Make your changes
4. Push and open a pull request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file
for details.

## Acknowledgments

- Built with [Expo Modules API](https://docs.expo.dev/modules/overview/)
- Android WAV recording powered by
  [AndroidWaveRecorder](https://github.com/squti/Android-Wave-Recorder) by
  @squti
- Android Voice Activity Detection powered by
  [Silero VAD](https://github.com/gkonovalov/android-vad) by @gkonovalov
- iOS Voice Activity Detection using Apple's Core ML Sound Classification
- Built for production use in audio applications

## Support

- Email: [sgamrekelashvili@gmail.com](mailto:sgamrekelashvili@gmail.com)
- Issues:
  [GitHub Issues](https://github.com/sgamrekelashvili/expo-audio-studio/issues)
- Discussions:
  [GitHub Discussions](https://github.com/sgamrekelashvili/expo-audio-studio/discussions)

---

<div align="center">
  <p><strong>Made with ❤️ for the React Native community</strong></p>
</div>
