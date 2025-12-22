# HLS Video Conversion Algorithm

**Date:** December 22, 2025  
**Version:** 2.0 (Pixel-Based with Master Playlist)

## Overview

The HLS (HTTP Live Streaming) conversion algorithm converts uploaded videos into adaptive bitrate streaming format. It creates multiple resolution variants (720p + 480p/360p, or single variant) optimized for different network conditions and devices.

## Algorithm Flow

```
┌─────────────────┐
│  Input Video    │
└────────┬────────┘
         │
         ├──> 1. Resolution Detection
         │       ├─ Get actual dimensions (width × height)
         │       ├─ Detect orientation (landscape/portrait)
         │       └─ Calculate resolution value
         │
         ├──> 2. Bitrate Calculation (Pixel-Based)
         │       ├─ High quality bitrate (for 720p variant)
         │       └─ Lower quality bitrate (for 480p/360p variant)
         │
         ├──> 3. Variant Selection Decision
         │       ├─ Single variant: Source ≤480p
         │       └─ Dual variant: Source >480p
         │
         ├──> 4. COPY Codec Decision
         │       ├─ Use COPY if normalized ≤target resolution
         │       └─ Use libx264 if needs re-encoding
         │
         ├──> 5. FFmpeg HLS Conversion
         │       ├─ High quality variant (720p) [if dual]
         │       └─ Lower quality variant (480p/360p)
         │
         ├──> 6. Master Playlist Creation
         │       ├─ Dual variant: Points to subdirectories
         │       └─ Single variant: Points to root playlist
         │
         └──> 7. Verification & Output
                ├─ Verify all playlists exist
                └─ Return success with output directory
```

## Step-by-Step Breakdown

### 1. Resolution Detection

**Purpose:** Determine video orientation and resolution value for routing decisions.

**Algorithm:**
```kotlin
// Get actual video dimensions
val (width, height) = VideoManager.getVideoResolution(context, inputUri)

// Detect resolution value based on orientation
val resolutionValue = if (width >= height) {
    height  // Landscape: use height (e.g., 1920×1080 → 1080p)
} else {
    width   // Portrait: use width (e.g., 1080×1920 → 1080p)
}
```

**Examples:**
- 1920×1080 (landscape) → 1080p
- 1080×1920 (portrait) → 1080p
- 1280×720 (landscape) → 720p
- 720×1280 (portrait) → 720p
- 854×480 (landscape) → 480p

### 2. Bitrate Calculation (Pixel-Based)

**Purpose:** Calculate appropriate bitrates based on actual pixel count, not just resolution.

**Constants:**
```kotlin
REFERENCE_720P_BITRATE = 1000  // Base bitrate for 720p (in kbps)
REFERENCE_720P_PIXELS = 921600  // 1280 × 720 pixels
MIN_BITRATE = 500  // Minimum bitrate for quality (in kbps)
```

**Formula:**
```
bitrate = (pixelCount / REFERENCE_720P_PIXELS) × REFERENCE_720P_BITRATE
finalBitrate = MAX(bitrate, MIN_BITRATE)
```

#### 2.1. High Quality Bitrate (720p Variant)

```kotlin
val highQualityBitrate = when {
    resolutionValue > 720 -> 1500  // >720p: fixed 1500k
    resolutionValue == 720 -> 1000  // =720p: fixed 1000k
    resolutionValue < 720 -> {
        // <720p: pixel-based proportional
        val pixelCount = width × height
        MAX(500, (pixelCount / 921600) × 1000)
    }
}
```

**Examples:**
- 1920×1080 (2,073,600 px) → **1500k** (>720p special case)
- 1280×720 (921,600 px) → **1000k** (reference)
- 854×480 (409,920 px) → **500k** (444k → 500k min)
- 640×360 (230,400 px) → **500k** (250k → 500k min)

#### 2.2. Lower Quality Bitrate (480p/360p Variant)

**Step 1:** Calculate target dimensions based on aspect ratio
```kotlin
val lowerResolution = if (useRoute2) 360 else 480

val (lowerWidth, lowerHeight) = if (aspectRatio < 1.0) {
    // Portrait: scale to target width
    val targetWidth = min(width, lowerResolution)
    val targetHeight = (targetWidth / aspectRatio).toInt()
    Pair(targetWidth, targetHeight)
} else {
    // Landscape: scale to target height
    val targetHeight = min(height, lowerResolution)
    val targetWidth = (targetHeight × aspectRatio).toInt()
    Pair(targetWidth, targetHeight)
}
```

**Step 2:** Calculate bitrate from pixel count
```kotlin
val lowerPixelCount = lowerWidth × lowerHeight
val calculatedBitrate = (lowerPixelCount / 921600) × 1000
val lowerBitrate = MAX(500, calculatedBitrate)
```

