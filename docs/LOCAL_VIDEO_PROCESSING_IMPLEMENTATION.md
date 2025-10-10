# Local Video Processing Implementation
**Last Updated:** October 10, 2025

## Overview

This implementation integrates the ffmpeg-kit-16KB library to enable local video processing in the Tweet app. The system converts uploaded videos to multi-resolution HLS format locally, with automatic fallback between COPY and libx264 codecs for optimal performance.

## Architecture

The implementation consists of four main components:

### 1. LocalHLSConverter
- **File**: `app/src/main/java/us/fireshare/tweet/video/LocalHLSConverter.kt`
- **Purpose**: Converts uploaded videos to multi-resolution HLS format using FFmpeg Kit
- **Key Features**:
  - **Multi-resolution support**: Generates 720p and 480p versions
  - **Smart codec selection**: COPY codec for compatible videos, libx264 for others
  - **Automatic fallback**: Falls back to libx264 if COPY fails
  - **Aspect ratio preservation**: Maintains original aspect ratio for portrait/landscape
  - **10-second segments**: HLS segments with standard naming (segment000.ts, segment001.ts)
  - **Master playlist**: HLS master playlist for adaptive bitrate streaming

### 2. ZipCompressor
- **File**: `app/src/main/java/us/fireshare/tweet/video/ZipCompressor.kt`
- **Purpose**: Compresses HLS files into a zip archive
- **Key Features**:
  - Recursively compresses directory contents
  - Maintains directory structure in zip file
  - Handles both files and directories
  - Provides error handling and logging

### 3. ZipUploadService
- **File**: `app/src/main/java/us/fireshare/tweet/video/ZipUploadService.kt`
- **Purpose**: Uploads zip files to `/process-zip` endpoint with progress polling
- **Key Features**:
  - Uses multipart form data for upload
  - Implements progress polling similar to existing `/convert-video` endpoint
  - Handles job status monitoring
  - Provides timeout and retry logic
  - Returns processed file CID on completion

### 4. LocalVideoProcessingService
- **File**: `app/src/main/java/us/fireshare/tweet/video/LocalVideoProcessingService.kt`
- **Purpose**: Orchestrates the entire local processing workflow
- **Key Features**:
  - Coordinates HLS conversion, zip compression, and upload
  - Manages temporary file cleanup
  - Provides unified error handling
  - Returns `MimeiFileType` compatible with existing system

## Integration with Existing System

### Modified Files

1. **build.gradle.kts**
   - Added ffmpeg-kit-16KB dependency: `com.arthenica:ffmpeg-kit-android-16kb:6.0-2`

2. **HproseInstance.kt**
   - Added import for `LocalVideoProcessingService`
   - Modified `uploadToIPFS` method to try local processing first
   - Added `processVideoLocally` method
   - Maintains fallback to existing remote processing

### Processing Flow

The new processing flow follows this sequence:

1. **Video Upload Detection**: When a video file is detected, the system first attempts local processing
2. **Local HLS Conversion**: Uses FFmpeg Kit to convert video to HLS format
3. **Zip Compression**: Compresses HLS files into a zip archive
4. **Zip Upload**: Uploads zip to `/process-zip` endpoint
5. **Progress Polling**: Monitors processing status until completion
6. **Fallback**: If local processing fails, falls back to existing remote processing

## HLS Structure

The generated HLS files follow a multi-resolution folder structure:

```
output_directory/
├── master.m3u8              # Master playlist (references both resolutions)
├── 720/
│   ├── playlist.m3u8        # 720p playlist
│   ├── segment000.ts        # 720p segments
│   ├── segment001.ts
│   └── ...
└── 480/
    ├── playlist.m3u8        # 480p playlist
    ├── segment000.ts        # 480p segments
    ├── segment001.ts
    └── ...
```

**Segment Naming:** Standard format `segment%03d.ts` generates `segment000.ts`, `segment001.ts`, etc.

## FFmpeg Configuration

### COPY Codec (For Compatible Videos)
Used when video is already ≤720p resolution (no re-encoding needed):

```bash
ffmpeg -i input.mp4 \
  -c copy \
  -fflags +genpts+igndts+flush_packets \
  -avoid_negative_ts make_zero \
  -max_interleave_delta 0 \
  -max_muxing_queue_size 1024 \
  -hls_time 10 \
  -hls_list_size 3 \
  -hls_flags delete_segments+independent_segments+split_by_time \
  -hls_segment_type mpegts \
  -hls_segment_filename "segment%03d.ts" \
  -f hls playlist.m3u8
```

