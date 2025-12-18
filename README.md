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

## What you get

- **Recording that actually works** - WAV format, 16kHz, proper quality
- **Real-time audio chunks** - Stream to OpenAI, Whisper, whatever you want
- **Detect when someone's talking** - Know when someone's actually speaking vs
  dead silence (uses Silero VAD on Android, Core ML on iOS)
- **Playback (with actual controls)** - Speed it up, slow it down, seek around
- **Live amplitude** - Build those fancy waveform UIs
- **File operations** - Join recordings, save wherever
- **Same shit on both platforms** - iOS and Android behave identically
- **TypeScript** - Because we're not animals

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

# Create a dev build, duh
npx expo run:ios
npx expo run:android
```

## Quick start

### Just record some audio already

```typescript
import ExpoAudioStudio from 'expo-audio-studio';

// Request permission first
const permission = await ExpoAudioStudio.requestMicrophonePermission();
if (!permission.granted) {
  console.log('Microphone permission denied');
  return;
}

// Listen to recording events
const subscription = ExpoAudioStudio.addListener(
  'onRecorderStatusChange',
  (event: AudioRecordingStateChangeEvent) => {
    console.log('Recording status:', event.status);
  }
);

// Start recording
const filePath = ExpoAudioStudio.startRecording();
console.log('Recording to:', filePath);

// Stop recording
const finalPath = await ExpoAudioStudio.stopRecording();
console.log('Recording saved to:', finalPath);

// Cleanup
subscription.remove();
```

### Detect speech

```typescript
import ExpoAudioStudio from 'expo-audio-studio';

// Choose how often you want events
ExpoAudioStudio.setVADEventMode('onEveryFrame'); // Real-time (default)
// setVADEventMode('onChange');  // Only state changes
// setVADEventMode('throttled', 100); // Every 100ms

// Enable VAD
ExpoAudioStudio.setVADEnabled(true);

// Listen to voice activity
const vadSubscription = ExpoAudioStudio.addListener(
  'onVoiceActivityDetected',
  (event: VoiceActivityEvent) => {
    if (event.isStateChange) {
      // State just changed - someone started or stopped talking
      console.log(
        event.isVoiceDetected ? 'Started talking!' : 'Stopped talking'
      );
    }
    console.log('Confidence:', event.confidence);
    console.log('Event type:', event.eventType);
  }
);

// Start recording - VAD will automatically start
ExpoAudioStudio.startRecording();
```

### Capture audio chunks

```typescript
import ExpoAudioStudio from 'expo-audio-studio';

// Enable chunk listening (disabled by default)
ExpoAudioStudio.setListenToChunks(true);

// Store chunks as they arrive
const audioChunks: string[] = [];

// Listen to audio chunks during recording
const chunkSubscription = ExpoAudioStudio.addListener(
  'onAudioChunk',
  (event: AudioChunkEvent) => {
    // event contains raw PCM audio data chunks
    if (event.type === 'batch' && event.chunks) {
      console.log('Received chunks:', event.chunks.length);
    }
  }
);

// Start recording - chunks will be sent in real-time
ExpoAudioStudio.startRecording();

// Later, when stopping...
await ExpoAudioStudio.stopRecording();

// Process the chunks - decode, concatenate, add WAV header, etc.
// See example/components/ChunkRecorder.tsx for full implementation

// Cleanup
chunkSubscription.remove();
ExpoAudioStudio.setListenToChunks(false);
```

### Play audio

```typescript
import ExpoAudioStudio from 'expo-audio-studio';

// Listen to playback events
const playerSubscription = ExpoAudioStudio.addListener(
  'onPlayerStatusChange',
  (event: PlayerStatusChangeEvent) => {
    console.log('Playing:', event.isPlaying);
  }
);

// Start playback
ExpoAudioStudio.startPlaying('/path/to/audio/file.wav');

// Control playback speed
ExpoAudioStudio.setPlaybackSpeed('1.5'); // 1.5x speed

