# Android fetchUser Retry Logic Update

This document summarizes the changes made to align the Android `fetchUser` retry logic with the iOS implementation.

## Changes Made

### 1. Retry Strategy Restructured

**Previous behavior:**
- Complex inline redirect handling
- Inconsistent IP resolution on retries
- Different backoff calculation

**New behavior (matching iOS):**
- **First attempt**: Uses existing baseUrl if available (unless forcing refresh)
- **Retry attempts (2nd, 3rd)**: Always resolves fresh provider IP BEFORE the attempt
- **Redirect handling**: When server returns redirect (String IP), immediately retries inline with that IP

### 2. Exponential Backoff Simplified

**Previous:**
```kotlin
val delayMs = minOf(3000L, 1000L * (1 shl (attempt - 1))) // Exponential backoff: 1s, 2s
```

**New (matching iOS):**
```kotlin
val delayMs = attempt * 1000L  // Exponential backoff: 1s, 2s (matching iOS: attempt * 1_000_000_000 nanoseconds)
```

- **Attempt 1 → 2**: 1 second delay
- **Attempt 2 → 3**: 2 second delay

### 3. Redirect Loop Detection Enhanced

**Before retries:**
- On retry attempts (2nd, 3rd), checks if resolved IP is the same as current IP
- Stops immediately if redirect loop detected (before making the call)

**During redirect:**
- When server returns redirect (String IP), checks for redirect loop
- If redirect loop detected, throws error immediately (no retry)

### 4. IP Resolution Logic

**First attempt:**
- Uses existing baseUrl if available and not forcing refresh
- Otherwise resolves fresh provider IP

**Retry attempts:**
- Always resolves fresh provider IP BEFORE making the call
- Checks for redirect loops before proceeding

### 5. Error Handling

- **Redirect loop errors**: Stops retries immediately (no further attempts)
- **Skip retries flag**: When `skipRetryAndBlacklist` is true, only 1 attempt (for username searches)
- **Network errors**: Retries with exponential backoff
- **All retries failed**: Records failure in blacklist (unless skipRetryAndBlacklist is true)

## Implementation Details

### Function: `updateUserFromServerWithRetry`

**Location:** `app/src/main/java/us/fireshare/tweet/HproseInstance.kt:2516`

**Key changes:**
1. Restructured to use `for` loop (1..maxRetries) instead of `while` loop
2. Clear separation between first attempt and retry attempts
3. IP resolution happens BEFORE each retry attempt
4. Redirect loop detection on retries BEFORE making the call
5. Simplified exponential backoff calculation

### Retry Flow

```
updateUserFromServerWithRetry(user, maxRetries, skipRetryAndBlacklist)
  ├─ Loop: 1 to maxRetries (3 or 1)
  │   ├─ Attempt 1:
  │   │   ├─ Use existing baseUrl OR resolve fresh IP
  │   │   └─ Make server call
  │   ├─ Attempts 2-3:
  │   │   ├─ Always resolve fresh IP
  │   │   ├─ Check redirect loop (same IP as current?)
  │   │   └─ Make server call
  │   └─ On error:
  │       ├─ Check redirect loop → stop if detected
  │       ├─ Check skipRetries → stop if true
  │       └─ Exponential backoff delay (1s, 2s)
  └─ All retries failed → return false
```

### Redirect Handling

When server returns redirect (String/IP):
1. Check for redirect loop (same IP as current)
2. Update baseUrl to redirect IP
3. Clear hproseService cache
4. Immediately retry with new baseUrl (inline)
5. Handle second redirect if it occurs

## Compatibility

- **Backward compatible**: Same function signature, same return type
- **Behavior**: More reliable retry logic matching iOS
- **Performance**: Slightly improved (fewer unnecessary IP resolutions on first attempt)

## Testing Recommendations

1. Test with existing baseUrl (should use it on first attempt)
2. Test with expired user (should resolve fresh IP)
3. Test retry scenarios (network failures)
4. Test redirect loops (should stop immediately)
5. Test username searches (should skip retries)

## Related Files

- `app/src/main/java/us/fireshare/tweet/HproseInstance.kt` - Main implementation
- `docs/IOS_FETCHUSER_RETRY_IMPLEMENTATION.md` - iOS reference implementation