**Examples (480p landscape 16:9):**
- 854×480 (409,920 px) → **500k** (444k → 500k min)

**Examples (360p landscape 16:9):**
- 640×360 (230,400 px) → **500k** (250k → 500k min)

**Examples (480p portrait 9:16):**
- 480×854 (409,920 px) → **500k** (444k → 500k min)

### 3. Variant Selection Decision

**Purpose:** Decide between single variant (480p only) or dual variant (720p + 480p).

**Logic:**
```kotlin
val shouldCreate720p = shouldCreateDualVariant && 
                       resolutionValue != null && 
                       resolutionValue > lowerResolution
```

**Decision Table:**

| Source Resolution | Lower Target | Dual Variant Flag | Result | Output |
|-------------------|-------------|-------------------|--------|--------|
| 1080p | 480p | true | **Dual** | 720p + 480p |
| 720p | 480p | true | **Dual** | 720p + 480p |
| 480p | 480p | true | **Single** | 480p only |
| 360p | 480p | true | **Single** | 360p only (no upscale) |
| 720p | 480p | false | **Single** | 480p only (forced) |

**Key Rules:**
1. ✅ Create dual variant if source > lower target resolution
2. ❌ Don't create dual variant if source ≤ lower target
3. ❌ Never upscale (if source < target, keep source resolution)

### 4. COPY Codec Decision

**Purpose:** Use COPY codec to preserve quality when re-encoding is not needed.

**720p Variant COPY Logic:**
```kotlin
val shouldUseCopyFor720p = isNormalized && 
                          normalizedResolution > 480 && 
                          normalizedResolution ≤ 720
```

**480p/360p Variant COPY Logic:**
```kotlin
val shouldUseCopyForLower = isNormalized && 
                           normalizedResolution ≤ lowerResolution
```

**Decision Table:**

| Normalized To | Target | Use COPY? | Reason |
|---------------|--------|-----------|--------|
| 720p | 720p | ✅ Yes | Exact match |
| 576p | 720p | ✅ Yes | Within range (>480p, ≤720p) |
| 480p | 720p | ❌ No | Below threshold |
| 480p | 480p | ✅ Yes | Exact match |
| 360p | 480p | ✅ Yes | ≤480p |
| - (not normalized) | any | ❌ No | Not normalized |

**Benefits:**
- ✅ Preserves original quality
- ✅ Faster conversion (no re-encoding)
- ✅ Preserves original bitrate
- ✅ Prevents quality loss

**Fallback:**
If COPY codec fails, automatically retry with libx264 encoding.

### 5. FFmpeg HLS Conversion

**Purpose:** Execute FFmpeg to create HLS segments and playlists.

**Parameters:**
```kotlin
HLS_SEGMENT_DURATION = 10  // 10 seconds per segment
HLS_PLAYLIST_SIZE = 0  // Keep all segments (VOD, not live)
```

#### 5.1. Dual Variant Structure

```
outputDir/
├── master.m3u8                 # Master playlist
├── 720p/
│   ├── playlist.m3u8          # 720p variant playlist
│   ├── segment000.ts
│   ├── segment001.ts
│   └── ...
└── 480p/ (or 360p/)
    ├── playlist.m3u8          # 480p/360p variant playlist
    ├── segment000.ts
    ├── segment001.ts
    └── ...
```

**master.m3u8:**
```m3u8
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
720p/playlist.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=854x480
480p/playlist.m3u8
```

#### 5.2. Single Variant Structure

```
outputDir/
├── master.m3u8                 # Master playlist (points to root)
├── playlist.m3u8               # Single variant playlist
├── segment000.ts
├── segment001.ts
└── ...
```

**master.m3u8:**
```m3u8
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=854x480
playlist.m3u8
```

#### 5.3. FFmpeg Command (libx264)

```bash
ffmpeg -i input.mp4 \
  -c:v libx264 \
  -c:a aac \
  -vf "scale=854:480:force_original_aspect_ratio=decrease:force_divisible_by=2" \
  -b:v 500k \
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
  -bufsize 500k \
  -maxrate 500k \
  -metadata:s:v:0 rotate=0 \
  -hls_time 10 \
  -hls_list_size 0 \
  -hls_flags independent_segments \
  -hls_segment_type mpegts \
  -hls_segment_filename "segment%03d.ts" \
  -f hls "playlist.m3u8"
```

**Key Parameters:**
- `-profile:v baseline` - Maximum compatibility (iOS/Android)
- `-pix_fmt yuv420p` - Standard color format
- `-g 30` - Keyframe every 30 frames (~1 second at 30fps)
- `-hls_time 10` - 10 second segments
- `-hls_flags independent_segments` - Each segment independently decodable

#### 5.4. FFmpeg Command (COPY codec)