### libx264 Codec (For Encoding)
Used when video needs scaling or re-encoding:

**720p Version:**
```bash
ffmpeg -i input.mp4 \
  -c:v libx264 \
  -c:a aac \
  -vf "scale=W:720:force_original_aspect_ratio=decrease:force_divisible_by=2" \
  -b:v 1000k \
  -b:a 128k \
  -preset fast \
  -tune zerolatency \
  -threads 2 \
  -hls_time 10 \
  -hls_list_size 3 \
  -hls_flags delete_segments+independent_segments+split_by_time \
  -hls_segment_type mpegts \
  -hls_segment_filename "segment%03d.ts" \
  -f hls 720/playlist.m3u8
```

**480p Version:**
```bash
ffmpeg -i input.mp4 \
  -c:v libx264 \
  -c:a aac \
  -vf "scale=W:480:force_original_aspect_ratio=decrease:force_divisible_by=2" \
  -b:v 500k \
  -b:a 96k \
  -preset fast \
  -tune zerolatency \
  -threads 2 \
  -hls_time 10 \
  -hls_list_size 3 \
  -hls_flags delete_segments+independent_segments+split_by_time \
  -hls_segment_type mpegts \
  -hls_segment_filename "segment%03d.ts" \
  -f hls 480/playlist.m3u8
```

### Master Playlist
```m3u8
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=1128000,RESOLUTION=1280x720
720/playlist.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=596000,RESOLUTION=854x480
480/playlist.m3u8
```

## Error Handling

The implementation includes comprehensive error handling:

- **FFmpeg Errors**: Captures and logs FFmpeg execution errors
- **File System Errors**: Handles file I/O operations with proper cleanup
- **Network Errors**: Implements retry logic for upload failures
- **Timeout Handling**: Prevents infinite polling with configurable timeouts
- **Fallback Mechanism**: Gracefully falls back to existing remote processing

## Testing

A basic test class is provided:
- **File**: `app/src/test/java/us/fireshare/tweet/video/LocalVideoProcessingServiceTest.kt`
- **Purpose**: Verifies service instantiation and basic functionality

## Benefits

1. **Reduced Server Load**: Local processing reduces backend processing requirements
2. **Faster Processing**: No network upload time for initial video processing
3. **Offline Capability**: Can process videos without network connectivity
4. **Cost Efficiency**: Reduces bandwidth and server costs
5. **Better User Experience**: Faster video processing and upload

## Dependencies

- **FFmpeg Kit**: `com.arthenica:ffmpeg-kit-android-16kb:6.0-2`
- **Existing Dependencies**: Ktor client, Gson, Timber logging

## Configuration

The system uses configurable parameters:

- **HLS Segment Duration**: 10 seconds
- **HLS Playlist Size**: 3 segments
- **Segment Naming**: `segment%03d.ts` (segment000.ts, segment001.ts, etc.)
- **Video Resolutions**: 
  - 720p: 1280x720 (landscape) or 720x1280 (portrait)
  - 480p: 854x480 (landscape) or 480x854 (portrait)
- **Video Bitrates**: 
  - 720p: 1000k
  - 480p: 500k
- **Audio Bitrates**:
  - 720p: 128k
  - 480p: 96k
- **Codec Selection**: Automatic (COPY for ≤720p videos, libx264 otherwise)
- **Threads**: 2 (for encoding)

## Recent Updates (October 2025)

### Segment Naming Standardization
- ✅ Updated segment naming from `%03d.ts` to `segment%03d.ts`
- ✅ Generates standard format: `segment000.ts`, `segment001.ts`, etc.
- ✅ Aligns with web streaming conventions

### Codec Optimization
- ✅ Implemented automatic COPY codec detection for ≤720p videos
- ✅ Falls back to libx264 if COPY fails
- ✅ Significantly faster processing for compatible videos

### Aspect Ratio Support
- ✅ Smart resolution calculation for portrait and landscape videos
- ✅ Maintains original aspect ratio
- ✅ Ensures even dimensions for codec compatibility

## Future Enhancements

Potential improvements for future versions:

1. ✅ **Multiple Bitrate Support**: ✅ IMPLEMENTED (720p + 480p)
2. **Progress Callbacks**: Real-time progress updates for UI
3. **Background Processing**: Use WorkManager for background processing
4. **Caching**: Cache processed videos for reuse
5. **Quality Selection**: Allow users to choose processing quality
6. **Batch Processing**: Process multiple videos simultaneously
7. **Hardware Acceleration**: Utilize device GPU for faster encoding