// Cleanup
playerSubscription.remove();
```

## The actual API

### Recording stuff

| Function                         | Description                            | Returns                             |
| -------------------------------- | -------------------------------------- | ----------------------------------- |
| `startRecording(directoryPath?)` | Start audio recording                  | `string` - File path                |
| `stopRecording()`                | Stop recording (async)                 | `Promise<string>` - Final file path |
| `pauseRecording()`               | Pause recording                        | `string` - Status message           |
| `resumeRecording()`              | Resume recording                       | `string` - Status message           |
| `setListenToChunks(enabled)`     | Enable/disable real-time chunk capture | `boolean` - Enabled state           |
| `lastRecording()`                | Get last recording path                | `string` or `null`                  |

### Playback stuff

| Function                  | Description                  | Returns           |
| ------------------------- | ---------------------------- | ----------------- |
| `startPlaying(path)`      | Start audio playback         | `string` - Status |
| `stopPlayer()`            | Stop playback                | `string` - Status |
| `pausePlayer()`           | Pause playback               | `string` - Status |
| `resumePlayer()`          | Resume playback              | `string` - Status |
| `setPlaybackSpeed(speed)` | Set playback speed (0.5-2.0) | `string` - Status |
| `seekTo(position)`        | Seek to position in seconds  | `string` - Status |

### VAD (Voice Activity Detection)

| Function                               | Description                       | Returns           |
| -------------------------------------- | --------------------------------- | ----------------- |
| `setVADEnabled(enabled)`               | Enable/disable VAD                | `string` - Status |
| `setVoiceActivityThreshold(threshold)` | Set detection threshold (0.0-1.0) | `string` - Status |
| `setVADEventMode(mode, throttleMs?)`   | Control event frequency           | `string` - Status |

### Audio analysis

| Function                                 | Description                   | Returns                        |
| ---------------------------------------- | ----------------------------- | ------------------------------ |
| `getDuration(uri)`                       | Get audio file duration       | `number` - Duration in seconds |
| `getAudioAmplitudes(fileUrl, barsCount)` | Get waveform data (dB values) | `object` - Amplitude data      |
| `setAmplitudeUpdateFrequency(hz)`        | Set amplitude update rate     | `string` - Status              |

### Constants you can check

| Constant          | Description                                  | Returns   |
| ----------------- | -------------------------------------------- | --------- |
| `currentPosition` | Current playback position in seconds         | `number`  |
| `meterLevel`      | Current audio level during recording (in dB) | `number`  |
| `playerStatus`    | Detailed player status information           | `object`  |
| `isVADActive`     | Whether VAD is currently active              | `boolean` |
| `isVADEnabled`    | Whether VAD is enabled by user preference    | `boolean` |

### File stuff

| Function                                | Description             | Returns                |
| --------------------------------------- | ----------------------- | ---------------------- |
| `listRecordings(directoryPath?)`        | List audio files        | `array` - File list    |
| `joinAudioFiles(filePaths, outputPath)` | Concatenate audio files | `string` - Output path |

### Events

#### Recording events

```typescript
import ExpoAudioStudio from 'expo-audio-studio';

