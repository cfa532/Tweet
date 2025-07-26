# Video Loading Algorithm

## Core Requirements

### 1. **HLS Fallback Sequence (NO RETRIES)**
The system MUST follow this exact sequence for ALL videos:

1. **Start with `master.m3u8`** (HLS master playlist)
2. **If that fails → try `playlist.m3u8`** (HLS playlist) 
3. **If that fails → try original URL** (progressive video)
4. **If all fail → stop player** (no more retries)

### 2. **URL Construction**
- **Base URL**: `if (url.endsWith("/")) url else "$url/"`
- **Master URL**: `${baseUrl}master.m3u8`
- **Playlist URL**: `${baseUrl}playlist.m3u8`
- **Original URL**: `url` (as provided)

### 3. **No Progressive Video Detection**
- **DO NOT** try to detect if a video is progressive
- **DO NOT** skip HLS attempts for any video
- **ALWAYS** try HLS first for ALL videos
- **NO** file extension checking or URL pattern detection

### 4. **No Retries**
- Each format is tried **exactly once**
- **NO** retry attempts for the same URL
- **NO** multiple ExoPlayer instances for the same video
- **NO** infinite loops or repeated attempts

### 5. **Memory Management**
- Failed players MUST be properly stopped with `exoPlayer.stop()`
- **NO** memory leaks from abandoned ExoPlayer instances
- Clean up resources after each failed attempt

### 6. **Exception Handling for Inaccessible URLs**
- **Network errors** (500, 404, connection failures) are expected and handled gracefully
- **No retries** for the same URL - if it fails, move to next fallback
- **Proper logging** of all errors for debugging
- **Graceful degradation** - if video fails, app continues to work
- **User experience** - no crashes or freezes from video loading failures

### 7. **Aspect Ratio Handling**
- **Primary source**: Each video has an `aspectRatio` file in its `MimeiFileType` attachment
- **Fallback mechanism**: `getVideoAspectRatio(context, uri)` is only used as a fallback
- **When to use fallback**: Only when the primary aspect ratio file is not available
- **Performance**: Avoid expensive URI-based aspect ratio extraction when possible

## Implementation Rules

### ✅ **Correct Behavior:**
```kotlin
// Always start with HLS
val initialMediaItem = MediaItem.Builder()
    .setUri(masterUrl)  // master.m3u8
    .setCustomCacheKey(ipfsId)
    .build()

// Fallback sequence in onPlayerError:
if (!hasTriedPlaylist) {
    // Try playlist.m3u8
} else if (!hasTriedOriginal) {
    // Try original URL
} else {
    // Stop player - all attempts failed
    exoPlayer.stop()
}

// Exception handling for inaccessible URLs
try {
    // Video loading logic
} catch (e: Exception) {
    Timber.e("Video loading failed: ${e.message}")
    // Move to next fallback or stop gracefully
}
```

### ❌ **Incorrect Behavior:**
- Progressive video detection
- Skipping HLS attempts
- Multiple retries of the same URL
- Multiple ExoPlayer instances
- Memory leaks from failed players
- Crashes from inaccessible URLs
- Always using URI-based aspect ratio extraction

## Expected Log Flow

```
1. "Created MediaSource for HLS URL: master.m3u8"
2. If fails: "Trying fallback to playlist URL: playlist.m3u8"
3. If fails: "Trying original URL as last resort: original_url"
4. If all fail: "All fallback attempts failed" + stop player
5. Error logs: "Video loading failed: [specific error message]"
```

## Error Handling Examples

### **Network Errors (Expected)**
```
- 500 Server Error: Move to next fallback
- 404 Not Found: Move to next fallback  
- Connection timeout: Move to next fallback
- DNS resolution failure: Move to next fallback
```

### **Graceful Degradation**
```
- Video fails to load → Show placeholder or skip
- Aspect ratio unavailable → Use default or fallback
- Network unavailable → Continue with other content
- Server errors → Log and continue
```

## Aspect Ratio Strategy

### **Primary Method**
```kotlin
// Use aspect ratio from MimeiFileType attachment
val aspectRatio = mimeiFileType.aspectRatio
```

### **Fallback Method**
```kotlin
// Only when primary method fails
val aspectRatio = SimplifiedVideoCacheManager.getVideoAspectRatio(context, uri)
```

### **Performance Considerations**
- **Avoid URI extraction** when aspect ratio file is available
- **Cache aspect ratios** to avoid repeated extraction
- **Handle extraction failures** gracefully
- **Use reasonable timeouts** for URI-based extraction

## Key Points to Remember

1. **HLS First**: Always try HLS before progressive
2. **No Detection**: Don't try to guess video format
3. **No Retries**: Each format once only
4. **Clean Stop**: Properly stop failed players
5. **IPFS URLs**: No file extensions, still try HLS first
6. **Exception Handling**: Graceful handling of inaccessible URLs
7. **Aspect Ratio**: Use attachment file first, URI extraction as fallback

## Common Mistakes to Avoid

- ❌ Adding progressive video detection
- ❌ Skipping HLS for certain URLs
- ❌ Retrying failed URLs
- ❌ Creating multiple players
- ❌ Forgetting to stop failed players
- ❌ Crashes from network errors
- ❌ Always using URI-based aspect ratio extraction
- ❌ Not handling inaccessible URLs gracefully 