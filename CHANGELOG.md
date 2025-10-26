# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2025-10-25

### Added
- 🎙️ **High-Quality Recording** - WAV format on both iOS and Android platforms
- 🎵 **Advanced Playback** - Speed control, seeking, pause/resume functionality
- 🧠 **Voice Activity Detection** - Real-time speech detection with confidence scoring
  - iOS: Core ML-based Sound Classification
  - Android: Silero VAD (Deep Neural Network-based)
- 📊 **Audio Analysis** - Amplitude monitoring, duration calculation, waveform data
- 📁 **File Management** - List, join, and organize audio files
- 🔄 **Cross-Platform** - Identical API on iOS and Android
- ⚡ **Performance Optimized** - Native implementations for smooth operation
- 🛡️ **Type Safe** - Full TypeScript support with comprehensive types
- 📱 **iOS Audio Session Management** - Configurable audio session with category, mode, and options
- 🎯 **Production Ready** - Tested in large-scale applications

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
