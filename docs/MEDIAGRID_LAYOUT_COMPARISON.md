# MediaGrid Layout Comparison: Before vs After

## Overview
This document illustrates the differences between the old fixed-weight approach and the new proportional sizing approach.

---

## Two Portrait Images

### Example: Image 1 (2:3 ratio = 0.667) + Image 2 (3:4 ratio = 0.75)

#### Before (Fixed Weights)
```
┌────────────┬────────────┐
│            │            │
│  Image 1   │  Image 2   │
│  (50%)     │  (50%)     │
│            │            │
└────────────┴────────────┘
```
- Both images get equal width (50% each)
- Narrower image (2:3) gets stretched/cropped more
- Wider image (3:4) has better fit

#### After (Proportional Sizing)
```
┌──────────┬────────────┐
│          │            │
│ Image 1  │  Image 2   │
│ (47.1%)  │  (52.9%)   │
│          │            │
└──────────┴────────────┘
```
- Weights: 0.667/(0.667+0.75) = 47.1% vs 0.75/(0.667+0.75) = 52.9%
- Both images show more content with less cropping
- Better visual balance

---

## Two Landscape Images

### Example: Image 1 (16:9 = 1.778) + Image 2 (21:9 = 2.333)

#### Before (Fixed Weights)
```
┌─────────────────────┐
│      Image 1        │
│      (50%)          │
├─────────────────────┤
│      Image 2        │
│      (50%)          │
└─────────────────────┘
```
- Equal heights (50% each)
- Ultra-wide image (21:9) gets severely cropped
- Standard image (16:9) has better fit

#### After (Proportional Sizing)
```
┌─────────────────────┐
│      Image 1        │
│      (43.2%)        │
├─────────────────────┤
│      Image 2        │
│      (56.8%)        │
└─────────────────────┘
```
- Heights calculated as: (1/ar) / sum(1/ar)
- Image 1: (1/1.778) / ((1/1.778) + (1/2.333)) = 43.2%
- Image 2: (1/2.333) / ((1/1.778) + (1/2.333)) = 56.8%
- Ultra-wide image gets more vertical space
- Less cropping on both images

---

## Mixed: Portrait + Landscape

### Example: Portrait (2:3 = 0.667) + Landscape (16:9 = 1.778)

#### Before (Fixed 4:3 Grid, 1:2 Weight)
```
Grid AR: 4:3 = 1.333
┌────────┬────────────────┐
│        │                │
│ Image1 │    Image 2     │
│ (33%)  │    (67%)       │
│        │                │
└────────┴────────────────┘
```
- Fixed landscape preference (1:2 ratio)
- Grid aspect ratio always 4:3
- Portrait image too narrow, landscape too wide

#### After (Dynamic Grid AR, Proportional)
```
Grid AR: (0.667 + 1.778) = 2.445 → clamped to 1.618
┌────────────┬──────────────────┐
│            │                  │
│  Image 1   │     Image 2      │
│  (27.3%)   │     (72.7%)      │
│            │                  │
└────────────┴──────────────────┘
```
- Dynamic grid AR based on content: (0.667 + 1.778) = 2.445
- Clamped to max 1.618 (golden ratio) to prevent extreme wideness
- Width proportions: 0.667/2.445 = 27.3%, 1.778/2.445 = 72.7%
- Both images better proportioned

---

## Three Portrait Images

### Example: Hero (2:3 = 0.667), Small 1 (3:4 = 0.75), Small 2 (4:5 = 0.8)

#### Before (Fixed Weights)
```
┌─────────┬────┐
│         │ S1 │
│  Hero   ├────┤
│ (61.8%) │ S2 │
│         │    │
└─────────┴────┘
```
- Hero: 61.8% width (golden ratio) ✓
- Small images: Equal heights (50% each)
- No consideration for different aspect ratios

