# MediaGrid Developer Guide

## Quick Reference for Understanding and Maintaining MediaGrid Layout

### Core Concept: Proportional Sizing

The MediaGrid uses **proportional sizing** instead of fixed weights. This means:
- Layout dimensions are calculated based on each media item's actual aspect ratio
- Items with wider aspect ratios get more width (in horizontal layouts)
- Items with taller aspect ratios get more height (in vertical layouts)

---

## Key Formulas

### Horizontal Layout (Row)
When items are side-by-side, sharing the same height:

```kotlin
// Calculate width proportion based on aspect ratio
val totalIdealWidth = ar0 + ar1 + ... + arN
val weight0 = ar0 / totalIdealWidth
val weight1 = ar1 / totalIdealWidth
// etc.
```

**Why?** If height is constant, width = height × aspectRatio. So items with larger AR need proportionally more width.

### Vertical Layout (Column)
When items are stacked, sharing the same width:

```kotlin
// Calculate height proportion (inverse of aspect ratio)
val weight0 = 1f / ar0
val weight1 = 1f / ar1
val totalWeight = weight0 + weight1
val normalizedWeight0 = weight0 / totalWeight
val normalizedWeight1 = weight1 / totalWeight
```

**Why?** If width is constant, height = width / aspectRatio. So items with smaller AR need proportionally more height.

---

## Layout Decision Tree

```
Number of items?
│
├─ 1 item
│  └─ Use individual AR (min 0.8)
│
├─ 2 items
│  ├─ Both portrait? → Horizontal, proportional widths
│  ├─ Both landscape? → Vertical, proportional heights
│  └─ Mixed? → Horizontal, proportional widths, dynamic grid AR
│
├─ 3 items
│  ├─ All portrait? → Hero left (61.8%), stack right with proportional heights
│  ├─ All landscape? → Hero top (61.8%), side-by-side bottom with proportional widths
│  └─ Mixed? → Adapt based on first item orientation, use proportional sizing
│
└─ 4+ items
   └─ 2x2 grid, AR based on all items' orientation (all landscape: 1.618, all portrait: 0.8, mixed: 1.0)
```

---

## Aspect Ratio Defaults

When media doesn't have an aspect ratio:

```kotlin
MediaType.Video, MediaType.HLS_VIDEO → 16f / 9f  // 1.778 (modern standard)
MediaType.Image → 1.618f                           // Golden ratio
Other → 1.618f                                     // Golden ratio
```

**Important**: Always prefer server-provided aspect ratios over defaults.

---

## Clamping Rules

Grid aspect ratios are clamped to prevent extreme layouts:

```kotlin
val clampedAR = aspectRatio.coerceIn(0.8f, 1.618f)
```

- **Minimum (0.8)**: Prevents overly tall/narrow grids
- **Maximum (1.618)**: Prevents overly wide grids (golden ratio is natural upper bound)

---

## Golden Ratio (0.618 / 61.8%)

Used in 3-item layouts for the "hero" image:

```kotlin
val goldenRatio = 0.618f
val remainder = 1f - goldenRatio  // 0.382 or 38.2%
```

**Why?** The golden ratio creates visually pleasing proportions where the hero image is dominant but not overwhelming.

---

## Common Patterns in Code

### Pattern 1: Proportional Width Calculation
```kotlin
// For items in a Row (horizontal layout)
val totalIdealWidth = ar0 + ar1 + ar2 + ...
val weight0 = ar0 / totalIdealWidth
val weight1 = ar1 / totalIdealWidth
// Use weights in Row { ... }
```

### Pattern 2: Proportional Height Calculation
```kotlin
// For items in a Column (vertical layout)
val weight0 = 1f / ar0
val weight1 = 1f / ar1
val totalWeight = weight0 + weight1
val normalizedWeight0 = weight0 / totalWeight
val normalizedWeight1 = weight1 / totalWeight
// Use normalizedWeights in Column { ... }
```

### Pattern 3: Dynamic Grid Aspect Ratio
```kotlin
// Calculate grid AR based on content
val dynamicAR = (ar0 + ar1 + ...).coerceIn(0.8f, 1.618f)
```

---

## Testing Checklist

When modifying MediaGrid, test these scenarios:

