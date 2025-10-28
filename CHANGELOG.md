## [1.2.6](https://github.com/SGamrekelashvili/expo-audio-studio/compare/v1.2.5...v1.2.6) (2025-10-28)


### Bug Fixes

* **android:** added missing setPlaybackSpeed api ([6a26eb9](https://github.com/SGamrekelashvili/expo-audio-studio/commit/6a26eb9e34644e3db0acbf539a7029ded90e0424))

## [1.2.5](https://github.com/SGamrekelashvili/expo-audio-studio/compare/v1.2.4...v1.2.5) (2025-10-28)


### Bug Fixes

* **android:** api and better class handling ([1cd132d](https://github.com/SGamrekelashvili/expo-audio-studio/commit/1cd132d4ef151295233c14f4ddda0014ff747b97))

## [1.2.4](https://github.com/SGamrekelashvili/expo-audio-studio/compare/v1.2.3...v1.2.4) (2025-10-28)


### Bug Fixes

* **api:** import ([4f26b69](https://github.com/SGamrekelashvili/expo-audio-studio/commit/4f26b6971ec0254b937632f09a52dfc42c4f282a))

## [1.2.3](https://github.com/SGamrekelashvili/expo-audio-studio/compare/v1.2.2...v1.2.3) (2025-10-28)


### Bug Fixes

* **android:** playback fully return duration when player end ([65518ce](https://github.com/SGamrekelashvili/expo-audio-studio/commit/65518cef169446405ef4e2a778f6c4899d451655))
* **api:** update imports & docs ([e8f7bed](https://github.com/SGamrekelashvili/expo-audio-studio/commit/e8f7bed968cf0314f8bd6db7276da5d837831490))

## [1.2.2](https://github.com/SGamrekelashvili/expo-audio-studio/compare/v1.2.1...v1.2.2) (2025-10-27)


### Bug Fixes

* **api:** move up setVADEventMode api ([fc1035d](https://github.com/SGamrekelashvili/expo-audio-studio/commit/fc1035d84540a442725754a9bc8116698499bf72))

## [1.2.1](https://github.com/SGamrekelashvili/expo-audio-studio/compare/v1.2.0...v1.2.1) (2025-10-27)


### Bug Fixes

* **ios:** remove unused function ([40eb942](https://github.com/SGamrekelashvili/expo-audio-studio/commit/40eb942909eaf1ee3dc6f06e5bd7cecd7e87714e))

# [1.2.0](https://github.com/SGamrekelashvili/expo-audio-studio/compare/v1.1.2...v1.2.0) (2025-10-27)


### Bug Fixes

* **android:** dead zones ([d9e83d1](https://github.com/SGamrekelashvili/expo-audio-studio/commit/d9e83d174f23af0da47b5a941ff80a8837ecf735))
* **api:** remove mettering from voiceActivityEvent becuase it was platform specific ([99e4b11](https://github.com/SGamrekelashvili/expo-audio-studio/commit/99e4b11861a5cc392350c492c28a0b8e8872f292))
* **api:** remove mettering from voiceActivityEvent becuase it was platform specific ([8a928c1](https://github.com/SGamrekelashvili/expo-audio-studio/commit/8a928c1c9125ff7e0b02917af62c587e358dc379))
* **api:** remove unused types ([359445b](https://github.com/SGamrekelashvili/expo-audio-studio/commit/359445b5e98a72610155161ea2f096ffd0399492))
* **docs:** platform specific session management ([8bd2275](https://github.com/SGamrekelashvili/expo-audio-studio/commit/8bd2275b8cf1ec7c7e47aec9ad65db83f62d92c1))
* **ios:** dead zones ([d38eb8a](https://github.com/SGamrekelashvili/expo-audio-studio/commit/d38eb8a7e5833dce01b20633c92a7241666da6ba))
* **vad:** configurations types and deprecated apis ([1f13e99](https://github.com/SGamrekelashvili/expo-audio-studio/commit/1f13e992a83c8c0eeeb006b4d13eb9613565481c))


### Features

* fixing apis with correct response types ([afaaedf](https://github.com/SGamrekelashvili/expo-audio-studio/commit/afaaedfa28d4dfa1fab5ca9e9b86acf5c3d5b6c3))

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
