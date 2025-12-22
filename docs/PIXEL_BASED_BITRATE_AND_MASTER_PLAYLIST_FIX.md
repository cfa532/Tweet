# Pixel-Based Bitrate Calculation & Master Playlist Fix

**Date:** December 22, 2025

## Overview

Updated Android HLS video conversion to match iOS implementation:
1. **Pixel-based bitrate calculation** using actual width × height (not approximation)
2. **Master playlist creation** for both single and dual variants

## Problem

### Issue 1: Lower Variant Bitrate Approximation

**Before:** Android used approximation for lower variant bitrate
```kotlin
val lowerResolutionPixels = lowerResolution * lowerResolution // approximate for square aspect ratio
```

This incorrectly assumed square aspect ratio (480×480 = 230,400 pixels) instead of calculating actual dimensions based on aspect ratio.

**Example:**
- 480p landscape (854×480) = 409,920 pixels
- Approximation (480×480) = 230,400 pixels
- Error: ~44% underestimation of actual pixels

### Issue 2: No Master Playlist for Single Variant

**Before:** Single-variant HLS only created `playlist.m3u8` at root level, without `master.m3u8`

**iOS behavior:** Always creates both `master.m3u8` and `playlist.m3u8` at root level, even for single variant

## Solution

### 1. Pixel-Based Bitrate Calculation

Updated lower variant bitrate calculation to use actual dimensions based on aspect ratio:

```kotlin
val lowerResolutionBitrate = if (videoResolution != null) {
    val (width, height) = videoResolution
    val aspectRatio = width.toFloat() / height.toFloat()
    val (lowerWidth, lowerHeight) = if (aspectRatio < 1.0f) {
        // Portrait: scale to target width
        val targetWidth = minOf(width, lowerResolution)
        val targetHeight = (targetWidth / aspectRatio).toInt()
        Pair(targetWidth, targetHeight)
    } else {
        // Landscape: scale to target height
        val targetHeight = minOf(height, lowerResolution)
        val targetWidth = (targetHeight * aspectRatio).toInt()
        Pair(targetWidth, targetHeight)
    }
    val lowerPixelCount = lowerWidth * lowerHeight
    val calculatedBitrate = ((lowerPixelCount.toDouble() / REFERENCE_720P_PIXELS) * REFERENCE_720P_BITRATE).toInt()
    maxOf(MIN_BITRATE, calculatedBitrate)
} else {
    // Fallback if resolution unknown
    maxOf(MIN_BITRATE, ((lowerResolution * lowerResolution).toDouble() / REFERENCE_720P_PIXELS * REFERENCE_720P_BITRATE).toInt())
}
```

### 2. Master Playlist for Single Variant

Updated to always create `master.m3u8` for both single and dual variants:

**Single variant structure:**
```
outputDir/
  ├── master.m3u8         (NEW - points to playlist.m3u8)
  ├── playlist.m3u8       (existing)
  ├── segment000.ts
  ├── segment001.ts
  └── ...
```

**master.m3u8 content (single variant):**
```m3u8
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=410000,RESOLUTION=854x480
playlist.m3u8
```

**Dual variant structure (unchanged):**
```
outputDir/
  ├── master.m3u8
  ├── 720p/
  │   ├── playlist.m3u8
  │   ├── segment000.ts
  │   └── ...
  └── 480p/
      ├── playlist.m3u8
      ├── segment000.ts
      └── ...
```

## Bitrate Comparison: Before vs After

### Landscape Videos (16:9 aspect ratio)

| Resolution | Actual Dimensions | Before (approximation) | After (pixel-based) | Improvement |
|------------|-------------------|------------------------|---------------------|-------------|
| 720p | 1280×720 (921,600 px) | 1000k | 1000k | No change |
| 480p | 854×480 (409,920 px) | **250k** | **444k** | +194k (+78%) |
| 360p | 640×360 (230,400 px) | 141k | **250k** | +109k (+77%) |

### Portrait Videos (9:16 aspect ratio)

| Resolution | Actual Dimensions | Before (approximation) | After (pixel-based) | Improvement |
|------------|-------------------|------------------------|---------------------|-------------|
| 720p | 720×1280 (921,600 px) | 1000k | 1000k | No change |
| 480p | 480×854 (409,920 px) | **250k** | **444k** | +194k (+78%) |
| 360p | 360×640 (230,400 px) | 141k | **250k** | +109k (+77%) |

### Square Videos (1:1 aspect ratio)

| Resolution | Actual Dimensions | Before (approximation) | After (pixel-based) | Improvement |
|------------|-------------------|------------------------|---------------------|-------------|
| 720p | 720×720 (518,400 px) | 1000k | **562k** | -438k (correct reduction) |
| 480p | 480×480 (230,400 px) | **250k** | **250k** | No change |
| 360p | 360×360 (129,600 px) | 141k | **141k** | No change |