const subscription = ExpoAudioStudio.addListener(
  'onRecorderStatusChange',
  (event: AudioRecordingStateChangeEvent) => {
    // event.status: 'recording' | 'stopped' | 'paused' | 'resumed' | 'error'
    // event.errorCode?: RecordingErrorCode - Only present when status is 'error'
    // event.errorMessage?: string - Human-readable error description

    if (event.status === 'error') {
      console.error(
        `Recording error [${event.errorCode}]: ${event.errorMessage}`
      );
    }
  }
);
```

##### Recording Error Codes

When `status` is `'error'`, the event includes `errorCode` and `errorMessage`:

| Error Code               | Description                                |
| ------------------------ | ------------------------------------------ |
| `RECORDER_STATE_ERROR`   | Unexpected recorder state during operation |
| `RECORDER_CREATE_FAILED` | Failed to create AVAudioRecorder           |
| `RECORDER_START_FAILED`  | `recorder.record()` returned false         |
| `RECORDER_SETUP_ERROR`   | Exception during recording setup           |
| `RECORDER_ENCODE_ERROR`  | Encode error during recording              |

```typescript
// Example: Handle specific error types
ExpoAudioStudio.addListener('onRecorderStatusChange', event => {
  if (event.status === 'error') {
    switch (event.errorCode) {
      case 'RECORDER_CREATE_FAILED':
        // Microphone permission issue or hardware problem
        Alert.alert('Recording Error', 'Could not access microphone');
        break;
      case 'RECORDER_ENCODE_ERROR':
        // Storage issue or codec problem
        Alert.alert('Recording Error', 'Failed to save recording');
        break;
      default:
        Alert.alert('Recording Error', event.errorMessage || 'Unknown error');
    }
  }
});

const subscription = ExpoAudioStudio.addListener(
  'onRecorderAmplitude',
  (event: AudioMeteringEvent) => {
    // event.amplitude: number (dB level)
  }
);

// Listen to audio chunks (requires setListenToChunks(true))
const chunkSubscription = ExpoAudioStudio.addListener(
  'onAudioChunk',
  (event: AudioChunkEvent) => {
    // Binary format:
    // event.chunks: Array of chunk objects when VAD detects voice
    // event.type: 'batch' - indicates batch mode
    // event.format: 'uint8array' - data format
    // Each chunk contains:
    //   - data: number[] - PCM audio as integers (0-255)
    //   - timestamp: number - When chunk was captured
    //   - hasVoice: boolean - VAD detection result
    //   - size: number - Chunk size in bytes
  }
);
```

#### Voice Activity Events

```typescript
import ExpoAudioStudio from 'expo-audio-studio';

const subscription = ExpoAudioStudio.addListener(
  'onVoiceActivityDetected',
  (event: VoiceActivityEvent) => {
    // event.isVoiceDetected: boolean - Is someone speaking?
    // event.confidence: number - How confident is the detection (0.0-1.0)
    // event.timestamp: number - When this happened
    // event.isStateChange: boolean - Did the state just change?
    // event.previousState: boolean - What was the previous state
    // event.eventType: 'speech_start' | 'speech_continue' | 'silence_start' | 'silence_continue'
  }
);
```

#### Playback Events

```typescript
import ExpoAudioStudio from 'expo-audio-studio';

// Listen to player status changes
const subscription = ExpoAudioStudio.addListener(
  'onPlayerStatusChange',
  (event: PlayerStatusChangeEvent) => {
    // event.isPlaying: boolean
    // event.didJustFinish: boolean
  }
);
```

#### iOS Lifecycle Events (iOS only)

These events help you handle audio session interruptions, device changes, and
app state transitions. **Important**: The native code does NOT auto-resume or
auto-stop - it notifies your JS code and lets you decide what to do.

```typescript
import ExpoAudioStudio from 'expo-audio-studio';
import type {
  InterruptionEndedEvent,
  RouteChangeEvent,
  AppStateChangeEvent,
} from 'expo-audio-studio';

// Called when audio interruption ends (phone call ended, Siri dismissed, etc.)
// Native code does NOT auto-resume - you decide whether to resume
const interruptionSub = ExpoAudioStudio.addListener(
  'onInterruptionEnded',
  (event: InterruptionEndedEvent) => {
    console.log('Interruption ended');
    console.log('Can resume:', event.canResume);
    console.log('Was playing:', event.wasPlayingBeforeInterruption);
    console.log('Was recording:', event.wasRecordingBeforeInterruption);

    // You control recovery - native code waits for your decision
    if (event.canResume && event.wasRecordingBeforeInterruption) {
      // Reactivate session and resume recording
      await ExpoAudioStudio.activateAudioSession();
      ExpoAudioStudio.resumeRecording();
    }
  }
);

