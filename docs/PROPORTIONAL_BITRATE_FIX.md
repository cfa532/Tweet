# Proportional Bitrate Calculation Fix

**Date:** December 21, 2025

## Overview

Removed hardcoded bitrates for videos with resolution < 720p and replaced them with proportional calculations based on 720p = 1000k.

## Problem

Previously, bitrates for videos < 720p were hardcoded:
- 480p: 600k (hardcoded)
- 360p: 400k or 500k (hardcoded, inconsistent)
- Below 360p: 300k (hardcoded)

This didn't accurately reflect the proportional relationship to 720p @ 1000k.

## Solution

Implemented proportional bitrate calculation using the formula:
```
bitrate = (1000 * resolution / 720)
```

With a minimum floor of 300k for very low resolutions.

## Changes Made

### 1. VideoNormalizer.getBitrateForResolution()

**File:** `app/src/fullPlay/java/us/fireshare/tweet/video/VideoNormalizer.kt`

**Before:**
```kotlin
private fun getBitrateForResolution(width: Int, height: Int): String {
    val maxDimension = maxOf(width, height)
    
    return when {
        maxDimension >= 720 -> "1000k"  // 720p and above
        maxDimension >= 480 -> "600k"   // 480p ❌ HARDCODED
        maxDimension >= 360 -> "400k"   // 360p ❌ HARDCODED
        else -> "300k"                   // Lower resolutions ❌ HARDCODED
    }
}
```

**After:**
```kotlin
private fun getBitrateForResolution(width: Int, height: Int): String {
    val maxDimension = maxOf(width, height)
    
    // Base: 720p = 1000k
    // Formula: bitrate = (1000 * resolution / 720)
    val proportionalBitrate = if (maxDimension >= 720) {
        1000  // 720p and above
    } else {
        // Calculate proportional bitrate for resolutions < 720p
        (1000 * maxDimension / 720).coerceAtLeast(300)  // Minimum 300k
    }
    
    return "${proportionalBitrate}k"
}
```

### 2. LocalHLSConverter.convertToHLS()

**File:** `app/src/fullPlay/java/us/fireshare/tweet/video/LocalHLSConverter.kt`

**Before:**
```kotlin
// Fixed bitrates (always calculated, never detected)
val resolution720pBitrate = "1000k"
val (lowerResolution, lowerResolutionBitrate) = if (useRoute2) {
    Pair(360, "500k")  // 360p: 500k ❌ HARDCODED
} else {
    Pair(480, "600k")  // 480p: 600k ❌ HARDCODED
}
```

**After:**
```kotlin
// Calculate bitrates proportionally based on 720p = 1000k
val resolution720pBitrate = "1000k"
val lowerResolution = if (useRoute2) 360 else 480
// Formula: bitrate = (1000 * resolution / 720)
val lowerResolutionBitrate = "${(1000 * lowerResolution / 720)}k"
```

## Bitrate Comparison

### Old (Hardcoded) vs New (Proportional)

| Resolution | Old Bitrate | New Bitrate (Calculated) | Change |
|------------|-------------|--------------------------|--------|
| 720p | 1000k | 1000k | No change |
| 480p | 600k | 666k | +66k (+11%) |
| 360p | 400k/500k | 500k | Standardized |
| 240p | 300k | 333k | +33k (+11%) |
| < 216p | 300k | 300k (minimum) | Floor applied |

## Benefits

✅ **Mathematically consistent** - All bitrates proportional to 720p = 1000k
✅ **More accurate** - Better quality for sub-720p videos
✅ **Maintainable** - Single formula instead of multiple hardcoded values
✅ **Future-proof** - Works for any resolution without code changes
✅ **No quality loss** - Higher bitrates preserve more detail

## Examples

### 480p Video Normalization
- **Old:** 600k (arbitrary)
- **New:** 666k (calculated: 1000 × 480 / 720)
- **Improvement:** +11% bitrate for better quality

### 360p HLS Conversion (Route 2)
- **Old:** 500k (hardcoded)
- **New:** 500k (calculated: 1000 × 360 / 720)
- **Result:** Same value, but now mathematically derived

### 540p Video (custom resolution)
- **Old:** Would use 600k (closest hardcoded value)
- **New:** 750k (calculated: 1000 × 540 / 720)
- **Improvement:** Accurate bitrate for the actual resolution

## Testing

Compilation verified successfully:
```bash
./gradlew compileFullReleaseKotlin
```

No errors or warnings introduced.

## Related Files

- `app/src/fullPlay/java/us/fireshare/tweet/video/VideoNormalizer.kt` - Normalization bitrate calculation
- `app/src/fullPlay/java/us/fireshare/tweet/video/LocalHLSConverter.kt` - HLS conversion bitrate calculation
- `app/src/fullPlay/java/us/fireshare/tweet/video/LocalVideoProcessingService.kt` - Already using proportional calculation (no changes needed)

## Notes

The `LocalVideoProcessingService.calculateNormalizationParams()` was already using proportional calculation correctly and required no changes.

---

**Status:** ✅ Complete
**Build Status:** ✅ Compiles successfully
**Impact:** All videos < 720p now get mathematically accurate bitrates

