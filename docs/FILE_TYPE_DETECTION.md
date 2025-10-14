# File Type Detection
**Last Updated:** October 14, 2025

## Overview

Robust file type detection system using multiple methods with fallback mechanisms. When uploaded files cannot be detected by MIME type or file extensions, the system uses magic bytes (file headers) for content-based detection.

## Implementation

### FileTypeDetector Class

**Location:** `app/src/main/java/us/fireshare/tweet/service/FileTypeDetector.kt`

The `FileTypeDetector` object provides comprehensive file type detection with three detection methods:

1. **Extension-based detection** (fastest)
2. **Magic bytes detection** (most reliable)
3. **Android MIME type detection** (fallback)

### Detection Methods

#### 1. Extension-Based Detection
- Fastest method
- Checks file extension against known patterns
- Supports common formats: images, videos, audio, documents, archives

#### 2. Magic Bytes Detection
- Reads first 32 bytes of file
- Checks against known file signatures
- **Supported Formats:**
  - **Images**: JPEG, PNG, GIF, WebP, BMP, TIFF, HEIC
  - **Videos**: MP4, AVI, MKV, WebM, 3GP, FLV, WMV, MOV, M4V
  - **Audio**: MP3, WAV, AAC, OGG, FLAC, M4A, WMA, Opus, AMR
  - **Documents**: PDF, Word (DOC/DOCX), Excel (XLS/XLSX), PowerPoint (PPT/PPTX)
  - **Text**: TXT, RTF, HTML, XML, JSON, CSV
  - **Archives**: ZIP, RAR, 7Z, TAR, GZ, BZ2

#### 3. Android MIME Type Detection
- Uses Android's built-in MIME type detection
- Leverages ContentResolver.getType()
- Provides fallback when other methods fail

## Integration

### Upload Process

Integrated into `MediaUploadService.kt`:

```kotlin
val mediaType = when {
    mimeType?.startsWith("image/") == true -> MediaType.Image
    mimeType?.startsWith("video/") == true -> MediaType.Video
    // ... other MIME type checks
    else -> {
        // Fallback: try extension detection
        val extensionType = when (extension) {
            "jpg", "jpeg", "png" -> MediaType.Image
            "mp4", "avi", "mov" -> MediaType.Video
            // ... other extensions
            else -> MediaType.Unknown
        }
        
        // If extension detection failed, use magic bytes
        if (extensionType == MediaType.Unknown) {
            FileTypeDetector.detectFileType(context, uri, fileName)
        } else {
            extensionType
        }
    }
}
```

### Media Preview

The `MediaPreviewGrid.kt` uses extension-based detection for display purposes.

## Usage

```kotlin
// Detect file type with automatic fallback
val mediaType = FileTypeDetector.detectFileType(context, uri, fileName)

if (mediaType != MediaType.Unknown) {
    // Proceed with upload using detected type
} else {
    // Handle unknown file type
}
```

## Files Modified

1. **app/src/main/java/us/fireshare/tweet/service/FileTypeDetector.kt** (NEW)
   - Complete file type detection implementation
   - Magic bytes detection for 20+ file formats
   - Comprehensive logging

2. **app/src/main/java/us/fireshare/tweet/service/MediaUploadService.kt**
   - Enhanced file type detection logic with fallback

3. **app/src/main/java/us/fireshare/tweet/widget/MediaPreviewGrid.kt**
   - Updated `inferMediaTypeFromAttachment` function

## Benefits

1. **Reliable Detection**: Files with missing or incorrect extensions are properly identified
2. **Better UX**: Users can upload files regardless of extension naming
3. **Robust Fallback**: Multiple detection methods ensure high success rate
4. **Debugging Support**: Comprehensive logging helps troubleshoot detection issues
5. **No Dependencies**: Uses Android's built-in capabilities

## Logging

Comprehensive logging using Timber:
- Detection method used
- Magic bytes signatures matched
- MIME types detected
- Conversion results

## Error Handling

- Graceful fallback between detection methods
- Comprehensive exception handling
- Detailed logging for debugging
- Returns `MediaType.Unknown` when all detection fails

This implementation ensures that uploaded files are correctly identified and processed, even when file extensions are missing or incorrect.

