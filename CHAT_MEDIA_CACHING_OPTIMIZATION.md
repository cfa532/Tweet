# Chat Media Caching Optimization

## Summary
Implemented optimistic UI and local caching for media files (images and videos) when sending in chat. The app now displays the local file immediately without downloading it from the network, significantly improving perceived performance and user experience.

## Changes Made

### 1. ImageCacheManager.kt
- **Added `cacheLocalImageFile()` function**: Caches a local image file from Uri directly using a media ID
- **Added helper functions**: 
  - `decodeBitmapFromStreamWithOrientation()`: Decodes bitmap with EXIF orientation correction
  - `rotateBitmap()`: Rotates bitmap by specified degrees
- These functions enable immediate caching of locally selected images before upload

### 2. VideoManager.kt
- **Added `cacheLocalVideoFile()` function**: Caches a local video file from Uri to the video cache directory
- **Added `getCachedLocalVideoFile()` function**: Retrieves the cached local video file if it exists
- Enables immediate display of videos without waiting for network download

### 3. ChatMessage.kt
- **Added `localAttachmentUri` field**: Transient String field that stores the local file Uri for optimistic display
- Field is marked with `@kotlinx.serialization.Transient` to prevent serialization
- Not stored in database, only used for temporary optimistic UI

### 4. ChatViewModel.kt
- **Modified `sendMessageWithAttachment()` function**:
  - Creates an optimistic message with `localAttachmentUri` immediately upon sending
  - Adds optimistic message to UI for instant visual feedback
  - Passes `optimisticMessageId` to worker for later replacement
- **Modified event listener**:
  - Detects and replaces optimistic messages when real message arrives from server
  - Matches by timestamp and checks for presence of `localAttachmentUri`

### 5. ChatMessageWorker.kt
- **Modified `doWork()` function**:
  - Added `optimisticMessageId` parameter handling
  - After successful upload, immediately caches the local file with the returned media ID
  - Handles both images (via `ImageCacheManager`) and videos (via `VideoManager`)
  - Caching happens in background, doesn't block upload process

### 6. ChatScreen.kt
- **Modified `ChatMediaPreview()` composable**:
  - Added `localAttachmentUri` parameter
  - Implements branching logic:
    - If `localAttachmentUri` is present and no attachments: Display from local Uri (optimistic UI)
    - If attachments are present: Display from network (normal flow)
  - Local display implementation:
    - Detects file type using `FileTypeDetector`
    - For images: Loads bitmap from local Uri and displays immediately
    - For videos: Extracts thumbnail from local Uri and displays with play button overlay
    - Shows loading indicators while processing local files
- **Updated `ChatMediaPreview()` call sites**:
  - Passes `message.localAttachmentUri` to display local files when available

## Technical Flow

### When User Sends Media:
1. User selects image/video from device
2. `ChatViewModel` creates optimistic message with `localAttachmentUri`
3. Message appears instantly in chat UI
4. `ChatMediaPreview` detects `localAttachmentUri` and displays local file
5. Background worker uploads file to IPFS
6. Once uploaded, worker caches local file with returned media ID
7. Worker sends real message with attachment info
8. `ChatViewModel` replaces optimistic message with real message
9. Future displays use cached file instead of downloading from network

### Benefits:
- **Instant Feedback**: Media appears immediately in chat
- **No Network Wait**: Local file displayed while uploading
- **Efficient Caching**: File cached with correct ID once uploaded
- **No Re-download**: Subsequent views use cached file
- **Seamless Transition**: Optimistic message smoothly replaced with real one

## Testing Recommendations
1. Send image in chat - verify immediate display
2. Send video in chat - verify thumbnail appears immediately
3. Verify file uploads successfully in background
4. Check that cached file is used for subsequent displays
5. Test with slow network to see optimization benefits
6. Verify error handling if upload fails

## Future Improvements
- Add progress indicator during upload
- Handle upload cancellation
- Implement retry logic for failed uploads
- Add support for other media types (audio, documents)
