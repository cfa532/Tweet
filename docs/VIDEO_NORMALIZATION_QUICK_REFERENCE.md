# Video Normalization and HLS Algorithm - Quick Reference

## Algorithm at a Glance

### Step 1: Resolution Detection
```
IF width ≥ height THEN
    resolution = height (landscape)
ELSE
    resolution = width (portrait)
END IF
```

### Step 2: Normalization Parameters
```
IF resolution > 720 THEN
    target_resolution = 720
    target_bitrate = 1500k
ELSE
    target_resolution = resolution
    target_bitrate = 1000k × (resolution / 720)
END IF
```

### Step 3: Size-Based Routing
```
normalize_video(input, target_resolution, target_bitrate) → normalized_video

IF normalized_size ≤ 32MB THEN
    route = PROGRESSIVE
ELSE
    route = HLS
END IF
```

### Step 4: HLS Variant Selection
```
IF route = HLS THEN
    IF normalized_resolution > 480 THEN
        variants = [720p, 480p]  // Dual variant
    ELSE
        variants = [480p]  // Single variant
    END IF
END IF
```

### Step 5: HLS Segment Creation
```
FOR EACH variant IN variants DO
    IF variant = 720p AND normalized_resolution > 480 AND normalized_resolution ≤ 720 THEN
        encoder = COPY  // Preserve native quality
    ELSE IF variant = 480p AND normalized_resolution ≤ 480 THEN
        encoder = COPY  // Preserve native quality
    ELSE
        encoder = LIBX264  // Re-encode
    END IF
    
    create_hls_segments(normalized_video, variant, encoder)
END FOR
```

## Processing Examples

| Input | Detection | Normalization | Size | Route | Variants | 720p Encoder | 480p Encoder |
|-------|-----------|---------------|------|-------|----------|--------------|--------------|
| 1080p, 100MB | 1080p | 720p@1500k | >32MB | HLS | Dual | COPY | Re-encode |
| 720p, 20MB | 720p | 720p@1000k | ≤32MB | Progressive | - | - | - |
| 576p, 100MB | 576p | 576p@800k | >32MB | HLS | Dual | COPY | Re-encode |
| 480p, 100MB | 480p | 480p@667k | >32MB | HLS | Single | - | COPY |
| 360p, 172MB | 360p | 360p@500k | >32MB | HLS | Single | - | COPY |

## Bitrate Calculation Table

| Resolution | Normalization Bitrate | Calculation |
|------------|----------------------|-------------|
| 1080p+ | 1500k | Fixed (high bitrate) |
| 720p | 1000k | 1000k × (720/720) |
| 576p | 800k | 1000k × (576/720) |
| 480p | 667k | 1000k × (480/720) |
| 360p | 500k | 1000k × (360/720) |

## Key Constants

```kotlin
NORMALIZATION_THRESHOLD = 720          // Resolution threshold
NORMALIZATION_HIGH_BITRATE = "1500k"   // Bitrate for >720p
NORMALIZATION_BASE_BITRATE = 1000      // Base for proportional calc
HLS_SIZE_THRESHOLD = 32 * 1024 * 1024  // 32MB in bytes
```

## Code Locations

| Component | File | Line Range |
|-----------|------|------------|
| Resolution Detection | `LocalVideoProcessingService.kt` | 209-220 |
| Normalization Params | `LocalVideoProcessingService.kt` | 222-236 |
| Normalize Video | `LocalVideoProcessingService.kt` | 238-320 |
| Size Routing | `LocalVideoProcessingService.kt` | 117-126 |
| Variant Selection | `LocalVideoProcessingService.kt` | 128-135 |
| HLS Conversion | `LocalHLSConverter.kt` | 78-333 |
| 720p COPY Logic | `LocalHLSConverter.kt` | 186-199 |
| 480p COPY Logic | `LocalHLSConverter.kt` | 240-253 |

## Testing Checklist

- [ ] 1080p video → Normalizes to 720p@1500k
- [ ] 720p video → Normalizes to 720p@1000k
- [ ] 576p video → Normalizes to 576p@800k, COPY for 720p variant
- [ ] 480p video → Normalizes to 480p@667k, single variant with COPY
- [ ] 360p video → Normalizes to 360p@500k, single variant with COPY
- [ ] Portrait video → Uses width as resolution
- [ ] Landscape video → Uses height as resolution
- [ ] <32MB normalized → Routes to progressive (TODO)
- [ ] >32MB normalized → Routes to HLS
- [ ] COPY encoder fallback → Falls back to libx264 if COPY fails

## FFmpeg Commands

### Normalization Command Template
```bash
ffmpeg -i input.mp4 \
  -c:v libx264 \
  -c:a aac \
  -vf "scale=WIDTH:HEIGHT:force_original_aspect_ratio=decrease:force_divisible_by=2" \
  -b:v BITRATE \
  -b:a 128k \
  -preset veryfast \
  -profile:v baseline \
  -pix_fmt yuv420p \
  -movflags +faststart \
  -y output.mp4
```

### HLS Conversion with COPY
```bash
ffmpeg -i input.mp4 \
  -c copy \
  -fflags +genpts+igndts+flush_packets \
  -avoid_negative_ts make_zero \
  -max_interleave_delta 0 \
  -max_muxing_queue_size 1024 \
  -hls_time 10 -hls_list_size 0 \
  -hls_flags independent_segments \
  -hls_segment_type mpegts \
  -hls_segment_filename "segment%03d.ts" \
  -f hls playlist.m3u8
```

### HLS Conversion with Re-encoding
```bash
ffmpeg -i input.mp4 \
  -c:v libx264 \
  -c:a aac \
  -vf "scale=WIDTH:HEIGHT:force_original_aspect_ratio=decrease:force_divisible_by=2" \
  -b:v BITRATE \
  -b:a 128k \
  -preset veryfast \
  -tune zerolatency \
  -profile:v baseline \
  -pix_fmt yuv420p \
  -g 30 \
  -level 3.1 \
  -hls_time 10 -hls_list_size 0 \
  -hls_flags independent_segments \
  -hls_segment_type mpegts \
  -hls_segment_filename "segment%03d.ts" \
  -f hls playlist.m3u8
```

## Implementation Status

✅ **COMPLETE** - All 5 steps implemented and integrated

**Date:** December 21, 2025

## Related Documentation

- [VIDEO_NORMALIZATION_HLS_UPDATE.md](VIDEO_NORMALIZATION_HLS_UPDATE.md) - Full implementation details

