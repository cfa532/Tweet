# Video Normalization and HLS Processing Update
**Last Updated:** December 21, 2024

## Overview

Updated the video normalization and HLS processing pipeline to ensure all videos are normalized first, then efficiently converted to HLS format using COPY codec when possible.

## Changes Made

### 1. Video Normalization Strategy

**All videos are now normalized** using `VideoNormalizer.normalizeTo720p1000k()`:

- **Videos > 720p**: Downscaled to 720p with 1000k bitrate
- **Videos ≤ 720p**: Kept at original resolution with proportional bitrate
  - Formula: `bitrate = 1000k × (resolution / 720)`
  - Example: 480p video gets ~667k bitrate

**Normalization Settings** (Applied to ALL videos):
```kotlin
-c:v libx264           // H.264 video codec
-c:a aac               // AAC audio codec
-preset veryfast       // Fast encoding preset
-profile:v baseline    // iOS/VideoJS compatibility
-pix_fmt yuv420p       // Standard pixel format
-g 30                  // Keyframe interval (30 frames)
-level 3.1             // H.264 level for compatibility
-threads 4             // Multi-threaded encoding
```

### 2. HLS Segment Creation with COPY Codec

After normalization, videos are converted to HLS format with **intelligent codec selection**:

#### COPY Codec Usage (No Re-encoding)

The HLS converter now uses COPY codec when:
- The input video is already normalized (`isNormalized = true`)
- The final HLS dimensions match the normalized video dimensions exactly (no scaling needed)
- This works for ANY resolution (720p, 576p, 480p, 360p, etc.)

**Benefits of COPY codec**:
- **Faster processing**: No re-encoding needed
- **Better quality**: No additional quality loss
- **Lower CPU usage**: Simple stream copying

#### Example Scenarios

**Scenario 1: 1080p Video**
1. Original: 1920×1080 (1080p)
2. Normalization: Downscale to 1280×720 (720p) @ 1000k with libx264
3. HLS 720p variant: **COPY codec** (matches normalized resolution)
4. HLS 480p variant: Re-encode with libx264 (downscale from 720p)

**Scenario 2: 480p Video**
1. Original: 854×480 (480p)
2. Normalization: Keep at 854×480 (480p) @ 667k with libx264
3. HLS 720p variant: Kept at 480p (no upscaling) → **COPY codec** ✓ (dimensions match)
4. HLS 480p variant: **COPY codec** ✓ (dimensions match normalized resolution)

**Scenario 3: 576p Video**
1. Original: 1024×576 (576p)
2. Normalization: Keep at 1024×576 (576p) @ 800k with libx264
3. HLS 720p variant: Kept at 576p (no upscaling) → **COPY codec** ✓ (dimensions match)
4. HLS 480p variant: Downscale to 480p → Re-encode with libx264

**Scenario 4: 360p Video**
1. Original: 640×360 (360p)
2. Normalization: Keep at 640×360 (360p) @ 500k with libx264
3. HLS 720p/480p variant: Kept at 360p (no upscaling) → **COPY codec** ✓ (dimensions match)
4. HLS 360p variant: **COPY codec** ✓ (dimensions match) [if using route 2]

### 3. Code Changes

#### LocalHLSConverter.kt

**720p Stream Logic** (line ~188-198):
```kotlin
// Use COPY codec if video is already normalized AND dimensions match exactly
// Works for any resolution (720p, 576p, 480p, etc.) - no scaling needed
val shouldUseCopyFor720p = isNormalized && 
    finalWidth720 == videoResolution?.first && 
    finalHeight720 == videoResolution?.second

if (shouldUseCopyFor720p) {
    Timber.tag(TAG).d("720p HLS stream: Using COPY codec (no scaling needed)")
} else {
    Timber.tag(TAG).d("720p HLS stream: re-encoding with libx264, bitrate=$target720pBitrate")
}
```

**Lower Resolution Stream Logic** (line ~240-250):
```kotlin
// Use COPY codec if video is already normalized AND dimensions match exactly
// Works for any resolution - if no scaling is needed, use COPY
val shouldUseCopyForLower = isNormalized && 
    finalWidthLower == videoResolution?.first && 
    finalHeightLower == videoResolution?.second

if (shouldUseCopyForLower) {
    Timber.tag(TAG).d("${lowerResolution}p HLS stream: Using COPY codec (no scaling needed)")
} else {
    Timber.tag(TAG).d("${lowerResolution}p HLS stream: re-encoding with libx264")
}
```

## Processing Flow

