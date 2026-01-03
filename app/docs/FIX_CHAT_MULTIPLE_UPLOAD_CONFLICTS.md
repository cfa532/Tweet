# Fix: Multiple Background Uploads Cancelling Each Other in ChatView

## Date
2026-01-03

## Problem
When multiple messages with attachments are sent in quick succession in ChatView, they appear to cancel each other out or get duplicated, causing upload failures and inconsistent message delivery.

## Root Cause

### Issue: Multiple Event Collectors Conflict

**Before the fix:**
- Every time `sendMessageWithAttachment()` was called, it created a NEW coroutine that started collecting from `TweetNotificationCenter.events` (lines 278-320)
- These collectors were launched in `viewModelScope` and never cancelled
- All collectors listened to the same shared `TweetNotificationCenter.events` Flow

**Problematic Code Flow:**
```kotlin
// User sends attachment #1
sendMessageWithAttachment() 
  → WorkManager.enqueue(workRequest1)
  → viewModelScope.launch { TweetNotificationCenter.events.collect { ... } }  // Collector #1

// User sends attachment #2  
sendMessageWithAttachment()
  → WorkManager.enqueue(workRequest2)
  → viewModelScope.launch { TweetNotificationCenter.events.collect { ... } }  // Collector #2

// User sends attachment #3
sendMessageWithAttachment()
  → WorkManager.enqueue(workRequest3)
  → viewModelScope.launch { TweetNotificationCenter.events.collect { ... } }  // Collector #3

// When worker #1 completes:
Worker #1 posts ChatMessageSent event
  → Collector #1 processes it ✓
  → Collector #2 processes it ✗ (wrong message!)
  → Collector #3 processes it ✗ (wrong message!)

// When worker #2 completes:
Worker #2 posts ChatMessageSent event
  → Collector #1 processes it ✗ (already finished)
  → Collector #2 processes it ✓
  → Collector #3 processes it ✗ (wrong message!)

// When worker #3 completes:
Worker #3 posts ChatMessageSent event
  → Collector #1 processes it ✗ (already finished)
  → Collector #2 processes it ✗ (already finished)
  → Collector #3 processes it ✓
```

**Symptoms:**
- Messages appear multiple times in UI
- Database insertions fail due to duplicate IDs
- Chat sessions update incorrectly
- Some messages never appear
- Upload progress shows inconsistent state
- Race conditions between collectors

## Solution

### Move Event Listener to Init Block (Single Collector Pattern)

**Strategy:**
1. Create ONE event listener in the `init` block
2. This single listener runs for the entire ViewModel lifetime
3. It handles ALL `ChatMessageSent` and `ChatMessageSendFailed` events for this chat
4. Remove the event listener creation from `sendMessageWithAttachment()`
5. The existing deduplication logic (`isNewMessage`) prevents any duplicates

**Fixed Code Flow:**
```kotlin
// ViewModel created
init {
    // ... load data ...
    startListeningToMessageEvents()  // Single collector starts
}

// User sends attachment #1
sendMessageWithAttachment() 
  → WorkManager.enqueue(workRequest1)
  → (No collector created!)

// User sends attachment #2  
sendMessageWithAttachment()
  → WorkManager.enqueue(workRequest2)
  → (No collector created!)

// User sends attachment #3
sendMessageWithAttachment()
  → WorkManager.enqueue(workRequest3)
  → (No collector created!)

// When any worker completes:
Worker posts ChatMessageSent event
  → SINGLE collector processes it ✓
  → Deduplication check ensures no duplicates
  → Message added to UI exactly once
```

## Code Changes

### 1. Added Import
```kotlin
import us.fireshare.tweet.datamodel.TweetCacheManager
```

### 2. Updated Init Block
```kotlin
init {
    // ... existing data loading code ...
    
    // CRITICAL: Start SINGLE event listener for ALL background message uploads
    // This prevents multiple collectors from interfering with each other
    startListeningToMessageEvents()
}
```

### 3. Created New Method: `startListeningToMessageEvents()`
```kotlin
/**
 * Start listening to message send events from background workers.
 * This should only be called ONCE in init block to prevent multiple collectors.
 */
private fun startListeningToMessageEvents() {
    viewModelScope.launch {
        Timber.tag("ChatViewModel").d("Starting single event listener for receiptId: $receiptId")
        TweetNotificationCenter.events.collect { event ->
            when (event) {
                is TweetEvent.ChatMessageSent -> {
                    if (event.message.receiptId == receiptId) {
                        Timber.tag("ChatViewModel").d("Received ChatMessageSent event: ${event.message.id}")
                        // Deduplication using unique message IDs
                        if (isNewMessage(event.message, _chatMessages.value)) {
                            // Add message to UI and database
                            _chatMessages.update { messages ->
                                (messages + event.message).sortedBy { it.timestamp }
                            }
                            chatRepository.insertMessage(event.message)
                            
                            // Trigger scroll to bottom for messages from current user
                            if (event.message.authorId == appUser.mid) {
                                _shouldScrollToBottom.value = true
                            }
                        } else {
                            Timber.tag("ChatViewModel").d("Skipping duplicate message: ${event.message.id}")
                        }
                        // Update chat session
                        val previewMessage = chatSessionRepository.updateChatSessionWithMessage(
                            appUser.mid, receiptId, event.message, hasNews = false
                        )
                        chatListViewModel?.updateSession(previewMessage, hasNews = false)
                    }
                }

                is TweetEvent.ChatMessageSendFailed -> {
                    Timber.tag("ChatViewModel").e("Received ChatMessageSendFailed: ${event.error}")
                    _toastMessage.value = "Failed to send message: ${event.error}"
                }

                else -> {
                    // Ignore other events
                }
            }
        }
    }
}
```

