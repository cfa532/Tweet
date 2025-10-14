# Video Upload Strategy Update
**Last Updated:** October 13, 2025 (Updated: Health check endpoint and tusServerUrl)

## Overview

This document summarizes the changes made to the video upload strategy, including removal of default cloudDrivePort values and implementation of intelligent video processing based on service availability.

⚠️ **IMPORTANT:** All video processing operations run in **background workers** using Android WorkManager. See **[BACKGROUND_VIDEO_PROCESSING.md](BACKGROUND_VIDEO_PROCESSING.md)** for complete background processing architecture details.

## Changes Summary

### 1. CloudDrivePort Made Optional

**Modified Files:**
- `app/src/main/java/us/fireshare/tweet/datamodel/User.kt`
- `app/src/main/java/us/fireshare/tweet/viewmodel/UserViewModel.kt`
- `app/src/main/java/us/fireshare/tweet/profile/SystemSettings.kt`
- `app/src/main/java/us/fireshare/tweet/video/ZipUploadService.kt`

**Key Changes:**
- Changed `cloudDrivePort` from `Int = TW_CONST.CLOUD_PORT` to `Int? = null` in User class
- Updated all references to handle nullable cloudDrivePort
- Removed default value assignment in registration and profile update flows
- Users can now leave cloudDrivePort empty if they don't have a conversion server

### 2. New Video Processing Strategy

**Created Files:**
- `app/src/main/java/us/fireshare/tweet/video/VideoNormalizer.kt`

**Modified Files:**
- `app/src/main/java/us/fireshare/tweet/HproseInstance.kt`

**Processing Flow:**

```
User Selects Video
    ↓
WorkManager Enqueues UploadTweetWorker
    ↓
[BACKGROUND WORKER - ALL BELOW RUNS IN BACKGROUND]
    ↓
Wake Lock Acquired (10 min protection)
    ↓
Check cloudDrivePort validity
    ↓
┌─────────────────────────────────┐
│ Is cloudDrivePort set?          │
│ Is conversion server available? │
└─────────────────────────────────┘
    ↓                    ↓
  YES                   NO
    ↓                    ↓
┌─────────────────┐  ┌───────────────────────────┐
│ HLS Conversion  │  │ Check video resolution    │
│ + Process-zip   │  └───────────────────────────┘
│ Upload          │       ↓              ↓
│ (Background)    │   > 720p        ≤ 720p
└─────────────────┘       ↓              ↓
                  ┌──────────────┐  ┌──────────────┐
                  │ Normalize +  │  │ Normalize to │
                  │ Resample to  │  │ standard MP4 │
                  │ 720p         │  │              │
                  │ (Background) │  │ (Background) │
                  └──────────────┘  └──────────────┘
                        ↓              ↓
                  ┌──────────────────────┐
                  │ Upload via IPFS      │
                  │ (Background)         │
                  └──────────────────────┘
                        ↓
                  Wake Lock Released
```

**All operations execute in background workers with wake lock protection.**

### 3. Video Normalization Features

The new `VideoNormalizer` class provides:

**Features:**
- Converts videos to standard MP4 format
- Optional resolution downsampling to 720p
- Maintains aspect ratio
- Uses FFmpeg libx264 codec with AAC audio
- Optimizes for streaming with `faststart` flag

**Command Examples:**

**Without Resampling:**
```bash
ffmpeg -i input.mp4 \
  -c:v libx264 \
  -c:a aac \
  -preset fast \
  -crf 23 \
  -movflags +faststart \
  -metadata:s:v:0 rotate=0 \
  output.mp4
```

**With 720p Resampling:**
```bash
ffmpeg -i input.mp4 \
  -c:v libx264 \
  -c:a aac \
  -vf "scale=1280:720:force_original_aspect_ratio=decrease:force_divisible_by=2" \
  -preset fast \
  -crf 23 \
  -movflags +faststart \
  -metadata:s:v:0 rotate=0 \
  output.mp4
```

### 4. Service Availability Check

**New Function:** `isConversionServerAvailable()`

**Checks:**
1. CloudDrivePort is set (not null)
2. WritableUrl can be resolved
3. tusServerUrl can be constructed
4. Health check endpoint `/health` responds with HTTP 200