// Called when audio route changes (Bluetooth connect/disconnect, headphones, etc.)
// Recording continues automatically on built-in mic when Bluetooth disconnects
const routeSub = ExpoAudioStudio.addListener(
  'onRouteChange',
  (event: RouteChangeEvent) => {
    console.log('Route changed:', event.reason);
    console.log('Message:', event.message);
    console.log('Still recording:', event.isRecording);

    // Possible reasons: 'deviceDisconnected', 'deviceConnected', 'categoryChange', 'override', 'unknown'
    if (event.reason === 'deviceDisconnected') {
      // Inform user that recording continues on built-in mic
      showToast('Bluetooth disconnected. Recording continues on phone mic.');
    }
  }
);

// Called when app goes to background or returns to foreground
const stateSub = ExpoAudioStudio.addListener(
  'onAppStateChange',
  (event: AppStateChangeEvent) => {
    console.log('App state:', event.state); // 'background' | 'foreground'
    console.log('Recording active:', event.isRecording);
    console.log('Playing active:', event.isPlaying);

    if (event.state === 'background' && event.isRecording) {
      // Recording continues in background if UIBackgroundModes audio is enabled
      // You can decide to stop here if you don't want background recording
    }
  }
);

// Don't forget to cleanup
interruptionSub.remove();
routeSub.remove();
stateSub.remove();
```

##### iOS Event Types

| Event                 | When it fires                             | What you should do                                  |
| --------------------- | ----------------------------------------- | --------------------------------------------------- |
| `onInterruptionEnded` | Phone call ends, Siri dismisses           | Decide whether to resume recording/playback         |
| `onRouteChange`       | Bluetooth disconnects, headphones plug in | Inform user, recording auto-continues on new device |
| `onAppStateChange`    | App backgrounds/foregrounds               | Handle background recording policy                  |

````

## Audio Chunk Processing (the good stuff)

Okay so here's the deal - audio chunks now stream as raw binary data, not that
Base64 nonsense. And they only get sent when someone's actually talking (thanks
to VAD), which cuts your data transfer by like 60-80%. Pretty neat.

### Why this chunk format doesn't suck

- **No more Base64** - Saves like 33% memory (your users' phones will thank you)
- **Smart VAD filtering** - Only sends chunks when someone's talking, not
  silence
- **Batched for speed** - Groups chunks together, 90% fewer bridge calls
- **Won't crash your app** - Hard 8KB limit per chunk
- **Zero-copy** - Data goes straight from native to JS, no middleman BS

### Stream to AI services (OpenAI, Deepgram, etc)

Alright, this is probably why you're here. Here's how to stream audio to AI
services in real-time:

```typescript
import ExpoAudioStudio from 'expo-audio-studio';

class AudioStreamer {
  private ws: WebSocket | null = null;
  private isStreaming = false;

  // Connect to AI service (e.g., OpenAI Realtime, Deepgram, etc.)
  async connect(url: string, apiKey: string) {
    this.ws = new WebSocket(url);

    this.ws.onopen = () => {
      console.log('Connected to AI service');
      // Send authentication if needed
      this.ws?.send(
        JSON.stringify({
          type: 'auth',
          api_key: apiKey,
        })
      );
    };

    this.ws.onmessage = event => {
      const response = JSON.parse(event.data);
      // Handle AI responses (transcription, etc.)
      console.log('AI Response:', response);
    };
  }

  // Start recording and streaming
  async startStreaming() {
    // Enable chunk capture with VAD filtering
    ExpoAudioStudio.setListenToChunks(true);

    // Listen to audio chunks (Dont forget to remove listener)
    ExpoAudioStudio.addListener('onAudioChunk', event => {
      if (
        event.type === 'batch' &&
        event.chunks &&
        this.ws?.readyState === WebSocket.OPEN
      ) {
        // Stream each chunk to AI service
        event.chunks.forEach(chunk => {
          // Send as binary WebSocket frame
          this.ws?.send(chunk);
        });
      }
    });

    // Start recording - chunks will be sent in real-time
    this.isStreaming = true;
    ExpoAudioStudio.startRecording();
  }