### 4. Simplified `sendMessageWithAttachment()`
```kotlin
private suspend fun sendMessageWithAttachment(
    content: String,
    attachmentUri: Uri,
    context: Context
) {
    // Show sending status toast
    _toastMessage.value = context.getString(R.string.uploading_attachment_background)

    // Get or create session ID for this conversation
    val sessionId = chatSessionRepository.getOrCreateSessionId(appUser.mid, receiptId)

    // Create work request for background processing
    val workRequest = OneTimeWorkRequestBuilder<SendChatMessageWorker>()
        .setInputData(
            workDataOf(
                "receiptId" to receiptId,
                "content" to content,
                "attachmentUri" to attachmentUri.toString(),
                "sessionId" to sessionId,
                "messageTimestamp" to System.currentTimeMillis()
            )
        )
        .setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            10_000L, // 10 seconds
            java.util.concurrent.TimeUnit.MILLISECONDS
        )
        .build()

    // Enqueue the work
    WorkManager.getInstance(context).enqueue(workRequest)
    
    Timber.tag("ChatViewModel").d("Enqueued background message upload worker for receiptId: $receiptId")
    // Worker completion events will be handled by the single event listener in init block
}
```

**Key Changes:**
- ✅ Removed 42 lines of duplicate event listener code
- ✅ Added single event listener in init block
- ✅ Added logging for debugging
- ✅ Preserved all deduplication and error handling logic

## Benefits

### 1. **No More Conflicts**
- Only ONE collector processes events
- No race conditions between collectors
- Predictable, deterministic behavior

### 2. **Proper Message Handling**
- Each message processed exactly once
- Deduplication works correctly
- Database operations succeed
- UI updates consistently

### 3. **Better Resource Usage**
- Single coroutine instead of N coroutines (where N = number of messages sent)
- Reduced memory footprint
- No collector accumulation over time

### 4. **Easier Debugging**
- Clear log messages show when collector starts
- Easy to trace event flow
- Single point of event handling

## Pattern Comparison

### ❌ **OLD Pattern (Multiple Collectors) - BROKEN**
```kotlin
fun sendMessageWithAttachment() {
    WorkManager.enqueue(work)
    
    // Creates new collector EVERY TIME
    viewModelScope.launch {
        events.collect { event -> 
            // Handle event
        }
    }
}
```

### ✅ **NEW Pattern (Single Collector) - FIXED**
```kotlin
init {
    startListeningToMessageEvents()  // Create collector ONCE
}

fun sendMessageWithAttachment() {
    WorkManager.enqueue(work)
    // No collector creation - handled by init block
}
```

## Similar Patterns in Codebase

This fix follows the same pattern used in:
- **`TweetFeedViewModel.startListeningToNotifications()`** - Prevents multiple listeners with `isListeningToNotifications` flag
- **`UserViewModel.startListeningToNotifications()`** - Single listener in init block
- **`TweetViewModel.startListeningToNotifications()`** - Single listener pattern

## Testing Recommendations

1. **Test rapid message sending:**
   - Send 5 messages with attachments in quick succession
   - Verify all 5 appear exactly once in UI
   - Verify all 5 are in database
   - Verify no duplicate errors

2. **Test slow uploads:**
   - Send message #1 (large file)
   - Send message #2 (small file) while #1 is uploading
   - Verify both complete successfully
   - Verify correct order in UI

3. **Test failure scenarios:**
   - Send message with invalid attachment
   - Verify error toast appears
   - Send another message immediately
   - Verify second message still processes

4. **Test chat navigation:**
   - Send message with attachment
   - Navigate away from chat
   - Navigate back
   - Verify message appears correctly

## Related Files
- `/app/src/main/java/us/fireshare/tweet/viewmodel/ChatViewModel.kt` - Fixed
- `/app/src/main/java/us/fireshare/tweet/service/ChatMessageWorker.kt` - No changes needed
- `/app/src/main/java/us/fireshare/tweet/datamodel/TweetNotificationCenter.kt` - No changes needed

## Migration Notes
- No database migration required
- No user data affected
- Backward compatible
- Fix takes effect immediately when ViewModel is recreated

## Performance Impact
- **Memory:** Reduced (N collectors → 1 collector)
- **CPU:** Reduced (no duplicate event processing)
- **Reliability:** Significantly improved (no race conditions)
- **User Experience:** Messages now send reliably without conflicts