```bash
ffmpeg -i input.mp4 \
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

**Key Difference:**
- `-c copy` - No re-encoding, just remux streams

### 6. Master Playlist Creation

**Purpose:** Create master playlist that references all variant playlists.

**Always created** for both single and dual variants (matches iOS behavior).

#### 6.1. Dual Variant Master Playlist

```kotlin
fun createMasterPlaylist(
    outputDir: File,
    width720: Int,
    height720: Int,
    widthLower: Int,
    heightLower: Int,
    resolution720pBitrate: String,
    lowerResolution: Int,
    lowerResolutionBitrate: String
)
```

**Content:**
```m3u8
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
720p/playlist.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=854x480
480p/playlist.m3u8
```

#### 6.2. Single Variant Master Playlist

```kotlin
fun createSingleVariantMasterPlaylist(
    outputDir: File,
    widthLower: Int,
    heightLower: Int,
    lowerResolution: Int,
    lowerResolutionBitrate: String
)
```

**Content:**
```m3u8
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=854x480
playlist.m3u8
```

**Note:** Points to root-level `playlist.m3u8`, not subdirectory.

### 7. Verification & Output

**Purpose:** Verify all required files were created before returning success.

**Dual Variant Verification:**
```kotlin
master.m3u8 exists ✓
720p/playlist.m3u8 exists ✓
480p/playlist.m3u8 exists ✓
```

**Single Variant Verification:**
```kotlin
master.m3u8 exists ✓
playlist.m3u8 exists ✓
```

**Output:**
- Success: `HLSConversionResult.Success(outputDir)`
- Failure: `HLSConversionResult.Error(message)`

## Complete Examples

### Example 1: 1080p Landscape Video → Dual Variant

**Input:**
- Dimensions: 1920×1080
- Resolution: 1080p
- File size: 50MB

**Step 1: Resolution Detection**
- Width: 1920, Height: 1080
- Orientation: Landscape (width ≥ height)
- Resolution value: 1080 (height)

**Step 2: Bitrate Calculation**
- High quality: 1500k (>720p special case)
- Lower quality (480p): 854×480 = 409,920 px → 500k (444k → 500k min)

**Step 3: Variant Selection**
- Source (1080p) > Lower target (480p) → **Dual variant**
- Create 720p variant: ✅ Yes
- Create 480p variant: ✅ Yes

**Step 4: COPY Codec Decision**
- 720p variant: ❌ No (not normalized)
- 480p variant: ❌ No (not normalized)
- Use libx264 for both

**Step 5: FFmpeg Conversion**
- 720p: 1280×720 @ 1500k (re-encoded with libx264)
- 480p: 854×480 @ 500k (re-encoded with libx264)

**Step 6: Master Playlist**
```m3u8
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720
720p/playlist.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=854x480
480p/playlist.m3u8
```

**Output Structure:**
```
outputDir/
├── master.m3u8
├── 720p/
│   ├── playlist.m3u8
│   └── segment*.ts
└── 480p/
    ├── playlist.m3u8
    └── segment*.ts
```

### Example 2: 480p Portrait Video → Single Variant

**Input:**
- Dimensions: 480×854
- Resolution: 480p
- File size: 15MB

**Step 1: Resolution Detection**
- Width: 480, Height: 854
- Orientation: Portrait (height > width)
- Resolution value: 480 (width)

**Step 2: Bitrate Calculation**
- High quality: 500k (444k → 500k min)
- Lower quality (480p): 480×854 = 409,920 px → 500k (444k → 500k min)

**Step 3: Variant Selection**
- Source (480p) = Lower target (480p) → **Single variant**
- Create 720p variant: ❌ No (source not > lower target)
- Create 480p variant: ✅ Yes

**Step 4: COPY Codec Decision**
- 480p variant: ❌ No (not normalized)
- Use libx264

**Step 5: FFmpeg Conversion**
- 480p: 480×854 @ 500k (re-encoded with libx264)

**Step 6: Master Playlist**
```m3u8
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=480x854
playlist.m3u8
```

**Output Structure:**
```
outputDir/
├── master.m3u8
├── playlist.m3u8
└── segment*.ts
```

### Example 3: 720p Video (Normalized) → Dual Variant with COPY

**Input:**
- Dimensions: 1280×720 (already normalized)
- Resolution: 720p
- File size: 30MB
- isNormalized: true
- normalizedResolution: 720

**Step 1: Resolution Detection**
- Width: 1280, Height: 720
- Orientation: Landscape
- Resolution value: 720

**Step 2: Bitrate Calculation**
- High quality: 1000k (=720p reference)
- Lower quality (480p): 854×480 = 409,920 px → 500k

**Step 3: Variant Selection**
- Source (720p) > Lower target (480p) → **Dual variant**
- Create 720p variant: ✅ Yes
- Create 480p variant: ✅ Yes

**Step 4: COPY Codec Decision**
- 720p variant: ✅ Yes (normalized to 720p, >480p and ≤720p)
- 480p variant: ❌ No (normalized to 720p, not ≤480p)

**Step 5: FFmpeg Conversion**
- 720p: 1280×720 @ 1000k (COPY codec, no re-encoding)
- 480p: 854×480 @ 500k (re-encoded with libx264)

**Step 6: Master Playlist**
```m3u8
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
720p/playlist.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=854x480
480p/playlist.m3u8
```

**Output Structure:**
```
outputDir/
├── master.m3u8
├── 720p/
│   ├── playlist.m3u8
│   └── segment*.ts (copied, not re-encoded)
└── 480p/
    ├── playlist.m3u8
    └── segment*.ts (re-encoded)
