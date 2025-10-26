## [1.1.2](https://github.com/SGamrekelashvili/expo-audio-studio/compare/v1.1.1...v1.1.2) (2025-10-26)


### Bug Fixes

* **ios:** use rate property instead of getPlaybackSpeed() method ([b85c87e](https://github.com/SGamrekelashvili/expo-audio-studio/commit/b85c87e185c976fd6e0af674b731bd2b0d35dc90))

## [1.1.1](https://github.com/SGamrekelashvili/expo-audio-studio/compare/v1.1.0...v1.1.1) (2025-10-26)


### Bug Fixes

* **config:** configure automatic config plugin ([4e35ac9](https://github.com/SGamrekelashvili/expo-audio-studio/commit/4e35ac99b25c981cb74037be2f0f01d24350ae54))

# [1.1.0](https://github.com/SGamrekelashvili/expo-audio-studio/compare/v1.0.0...v1.1.0) (2025-10-26)


### Bug Fixes

* **ci:** configure git authentication for semantic-release ([f24caab](https://github.com/SGamrekelashvili/expo-audio-studio/commit/f24caabb1d67bb84bb762136b3da99ae2af5fef1))
* **ci:** use personal access token for semantic-release ([123bcd0](https://github.com/SGamrekelashvili/expo-audio-studio/commit/123bcd080b6ccd8f3a444e5f6f7fc8af54744ceb))
* **config:** update plugin package name reference ([47ea418](https://github.com/SGamrekelashvili/expo-audio-studio/commit/47ea418142520d0bbc0ba560f4178a224d424925))
* **playback:** standardize playerStatus interface across platforms ([e4a7377](https://github.com/SGamrekelashvili/expo-audio-studio/commit/e4a7377b7e1279a4df6c834b69996bfdb8dbf550))
* **types:** standardize playerStatus interface to match native implementations ([2791b39](https://github.com/SGamrekelashvili/expo-audio-studio/commit/2791b3903f0ce9fdb9c791760d6419f94b49cba7))


### Features

* **config:** configure automatic config plugin ([6aa8ada](https://github.com/SGamrekelashvili/expo-audio-studio/commit/6aa8adac2c2cc1240a3a2179f3c89c1af16fbec7))

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2025-10-25

### Added
- **High-Quality Recording** - WAV format on both iOS and Android platforms
- **Playback Controls** - Speed control, seeking, pause/resume functionality
- **Voice Activity Detection** - Real-time speech detection with confidence scoring
  - iOS: Core ML-based Sound Classification
  - Android: Silero VAD (Deep Neural Network-based)
- **Audio Analysis** - Amplitude monitoring, duration calculation, waveform data
- **File Management** - List, join, and organize audio files
- **Cross-Platform** - Identical API on iOS and Android
- **Performance Optimized** - Native implementations for smooth operation
- **Type Safe** - Full TypeScript support with comprehensive types
- **iOS Audio Session Management** - Configurable audio session with category, mode, and options
- **Production Ready** - Tested in large-scale applications

### Features
- Recording with pause/resume functionality
- Playback with variable speed (0.5x - 2.0x)
- Real-time amplitude monitoring during recording
- Voice Activity Detection with configurable thresholds
- Audio file concatenation
- Waveform data extraction for visualization
- Comprehensive event system for recording, playback, and VAD
- Microphone permission management

### Platform Support
- iOS 13.0+ (Core ML required for VAD)
- Android API 21+ (Android 5.0)

### Dependencies
- AndroidWaveRecorder 2.1.0 (MIT License)
- Silero VAD 2.0.10 (MIT License)
- Core ML (Built-in Apple framework)
- AVFoundation (Built-in Apple framework)

[1.0.0]: https://github.com/SGamrekelashvili/expo-audio-studio/releases/tag/v1.0.0
