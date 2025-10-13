# Media Upload Service Refactoring
**Date:** October 13, 2025

## Overview

This document describes the refactoring of media upload functionality from `HproseInstance.kt` into a dedicated `MediaUploadService.kt` class to improve code organization and maintainability.

## Problem

The `HproseInstance.kt` file had grown too large (2400+ lines) with significant portions dedicated to media upload operations. This made the codebase difficult to navigate and maintain.

## Solution

Created a new service class `MediaUploadService` that encapsulates all media upload-related functionality, including:

### Extracted Functions

1. **`uploadToIPFS()`** - Main entry point for media uploads
2. **`isConversionServerAvailable()`** - Checks if video conversion server is accessible
3. **`processVideoLocally()`** - Handles local video processing logic
4. **`pollVideoConversionStatus()`** - Polls video conversion job status
5. **`uploadToIPFSOriginal()`** - Chunked IPFS upload implementation
6. **`getFileSize()`** - Calculates file size from URI
7. **`getImageAspectRatio()`** - Calculates image aspect ratio with EXIF support

## Architecture

### New File Structure

```
app/src/main/java/us/fireshare/tweet/
├── HproseInstance.kt (simplified)
└── service/
    └── MediaUploadService.kt (new)
```

### MediaUploadService Class

**Location:** `app/src/main/java/us/fireshare/tweet/service/MediaUploadService.kt`

**Dependencies:**
- `Context` - Android context for file operations
- `HttpClient` - Ktor client for network operations
- `User` - Current user information
- `appId` - Application ID for IPFS operations

**Constructor:**
```kotlin
class MediaUploadService(
    private val context: Context,
    private val httpClient: HttpClient,
    private val appUser: User,
    private val appId: MimeiId
)
```

### HproseInstance Integration

The `HproseInstance` now delegates to `MediaUploadService`:

```kotlin
// Lazy initialization in HproseInstance
private val mediaUploadService: MediaUploadService by lazy {
    MediaUploadService(applicationContext, httpClient, appUser, appId)
}

// Delegation example
suspend fun uploadToIPFS(
    context: Context,
    uri: Uri,
    referenceId: MimeiId? = null,
    noResample: Boolean = false
): MimeiFileType? {
    return mediaUploadService.uploadToIPFS(uri, referenceId, noResample)
}
```

## Functionality Preserved

All existing functionality has been preserved:

### Video Processing Strategy

1. **Server Available:**
   - Convert to HLS format (multi-resolution)
   - Compress to ZIP
   - Upload to `/process-zip` endpoint
   - Poll for completion

2. **Server Unavailable:**
   - Check video resolution
   - Resample to 720p if > 720p
   - Normalize to standard MP4
   - Upload via IPFS

### Image Processing

- EXIF orientation support
- Aspect ratio calculation
- Multiple fallback methods for dimension detection

### File Upload

- Chunked upload for large files
- Progress tracking
- Retry logic with exponential backoff
- Error handling and recovery

## Benefits

1. **Improved Organization:** Media upload logic is now in a dedicated service class
2. **Better Maintainability:** Easier to locate and modify upload-related code
3. **Reduced Coupling:** `HproseInstance` is now more focused on its core responsibilities
4. **Testability:** `MediaUploadService` can be tested independently
5. **Code Reusability:** Upload service can be used from other components if needed

## File Size Comparison

**Before Refactoring:**
- `HproseInstance.kt`: ~2449 lines

**After Refactoring:**
- `HproseInstance.kt`: ~2270 lines (reduced by ~180 lines)
- `MediaUploadService.kt`: ~570 lines (new file)

**Net Impact:** Functionality separated into focused, manageable components.

## API Compatibility

All public APIs in `HproseInstance` remain unchanged:

```kotlin
// These methods still work exactly the same way
uploadToIPFS(context, uri, referenceId, noResample)
getFileSize(context, uri)
getImageAspectRatio(context, uri)
```

No changes required in calling code.

## Implementation Details

### Service Initialization

The service is lazily initialized to ensure `applicationContext` is available:

```kotlin
private val mediaUploadService: MediaUploadService by lazy {
    MediaUploadService(applicationContext, httpClient, appUser, appId)
}
```

### Error Handling

- Maintains all existing error handling logic
- Proper timber logging for debugging
- Graceful degradation when services are unavailable

### Resource Management

- Automatic cleanup of temporary files
- Proper stream management with `use` blocks
- Memory-efficient chunked upload

## Testing Checklist

- [x] Build compilation successful
- [ ] Video upload with conversion server available
- [ ] Video upload with conversion server unavailable
- [ ] Image upload and aspect ratio calculation
- [ ] File size calculation for various file types
- [ ] IPFS chunked upload
- [ ] Error handling and retry logic

## Future Enhancements

Potential improvements now that upload logic is separated:

1. **Unit Testing:** Write comprehensive tests for `MediaUploadService`
2. **Progress Callbacks:** Add upload progress reporting interface
3. **Cancellation Support:** Implement upload cancellation
4. **Upload Queue:** Batch multiple uploads
5. **Compression Options:** Configurable quality/compression settings

## Migration Notes

**For Developers:**
- No code changes required in existing upload calls
- Upload logic now in `service/MediaUploadService.kt`
- Legacy implementations kept as `_Legacy` methods for reference

**For Reviewers:**
- Focus review on `MediaUploadService.kt`
- Verify delegation in `HproseInstance.kt`
- Check that no functionality was lost in the move

## Related Documentation

- [VIDEO_UPLOAD_STRATEGY_UPDATE.md](VIDEO_UPLOAD_STRATEGY_UPDATE.md) - Video processing strategy
- [LOCAL_VIDEO_PROCESSING_IMPLEMENTATION.md](LOCAL_VIDEO_PROCESSING_IMPLEMENTATION.md) - Local video processing details