#### After (Proportional Heights)
```
┌─────────┬────┐
│         │ S1 │
│         │48.4%
│  Hero   ├────┤
│ (61.8%) │ S2 │
│         │51.6%
└─────────┴────┘
```
- Hero: 61.8% width (golden ratio) ✓
- Small 1: (1/0.75) / ((1/0.75) + (1/0.8)) = 48.4%
- Small 2: (1/0.8) / ((1/0.75) + (1/0.8)) = 51.6%
- Better balance between small images

---

## Three Landscape Images

### Example: Hero (16:9 = 1.778), Small 1 (16:9 = 1.778), Small 2 (21:9 = 2.333)

#### Before (Fixed Weights)
```
┌─────────────────┐
│      Hero       │
│     (61.8%)     │
├────────┬────────┤
│  S1    │   S2   │
│ (50%)  │ (50%)  │
└────────┴────────┘
```
- Hero: 61.8% height (golden ratio) ✓
- Small images: Equal widths (50% each)
- Ultra-wide image (21:9) gets cropped

#### After (Proportional Widths)
```
┌─────────────────┐
│      Hero       │
│     (61.8%)     │
├───────┬─────────┤
│  S1   │   S2    │
│(43.2%)│ (56.8%) │
└───────┴─────────┘
```
- Hero: 61.8% height (golden ratio) ✓
- Small 1: 1.778 / (1.778 + 2.333) = 43.2%
- Small 2: 2.333 / (1.778 + 2.333) = 56.8%
- Ultra-wide image gets more space, less cropping

---

## Default Aspect Ratio Changes

### Video Content

#### Before
```
Default AR: 4:3 = 1.333
┌──────────────┐
│              │
│    Video     │
│   (4:3 AR)   │
│              │
└──────────────┘
```
- Old TV format (4:3)
- Doesn't match modern video standards
- More cropping for widescreen content

#### After
```
Default AR: 16:9 = 1.778
┌────────────────────┐
│      Video         │
│    (16:9 AR)       │
└────────────────────┘
```
- Modern standard video format
- Better fit for most video content
- Matches iOS implementation

---

## Benefits Summary

### 1. Better Content Visibility
- **Before**: Fixed weights often caused excessive cropping
- **After**: Proportional sizing shows maximum content

### 2. Visual Balance
- **Before**: Unbalanced layouts with extreme aspect ratio differences
- **After**: Natural proportions that respect each item's dimensions

### 3. Cross-Platform Consistency
- **Before**: Different layouts on iOS and Android
- **After**: Identical proportional sizing on both platforms

### 4. Modern Standards
- **Before**: 4:3 default for videos (outdated)
- **After**: 16:9 default for videos (current standard)

### 5. Intelligent Adaptation
- **Before**: Fixed rules regardless of content
- **After**: Dynamic calculations based on actual aspect ratios

---

## Example Calculations Reference

### Width Proportion (Horizontal Layout)
```
Given: Two items with aspect ratios ar1 and ar2
Sharing the same height H:

idealWidth1 = H × ar1
idealWidth2 = H × ar2
totalIdealWidth = idealWidth1 + idealWidth2

proportion1 = idealWidth1 / totalIdealWidth = ar1 / (ar1 + ar2)
proportion2 = idealWidth2 / totalIdealWidth = ar2 / (ar1 + ar2)
```

### Height Proportion (Vertical Layout)
```
Given: Two items with aspect ratios ar1 and ar2
Sharing the same width W:

idealHeight1 = W / ar1
idealHeight2 = W / ar2
totalIdealHeight = idealHeight1 + idealHeight2

proportion1 = idealHeight1 / totalIdealHeight = (1/ar1) / ((1/ar1) + (1/ar2))
proportion2 = idealHeight2 / totalIdealHeight = (1/ar2) / ((1/ar1) + (1/ar2))
```

---

*This document demonstrates the mathematical and visual improvements in the updated MediaGrid implementation.*

