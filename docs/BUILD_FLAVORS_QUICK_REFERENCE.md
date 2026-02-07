# Build Flavors - Quick Reference 🚀

## Build Commands

```bash
# Full Version (with FFmpeg, ~54 MB)
./gradlew assembleFullRelease      # Production
./gradlew assembleFullDebug        # Development

# Mini Version (without FFmpeg, ~10 MB)
./gradlew assembleMiniRelease      # Production
./gradlew assembleMiniDebug        # Development
```

## Output Files

```
app/build/outputs/apk/
├── full/release/app-full-release.apk     # Full production
├── full/debug/app-full-debug.apk         # Full debug
├── mini/release/app-mini-release.apk     # Mini production
└── mini/debug/app-mini-debug.apk         # Mini debug
```

## Key Differences

| Feature | Full Version | Mini Version |
|---------|-------------|--------------|
| APK Size | ~54 MB | ~10 MB |
| FFmpeg | ✅ Included | ❌ Excluded |
| Video Processing | Local (offline) | Backend (online) |
| Version Name | "38" | "38-mini" |
| `IS_MINI_VERSION` | `false` | `true` |

## Build Config

Access in code:
```kotlin
if (BuildConfig.IS_MINI_VERSION) {
    // Mini version - use backend processing
} else {
    // Full version - use local FFmpeg
}
```

## Android Studio

1. View → Tool Windows → **Build Variants**
2. Select variant:
   - `fullDebug` or `fullRelease`
   - `miniDebug` or `miniRelease`
3. Build → Build Bundle(s) / APK(s) → **Build APK(s)**

## Testing

```bash
# Install and test mini version
adb install app/build/outputs/apk/mini/debug/app-mini-debug.apk

# Install and test full version
adb install app/build/outputs/apk/full/debug/app-full-debug.apk
```

## Documentation

📖 **Full Details**: `docs/BUILD_FLAVORS.md`

---

**Quick Tip**: Build mini version for fast distribution, full version for server hosting.

