# Tweet App Quick Start Guide

**Last Updated:** May 2026

## 1) Prerequisites

- Android Studio (2024+)
- Java 17
- Android SDK + platform tools
- Device or emulator (Android 10+ recommended)

## 2) Build and Run

```bash
# From repository root
./gradlew clean assembleDebug installDebug

# Launch app
adb shell am start -n us.fireshare.tweet.debug/us.fireshare.tweet.TweetActivity
```

## 3) First-Run Verification

Verify these core flows:

1. App launches without crash
2. Feed renders and scrolls
3. Search opens and returns results
4. Media preview loads
5. Fullscreen video opens

## 4) Recommended Reading Order

1. `../README.md`
2. `INDEX.md`
3. `TECHNICAL_ARCHITECTURE.md`
4. `VIDEO_LOADING_ALGORITHM.md`
5. `PERFORMANCE_AND_MEMORY_REVIEW.md`

## 5) Useful Commands

```bash
# Run unit tests
./gradlew test

# Build a specific variant
./gradlew assembleDebug

# View app logs
adb logcat | grep "us.fireshare.tweet"
```

## 6) Troubleshooting

- **Gradle sync issues**
  - `./gradlew clean && ./gradlew --stop`
- **Java issues**
  - ensure `java -version` shows 17
- **Install issues**
  - check `adb devices`, then reinstall with `./gradlew installDebug`
- **Crash on launch**
  - inspect `adb logcat | grep -E "AndroidRuntime|FATAL"`

## 7) Where To Go Next

- Build and debug details: `ANDROID_STUDIO_BUILD_GUIDE.md`, `DEBUG_SETUP_AND_LOGGING_GUIDE.md`
- Architecture: `TECHNICAL_ARCHITECTURE.md`
- Video/media: `VIDEO_LOADING_ALGORITHM.md`, `FULLSCREEN_VIDEO_PLAYER.md`
- Reliability: `NETWORK_CONSOLIDATION_2025.md`, `MEMORY_OPTIMIZATION_GUIDE.md`