```
┌─────────────────────────────────────────────────────────────┐
│ 1. Upload Video (Any Resolution/Format)                     │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│ 2. Normalize Video (VideoNormalizer)                        │
│    - >720p: Downscale to 720p @ 1000k                       │
│    - ≤720p: Keep resolution @ proportional bitrate          │
│    - Always apply: libx264, baseline, yuv420p, g=30         │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│ 3. Create HLS Segments (LocalHLSConverter)                  │
│    For each variant (720p, 480p, or 360p):                  │
│    - If normalized resolution matches: COPY codec           │
│    - If resolution differs: Re-encode with libx264          │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│ 4. Create ZIP and Upload (LocalVideoProcessingService)      │
│    - Compress HLS files                                      │
│    - Upload to /process-zip                                  │
│    - Poll for completion                                     │
└─────────────────────────────────────────────────────────────┘
```

## FFmpeg Commands

### Normalization Command (libx264 - Always)
```bash
ffmpeg -i "input.mp4" \
  -c:v libx264 \
  -c:a aac \
  -vf "scale=W:H:force_original_aspect_ratio=decrease:force_divisible_by=2" \
  -preset veryfast \
  -profile:v baseline \
  -pix_fmt yuv420p \
  -g 30 \
  -level 3.1 \
  -threads 4 \
  -b:v 1000k \
  -b:a 128k \
  -maxrate 1000k \
  -bufsize 1000k \
  -movflags +faststart \
  -metadata:s:v:0 rotate=0 \
  "normalized.mp4"
```

### HLS Segment Creation - COPY Codec (When Resolution Matches)
```bash
ffmpeg -i "normalized.mp4" \
  -c copy \
  -fflags +genpts+igndts+flush_packets \
  -avoid_negative_ts make_zero \
  -max_interleave_delta 0 \
  -max_muxing_queue_size 1024 \
  -hls_time 10 \
  -hls_list_size 0 \
  -hls_flags independent_segments \
  -hls_segment_type mpegts \
  -hls_segment_filename "segment%03d.ts" \
  -f hls "playlist.m3u8"
```

### HLS Segment Creation - libx264 (When Resolution Differs)
```bash
ffmpeg -i "normalized.mp4" \
  -c:v libx264 \
  -c:a aac \
  -vf "scale=W:H:force_original_aspect_ratio=decrease:force_divisible_by=2" \
  -b:v 600k \
  -b:a 128k \
  -preset veryfast \
  -tune zerolatency \
  -profile:v baseline \
  -pix_fmt yuv420p \
  -g 30 \
  -level 3.1 \
  -threads 4 \
  -max_muxing_queue_size 1024 \
  -fflags +genpts+igndts+flush_packets \
  -avoid_negative_ts make_zero \
  -max_interleave_delta 0 \
  -bufsize 600k \
  -maxrate 600k \
  -metadata:s:v:0 rotate=0 \
  -hls_time 10 \
  -hls_list_size 0 \
  -hls_flags independent_segments \
  -hls_segment_type mpegts \
  -hls_segment_filename "segment%03d.ts" \
  -f hls "playlist.m3u8"
```

## Performance Impact

### Before Update
- All HLS variants were re-encoded, even if source was already at target resolution
- Result: Unnecessary CPU usage and quality loss

### After Update
- Normalized video uses COPY codec when creating matching-resolution HLS segments
- Estimated improvement:
  - **720p videos**: ~40-50% faster HLS processing (720p variant uses COPY)
  - **480p videos**: ~40-50% faster HLS processing (480p variant uses COPY)
  - **360p videos**: ~40-50% faster HLS processing (360p variant uses COPY, route 2 only)
  - **Better quality**: No additional quality loss from double-encoding

## Testing

To verify the changes, check the logs for:

### Normalization Logs
```
VideoNormalizer: Normalized to 1280×720 (720p) with 1000k bitrate
VideoNormalizer: Normalized to 854×480 (480p) with 667k bitrate
```

### HLS Conversion Logs
```
LocalHLSConverter: 720p HLS stream: Using COPY codec (video already normalized to 720p/1000k)
LocalHLSConverter: 480p HLS stream: Using COPY codec (video already normalized to 480p/667k)
LocalHLSConverter: 480p HLS stream: re-encoding with libx264, bitrate=600k
```

## Related Files

- `app/src/fullPlay/java/us/fireshare/tweet/video/VideoNormalizer.kt` - Video normalization logic
- `app/src/fullPlay/java/us/fireshare/tweet/video/LocalHLSConverter.kt` - HLS conversion with COPY codec logic
- `app/src/main/java/us/fireshare/tweet/video/LocalVideoProcessingService.kt` - Orchestration service
- `app/src/main/java/us/fireshare/tweet/service/MediaUploadService.kt` - Upload service that calls normalization

## Benefits

1. **Consistency**: All videos are normalized to standard format with compatible codec settings
2. **Efficiency**: COPY codec used when possible, saving processing time and CPU
3. **Quality**: No unnecessary re-encoding, preserving video quality
4. **Compatibility**: All videos have iOS/VideoJS compatible settings (baseline profile, yuv420p, etc.)
5. **Proportional Bitrates**: Videos maintain quality appropriate to their resolution

