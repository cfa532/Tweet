# Memory Optimization Guide for Video Upload

## Overview

This document describes the memory optimizations implemented for handling large video files (200MB+ ZIP files) on Android devices with limited heap memory (typically 384MB-512MB).

## Problem

The previous OOM (Out of Memory) error occurred during ZIP upload:

```
io.ktor.utils.io.ClosedByteChannelException: Failed to allocate a 8208 byte allocation 
with 2353360 free bytes and 2298KB until OOM, target footprint 402653184
  at io.ktor.http.cio.ChunkedTransferEncodingKt.encodeChunked
```

**Root causes:**
1. High `MIN_BITRATE` (500k) was inflating low-bitrate videos (70MB → 214MB)
2. Ktor's chunked transfer encoding was creating large internal buffers
3. No explicit control over upload buffer sizes
4. No memory monitoring during critical operations

## Solutions Implemented

### 1. Reduced MIN_BITRATE (500k → 300k)

**Files affected:**
- `app/src/fullPlay/java/us/fireshare/tweet/video/LocalVideoProcessingService.kt`
- `app/src/fullPlay/java/us/fireshare/tweet/video/LocalHLSConverter.kt`
- `Tweet-iOS/Sources/Core/HproseInstance.swift`
- `Tweet-iOS/Sources/Core/VideoConversionService.swift`

**Impact:**
- 70MB original → 140MB normalized (vs 214MB with 500k)
- 292MB ZIP file (vs 428MB with 500k)
- **35% reduction in file size**

### 2. Optimized ZIP Compression

**File:** `app/src/main/java/us/fireshare/tweet/video/ZipCompressor.kt`

**Optimizations:**
```kotlin
private const val BUFFER_SIZE = 4096 // 4KB buffer for memory efficiency
private const val LARGE_FILE_THRESHOLD = 10L * 1024 * 1024 // 10MB threshold
private const val MAX_MEMORY_BUFFER = 2L * 1024 * 1024 // 2MB max buffer size
```

**Features:**
- ✅ Memory-mapped I/O for files >10MB
- ✅ Buffered streaming with 4KB chunks for small files
- ✅ 2MB max chunks for large file processing
- ✅ Compression level 1 (fast, low memory)
- ✅ Never loads entire files into heap memory

### 3. Memory-Controlled Upload

**File:** `app/src/main/java/us/fireshare/tweet/video/ZipUploadService.kt`

**New constants:**
```kotlin
private const val UPLOAD_BUFFER_SIZE = 8192 // 8KB chunks
private const val MAX_UPLOAD_CHUNK = 64 * 1024 // 64KB max chunk
```

**Key improvements:**
- ✅ Explicit 8KB buffer size for upload streaming
- ✅ Custom `appendInput()` with `buildPacket()` for fine-grained control
- ✅ Progress logging every 10% to monitor upload
- ✅ Memory usage monitoring before/after upload
- ✅ GC hint after upload completion

**Memory monitoring:**
```kotlin
private fun logMemoryUsage(context: String) {
    val runtime = Runtime.getRuntime()
    val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val maxMemory = runtime.maxMemory() / (1024 * 1024)
    val percentUsed = (usedMemory * 100) / maxMemory
    
    if (percentUsed > 85) {
        Timber.tag(TAG).w("[$context] HIGH MEMORY USAGE: $percentUsed% - risk of OOM")
    }
}
```

## Memory Budget Analysis

### Typical Android Device Heap Limits

| Device Type | Heap Limit | Safe Upload Size | Notes |
|-------------|------------|------------------|-------|
| Low-end (2GB RAM) | 256MB | 150MB | Risk with longer videos |
| Mid-range (4GB RAM) | 384MB | 250MB | Current test case (292MB) works |
| High-end (8GB+ RAM) | 512MB+ | 400MB+ | Safe for most use cases |

### Memory Usage Breakdown (292MB ZIP upload)

| Component | Memory Usage | Notes |
|-----------|--------------|-------|
| App baseline | ~80-100MB | UI, services, caches |
| ZIP compression | ~10-20MB | Buffered, uses disk storage |
| **Upload buffer** | **8KB** | **Controlled chunking** |
| Ktor internal buffers | ~5-15MB | Reduced with explicit buffer control |
| **Total peak** | **~100-150MB** | **Safe margin on 384MB heap** |