```

## Route Selection

The algorithm supports two routes for HLS conversion:

### Route 1 (Default): 720p + 480p
- Lower resolution: **480p**
- Use case: Standard quality streaming

### Route 2 (useRoute2=true): 720p + 360p
- Lower resolution: **360p**
- Use case: Lower bandwidth scenarios

**Selection:**
```kotlin
val lowerResolution = if (useRoute2) 360 else 480
```

## Dynamic Timeout Calculation

**Purpose:** Prevent timeouts for large/long videos while not waiting unnecessarily for small videos.

**Formula:**
```kotlin
val durationBasedTimeout = (videoDurationMs / 1000) × BASE_PROCESSING_RATE_MS_PER_SECOND
val sizeBasedTimeout = (fileSizeBytes / (1024 × 1024)) × FILE_SIZE_MULTIPLIER × 1000

val calculatedTimeout = durationBasedTimeout + sizeBasedTimeout
val finalTimeout = clamp(calculatedTimeout, MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
```

**Constants:**
- `BASE_PROCESSING_RATE_MS_PER_SECOND = 2000` (2 seconds processing per 1 second video)
- `FILE_SIZE_MULTIPLIER = 0.001` (1ms per MB)
- `MIN_TIMEOUT_MS = 600,000` (10 minutes)
- `MAX_TIMEOUT_MS = 10,800,000` (3 hours)

**Examples:**
- 30 second, 10MB video → 1 minute timeout
- 5 minute, 50MB video → 10 minutes timeout (minimum)
- 30 minute, 200MB video → 60 minutes timeout

## Key Features

### ✅ Pixel-Based Bitrate Calculation
- Calculates bitrate based on actual pixel count (width × height)
- Accurate for all aspect ratios (16:9, 4:3, 1:1, 21:9, 9:16, etc.)
- Prevents overestimation for non-standard aspect ratios

### ✅ No Upscaling
- Never increases resolution above source
- Preserves original quality when source < target

### ✅ COPY Codec Optimization
- Uses COPY codec when possible to preserve quality
- Automatic fallback to libx264 if COPY fails

### ✅ Consistent Master Playlist
- Always creates master.m3u8 (matches iOS)
- Single and dual variants both have master playlist

### ✅ iOS Compatibility
- Profile baseline
- yuv420p pixel format
- Keyframe interval (g=30)
- Level 3.1

### ✅ Dynamic Timeout
- Adjusts timeout based on video duration and file size
- Prevents premature timeout for large videos

### ✅ Fallback Handling
- COPY codec → libx264 fallback
- Resolution unknown → sensible defaults
- Error handling at each step

## Constants Reference

```kotlin
// Bitrate constants
REFERENCE_720P_BITRATE = 1000  // Base bitrate for 720p (kbps)
REFERENCE_720P_PIXELS = 921600  // 1280 × 720 pixels
MIN_BITRATE = 500  // Minimum bitrate for quality (kbps)

// HLS segment settings
HLS_SEGMENT_DURATION = 10  // Segment duration (seconds)
HLS_PLAYLIST_SIZE = 0  // Keep all segments (VOD)

// Timeout settings
MIN_TIMEOUT_MS = 600000  // 10 minutes
MAX_TIMEOUT_MS = 10800000  // 3 hours
BASE_PROCESSING_RATE_MS_PER_SECOND = 2000  // 2 seconds per 1 second video
FILE_SIZE_MULTIPLIER = 0.001  // 1ms per MB
```

## Related Documentation

- `PIXEL_BASED_ALGORITHM_VERIFICATION.md` - Pixel-based calculation details
- `BITRATE_CONSTANTS_REFACTOR.md` - Constants standardization
- `MIN_BITRATE_500K_ENFORCEMENT.md` - Minimum bitrate enforcement
- `IOS_ANDROID_VIDEO_COMPARISON.md` - iOS/Android parity

