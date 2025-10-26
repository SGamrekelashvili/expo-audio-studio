# Amplitude Sending Speed Analysis & Synchronization

## Current Implementation Differences

### iOS (RecorderManager.swift)

- **Frequency**: 100ms (10 Hz) - Fixed timer interval
- **Implementation**: `Timer(timeInterval: 0.1, repeats: true)`
- **Thread**: Main thread dispatch guaranteed
- **Amplitude Range**: -160dB to 0dB (native AVAudioRecorder)

### Android (MediaRecorderProvider.kt)

- **Frequency**: Variable (depends on WaveRecorder library)
- **Implementation**: Library callback `onAmplitudeListener`
- **Thread**: Library-managed
- **Amplitude Range**: Converted to match iOS (-160dB to 0dB)

## Issues Identified

1. **Inconsistent Timing**: iOS has fixed 100ms, Android is variable
2. **Different Threading**: iOS explicit main thread, Android library-managed
3. **Library Dependency**: Android relies on external library timing

## Recommended Solutions

### Option 1: Standardize Android to Match iOS (Recommended)

```kotlin
// Add custom timer in MediaRecorderProvider.kt
private var amplitudeTimer: Timer? = null
private val amplitudeHandler = Handler(Looper.getMainLooper())

private fun startAmplitudeMonitoring() {
    amplitudeTimer = Timer()
    amplitudeTimer?.scheduleAtFixedRate(object : TimerTask() {
        override fun run() {
            val currentAmplitude = getCurrentAmplitude()
            amplitudeHandler.post {
                sendAmplitudeEvent(mapOf("amplitude" to currentAmplitude))
            }
        }
    }, 0, 100) // 100ms interval to match iOS
}
```

### Option 2: Make iOS Configurable

```swift
// Add configurable interval in RecorderManager.swift
private var amplitudeInterval: TimeInterval = 0.1 // Default 100ms

func setAmplitudeUpdateInterval(_ interval: TimeInterval) {
    amplitudeInterval = max(0.05, min(1.0, interval)) // 50ms to 1000ms range
}
```

### Option 3: Expose Timing Configuration to JavaScript

```typescript
// Add to ExpoAudioStudioModule.ts
export function setAmplitudeUpdateFrequency(frequencyHz: number): void {
  return ExpoAudioStudioModule.setAmplitudeUpdateFrequency(frequencyHz);
}
```

## Performance Considerations

### Current Performance Impact

- **iOS**: Minimal (native Timer + AVAudioRecorder)
- **Android**: Depends on WaveRecorder library efficiency

### Recommended Frequency

- **High Precision**: 50ms (20 Hz) - For real-time visualizations
- **Standard**: 100ms (10 Hz) - Current iOS implementation
- **Battery Friendly**: 200ms (5 Hz) - For basic monitoring

### Memory Impact

- Each amplitude event creates a small JavaScript object
- At 10 Hz: 600 events/minute
- At 20 Hz: 1200 events/minute

## Implementation Priority

1. **High Priority**: Standardize Android to 100ms fixed interval
2. **Medium Priority**: Add configurable frequency
3. **Low Priority**: Expose to JavaScript configuration

## Testing Recommendations

1. **Timing Test**: Log timestamps of amplitude events on both platforms
2. **Performance Test**: Monitor CPU/battery usage at different frequencies
3. **Accuracy Test**: Compare amplitude values between platforms
4. **UI Test**: Test real-time visualizations with different frequencies