### Two Items
- [ ] Two portraits with different ARs (e.g., 2:3 and 4:5)
- [ ] Two landscapes with different ARs (e.g., 16:9 and 21:9)
- [ ] Portrait + landscape (both combinations)
- [ ] Extreme AR differences (e.g., 0.5 and 2.0)

### Three Items
- [ ] All portraits (different ARs)
- [ ] All landscapes (different ARs)
- [ ] Portrait first + two landscapes
- [ ] Landscape first + two portraits
- [ ] Mixed combinations

### Four+ Items
- [ ] All same orientation
- [ ] Mixed orientations
- [ ] 5+ items (should show "+N more" overlay)

### Edge Cases
- [ ] Items without aspect ratio (should use defaults)
- [ ] Very extreme aspect ratios (test clamping)
- [ ] Videos vs images vs mixed media
- [ ] Single image with extreme AR

---

## Performance Considerations

### Cached Aspect Ratios
```kotlin
val cachedAspectRatios by remember(limitedMediaList) {
    derivedStateOf {
        limitedMediaList.map { item -> aspectRatioOf(item) }
    }
}
```

**Why?** Aspect ratio calculation happens once per media list change, not on every recomposition.

### Stable Keys for LazyGrid
```kotlin
key = { index, item -> "${item.mid}_$index" }
```

**Why?** Helps Compose track items efficiently during scrolling and updates.

---

## Debugging Tips

### 1. Check Aspect Ratios
```kotlin
Timber.d("Item ARs: ${cachedAspectRatios.joinToString()}")
```

### 2. Verify Weight Calculations
```kotlin
Timber.d("Weights: w0=$weight0, w1=$weight1, sum=${weight0 + weight1}")
```

### 3. Monitor Grid Aspect Ratio
```kotlin
val gridAR = calculateGridAspectRatio()
Timber.d("Grid AR: $gridAR (clamped: ${gridAR.coerceIn(0.8f, 1.618f)})")
```

### 4. Visual Debugging
Add borders to see actual dimensions:
```kotlin
.border(1.dp, Color.Red)  // Temporary debugging border
```

---

## Common Issues and Solutions

### Issue: Items look stretched or squished
**Solution**: Check if aspect ratios are being calculated correctly. Verify that `aspectRatioOf()` is returning valid values.

### Issue: Uneven spacing between items
**Solution**: Ensure `Arrangement.spacedBy(1.dp)` is applied consistently. Check that `.clipToBounds()` is used on all items.

### Issue: Layout doesn't match iOS
**Solution**: Compare the proportional sizing calculations. Ensure:
- Same aspect ratio defaults (16:9 for videos)
- Same golden ratio usage (0.618)
- Same clamping bounds (0.8 to 1.618)

### Issue: Performance problems with many items
**Solution**: Verify that `cachedAspectRatios` is being used instead of recalculating. Check that LazyGrid has stable keys.

---

## Code Maintenance Guidelines

### When Adding New Layout Cases
1. Calculate proportional dimensions based on aspect ratios
2. Apply clamping where appropriate (0.8 to 1.618)
3. Use consistent spacing (1.dp)
4. Test with various aspect ratio combinations
5. Document the layout logic

### When Modifying Aspect Ratio Logic
1. Update both Android and iOS implementations
2. Test with edge cases (no AR, extreme AR)
3. Verify defaults match current standards
4. Check performance impact

### When Debugging Layout Issues
1. Add temporary logging for dimensions
2. Use visual borders to see actual bounds
3. Compare with iOS implementation
4. Test on different screen sizes

---

## Related Documentation

- [MEDIAGRID_IMPROVEMENTS.md](MEDIAGRID_IMPROVEMENTS.md) - Overview of recent changes
- [MEDIAGRID_LAYOUT_COMPARISON.md](MEDIAGRID_LAYOUT_COMPARISON.md) - Visual before/after comparison
- iOS Reference: `/Tweet-iOS/Sources/Features/MediaViews/MediaGridView.swift`

---

## Contact

For questions about MediaGrid implementation, refer to:
- Code comments in `MediaGrid.kt`
- iOS implementation in `MediaGridView.swift`
- This developer guide

---

*Last updated: December 28, 2025*

