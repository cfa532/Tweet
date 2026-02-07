# Minimum Bitrate 500k Enforcement

**Date:** December 22, 2025

## Overview

Enforced a **500k minimum bitrate** across all video conversion code to ensure consistent quality. Previously, some functions used 300k minimum, which resulted in poor quality for very low resolution videos.

## Problem

Different parts of the codebase had inconsistent minimum bitrate values:
- `LocalHLSConverter.kt`: ✅ 500k (already correct)
- `VideoNormalizer.kt`: ❌ **300k** (inconsistent)
- `LocalVideoProcessingService.kt`: ❌ **No minimum** (could be < 500k)

### Example Impact (Before Fix)

For a 240p video (426×240 = 102,240 pixels):
- Proportional calculation: (102,240 / 921,600) × 1000k = **111k**
- With 300k minimum: **300k** (poor quality)
- With 500k minimum: **500k** ✅ (acceptable quality)

## Solution

Updated all video conversion functions to enforce **500k minimum bitrate** consistently.

## Changes Made

### 1. LocalHLSConverter.kt - Already Correct ✅

**File:** `app/src/fullPlay/java/us/fireshare/tweet/video/LocalHLSConverter.kt`

**Constant defined at line 36:**
```kotlin
private const val MIN_BITRATE = 500  // Minimum bitrate in kbps
```

**Enforced in 3 places:**

#### a) High quality bitrate for <720p videos (line 116)
```kotlin
maxOf(MIN_BITRATE, ((pixelCount.toDouble() / REFERENCE_720P_PIXELS) * REFERENCE_720P_BITRATE).toInt())
```

#### b) Lower variant bitrate (line 142)
```kotlin
maxOf(MIN_BITRATE, calculatedBitrate)
```

#### c) Fallback lower variant (line 145)
```kotlin
maxOf(MIN_BITRATE, ((lowerResolution * lowerResolution).toDouble() / REFERENCE_720P_PIXELS * REFERENCE_720P_BITRATE).toInt())
```

### 2. VideoNormalizer.kt - Fixed ✅

**File:** `app/src/fullPlay/java/us/fireshare/tweet/video/VideoNormalizer.kt`

#### Change 1: normalizeTo720p1000k function (line 155)
Already had 500k minimum:
```kotlin
calculatedBitrate.coerceAtLeast(500)  // Minimum 500k
```
✅ No change needed

#### Change 2: getBitrateForResolution function (line 490)
**Before:**
```kotlin
((pixelCount.toDouble() / REFERENCE_720P_PIXELS) * 1000).toInt().coerceAtLeast(300)  // Minimum 300k
```

**After:**
```kotlin
val MIN_BITRATE = 500  // Minimum bitrate in kbps (matches LocalHLSConverter)
// ...
((pixelCount.toDouble() / REFERENCE_720P_PIXELS) * 1000).toInt().coerceAtLeast(MIN_BITRATE)
```
✅ **Changed from 300k → 500k**

### 3. LocalVideoProcessingService.kt - Fixed ✅

**File:** `app/src/fullPlay/java/us/fireshare/tweet/video/LocalVideoProcessingService.kt`

#### calculateNormalizationParams function (line 272)
**Before:**
```kotlin
// Videos ≤720p: normalize at original resolution @ proportional bitrate
val proportionalBitrate = (NORMALIZATION_BASE_BITRATE * resolution / 720)
Pair(resolution, "${proportionalBitrate}k")
```

**After:**
```kotlin
// Videos ≤720p: normalize at original resolution @ proportional bitrate
// Enforce minimum 500k for quality
val proportionalBitrate = maxOf(500, (NORMALIZATION_BASE_BITRATE * resolution / 720))
Pair(resolution, "${proportionalBitrate}k")
```
✅ **Added 500k minimum enforcement**

## Bitrate Comparison: Before vs After

### Videos Affected by Minimum Bitrate

| Resolution | Dimensions | Pixel Count | Proportional Calc | Before | After | Improvement |
|------------|------------|-------------|-------------------|--------|-------|-------------|
| 360p | 640×360 | 230,400 | 250k | 300k | **500k** | +200k (+67%) |
| 240p | 426×240 | 102,240 | 111k | 300k | **500k** | +200k (+67%) |
| 180p | 320×180 | 57,600 | 62k | 300k | **500k** | +200k (+67%) |

### Videos NOT Affected (Above Minimum)

| Resolution | Dimensions | Pixel Count | Proportional Calc | Before | After | Change |
|------------|------------|-------------|-------------------|--------|-------|--------|
| 720p | 1280×720 | 921,600 | 1000k | 1000k | 1000k | No change |
| 576p | 1024×576 | 589,824 | 640k | 640k | 640k | No change |
| 480p | 854×480 | 409,920 | 444k | 444k | **500k** | +56k (+13%) |

## All Locations with 500k Minimum Enforcement

### Summary Table

| File | Function | Line | Status |
|------|----------|------|--------|
| `LocalHLSConverter.kt` | `convertToHLS()` - high quality | 116 | ✅ Already correct |
| `LocalHLSConverter.kt` | `convertToHLS()` - lower variant | 142 | ✅ Already correct |
| `LocalHLSConverter.kt` | `convertToHLS()` - fallback | 145 | ✅ Already correct |
| `VideoNormalizer.kt` | `normalizeTo720p1000k()` | 155 | ✅ Already correct |
| `VideoNormalizer.kt` | `getBitrateForResolution()` | 490 | ✅ **Fixed: 300k → 500k** |
| `LocalVideoProcessingService.kt` | `calculateNormalizationParams()` | 272 | ✅ **Fixed: Added minimum** |

## Benefits

1. **Consistent minimum quality** - All videos guaranteed ≥500k bitrate
2. **Better quality for low-resolution videos** - Especially 360p, 240p, 180p
3. **Predictable behavior** - Same minimum across all conversion paths
4. **Matches iOS implementation** - iOS also uses 500k minimum

## Testing Recommendations

Test with various low-resolution videos to verify quality improvements:
- 240p (426×240) - should get 500k bitrate
- 360p (640×360) - should get 500k bitrate
- 480p (854×480) - should get 500k bitrate (close to calculated 444k)

Verify:
1. ✅ All videos get at least 500k bitrate
2. ✅ Quality is acceptable even for very low resolutions
3. ✅ No regression for higher resolution videos
4. ✅ File sizes are reasonable (not excessive)

## Build Status

✅ Compilation successful
✅ No linter errors
✅ Ready for testing

## Related Documentation

- `PIXEL_BASED_BITRATE_AND_MASTER_PLAYLIST_FIX.md` - Pixel-based bitrate calculation
- `PROPORTIONAL_BITRATE_FIX.md` - Proportional bitrate algorithm
- `IOS_ANDROID_VIDEO_COMPARISON.md` - iOS/Android parity

## Quality Guidelines

**Reference:**
- 720p (1280×720 = 921,600 pixels) = 1000k bitrate
- Minimum quality threshold = 500k bitrate

**Rationale for 500k minimum:**
- Below 500k, video quality becomes noticeably poor
- Modern codecs (H.264) require minimum bitrate for acceptable quality
- Matches industry standards for mobile video
- Consistent with iOS implementation

