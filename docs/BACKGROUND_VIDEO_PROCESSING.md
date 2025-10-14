# Background Video Processing Architecture
**Last Updated:** October 14, 2025

## Overview

All video upload and processing operations in the Tweet app are handled by **background workers** using Android WorkManager. This ensures that video processing continues even when the app is in the background or the device screen is off.

## Background Worker Architecture

### 1. UploadTweetWorker

**File:** `app/src/main/java/us/fireshare/tweet/service/TweetWorker.kt`

The main background worker that handles all media uploads, including video processing.

**Key Features:**
- Extends `CoroutineWorker` for coroutine-based background processing
- Runs entirely in background with `Dispatchers.IO` context
- Protected by wake lock (up to 10 minutes) to prevent device sleep interruption
- Implements exponential backoff retry logic (up to 3 attempts)
- Processes attachments in pairs (2 at a time) for optimal resource usage

**Worker Configuration:**
```kotlin
val uploadRequest = OneTimeWorkRequest.Builder(UploadTweetWorker::class.java)
    .setInputData(data)
    .setBackoffCriteria(
        BackoffPolicy.EXPONENTIAL,
        10_000L, // 10 seconds
        java.util.concurrent.TimeUnit.MILLISECONDS
    )
    .build()
```

### 2. Parallel Upload Processing

**Implementation:**
```kotlin
val attachments = mutableListOf<MimeiFileType>()
val uriPairs = attachmentUris.chunked(2)
for (pair in uriPairs) {
    val deferreds = mutableListOf<Deferred<MimeiFileType?>>()
    for (uriString in pair) {
        val deferred = CoroutineScope(Dispatchers.IO).async {
            val result = uploadToIPFS(applicationContext, uriString.toUri())
            result
        }
        deferreds.add(deferred)
    }
    val results = deferreds.awaitAll()
    // Process results...
}
```

**Characteristics:**
- Processes attachments in **pairs** (2 concurrent uploads)
- Each pair is processed in parallel using coroutines
- Waits for pair completion before starting next pair
- Balances performance with resource constraints

## Video Processing Flow (All in Background)

### Complete Flow Diagram

```
User Selects Video
    ↓
WorkManager Enqueues UploadTweetWorker
    ↓
[BACKGROUND WORKER STARTS]
    ↓
Wake Lock Acquired (10 min)
    ↓
uploadToIPFS() Called
    ↓
┌─────────────────────────────────────┐
│ MediaUploadService.uploadToIPFS()   │
│ (Runs in Dispatchers.IO)            │
└─────────────────────────────────────┘
    ↓
Detect Video MediaType
    ↓
processVideoLocally() Called
    ↓
┌─────────────────────────────────────────┐
│ Check Conversion Server Availability    │
│ - cloudDrivePort check                  │
│ - /health endpoint ping                 │
└─────────────────────────────────────────┘
    ↓
┌─────────────────┬─────────────────────┐
│ Server Available│  Server Unavailable │
└─────────────────┴─────────────────────┘
    ↓                    ↓
[PATH A: HLS]        [PATH B: FALLBACK]
```

### Path A: HLS Conversion (Background)

**All operations run in background worker context:**

1. **LocalVideoProcessingService.processVideo()** - `withContext(Dispatchers.IO)`
   - Creates temporary directory
   - Calls HLS converter

2. **LocalHLSConverter.convertToHLS()** - `withContext(Dispatchers.IO)`
   - FFmpeg processing (CPU intensive)
   - Generates 720p and 480p HLS versions
   - Creates master playlist
   - Smart codec selection (COPY or libx264)

3. **ZipCompressor.compressHLSDirectory()**
   - Compresses HLS files into zip archive
   - Runs in worker context

4. **ZipUploadService.uploadZipFile()**
   - Uploads zip to `/process-zip` endpoint
   - Runs in worker context

5. **Server Polling** (Background)
   - Polls conversion status every 3 seconds
   - Max polling time: 2 hours
   - Exponential backoff on failures

6. **Cleanup** (Background)
   - Deletes temporary files
   - Releases resources

### Path B: Fallback Normalization (Background)

**All operations run in background worker context:**

1. **Resolution Check**
   - `VideoManager.getVideoResolution()` - background execution
   - Determines if video needs resampling (>720p)

2. **VideoNormalizer.normalizeVideo()** - `withContext(Dispatchers.IO)`
   - FFmpeg normalization (CPU intensive)
   - Optional 720p downsampling
   - Converts to standard MP4

3. **IPFS Upload** - Background
   - `uploadToIPFSOriginal()` in worker context
   - Chunks uploaded in IO dispatcher

