# Local Video Processing Implementation

## Overview

This implementation integrates the ffmpeg-kit-16KB library to enable local video processing in the Tweet app. The system converts uploaded videos to HLS format locally, compresses them into zip files, and uploads them to a `/process-zip` endpoint.

## Architecture

The implementation consists of four main components:

### 1. LocalHLSConverter
- **File**: `app/src/main/java/us/fireshare/tweet/video/LocalHLSConverter.kt`
- **Purpose**: Converts uploaded videos to HLS format using FFmpeg Kit
- **Key Features**:
  - Uses FFmpeg Kit for local video processing
  - Creates HLS segments with 10-second duration
  - Generates `master.m3u8` and `playlist.m3u8` files compatible with simplevideoplayer
  - Handles video scaling to 1280x720 resolution
  - Uses H.264 video codec and AAC audio codec

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

The generated HLS files follow the structure expected by simplevideoplayer:

```
output_directory/
├── master.m3u8          # Master playlist
├── playlist.m3u8        # Main playlist
├── segment_000.ts       # Video segments
├── segment_001.ts
└── ...
```

## FFmpeg Configuration

The FFmpeg command used for HLS conversion:

```bash
ffmpeg -i input.mp4 \
  -c:v libx264 \
  -c:a aac \
  -b:v 1000k \
  -b:a 128k \
  -vf "scale=1280:720" \
  -hls_time 10 \
  -hls_list_size 3 \
  -hls_flags delete_segments \
  -f hls \
  playlist.m3u8
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

- **HLS Segment Duration**: 10 seconds (configurable)
- **HLS Playlist Size**: 3 segments (configurable)
- **Video Resolution**: 1280x720 (configurable)
- **Video Bitrate**: 1000k (configurable)
- **Audio Bitrate**: 128k (configurable)
- **Polling Interval**: 3 seconds (configurable)
- **Max Polling Time**: 2 hours (configurable)

## Future Enhancements

Potential improvements for future versions:

1. **Multiple Bitrate Support**: Generate multiple quality levels
2. **Progress Callbacks**: Real-time progress updates for UI
3. **Background Processing**: Use WorkManager for background processing
4. **Caching**: Cache processed videos for reuse
5. **Quality Selection**: Allow users to choose processing quality
6. **Batch Processing**: Process multiple videos simultaneously