  // Stop streaming
  async stopStreaming() {
    await ExpoAudioStudio.stopRecording();
    ExpoAudioStudio.setListenToChunks(false);
    this.isStreaming = false;

    // Close WebSocket
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
  }
}

// Usage example
const streamer = new AudioStreamer();

// Connect to OpenAI Realtime API
await streamer.connect('wss://api.openai.com/v1/realtime', 'your-api-key');

// Or Deepgram
// await streamer.connect(
//   'wss://api.deepgram.com/v1/listen?encoding=linear16&sample_rate=16000&channels=1',
//   'your-deepgram-key'
// );

// Start streaming audio
await streamer.startStreaming();

// Later, stop streaming
await streamer.stopStreaming();
````

### Stream to your own backend

If you've got your own server, here's a smarter way (batch chunks to reduce
network calls):

```typescript
import ExpoAudioStudio from 'expo-audio-studio';

const streamToBackend = async (apiEndpoint: string) => {
  const chunkBuffer: Uint8Array[] = [];
  const BATCH_SIZE = 5; // Send every 5 chunks

  ExpoAudioStudio.setListenToChunks(true);

  ExpoAudioStudio.addListener('onAudioChunk', async event => {
    if (event.type === 'batch' && event.chunks) {
      // Process and store them
      chunkBuffer.push(...event.chunks);

      // Send when we have enough chunks
      if (chunkBuffer.length >= BATCH_SIZE) {
        const chunksToSend = chunkBuffer.splice(0, BATCH_SIZE);

        // Convert to format your backend expects
        const audioData = chunksToSend.map(chunk => Array.from(chunk));

        try {
          const response = await fetch(apiEndpoint, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
            },
            body: JSON.stringify({
              audio_chunks: audioData,
              format: 'pcm16',
              sample_rate: 16000,
              timestamp: Date.now(),
            }),
          });

          const result = await response.json();
          console.log('Backend response:', result);
        } catch (error) {
          console.error('Failed to send chunks:', error);
        }
      }
    }
  });

  // Start recording
  ExpoAudioStudio.startRecording();
};
```

#### Smart voice assistant pattern

```typescript
// This pattern works great for voice assistants, live transcription, etc.
let audioQueue: Uint8Array[] = [];

// Setup VAD to only send when user is speaking
ExpoAudioStudio.setVADEnabled(true);
ExpoAudioStudio.setVoiceActivityThreshold(0.5);

ExpoAudioStudio.addListener('onAudioChunk', event => {
  if (event.type === 'batch' && event.chunks) {
    // Process batch of chunks (up to 10 at once)
    audioQueue.push(...voiceChunks);
  }
});

ExpoAudioStudio.addListener('onVoiceActivityDetected', async event => {
  // User stopped speaking - send accumulated audio
  if (event.eventType === 'silence_start' && audioQueue.length > 0) {
    // Send to your AI service
    await sendToAI(audioQueue);
    audioQueue = [];
  }
});
```

### Save recordings wherever you want

```typescript
const filePath = startRecording('/path/to/custom/directory');
```

### iOS audio session setup

> **WARNING:** Don't touch the audio session config while recording or playing.
> Your app WILL freeze for seconds. I learned this the hard way.

```typescript
import ExpoAudioStudio from 'expo-audio-studio';

await ExpoAudioStudio.configureAudioSession({
  category: 'playAndRecord',
  mode: 'default',
  options: {
    defaultToSpeaker: true,
    allowBluetooth: true,
  },
});

await ExpoAudioStudio.activateAudioSession();
```

### Tweak voice detection sensitivity

