# File Type Detection Implementation

## Overview

This implementation provides robust file type detection using multiple methods with fallback mechanisms. When uploaded files cannot be detected by input arguments or file extensions, the system uses magic bytes (file headers) and Apache Tika for content-based detection.

## Implementation Details

### FileTypeDetector Class

Located at: `app/src/main/java/us/fireshare/tweet/service/FileTypeDetector.kt`

The `FileTypeDetector` object provides a comprehensive file type detection system with three detection methods:

1. **Extension-based detection** (fastest)
2. **Magic bytes detection** (most reliable)
3. **Android MIME type detection** (fallback)

### Detection Methods

#### 1. Extension-based Detection
- Fastest method
- Checks file extension against known patterns
- Supports common formats: images, videos, audio, documents, archives

#### 2. Magic Bytes Detection
- Reads first 32 bytes of file
- Checks against known file signatures
- Supports formats:
  - **Images**: JPEG, PNG, GIF, WebP, BMP, TIFF, HEIC
  - **Videos**: MP4, AVI, MKV, WebM, 3GP, FLV, WMV, MOV
  - **Audio**: MP3, WAV, AAC, OGG, FLAC, M4A
  - **Documents**: PDF
  - **Archives**: ZIP, RAR, 7Z

#### 3. Android MIME Type Detection
- Uses Android's built-in MIME type detection
- Leverages ContentResolver.getType() for system-level detection
- Provides fallback when other methods fail

### Integration Points

#### Upload Process
The file type detection is integrated into the upload process in `HproseInstance.kt`:

```kotlin
// In uploadToIPFS function
val mediaType = when {
    mimeType?.startsWith("image/") == true -> MediaType.Image
    mimeType?.startsWith("video/") == true -> MediaType.Video
    mimeType?.startsWith("audio/") == true -> MediaType.Audio
    // ... other MIME type checks
    else -> {
        // Fallback: try to determine type from file extension
        val extensionType = when (extension) {
            "jpg", "jpeg", "png", "gif", "webp" -> MediaType.Image
            "mp4", "avi", "mov", "mkv" -> MediaType.Video
            // ... other extensions
            else -> MediaType.Unknown
        }
        
        // If extension detection failed, use FileTypeDetector for magic bytes detection
        if (extensionType == MediaType.Unknown) {
            FileTypeDetector.detectFileType(context, uri, fileName)
        } else {
            extensionType
        }
    }
}
```

#### Media Preview
The `inferMediaTypeFromAttachment` function in `MediaPreviewGrid.kt` uses extension-based detection for display purposes.

### Dependencies

No additional dependencies required - uses Android's built-in MIME type detection and custom magic bytes implementation.

### Usage Example

```kotlin
// Detect file type with fallback
val mediaType = FileTypeDetector.detectFileType(context, uri, fileName)

// Use in upload process
if (mediaType != MediaType.Unknown) {
    // Proceed with upload using detected type
} else {
    // Handle unknown file type
}
```

### Logging

The implementation includes comprehensive logging using Timber:
- Detection method used
- Magic bytes signatures matched
- MIME types detected by Tika
- Conversion results

### Benefits

1. **Robust Detection**: Multiple fallback methods ensure reliable file type detection
2. **Performance**: Fastest method (extension) is tried first
3. **Accuracy**: Magic bytes provide reliable detection even with incorrect extensions
4. **Comprehensive**: Supports wide range of file formats
5. **Maintainable**: Clear separation of detection methods

### Supported File Types

#### Images
- JPEG, PNG, GIF, WebP, BMP, TIFF, HEIC

#### Videos
- MP4, MOV, AVI, MKV, WebM, 3GP, FLV, WMV, M4V

#### Audio
- MP3, WAV, AAC, OGG, FLAC, M4A, WMA, Opus, AMR

#### Documents
- PDF, Word (DOC/DOCX), Excel (XLS/XLSX), PowerPoint (PPT/PPTX)
- Text files (TXT, RTF, HTML, XML, JSON, CSV)

#### Archives
- ZIP, RAR, 7Z, TAR, GZ, BZ2

### Error Handling

- Graceful fallback between detection methods
- Comprehensive exception handling
- Detailed logging for debugging
- Returns `MediaType.Unknown` when detection fails

This implementation ensures that uploaded files are correctly identified and processed, even when file extensions are missing or incorrect.
