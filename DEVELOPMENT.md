# 🛠️ Development Guide

## 🚀 Quick Start

### Prerequisites
- Node.js 18+ 
- Yarn or npm
- Xcode (for iOS development)
- Android Studio (for Android development)

### Setup
```bash
# Install dependencies
yarn install

# Setup git hooks
yarn prepare

# Run type checking
yarn type-check

# Run linting
yarn lint

# Run tests
yarn test
```

## 📋 Available Scripts

### 🔧 Development
- `yarn build` - Build the module
- `yarn clean` - Clean build artifacts
- `yarn type-check` - Run TypeScript type checking
- `yarn validate` - Run all checks (type-check + lint + format + test)

### 🧹 Code Quality
- `yarn lint` - Run ESLint with auto-fix
- `yarn lint:check` - Run ESLint without auto-fix
- `yarn format` - Format code with Prettier
- `yarn format:check` - Check code formatting

### 🧪 Testing
- `yarn test` - Run tests once
- `yarn test:watch` - Run tests in watch mode
- `yarn test:coverage` - Run tests with coverage report

### 📱 Example App
- `yarn example:install` - Install example app dependencies
- `yarn example:ios` - Run example on iOS
- `yarn example:android` - Run example on Android

### 🔧 Native Development
- `yarn open:ios` - Open iOS project in Xcode
- `yarn open:android` - Open Android project in Android Studio

## 🎯 Code Quality Standards

### ESLint Configuration
We use a comprehensive ESLint setup with:
- **TypeScript support** - Full type checking
- **React/React Native rules** - Best practices
- **Prettier integration** - Consistent formatting
- **Custom rules** - Project-specific standards

### Prettier Configuration
- **Single quotes** for strings
- **Semicolons** required
- **2 spaces** for indentation
- **100 character** line length
- **Trailing commas** in ES5 contexts

