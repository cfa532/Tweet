# iOS fetchUser and Retry Implementation

This document summarizes how the iOS version implements `fetchUser` with retry logic.

## Overview

The iOS implementation has two main functions:
1. **`fetchUser`** - Main entry point that handles caching, blacklist checking, and coordinates the fetch
2. **`updateUserFromServer`** - Handles the actual server fetch with retry logic

## Key Features

### 1. Cache-First Strategy
- Checks cache first before making network calls
- Uses cached user if valid and not expired
- Resolves IP if cached user has nil baseUrl (old cache data)

### 2. Retry Logic

#### Maximum Attempts
- **Default**: 3 attempts
- **Username searches**: 1 attempt (skipRetries = true)
- Controlled by `skipRetries` parameter

#### Retry Strategy

**First Attempt:**
- Uses existing baseUrl if available and not forcing refresh
- Otherwise resolves fresh provider IP

**Retry Attempts (2nd, 3rd):**
- Always force fresh IP resolution
- Checks for redirect loops (same IP as previous attempt)
- Stops immediately if redirect loop detected

#### Exponential Backoff
```swift
let delay = UInt64(attempt) * 1_000_000_000  // 1s, 2s
try await Task.sleep(nanoseconds: delay)
```

- **Attempt 1 → 2**: 1 second delay
- **Attempt 2 → 3**: 2 second delay

### 3. Recovery Logic

Before each retry, `handleRetryRecovery` is called:
- Checks if app's base server is healthy
- Reinitializes app entry if server is unhealthy
- Updates HproseInstance baseUrl if needed
- Resolves fresh provider IP for the user

### 4. Redirect Loop Detection

On retry attempts, checks if resolved IP is the same as current IP:
```swift
if normalizedProviderIp == normalizedCurrentIp && !normalizedCurrentIp.isEmpty {
    // Redirect loop detected - stop retries immediately
    throw NSError(domain: "HproseClient", code: -2, ...)
}
```

### 5. Concurrent Update Prevention

Uses `ongoingUserUpdates` set to prevent concurrent updates for the same user:
- If another update is in progress, returns cached user instead
- Uses `DispatchQueue` for thread-safe access

### 6. Blacklist Integration

- Checks blacklist before fetching
- Records success after successful fetch
- Records failure and adds to blacklist after all retries fail
- Skips blacklist updates for username searches (skipRetries = true)

## Code Flow

```
fetchUser(userId, baseUrl)
  ├─ Check GUEST_ID → return early
  ├─ Check blacklist → return cached user
  ├─ Check cache → return if valid & not expired
  ├─ Resolve IP if needed (for old cache data)
  └─ updateUserFromServer(userId, baseUrl, skipRetries)
       ├─ Check concurrent updates → wait if in progress
       ├─ Loop: 1 to maxAttempts (3 or 1)
       │   ├─ Attempt 1: Use existing baseUrl OR resolve fresh IP
       │   ├─ Attempts 2-3: Always resolve fresh IP
       │   ├─ Check redirect loop (retries only)
       │   ├─ updateUserFromServerInternal(user)
       │   └─ On error:
       │       ├─ Check redirect loop → throw immediately
       │       ├─ handleRetryRecovery() → check server health
       │       └─ Exponential backoff delay
       └─ If all retries fail → throw error
```

## Key Implementation Details

### Retry Count
```swift
let maxAttempts = skipRetries ? 1 : 3
```

### Backoff Delay
```swift
let delay = UInt64(attempt) * 1_000_000_000  // nanoseconds
try await Task.sleep(nanoseconds: delay)
```

### Recovery Before Retry
```swift
if attempt < maxAttempts {
    await handleRetryRecovery(for: user)
    // Then exponential backoff delay
}
```

### Special Cases

1. **Username searches** (skipRetries = true): Only 1 attempt, no blacklist updates
2. **Redirect loops**: Immediately stops retries, throws error
3. **Concurrent updates**: Returns cached user instead of making duplicate requests
4. **App not initialized**: May still fetch if IP was resolved or baseUrl is empty (force refresh)

## Location

- **Main function**: `HproseInstance.swift:1013` - `fetchUser(_:baseUrl:)`
- **Retry logic**: `HproseInstance.swift:1210` - `updateUserFromServer(_:baseUrl:skipRetries:)`
- **Recovery logic**: `HproseInstance.swift:1372` - `handleRetryRecovery(for:)`