4. **Cleanup** (Background)
   - Deletes temporary normalized file

### Wake Lock Protection

**Implementation:**
```kotlin
val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
val wakeLock = powerManager.newWakeLock(
    PowerManager.PARTIAL_WAKE_LOCK,
    "Tweet:UploadWakeLockTag"
)
wakeLock.acquire(10 * 60 * 1000L /*10 minutes*/)
try {
    // All video processing happens here
} finally {
    wakeLock.release()
}
```

**Purpose:**
- Prevents device sleep during video processing
- Ensures HLS conversion and upload complete uninterrupted
- Automatically releases after 10 minutes or completion

## Multiple Video Handling

### Concurrent Processing Strategy

**Current Implementation:**
- Videos processed in **pairs** (2 at a time)
- Each pair runs in parallel async coroutines
- Sequential processing between pairs

**Example with 4 Videos:**
```
Pair 1: [Video 1] + [Video 2] → Process in parallel
   ↓ (await completion)
Pair 2: [Video 3] + [Video 4] → Process in parallel
   ↓
All uploads complete
```

### Resource Management

**Why Pairs?**
1. **CPU Constraint:** FFmpeg processing is CPU intensive
2. **Memory Management:** Each video requires temporary storage
3. **Battery Consideration:** Balance performance with power consumption
4. **Reliability:** Reduce likelihood of out-of-memory errors

**Per-Video Resources:**
- Temporary HLS directory (can be several hundred MB)
- FFmpeg processing threads (2 threads per video)
- Network upload buffers
- Wake lock protection

## Background Processing Benefits

### 1. Non-Blocking UI
- User can navigate away from upload screen
- App can be minimized
- Other app functions remain responsive

### 2. Reliability
- Wake lock prevents interruption
- Automatic retry on failure (3 attempts)
- Exponential backoff for network issues
- Graceful error handling

### 3. Progress Persistence
- WorkManager persists work state
- Survives app process death
- Can resume after device reboot (if configured)

### 4. Resource Efficiency
- Dispatcher.IO optimized for blocking operations
- Controlled concurrency (pairs)
- Automatic cleanup of temporary files

## Error Handling in Background

### Retry Logic

**WorkManager Configuration:**
```kotlin
.setBackoffCriteria(
    BackoffPolicy.EXPONENTIAL,
    10_000L, // Start with 10 seconds
    java.util.concurrent.TimeUnit.MILLISECONDS
)
```

**Behavior:**
- Attempt 1: Immediate execution
- Attempt 2: After 10 seconds
- Attempt 3: After 20 seconds (exponential)
- After 3 failures: Final failure, notify user

### Failure Notifications

**On Final Failure (3rd attempt):**
```kotlin
if (runAttemptCount >= 3) {
    TweetNotificationCenter.postAsync(
        TweetEvent.TweetUploadFailed("Attachment upload failed")
    )
    // Clean up incomplete upload tracking
    HproseInstance.removeIncompleteUpload(applicationContext, workId)
}
```

### Incomplete Upload Tracking

**Purpose:** Track uploads that are in progress for potential resume

**Saved Information:**
- Work ID (for cleanup)
- Tweet content
- Attachment URIs
- Privacy settings
- Timestamp
- Video conversion job ID (if applicable)
- Conversion server URL (if applicable)

**Resume on App Restart:**
```kotlin
// Check for incomplete uploads on app start
HproseInstance.checkIncompleteUploads(context)

// For video conversions with job ID
if (upload.videoConversionJobId != null) {
    // Resume polling in background coroutine
    CoroutineScope(Dispatchers.IO).launch {
        val result = pollVideoConversionStatus(...)
        if (result != null) {
            // Complete the upload
            HproseInstance.uploadTweet(tweet)
            removeIncompleteUpload(context, upload.workId)
        }
    }
}
```

## Chat Video Attachments

**Worker:** `SendChatMessageWorker`

**Similar Background Architecture:**
```kotlin
val workRequest = OneTimeWorkRequestBuilder<SendChatMessageWorker>()
    .setInputData(workDataOf(
        "receiptId" to receiptId,
        "content" to content,
        "attachmentUri" to attachmentUri.toString(),
        "sessionId" to sessionId,
        "messageTimestamp" to System.currentTimeMillis()
    ))
    .setBackoffCriteria(
        BackoffPolicy.EXPONENTIAL,
        10_000L,
        java.util.concurrent.TimeUnit.MILLISECONDS
    )
    .build()

WorkManager.getInstance(context).enqueue(workRequest)
```

