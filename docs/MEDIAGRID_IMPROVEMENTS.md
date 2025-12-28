# MediaGrid Layout Improvements

## Overview
Updated Android `MediaGrid.kt` to match iOS proportional sizing approach for better media presentation and consistent cross-platform experience.

## Key Changes

### 1. Proportional Sizing Algorithm
**Previously**: Used fixed weight ratios (1:1, 1:2, etc.)
**Now**: Calculates proportional dimensions based on actual media aspect ratios

#### Benefits:
- Maximum content visibility without excessive cropping
- Better visual balance between items with different aspect ratios
- Consistent cross-platform behavior (matches iOS)

### 2. Two-Item Layouts

#### Both Portrait Images
- **Old**: Equal widths (weight 1f each)
- **New**: Proportional widths based on aspect ratios
  ```kotlin
  val totalIdealWidth = ar0 + ar1
  val weight0 = ar0 / totalIdealWidth
  val weight1 = ar1 / totalIdealWidth
  ```

#### Both Landscape Images
- **Old**: Equal heights (weight 1f each)
- **New**: Proportional heights based on aspect ratios
  ```kotlin
  val weight0 = 1f / ar0  // Inverse for height calculation
  val weight1 = 1f / ar1
  val normalizedWeight0 = weight0 / (weight0 + weight1)
  ```

#### Mixed Orientations
- **Old**: Fixed 4:3 aspect ratio, simple 1:2 weight split
- **New**: Dynamic grid aspect ratio + proportional widths
  ```kotlin
  val gridAspectRatio = (ar0 + ar1).coerceIn(0.8f, 1.618f)
  ```

### 3. Three-Item Layouts

All three-item layouts now maintain golden ratio (0.618) for hero image, but use **proportional sizing** for the two smaller images:

#### All Portrait
- Hero: 61.8% width (left)
- Smaller images: Stacked vertically with proportional heights
  ```kotlin
  val weight1 = 1f / ar1
  val weight2 = 1f / ar2
  val normalizedWeight1 = weight1 / (weight1 + weight2)
  ```

#### All Landscape
- Hero: 61.8% height (top)
- Smaller images: Side-by-side with proportional widths
  ```kotlin
  val totalIdealWidth = ar1 + ar2
  val weight1 = ar1 / totalIdealWidth
  ```

#### Mixed Orientations
- Adapts layout based on first item orientation
- Uses same proportional sizing for smaller images

### 4. Default Aspect Ratios

Updated to match iOS standards:
- **Videos**: Changed from 4:3 (1.333) to **16:9 (1.778)** - standard modern video format
- **Images**: Kept 1.618 (golden ratio) - provides better visual consistency
- **Other media**: 1.618 (golden ratio)

### 5. Aspect Ratio Clamping

Implemented consistent clamping across all layouts:
- **Minimum**: 0.8 (portrait/tall)
- **Maximum**: 1.618 (landscape/golden ratio)

Prevents extreme layouts that are too narrow or too wide.

## Mathematical Approach

### Width Proportions (Horizontal Layouts)
For items sharing the same height, width is proportional to aspect ratio:
```
idealWidth = height × aspectRatio
proportion = individualIdealWidth / totalIdealWidth
```

### Height Proportions (Vertical Layouts)
For items sharing the same width, height is inversely proportional to aspect ratio:
```
idealHeight = width / aspectRatio
proportion = individualIdealHeight / totalIdealHeight
```

## Testing Recommendations

Test with various media combinations:
1. **Two portraits** with different aspect ratios (e.g., 2:3 and 4:5)
2. **Two landscapes** with different aspect ratios (e.g., 16:9 and 21:9)
3. **Mixed orientations** (portrait + landscape)
4. **Three images** - all portrait, all landscape, and mixed
5. **Videos with different aspect ratios** (16:9, 4:3, 1:1)

## Cross-Platform Consistency

The Android implementation now matches iOS behavior:
- Same proportional sizing algorithm
- Same default aspect ratios
- Same golden ratio usage for 3-item layouts
- Same aspect ratio clamping bounds (0.8 to 1.618)

## Code Quality

- ✅ No linting errors
- ✅ Maintains existing video playback functionality
- ✅ Preserves performance optimizations (cached aspect ratios)
- ✅ Backward compatible with existing media items
- ✅ Comprehensive documentation added

## Related Files
- `/app/src/main/java/us/fireshare/tweet/widget/MediaGrid.kt` - Main implementation
- iOS reference: `/Tweet-iOS/Sources/Features/MediaViews/MediaGridView.swift`

---
*Last updated: December 28, 2025*