**Implementation:**
```kotlin
private suspend fun isConversionServerAvailable(): Boolean {
    return try {
        // Check if cloudDrivePort is valid
        if (appUser.cloudDrivePort == null) {
            return false
        }
        
        // Ensure writableUrl is resolved
        if (appUser.writableUrl.isNullOrEmpty()) {
            val resolved = appUser.resolveWritableUrl()
            if (resolved.isNullOrEmpty()) {
                return false
            }
        }
        
        val tusServerUrl = appUser.tusServerUrl
        if (tusServerUrl.isNullOrEmpty()) {
            return false
        }
        
        // Try to ping the /health endpoint
        val healthCheckUrl = "$tusServerUrl/health"
        val response = httpClient.get(healthCheckUrl)
        response?.status == HttpStatusCode.OK
    } catch (e: Exception) {
        false
    }
}
```

### 5. TUS Server URL Property

**Property:** `User.tusServerUrl`

A computed property that constructs the TUS (resumable upload) server URL by:
1. Taking the user's `writableUrl`
2. Replacing the port with `cloudDrivePort` (or `TW_CONST.CLOUD_PORT = 8010` as fallback)
3. Preserving the full path, query, and fragment (important for servers hosted at subpaths)

**Important:** Callers must ensure `writableUrl` is resolved (by calling `resolveWritableUrl()`) before accessing this property.

**Example:**
- writableUrl: `http://example.com:8081/api/v1`
- cloudDrivePort: `8082`
- tusServerUrl: `http://example.com:8082/api/v1`
- Health check: `http://example.com:8082/api/v1/health`

## Benefits

1. **Flexibility**: Users can now operate without a conversion server
2. **Resilience**: Automatic fallback to IPFS upload when server is unavailable
3. **Bandwidth Optimization**: Videos > 720p are automatically downsampled when using IPFS
4. **Quality Control**: All videos are normalized to standard MP4 format
5. **User Experience**: Seamless operation regardless of server availability

## Usage Guidelines

### For Users Without Conversion Server

1. Leave cloudDrivePort field empty in registration/settings
2. Videos will be automatically normalized and uploaded via IPFS
3. Videos > 720p will be downsampled to reduce bandwidth

### For Users With Conversion Server

1. Set cloudDrivePort to the appropriate port (e.g., 8010)
2. Ensure conversion server is running and accessible
3. Videos will be converted to HLS format for adaptive streaming
4. If server becomes unavailable, automatic fallback to IPFS upload

## Technical Details

### Resolution Detection

Uses `VideoManager.getVideoResolution()` to determine video dimensions:
- Returns `Pair<Int, Int>` representing (width, height)
- Considers video rotation metadata
- Returns null if resolution cannot be determined

### 720p Threshold

A video is considered > 720p if:
- **Landscape**: width > 1280 OR height > 720
- **Portrait**: width > 720 OR height > 1280

### Aspect Ratio Preservation

The normalizer maintains original aspect ratio:
- Portrait videos: scale to width 720, calculate height
- Landscape videos: scale to height 720, calculate width
- Ensures even dimensions for codec compatibility
- Caps dimensions at 1280 pixels

## Error Handling

The implementation includes comprehensive error handling:

1. **Service Availability**: Graceful fallback if health check fails
2. **Normalization Errors**: Returns error result if FFmpeg fails
3. **Upload Errors**: Proper error propagation to UI layer
4. **Cleanup**: Temporary files are always cleaned up

## Future Enhancements

Potential improvements:
1. Configurable quality settings (CRF value)
2. Multiple resolution presets
3. Hardware acceleration support
4. Progress callbacks during normalization
5. Batch processing optimization

## Migration Notes

**For Existing Users:**
- Users with existing cloudDrivePort values will continue to work normally
- New users can choose to leave the field empty
- No data migration required

**For Server Operators:**
- Implement `/health` endpoint for health checks (not `/process-zip/health`)
- Ensure endpoint returns HTTP 200 when service is available
- Example implementation:
  ```javascript
  app.get('/health', (req, res) => {
    res.status(200).json({ 
      status: 'ok', 
      message: 'Server is running',
      timestamp: new Date().toISOString()
    });
  });
  ```
- The TUS server may be hosted at a subpath (e.g., `/api/v1`), health check will be at `{subpath}/health`

## Testing Checklist

- [x] Build compilation successful
- [ ] User registration without cloudDrivePort
- [ ] Video upload with server available
- [ ] Video upload with server unavailable
- [ ] Resolution detection for various video formats
- [ ] 720p downsampling for high-resolution videos
- [ ] Aspect ratio preservation in resampling
- [ ] IPFS upload of normalized videos
- [ ] HLS conversion when server is available
- [ ] Cleanup of temporary files