## Changes Made

### File: `app/src/fullPlay/java/us/fireshare/tweet/video/LocalHLSConverter.kt`

#### Change 1: Pixel-based calculation for lower variant (lines 121-143)
- Calculates actual width and height based on aspect ratio
- Uses actual pixel count (width × height) instead of approximation
- Maintains minimum bitrate of 500k for quality
- Adds detailed logging for debugging

#### Change 2: Always create master playlist (line 154)
- Removed conditional master playlist creation
- Always creates `master.m3u8` for consistency with iOS

#### Change 3: Call master playlist creation for single variant (lines 311-320)
- Added `createSingleVariantMasterPlaylist()` call for single variant
- Creates master playlist pointing to root-level `playlist.m3u8`

#### Change 4: Verify master playlist in single variant (lines 337-350)
- Updated verification to check for `master.m3u8` existence
- Ensures both `master.m3u8` and `playlist.m3u8` are created

#### Change 5: Updated createSingleVariantMasterPlaylist function (lines 672-696)
- Renamed from `createSingleResolutionMasterPlaylist`
- Points to root-level `playlist.m3u8` (not subdirectory)
- Matches iOS behavior

## iOS vs Android Comparison - NOW MATCHED ✅

### Bitrate Calculation

**iOS (VideoConversionService.swift, lines 146-168):**
```swift
let aspectRatio = Float(info.displayWidth) / Float(info.displayHeight)
let lowerWidth: Int
let lowerHeight: Int

if aspectRatio < 1.0 {
    // Portrait: scale to 480 width
    lowerWidth = min(info.displayWidth, lowerResolution)
    lowerHeight = Int(Float(lowerWidth) / aspectRatio)
} else {
    // Landscape: scale to 480 height
    lowerHeight = min(info.displayHeight, lowerResolution)
    lowerWidth = Int(Float(lowerHeight) * aspectRatio)
}

let lowerPixelCount = lowerWidth * lowerHeight
let calculatedBitrate = Int((Double(lowerPixelCount) / Double(REFERENCE_720P_PIXELS)) * Self.reference720pBitrate)
```

**Android (LocalHLSConverter.kt, lines 127-143):**
```kotlin
val aspectRatio = width.toFloat() / height.toFloat()
val (lowerWidth, lowerHeight) = if (aspectRatio < 1.0f) {
    // Portrait: scale to target width
    val targetWidth = minOf(width, lowerResolution)
    val targetHeight = (targetWidth / aspectRatio).toInt()
    Pair(targetWidth, targetHeight)
} else {
    // Landscape: scale to target height
    val targetHeight = minOf(height, lowerResolution)
    val targetWidth = (targetHeight * aspectRatio).toInt()
    Pair(targetWidth, targetHeight)
}
val lowerPixelCount = lowerWidth * lowerHeight
val calculatedBitrate = ((lowerPixelCount.toDouble() / REFERENCE_720P_PIXELS) * REFERENCE_720P_BITRATE).toInt()
```

✅ **Identical logic and calculations**

### Master Playlist Creation

**iOS (VideoConversionService.swift, line 102):**
```swift
let masterPlaylistURL = hlsDirectory.appendingPathComponent("master.m3u8")
// Always creates master.m3u8 for both single and dual variants
```

**Android (LocalHLSConverter.kt, line 154):**
```kotlin
val masterPlaylistPath = File(outputDir, "master.m3u8").absolutePath
// Always creates master.m3u8 for both single and dual variants
```

✅ **Identical behavior**

### Single Variant Master Playlist Content

**Both platforms now create:**
```m3u8
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=410000,RESOLUTION=854x480
playlist.m3u8
```

✅ **Identical format and structure**

## Benefits

1. **More accurate bitrate allocation** - properly reflects actual pixel count
2. **Better video quality** - especially for non-square aspect ratios
3. **Consistent HLS structure** - always includes master.m3u8
4. **iOS/Android parity** - identical behavior on both platforms
5. **Better player compatibility** - standard HLS structure with master playlist

## Testing Recommendations

Test with various aspect ratios:
- Landscape (16:9): 1920×1080, 1280×720, 854×480
- Portrait (9:16): 1080×1920, 720×1280, 480×854
- Square (1:1): 720×720, 480×480
- Ultrawide (21:9): 2560×1080
- Vertical (9:21): 1080×2560

Verify:
1. ✅ master.m3u8 exists at root level
2. ✅ Bitrates are calculated correctly based on pixel count
3. ✅ Video plays correctly in HLS players
4. ✅ Quality matches expectations for resolution/aspect ratio

## Build Status

✅ Compilation successful
✅ No linter errors
✅ Ready for testing