```typescript
// More sensitive (for quiet rooms)
ExpoAudioStudio.setVoiceActivityThreshold(0.3);

// Less sensitive (for noisy places)
ExpoAudioStudio.setVoiceActivityThreshold(0.7);

ExpoAudioStudio.setVADEnabled(true);
```

### Real example: Voice-activated recording

Here's something I actually use in production:

```typescript
import ExpoAudioStudio from 'expo-audio-studio';

// Only notify on state changes for battery efficiency
ExpoAudioStudio.setVADEventMode('onChange');
ExpoAudioStudio.setVADEnabled(true);

const subscription = ExpoAudioStudio.addListener(
  'onVoiceActivityDetected',
  (event: VoiceActivityEvent) => {
    if (event.eventType === 'speech_start') {
      console.log('🎤 Voice detected');
    } else if (event.eventType === 'silence_start') {
      console.log('🔇 Silence detected');
    }
  }
);
```

#### Example 2

```typescript
import ExpoAudioStudio from 'expo-audio-studio';

// Only notify in every 250ms
ExpoAudioStudio.setVADEventMode('throttled', 250);
ExpoAudioStudio.setVADEnabled(true);

const subscription = ExpoAudioStudio.addListener(
  'onVoiceActivityDetected',
  (event: VoiceActivityEvent) => {
    if (event.isVoiceDetected) {
      console.log('Voice detected');
    } else {
      console.log('Silence detected');
    }
  }
);
```

### Get waveform data

```typescript
const waveformData = ExpoAudioStudio.getAudioAmplitudes(
  '/path/to/file.wav',
  100
);
console.log('Amplitude values:', waveformData.amplitudes);

// Normalize for UI visualization (dB to 0-1 range)
const normalized = waveformData.amplitudes.map(dB =>
  Math.max(0, (dB + 60) / 60)
);

const duration = ExpoAudioStudio.getDuration('/path/to/file.wav');
```

### Merge audio files

```typescript
const inputFiles = [
  '/path/to/file1.wav',
  '/path/to/file2.wav',
  '/path/to/file3.wav',
];

const outputPath = '/path/to/joined_audio.wav';
const result = ExpoAudioStudio.joinAudioFiles(inputFiles, outputPath);
console.log('Joined file created:', result);
```

## Audio format

Recordings use WAV format (PCM16, 16kHz, 16-bit mono) on both platforms. This
provides good quality while keeping file sizes reasonable.

### How it works (if you care)

#### iOS

Uses AVAudioEngine for recording, Core ML for detecting when someone's talking
(iOS 14+). Records at 16kHz mono PCM. Handles all the annoying AVAudioSession
stuff so you don't have to.

#### Android

AndroidWaveRecorder for recording, Silero VAD for voice detection (seriously,
Silero is amazing). Same audio format as iOS. Uses hardware acceleration when
available, has noise suppression on newer phones.

## Voice detection details

Both iOS and Android now fire the same events, so you can expect identical
behavior.

### Controlling event frequency

You can choose how often you want to receive voice detection events:

```typescript
// Get events for every audio frame processed (~32ms, about 30 per second)
// Perfect for real-time visualizations or instant response
ExpoAudioStudio.setVADEventMode('onEveryFrame');

// Only get notified when voice state changes (speech starts/stops)
// Battery-friendly and great for simple on/off detection
ExpoAudioStudio.setVADEventMode('onChange');

// Get updates every X milliseconds, plus immediate state changes
// Nice balance between real-time and performance
ExpoAudioStudio.setVADEventMode('throttled', 250); // every 250ms
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

### Development

Want to contribute or just mess around with the code?

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

Got ideas? Found bugs? Send a PR. Just don't break anything that already works.

1. Fork the repo
2. Create a branch: `git checkout -b feature/my-feature`
3. Make your changes
4. Push and open a pull request

## License

MIT - do whatever you want with it

---

<div align="center">
  <p><strong>Made with frustration and coffee in Tbilisi 🇬🇪</strong></p>
</div>