### Commit Message Format
We use [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

**Types:**
- `feat` - New feature
- `fix` - Bug fix
- `docs` - Documentation changes
- `style` - Code style changes
- `refactor` - Code refactoring
- `perf` - Performance improvements
- `test` - Adding or updating tests
- `build` - Build system changes
- `ci` - CI/CD changes
- `chore` - Other changes
- `vad` - Voice Activity Detection specific
- `audio` - Audio processing specific

**Scopes:**
- `vad` - Voice Activity Detection
- `recording` - Audio recording
- `playback` - Audio playback
- `types` - TypeScript definitions
- `api` - API changes
- `ios` - iOS specific
- `android` - Android specific
- `docs` - Documentation
- `example` - Example app
- `tests` - Test files
- `config` - Configuration

**Examples:**
```bash
feat(vad): Add real-time confidence scoring
fix(recording): Resolve pause/resume state sync issue
docs(api): Update VAD configuration examples
test(vad): Add comprehensive VAD session tests
```

## 🧪 Testing Strategy

### Test Structure
```
src/
├── __tests__/
│   ├── index.test.ts          # Main API tests
│   ├── vad.test.ts           # VAD-specific tests
│   └── types.test.ts         # Type validation tests
└── ...
```

### Test Categories
1. **Unit Tests** - Individual function testing
2. **Integration Tests** - Component interaction testing
3. **Type Tests** - TypeScript type validation
4. **Mock Tests** - Native module mocking

### Coverage Requirements
- **Branches**: 70%
- **Functions**: 70%
- **Lines**: 70%
- **Statements**: 70%

### Running Tests
```bash
# Run all tests
yarn test

# Run specific test file
yarn test vad.test.ts

# Run tests in watch mode
yarn test:watch

# Generate coverage report
yarn test:coverage
```

## 🔄 Git Workflow

### Pre-commit Hooks
Automatically runs on `git commit`:
1. **Lint-staged** - Lint and format staged files
2. **Type checking** - Validate TypeScript types
3. **Tests** - Run affected tests

### Commit Message Validation
Automatically runs on `git commit`:
- Validates commit message format
- Ensures conventional commit standards
- Provides helpful error messages

### Branch Protection
Recommended branch protection rules:
- Require pull request reviews
- Require status checks to pass
- Require up-to-date branches
- Restrict pushes to main branch

## 📁 Project Structure

```
expo-audio-studio/
├── src/                          # TypeScript source code
│   ├── __tests__/               # Test files
│   ├── ExpoAudioStudio.types.ts # Type definitions
│   ├── ExpoAudioStudioModule.ts # Native module interface
│   └── index.ts                 # Public API exports
├── ios/                         # iOS native implementation
│   ├── Audio/                   # Audio playback components
│   ├── Recorder/                # Recording components
│   └── SoundClassification/     # VAD implementation
├── android/                     # Android native implementation
│   ├── player/                  # Audio playback components
│   └── recorder/                # Recording components
├── example/                     # Example React Native app
├── build/                       # Compiled output
├── coverage/                    # Test coverage reports
└── docs/                        # Documentation
```

## 🎯 Development Best Practices

### TypeScript
- **Strict mode enabled** - Maximum type safety
- **Explicit return types** for public APIs
- **Comprehensive type definitions** for all interfaces
- **No `any` types** without justification

### Code Style
- **Functional programming** preferred where appropriate
- **Immutable data structures** when possible
- **Clear, descriptive naming** for variables and functions
- **Single responsibility principle** for functions and classes

### Error Handling
- **Comprehensive error types** with specific error codes
- **Graceful degradation** for unsupported features
- **Detailed error messages** for debugging
- **Proper error propagation** through the call stack

### Performance
- **Lazy loading** for heavy components
- **Efficient memory management** with proper cleanup
- **Optimized native implementations** for real-time processing
- **Minimal JavaScript bridge calls** for performance-critical operations

### Documentation
- **JSDoc comments** for all public APIs
- **Usage examples** in documentation
- **Type annotations** for better IDE support
- **Comprehensive README** with getting started guide

## 🔧 Native Development

### iOS Development
```bash
# Open iOS project
yarn open:ios

# Build iOS module
cd ios && xcodebuild -workspace ExpoAudioStudio.xcworkspace -scheme ExpoAudioStudio build

# Run iOS tests
cd ios && xcodebuild test -workspace ExpoAudioStudio.xcworkspace -scheme ExpoAudioStudio -destination 'platform=iOS Simulator,name=iPhone 14'
```

### Android Development
```bash
# Open Android project
yarn open:android

# Build Android module
cd android && ./gradlew build

# Run Android tests
cd android && ./gradlew test
```

### Native Module Changes
When making changes to native code:
1. Update TypeScript definitions
2. Update documentation
3. Add tests for new functionality
4. Test on both platforms
5. Update example app if needed

## 🚀 Release Process

### Version Bumping
```bash
# Patch version (bug fixes)
npm version patch

# Minor version (new features)
npm version minor

# Major version (breaking changes)
npm version major
```

### Pre-release Checklist
- [ ] All tests passing
- [ ] Code coverage meets requirements
- [ ] Documentation updated
- [ ] Example app works on both platforms
- [ ] CHANGELOG.md updated
- [ ] Version bumped appropriately

### Publishing
```bash
# Build and validate
yarn validate

# Publish to npm
npm publish
```

## 🐛 Troubleshooting

### Common Issues

#### TypeScript Errors
```bash
# Clear TypeScript cache
rm -rf node_modules/.cache
yarn install

# Rebuild types
yarn build
```

#### ESLint Issues
```bash
# Fix auto-fixable issues
yarn lint

# Check specific file
yarn lint:check src/specific-file.ts
```

#### Test Failures
```bash
# Run tests with verbose output
yarn test --verbose

# Run specific test
yarn test --testNamePattern="VAD initialization"
```

#### Native Module Issues
```bash
# Clean and rebuild
yarn clean
yarn build

# Reset example app
cd example
rm -rf node_modules
yarn install
cd ios && pod install
```

### Getting Help
- Check existing [GitHub Issues](https://github.com/SGamrekelashvili/expo-audio-studio/issues)
- Create a new issue with:
  - Clear description of the problem
  - Steps to reproduce
  - Expected vs actual behavior
  - Environment details (OS, Node version, etc.)
  - Code samples or logs

## 📚 Additional Resources

- [Expo Modules API](https://docs.expo.dev/modules/overview/)
- [React Native Documentation](https://reactnative.dev/docs/getting-started)
- [TypeScript Handbook](https://www.typescriptlang.org/docs/)
- [Jest Testing Framework](https://jestjs.io/docs/getting-started)
- [ESLint Configuration](https://eslint.org/docs/user-guide/configuring/)
- [Prettier Configuration](https://prettier.io/docs/en/configuration.html)
