# Mini Variant FFmpeg Safety Fix

**Date:** December 21, 2025

## Overview

Fixed the mini variant to ensure it never calls FFmpeg-dependent code at runtime. The mini variant now properly skips all video processing operations that require FFmpeg and uploads videos directly to IPFS.

## Problem

The mini variant build was failing because:
1. `MediaUploadService.kt` in the main source set was calling `LocalVideoProcessingService` and `VideoNormalizer`
2. These classes exist in the mini variant as stubs, but they reference FFmpeg classes internally
3. Even though stubs would return errors, attempting to call them was problematic:
   - Runtime risk of FFmpeg references
   - Confusing error logs
   - Inefficient execution

## Solution

Added `BuildConfig.IS_MINI_VERSION` checks throughout `MediaUploadService.kt` to prevent FFmpeg operations from being called in the mini variant.

### Changes Made

#### 1. Added BuildConfig Import

```kotlin
import us.fireshare.tweet.BuildConfig
```

#### 2. Updated `processVideoWithRouting()`

Added early return for mini version:

```kotlin
private suspend fun processVideoWithRouting(...): MimeiFileType? {
    return try {
        // Mini version: Skip FFmpeg processing, upload directly to IPFS
        if (BuildConfig.IS_MINI_VERSION) {
            Timber.tag(TAG).d("Mini version detected: Uploading video directly without local processing")
            return uploadToIPFSOriginal(uri, fileName, fileTimestamp, referenceId, MediaType.Video)
        }
        
        // Full version continues with FFmpeg processing...
    }
}
```

#### 3. Updated `processVideoLocally()`

Added safety check:

```kotlin
private suspend fun processVideoLocally(...): MimeiFileType? {
    return try {
        // Safety check: Mini version should never call this method
        if (BuildConfig.IS_MINI_VERSION) {
            Timber.tag(TAG).w("Mini version attempted to call processVideoLocally - uploading directly")
            return uploadToIPFSOriginal(uri, fileName, fileTimestamp, referenceId, MediaType.Video)
        }
        
        // Full version processing...
    }
}
```

#### 4. Updated `normalizeAndUploadVideo()`

Added safety check:

```kotlin
private suspend fun normalizeAndUploadVideo(...): MimeiFileType? {
    return try {
        // Safety check: Mini version should never call this method (no FFmpeg)
        if (BuildConfig.IS_MINI_VERSION) {
            Timber.tag(TAG).w("Mini version attempted to call normalizeAndUploadVideo - uploading directly")
            return uploadToIPFSOriginal(uri, fileName, fileTimestamp, referenceId, MediaType.Video)
        }
        
        // Full version processing...
    }
}
```

#### 5. Updated `routeVideoBySize()`

Added safety check:

```kotlin
private suspend fun routeVideoBySize(...): MimeiFileType? {
    // Safety check: Mini version should never call this method
    if (BuildConfig.IS_MINI_VERSION) {
        Timber.tag(TAG).w("Mini version attempted to call routeVideoBySize - uploading directly")
        return uploadToIPFSOriginal(videoUri, fileName, fileTimestamp, referenceId, MediaType.Video)
    }
    
    // Full version processing...
}
```

#### 6. Created Stub `LocalVideoProcessingService` for Mini

Created `/app/src/mini/java/us/fireshare/tweet/video/LocalVideoProcessingService.kt`:

```kotlin
/**
 * Stub implementation of LocalVideoProcessingService for mini variant.
 * The mini variant doesn't include FFmpeg, so local video processing is not supported.
 * Video processing should use backend services instead.
 */
class LocalVideoProcessingService(...) {
    suspend fun processVideo(...): VideoProcessingResult {
        return VideoProcessingResult.Error("Local video processing not available in mini version. Use backend processing instead.")
    }
    
    sealed class VideoProcessingResult {
        data class Success(val mimeiFile: MimeiFileType) : VideoProcessingResult()
        data class Error(val message: String) : VideoProcessingResult()
    }
}
```

## Behavior by Variant

### Full Version (54 MB)
- `BuildConfig.IS_MINI_VERSION = false`
- Uses FFmpeg for local video processing
- Processes videos with normalization and HLS conversion
- Works offline

### Mini Version (11 MB)
- `BuildConfig.IS_MINI_VERSION = true`
- Skips all FFmpeg operations
- Uploads videos directly to IPFS without processing
- Requires internet connection for uploads
- No local video processing

## Video Upload Flow

### Full Version Flow
1. Video selected by user
2. Check if conversion server available
3. If available: Normalize → Convert to HLS → Upload
4. If not available: Normalize → Upload as MP4
5. Fallback: Upload original video

### Mini Version Flow
1. Video selected by user
2. **Skip all processing** (BuildConfig check)
3. Upload original video directly to IPFS
4. Backend handles any necessary processing

## Build Commands

```bash
# Build full version (with FFmpeg)
./gradlew assembleFullRelease

# Build mini version (without FFmpeg)
./gradlew assembleMiniRelease

# Build both
./gradlew assembleFullRelease assembleMiniRelease
```

## Output

```
app/build/outputs/apk/
├── full/release/
│   └── app-full-release.apk   (54 MB)
└── mini/release/
    └── app-mini-release.apk   (11 MB)
```

## Benefits

✅ **No FFmpeg calls in mini**: Mini version never attempts FFmpeg operations
✅ **Clean logs**: No confusing error logs from FFmpeg stubs
✅ **Better performance**: Direct upload without attempting operations that will fail
✅ **Type safety**: Stubs exist for compilation, runtime checks prevent execution
✅ **Maintainable**: Clear separation between full and mini behavior

## Testing

### Test Mini Version
```bash
adb install app/build/outputs/apk/mini/release/app-mini-release.apk
adb logcat | grep "MediaUploadService"
```

Expected logs:
- "Mini version detected: Uploading video directly without local processing"
- No FFmpeg-related errors
- Direct IPFS upload messages

### Test Full Version
```bash
adb install app/build/outputs/apk/full/release/app-full-release.apk
adb logcat | grep "MediaUploadService\|LocalVideoProcessing"
```

Expected logs:
- "Starting video processing with new normalization and routing algorithm"
- FFmpeg processing logs
- HLS conversion logs

## Related Files

- `app/src/main/java/us/fireshare/tweet/service/MediaUploadService.kt` - Main upload service with IS_MINI_VERSION checks
- `app/src/mini/java/us/fireshare/tweet/video/LocalVideoProcessingService.kt` - Stub implementation
- `app/src/mini/java/us/fireshare/tweet/video/VideoNormalizer.kt` - Stub implementation
- `app/src/mini/java/us/fireshare/tweet/video/LocalHLSConverter.kt` - Stub implementation
- `app/src/fullPlay/java/us/fireshare/tweet/video/LocalVideoProcessingService.kt` - Full implementation

## Related Documentation

- [BUILD_FLAVORS.md](BUILD_FLAVORS.md) - Build variant configuration
- [BUILD_FLAVORS_QUICK_REFERENCE.md](BUILD_FLAVORS_QUICK_REFERENCE.md) - Quick build commands
- [LOCAL_VIDEO_PROCESSING_IMPLEMENTATION.md](LOCAL_VIDEO_PROCESSING_IMPLEMENTATION.md) - FFmpeg integration details

---

**Status:** ✅ Complete
**Build Status:** ✅ Both variants build successfully
**Test Status:** ⏳ Requires runtime testing