**Processing:**
- Same background video processing pipeline
- HLS conversion if server available
- Fallback normalization if not
- All operations in worker context

## Performance Characteristics

### HLS Conversion (Background)
- **Time:** 30 seconds - 5 minutes (depending on video length and device)
- **CPU:** High (FFmpeg encoding)
- **Memory:** Moderate (temporary files)
- **Network:** Medium (zip upload)

### Fallback Normalization (Background)
- **Time:** 20 seconds - 3 minutes (depending on video length)
- **CPU:** High (FFmpeg encoding)
- **Memory:** Low-Moderate
- **Network:** High (IPFS chunked upload)

### Multiple Videos (4 videos example)
- **Time:** Sequential pairs, ~2-10 minutes total
- **CPU:** High (sustained during processing)
- **Memory:** Moderate (2 videos worth of temp files at once)
- **Network:** Variable (parallel uploads within pairs)

## Best Practices for Users

### Optimal Upload Conditions
1. **WiFi Connection:** Reduces mobile data usage
2. **Charging:** Prevents battery drain during long conversions
3. **Background Restriction:** Ensure app has background processing permission
4. **Storage Space:** Ensure adequate free space for temporary files

### Monitoring Progress
- WorkManager status can be observed
- Notification system shows upload progress
- Incomplete uploads tracked for resume

## Technical Implementation Details

### Dispatcher Configuration

**All video operations use:**
```kotlin
withContext(Dispatchers.IO) {
    // Video processing code
}
```

**Or:**
```kotlin
CoroutineScope(Dispatchers.IO).async {
    // Parallel upload code
}
```

### FFmpeg Execution

**Always runs in background context:**
```kotlin
// In LocalHLSConverter, called from worker
suspend fun convertToHLS(...): HLSConversionResult = withContext(Dispatchers.IO) {
    // FFmpeg commands execute here
    FFmpegKit.execute(command)
}
```

### Network Operations

**All HTTP calls in IO context:**
```kotlin
// Health check
withContext(Dispatchers.IO) {
    httpClient.get(healthCheckUrl)
}

// Status polling
httpClient.get(statusURL) // Already in IO context from worker
```

## Monitoring and Debugging

### Logging

**Key Log Tags:**
- `UploadTweetWorker` - Main worker operations
- `MediaUploadService` - Video processing decisions
- `LocalVideoProcessingService` - HLS conversion orchestration
- `LocalHLSConverter` - FFmpeg execution
- `ZipUploadService` - Server upload and polling
- `VideoNormalizer` - Fallback normalization

**Example Log Flow:**
```
UploadTweetWorker: Starting upload for URI: content://...
UploadTweetWorker: Calling uploadToIPFS for URI: content://...
MediaUploadService: Processing video via HLS conversion server
LocalVideoProcessingService: Starting multi-resolution HLS conversion
LocalHLSConverter: Video resolution: 1920x1080, using libx264 codec
LocalHLSConverter: 720p conversion completed successfully
LocalHLSConverter: 480p conversion completed successfully
ZipCompressor: Compressed HLS directory successfully
ZipUploadService: Uploading zip file to /process-zip
ZipUploadService: Polling job status: processing (45%)
ZipUploadService: Job completed: QmXxxxxx
UploadTweetWorker: Successfully uploaded attachment: QmXxxxxx
```

### WorkManager Inspection

**Check Work Status:**
```kotlin
WorkManager.getInstance(context)
    .getWorkInfoByIdLiveData(workId)
    .observe(lifecycleOwner) { workInfo ->
        when (workInfo.state) {
            WorkInfo.State.RUNNING -> // Processing
            WorkInfo.State.SUCCEEDED -> // Complete
            WorkInfo.State.FAILED -> // Failed
        }
    }
```

## Summary

✅ **All video processing is handled by background workers:**
- ✅ HLS conversion (FFmpeg processing)
- ✅ Zip compression
- ✅ Server uploads  
- ✅ Fallback normalization (FFmpeg resampling)
- ✅ IPFS uploads
- ✅ Server polling for completion

✅ **Non-blocking to UI:**
- ✅ User can leave app
- ✅ Wake lock prevents interruption
- ✅ Automatic retry on failure
- ✅ Persistent work across app restarts

✅ **Optimized for reliability and performance:**
- ✅ Pair-wise processing (2 videos at a time)
- ✅ Exponential backoff retry
- ✅ Automatic cleanup
- ✅ Comprehensive error handling

The entire video upload pipeline operates independently of UI, ensuring reliable background processing for all video conversion scenarios.