## Testing Recommendations

### 1. Memory Stress Test

Test with progressively larger videos:

```bash
# Test cases (resolution @ bitrate → normalized size → ZIP size)
1. 640x360 @ 100k, 5min  → ~70MB   → ~100MB  → ~140MB ZIP  ✅ Safe
2. 1280x720 @ 500k, 10min → ~200MB → ~250MB  → ~350MB ZIP  ⚠️ Test
3. 1280x720 @ 1000k, 15min → ~500MB → ~600MB → ~800MB ZIP  ❌ Will OOM
```

### 2. Monitor Memory Logs

Look for these log entries:

```
ZipUploadService: [Before ZIP upload] Memory: 120MB used / 384MB max (31%), 264MB free
ZipUploadService: Upload progress: 50% (146MB / 292MB)
ZipUploadService: [After ZIP upload] Memory: 145MB used / 384MB max (37%), 239MB free
ZipUploadService: [After GC suggestion] Memory: 98MB used / 384MB max (25%), 286MB free
```

**Warning signs:**
```
ZipUploadService: [Before ZIP upload] HIGH MEMORY USAGE: 87% - risk of OOM
```

### 3. Edge Cases to Test

- ✅ Multiple uploads in quick succession
- ✅ Background upload while app is in background
- ✅ Upload during low memory conditions
- ✅ Very long videos (45+ minutes)
- ✅ 4K source material downscaled to 720p

## Remaining Risks

### 1. Very Large Videos (>1 hour, 4K)

**Scenario:** 4K@30fps, 60min → normalized to 720p@300k

**Estimated sizes:**
- Original: 2-3GB
- Normalized: 400-500MB  
- ZIP: 600-700MB

**Risk level:** ❌ **High OOM risk**

**Mitigation options:**
1. **Client-side limit** - Reject videos >45 minutes
2. **Progressive upload** - Split HLS segments into multiple smaller ZIPs
3. **Server-side processing** - Upload original, normalize on server

### 2. Low Memory Devices (<384MB heap)

**Risk level:** ⚠️ **Medium risk** for videos >30min

**Mitigation:**
- Detect low memory devices (`Runtime.getRuntime().maxMemory()`)
- Show warning for large video uploads
- Suggest reducing video length or resolution

### 3. Simultaneous Background Tasks

**Risk:** Memory competition from other services

**Mitigation:**
- Already using WorkManager with constraints
- Consider adding explicit memory check before starting upload
- Cancel upload if memory drops below threshold

## Implementation Checklist

- [x] Reduce MIN_BITRATE to 300k (Android & iOS)
- [x] Optimize ZIP compression with memory-mapped I/O
- [x] Implement controlled upload buffering (8KB chunks)
- [x] Add memory usage logging
- [x] Add GC hint after upload
- [x] Document memory budget and limits
- [ ] Add client-side video length/size limits (recommended)
- [ ] Implement progressive ZIP upload for very large files (future enhancement)
- [ ] Add pre-upload memory check (future enhancement)

## Monitoring in Production

Add analytics for:

1. **Upload memory metrics**
   - Peak memory usage during upload
   - Memory usage percentage
   - OOM error rate by device type

2. **Video size distribution**
   - Normalized file sizes
   - ZIP file sizes
   - Video duration distribution

3. **Failure analysis**
   - OOM errors by device RAM
   - OOM errors by video size
   - Upload retry success rate

## Code References

- ZIP compression: `app/src/main/java/us/fireshare/tweet/video/ZipCompressor.kt`
- ZIP upload: `app/src/main/java/us/fireshare/tweet/video/ZipUploadService.kt`
- Normalization: `app/src/fullPlay/java/us/fireshare/tweet/video/LocalVideoProcessingService.kt`
- HLS conversion: `app/src/fullPlay/java/us/fireshare/tweet/video/LocalHLSConverter.kt`

## Further Reading

- [Android Memory Management](https://developer.android.com/topic/performance/memory)
- [Java Memory-Mapped Files](https://docs.oracle.com/javase/8/docs/api/java/nio/MappedByteBuffer.html)
- [Ktor Streaming](https://ktor.io/docs/request.html#upload_file)

