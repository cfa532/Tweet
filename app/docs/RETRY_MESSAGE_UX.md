# Retry Message UX Implementation

Date: 2026-01-02

## Overview

Added localized retry status messages to inform users when the app is retrying network requests due to unreliable network conditions.

## User Experience Flow

### Before (No Feedback)
```
[Loading spinner]
       ↓
  (30s silence)
       ↓
  (30s silence)
       ↓
  Finally loads or fails
```
**Problem**: User doesn't know if app is frozen or working

### After (With Feedback)
```
[Loading spinner]
       ↓
  (10s timeout)
       ↓
"Retrying 1/5..."  ✅
       ↓
  (10s timeout)
       ↓
"Retrying 2/5..."  ✅
       ↓
  Loads successfully
```
**Result**: User knows app is actively working

## Implementation

### 1. ViewModel State (`TweetFeedViewModel.kt`)

Added retry message state:
```kotlin
// Retry status message (null when not retrying)
private val _retryMessage = MutableStateFlow<String?>(null)
val retryMessage: StateFlow<String?> get() = _retryMessage.asStateFlow()
```

### 2. Retry Callback (`HproseInstance.kt`)

Added callback parameter to `getTweetFeed()`:
```kotlin
suspend fun getTweetFeed(
    user: User = appUser,
    pageNumber: Int = 0,
    pageSize: Int = 5,
    entry: String = "get_tweet_feed",
    maxRetries: Int = 5,
    onRetry: ((attempt: Int, maxRetries: Int) -> Unit)? = null  // ✅ New
): List<Tweet?>
```

Callback invoked during retry:
```kotlin
if (forceRefresh) {
    // Notify UI about retry attempt
    onRetry?.invoke(attempt, maxRetries)  // ✅
    
    Timber.tag("getTweetFeed").d("🔄 Retry attempt $attempt...")
    // ... refresh baseUrl and retry
}
```

### 3. ViewModel Integration

Connected callback to update retry message:
```kotlin
HproseInstance.getTweetFeed(
    appUser,
    pageNumber,
    pageSize,
    onRetry = { attempt, maxRetries ->
        // Show retry message when no tweets are loaded yet
        if (_tweets.value.isEmpty() && pageNumber == 0) {
            viewModelScope.launch(Main) {
                val context = contextRef.get()
                if (context != null) {
                    val message = context.getString(R.string.retrying, attempt, maxRetries)
                    _retryMessage.value = message  // ✅ Update UI
                }
            }
        }
    }
)

// Clear retry message on success or failure
_retryMessage.value = null
```

### 4. UI Display (`TweetFeedScreen.kt`)

Show retry message with loading spinner:
```kotlin
// Collect retry message
val retryMessage by viewModel.retryMessage.collectAsState()

// Display during loading
if (initState) {
    Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        
        // Show retry message if retrying
        retryMessage?.let { message ->
            Text(
                text = message,  // "Retrying 2/5..."
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
```

### 5. Localized Strings

#### English (`values/strings.xml`)
```xml
<string name="retrying">Retrying %1$d/%2$d...</string>
```

#### Chinese (`values-zh/strings.xml`)
```xml
<string name="retrying">正在重試 %1$d/%2$d...</string>
```

#### Japanese (`values-ja/strings.xml`)
```xml
<string name="retrying">再試行中 %1$d/%2$d...</string>
```

## Behavior

### When Retry Message Shows

✅ **Shows When:**
- First page load (pageNumber == 0)
- No tweets loaded yet (_tweets.isEmpty())
- Network request is retrying (attempt > 0)

❌ **Doesn't Show When:**
- User is loading next page (pageNumber > 0)
- Tweets already displayed (_tweets.isNotEmpty())
- Request succeeds on first attempt

### Example User Experience

```
User opens app
       ↓
[Loading...]
       ↓
Network timeout (10s)
       ↓
[Loading... "正在重試 1/5..."]  ✅ Chinese user sees localized message
       ↓
Network timeout (10s)
       ↓
[Loading... "正在重試 2/5..."]  ✅ Progress indicator
       ↓
Success! Tweets appear
       ↓
Message disappears
```

## Benefits

### 1. User Confidence
```
Before: "Is the app frozen?"
After:  "It's retrying, making progress"
```

### 2. Perceived Performance
```
Before: Feels like 50 seconds of nothing
After:  Feels like active work happening
```

### 3. Network Quality Feedback
```
User sees: "Retrying 3/5..."
User thinks: "My network is slow, I should check"
```

### 4. Reduced Support Requests
```
Before: "App doesn't work!"
After:  User understands it's network issue
```

## Testing Scenarios

### 1. Good Network
```
Expected: No retry message shown
Result: Content loads immediately
```

### 2. Slow Network (timeout after 10s)
```
Expected: "Retrying 1/5..." appears
Result: User knows app is working
Time: Retry succeeds within 20s
```

### 3. Very Poor Network (multiple timeouts)
```
Timeline:
10s: Network timeout
11s: "正在重試 1/5..." (Chinese locale)
20s: Network timeout
21s: "正在重試 2/5..."
30s: Network timeout
31s: "正在重試 3/5..."
35s: Success! Message clears

Result: User sees continuous progress
```

### 4. Offline → Online Transition
```
0s: User opens app offline
10s: Timeout, "Retrying 1/5..."
15s: User enables WiFi
16s: Success, message clears

Result: Smooth recovery experience
```

## Implementation Notes

### Thread Safety
- ViewModel updates happen on Main thread via `viewModelScope.launch(Main)`
- StateFlow updates are thread-safe
- Callback is invoked from IO thread, safely communicates to Main

### Memory Management
- Uses WeakReference for Context (contextRef.get())
- Prevents memory leaks if ViewModel outlives Activity
- Message automatically clears on success/failure

### Backward Compatibility
- Callback parameter is optional (`onRetry: (...) -> Unit)? = null`)
- Existing code without callback continues to work
- No breaking changes to existing callers

## Combined with Previous Optimizations

This retry message completes the unreliable network optimization suite:

| Optimization | Benefit |
|--------------|---------|
| 10s timeout (vs 30s) | 3x faster failure detection |
| 5 retries (vs 2) | 2.5x more attempts |
| 5 tweets/page (vs 20) | 4x less data, 4x faster |
| **Retry messages** | **User confidence & feedback** ✅ |

## User Feedback Expected

### Positive Indicators
- ✅ "I can see it's trying"
- ✅ "Better than just spinning"
- ✅ "I know it's working"

### Success Metrics
- 📉 Reduced "app frozen" reports
- 📈 Better app store ratings
- 📉 Lower uninstall rate during network issues

## Conclusion

The retry message provides critical user feedback during network retries:
- ✅ **Localized** (English, Chinese, Japanese)
- ✅ **Informative** (shows progress: 2/5)
- ✅ **Non-intrusive** (only when needed)
- ✅ **Automatic** (clears on success/failure)

**Result: Users feel confident the app is working, even in poor network conditions!** 🚀

