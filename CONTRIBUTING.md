# Contributing to Expo Audio Studio

Thank you for your interest in contributing to Expo Audio Studio! This document provides guidelines and information for contributors.

## 🚀 Getting Started

### Prerequisites

- Node.js 18+ and npm
- Expo CLI (`npm install -g @expo/cli`)
- iOS development: Xcode 14+ and iOS 13+ device/simulator
- Android development: Android Studio and Android 5.0+ (API 21+) device/emulator

### Development Setup

1. **Fork and Clone**
   ```bash
   git clone https://github.com/YOUR_USERNAME/expo-audio-studio.git
   cd expo-audio-studio
   ```

2. **Install Dependencies**
   ```bash
   npm install
   ```

3. **Run the Example App**
   ```bash
   cd example
   npm install
   npx expo run:ios
   # or
   npx expo run:android
   ```

## 🏗️ Project Structure

```
expo-audio-studio/
├── src/                          # TypeScript source code
│   ├── index.ts                  # Main exports
│   ├── ExpoAudioStudioModule.ts  # Module interface
│   └── ExpoAudioStudio.types.ts  # Type definitions
├── ios/                          # iOS native implementation
│   ├── ExpoAudioStudioModule.swift
│   ├── Recorder/                 # Recording components
│   ├── Player/                   # Playback components
│   └── SoundClassification/      # VAD implementation
├── android/                      # Android native implementation
│   └── src/main/java/expo/modules/audiostudio/
│       ├── ExpoAudioHandlerModule.kt
│       ├── recorder/             # Recording components
│       ├── player/               # Playback components
│       └── AudioAmplitudeAnalyzer.kt
├── example/                      # Example React Native app
└── docs/                         # Additional documentation
```

## 🛠️ Development Workflow

### Making Changes

1. **Create a Feature Branch**
   ```bash
   git checkout -b feature/your-feature-name
   ```

2. **Make Your Changes**
   - Follow the existing code style
   - Add tests for new functionality
   - Update documentation as needed

3. **Test Your Changes**
   ```bash
   # Run TypeScript checks
   npm run build
   
   # Test in example app
   cd example
   npx expo run:ios
   npx expo run:android
   ```

4. **Commit and Push**
   ```bash
   git add .
   git commit -m "feat: add amazing new feature"
   git push origin feature/your-feature-name
   ```

5. **Create Pull Request**
   - Use the PR template
   - Include screenshots/videos for UI changes
   - Reference any related issues

### Code Style

- **TypeScript**: Use strict typing, avoid `any`
- **Native Code**: Follow platform conventions (Swift for iOS, Kotlin for Android)
- **Naming**: Use descriptive names, follow existing patterns
- **Comments**: Document complex logic and public APIs

### Commit Messages

We follow [Conventional Commits](https://www.conventionalcommits.org/):

- `feat:` - New features
- `fix:` - Bug fixes
- `docs:` - Documentation changes
- `style:` - Code style changes
- `refactor:` - Code refactoring
- `test:` - Test additions/changes
- `chore:` - Build process or auxiliary tool changes

Examples:
```
feat: add voice activity detection threshold configuration
fix: resolve iOS recording permission issue
docs: update API documentation for new VAD features
```

## 🧪 Testing

### Manual Testing

1. **Test on Both Platforms**
   - iOS simulator and physical device
   - Android emulator and physical device

2. **Test Core Features**
   - Audio recording (start, stop, pause, resume)
   - Audio playback (play, pause, seek, speed control)
   - Voice Activity Detection
   - File management operations
   - Permission handling

3. **Test Edge Cases**
   - No microphone permission
   - Background/foreground transitions
   - Audio interruptions (calls, other apps)
   - Low storage scenarios

### Automated Testing

```bash
# Run TypeScript tests
npm test

# Run linting
npm run lint

# Type checking
npm run type-check
```

## 📝 Documentation

### API Documentation

- Update JSDoc comments for new/changed functions
- Include usage examples in comments
- Update type definitions in `ExpoAudioStudio.types.ts`

### README Updates

- Add new features to the features list
- Update API reference tables
- Add usage examples for new functionality

### Changelog

Update `CHANGELOG.md` with your changes:

```markdown
## [Unreleased]

### Added
- New voice activity detection threshold configuration

### Fixed
- iOS recording permission issue

### Changed
- Improved error handling in playback functions
```

## 🐛 Bug Reports

When reporting bugs, please include:

1. **Environment Information**
   - Expo SDK version
   - React Native version
   - Platform (iOS/Android) and version
   - Device model

2. **Steps to Reproduce**
   - Clear, numbered steps
   - Expected vs actual behavior
   - Screenshots/videos if applicable

3. **Code Sample**
   - Minimal reproducible example
   - Relevant configuration

## 💡 Feature Requests

For new features:

1. **Check Existing Issues** - Avoid duplicates
2. **Describe the Use Case** - Why is this needed?
3. **Propose Implementation** - How should it work?
4. **Consider Alternatives** - Are there other solutions?

## 🔄 Release Process

### Version Bumping

We follow [Semantic Versioning](https://semver.org/):

- **Major** (1.0.0): Breaking changes
- **Minor** (0.1.0): New features, backward compatible
- **Patch** (0.0.1): Bug fixes, backward compatible

### Release Checklist

- [ ] Update version in `package.json`
- [ ] Update `CHANGELOG.md`
- [ ] Test on both platforms
- [ ] Update documentation
- [ ] Create GitHub release
- [ ] Publish to npm

## 🤝 Community Guidelines

### Code of Conduct

- Be respectful and inclusive
- Focus on constructive feedback
- Help others learn and grow
- Follow the [Contributor Covenant](https://www.contributor-covenant.org/)

### Getting Help

- 💬 [GitHub Discussions](https://github.com/sgamrekelashvili/expo-audio-studio/discussions) - General questions
- 🐛 [GitHub Issues](https://github.com/sgamrekelashvili/expo-audio-studio/issues) - Bug reports
- 📧 [Email](mailto:sgamrekelashvili@gmail.com) - Direct contact

## 🏆 Recognition

Contributors will be:

- Listed in the README contributors section
- Mentioned in release notes
- Given credit in commit messages
- Invited to join the maintainers team (for significant contributions)

## 📚 Resources

- [Expo Modules API](https://docs.expo.dev/modules/overview/)
- [React Native Documentation](https://reactnative.dev/docs/getting-started)
- [iOS Audio Development](https://developer.apple.com/documentation/avfoundation)
- [Android Audio Development](https://developer.android.com/guide/topics/media/audio-capture)

---

Thank you for contributing to Expo Audio Studio! Your efforts help make audio development easier for the entire React Native community. 🎉
