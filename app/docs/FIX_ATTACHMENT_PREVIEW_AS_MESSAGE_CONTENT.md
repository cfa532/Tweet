# Fix: Attachment Preview Phrases Appearing as Actual Chat Messages

## Problem

When the app processes incoming messages with attachments while in the background, phrases like "Image sent", "Video received", etc. were sometimes appearing as actual chat message content instead of only as preview text in the `ChatSession.lastMessage` field.

### User Report
> "In chatscreen, on certain branch of uploading a message with attachment, sometimes a phrase is added by the app into chat, such as image is sent, video is received. The message means to be added to lastMessage field of chatsession object, not a chat message."
> 
> "It seems happen when the message is processed while the app is in background"

## Root Cause

The bug was in `TweetActivity.kt` lines 246-268:

```kotlin
val updatedSessions = chatSessionRepository.mergeMessagesWithSessions(
    existingSessions,
    trulyNewMessages
)

updatedSessions.forEach { chatSession ->
    chatSessionRepository.updateChatSessionWithMessage(
        HproseInstance.appUser.mid,
        chatSession.receiptId,
        chatSession.lastMessage,  // ← BUG: This already has preview text!
        hasNews = chatSession.hasNews
    )
}
```

### The Flow:

1. **`mergeMessagesWithSessions()`** creates `ChatSession` objects where:
   - `lastMessage` has preview text like "Image sent" in its `content` field
   - This is done via `ChatMessage.withAttachmentPreview()` for UI display

2. **`updateChatSessionWithMessage(chatSession.lastMessage)`** is called with the preview message

3. **Inside `ChatSessionRepository.updateChatSessionWithMessage()` line 79-82**:
   ```kotlin
   // Message doesn't exist, insert it ONCE with sessionId already set
   val entityToInsert = message.toEntity().copy(sessionId = sessionId)
   chatMessageDao.insertMessage(entityToInsert)  // ← Inserts preview message to DB!
   ```

4. **Result**: The preview message with "Image sent" content gets permanently saved to the `chat_messages` table

5. **When user opens the chat**: Messages are loaded from database with `loadMessagesBySession()`, which returns the saved content including "Image sent"

## The Fix

Changed `TweetActivity.kt` to insert the **original messages** (without preview text) instead of the preview messages from `ChatSession` objects:

### Before:
```kotlin
val updatedSessions = chatSessionRepository.mergeMessagesWithSessions(
    existingSessions,
    trulyNewMessages
)

updatedSessions.forEach { chatSession ->
    chatSessionRepository.updateChatSessionWithMessage(
        HproseInstance.appUser.mid,
        chatSession.receiptId,
        chatSession.lastMessage,  // Preview message - BUG!
        hasNews = chatSession.hasNews
    )
}
```

### After:
```kotlin
// Group messages by partner to get the last message for each conversation
val messagesByPartner = trulyNewMessages.groupBy { message ->
    if (message.authorId == HproseInstance.appUser.mid) {
        message.receiptId  // Outgoing: use receiptId (recipient)
    } else {
        message.authorId   // Incoming: use authorId (sender)
    }
}

messagesByPartner.forEach { (partnerId, messages) ->
    // Get the last (newest) message from the group
    val lastMessage = messages.maxByOrNull { it.timestamp } ?: return@forEach
    
    // Insert the ORIGINAL message (not the preview) to database
    chatSessionRepository.updateChatSessionWithMessage(
        HproseInstance.appUser.mid,
        partnerId,
        lastMessage,  // Original message from server
        hasNews = true
    )
}
```

## Why This Works

1. **Original messages are inserted**: `trulyNewMessages` contains the raw messages from the server with original content (null or user text) and attachments
2. **Preview text is applied later**: When sessions are loaded via `getAllSessions()`, the preview is applied dynamically:
   ```kotlin
   val message = messageEntity.toChatMessage()
   val previewMessage = message.withAttachmentPreview(context)
   sessionEntity.toChatSession(previewMessage)
   ```
3. **Database stays clean**: Only original message content is stored, preview is only for UI display
4. **Chat screen displays correctly**: Messages loaded from DB have original content, attachments are displayed properly

## Prevention

The key principle is:

**Preview text should NEVER be saved to the database, only applied dynamically when loading sessions for UI display.**

Any code that calls `chatSessionRepository.updateChatSessionWithMessage()` must pass the **original message**, not a message that has already had `withAttachmentPreview()` applied to it.

## Related Code

- `ChatSessionRepository.kt`:
  - `mergeMessagesWithSessions()` (line 219-291): Creates preview messages for ChatSession
  - `updateChatSessionWithMessage()` (line 52-97): Inserts message to DB if not exists
  - `getAllSessions()` (line 28-39): Applies preview when loading from DB
  - `withAttachmentPreview()` (line 293-296): Extension that adds preview text

- `TweetActivity.kt` (line 236-270): Background message processing

- `ChatViewModel.kt`: Uses `updateChatSessionWithMessage()` correctly with original messages

## Testing

1. Send a message with image attachment while app is in background
2. Wait for background worker to process the message
3. Open the chat screen
4. Verify:
   - Chat screen shows the image, not "Image sent" text
   - Chat list shows "Image sent" as preview (correct)
   - Database `chat_messages.content` is null or original text, not "Image sent"

