# iOS Redirect Loop Handling

This document explains how iOS handles redirects when the server returns the same IP address.

## Overview

iOS has **two levels of redirect loop detection** to prevent infinite redirects:

1. **First redirect check**: When server redirects to the same IP you're already on
2. **Second redirect check**: When redirected server redirects back to the same IP

Both scenarios throw an error with code `-2` and **stop retries immediately**.

## Implementation Details

### Location

The redirect loop detection is implemented in:
- `HproseInstance.swift` - `updateUserFromServerInternal` function (lines 1554-1630)

### First Redirect Check

**When:** Server returns a provider IP (String) indicating a redirect

**Detection:**
```swift
// Normalize both IPs for comparison
let normalizedRedirectIp = ipAddress.removePrefix("http://")...
let normalizedCurrentIp = currentBaseUrlString.removePrefix("http://")...

// Compare normalized IPs
if normalizedCurrentIp == normalizedRedirectIp {
    // Redirect loop detected - being redirected to the same IP we're already on
    throw NSError(domain: "HproseClient", code: -2, 
        userInfo: [NSLocalizedDescriptionKey: "Redirect loop detected - redirected to same IP: \(ipAddress)"])
}
```

**What happens:**
- Normalizes both the current baseUrl IP and the redirect IP (removes `http://` prefix)
- Compares normalized IPs
- If they match → throws error with code `-2`
- Stops immediately (no retry)

### Second Redirect Check

**When:** After following a redirect, the redirected server also returns another redirect (second IP)

**Detection:**
```swift
// After redirect, if redirected server also returns an IP
if let newIpAddress = newResponse as? String {
    let newNormalizedIp = newIpAddress.removePrefix("http://")...
    
    // Compare with the redirect IP we just tried
    if newNormalizedIp == normalizedRedirectIp {
        // Redirect loop - same IP returned twice
        throw NSError(domain: "HproseClient", code: -2, 
            userInfo: [NSLocalizedDescriptionKey: "Redirect loop detected - redirected server returned same IP: \(newIpAddress)"])
    }
}
```

**What happens:**
- After following first redirect, if server returns another redirect IP
- Normalizes the second redirect IP
- Compares with the first redirect IP
- If they match → throws error with code `-2`
- Stops immediately (no retry)

### Error Code -2 Handling

**In retry logic:**
```swift
catch {
    // Check if this is a redirect loop error (code -2) - don't retry in this case
    if let nsError = error as NSError?, nsError.code == -2 {
        print("DEBUG: [updateUserFromServer] Redirect loop detected, stopping retries")
        throw error  // Stop immediately, no retry
    }
    // ... other error handling
}
```

**Behavior:**
- Error code `-2` = redirect loop detected
- Stops all retries immediately
- Does NOT retry with exponential backoff
- Does NOT resolve fresh IP
- Propagates error up to caller

## Redirect Flow

```
Server Call (current IP: 1.2.3.4)
  ├─ Server returns IP redirect
  ├─ Check: redirect IP == current IP?
  │   ├─ YES → Throw error -2 (STOP)
  │   └─ NO  → Follow redirect
  │
  └─ Follow redirect (new IP: 5.6.7.8)
      ├─ Redirected server returns another IP redirect
      ├─ Check: second redirect IP == first redirect IP?
      │   ├─ YES → Throw error -2 (STOP)
      │   └─ NO  → Continue processing
      └─ Process response
```

## Key Points

1. **Two checks**: Both first and second redirect are checked for loops
2. **Normalization**: IPs are normalized (remove `http://` prefix) before comparison
3. **Immediate stop**: Error code `-2` stops all retries immediately
4. **No recovery**: Redirect loops are considered fatal errors
5. **Error propagation**: Error propagates to caller, no silent failure

## Comparison with Retry Logic

**Redirect loop detection (error -2):**
- Stops immediately
- No retries
- No exponential backoff
- Considered fatal

**Other errors:**
- Retries with exponential backoff
- Resolves fresh IP on retries
- Recovery attempts

## Code References

### First Redirect Check
```1554:1573:/Users/cfa532/Documents/GitHub/Tweet-iOS/Sources/Core/HproseInstance.swift
if let ipAddress = ipAddress, !ipAddress.isEmpty {
    // Check if we're being redirected to the same IP we're already on (redirect loop)
    // Normalize both IPs for comparison (remove http:// prefix, ensure format consistency)
    let normalizedRedirectIp: String
    if ipAddress.hasPrefix("http://") {
        normalizedRedirectIp = String(ipAddress.dropFirst(7))
    } else if ipAddress.hasPrefix("http") {
        normalizedRedirectIp = String(ipAddress.dropFirst(4))
    } else {
        normalizedRedirectIp = ipAddress
    }
    
    let currentBaseUrlString = user.baseUrl?.absoluteString ?? ""
    let normalizedCurrentIp = currentBaseUrlString.hasPrefix("http://") ? String(currentBaseUrlString.dropFirst(7)) : currentBaseUrlString
    
    // Compare normalized IPs (should match if redirecting to same server)
    if normalizedCurrentIp == normalizedRedirectIp {
        // Redirect loop detected - being redirected to the same IP we're already on
        print("DEBUG: [updateUserFromServer] Redirect loop detected for \(user.mid) - redirected to same IP: \(ipAddress) (current: \(currentBaseUrlString))")
        throw NSError(domain: "HproseClient", code: -2, userInfo: [NSLocalizedDescriptionKey: "Redirect loop detected - redirected to same IP: \(ipAddress)"])
    }
```

### Second Redirect Check
```1614:1630:/Users/cfa532/Documents/GitHub/Tweet-iOS/Sources/Core/HproseInstance.swift
} else if let newIpAddress = newResponse as? String {
    // Second redirect returned - check if it's the same IP (redirect loop)
    let newNormalizedIp: String
    if newIpAddress.hasPrefix("http://") {
        newNormalizedIp = String(newIpAddress.dropFirst(7))
    } else if newIpAddress.hasPrefix("http") {
        newNormalizedIp = String(newIpAddress.dropFirst(4))
    } else {
        newNormalizedIp = newIpAddress
    }
    
    // Compare with the redirect IP we just tried
    if newNormalizedIp == normalizedRedirectIp {
        // Redirect loop - same IP returned twice
        print("DEBUG: [updateUserFromServer] Redirect loop detected for \(user.mid) - redirected server returned same IP: \(newIpAddress) (same as first redirect: \(ipAddress))")
        throw NSError(domain: "HproseClient", code: -2, userInfo: [NSLocalizedDescriptionKey: "Redirect loop detected - redirected server returned same IP: \(newIpAddress)"])
    }
```

### Error Code -2 Handling
```1320:1324:/Users/cfa532/Documents/GitHub/Tweet-iOS/Sources/Core/HproseInstance.swift
// Check if this is a redirect loop error (code -2) - don't retry in this case
if let nsError = error as NSError?, nsError.code == -2 {
    print("DEBUG: [updateUserFromServer] Redirect loop detected, stopping retries for userId: \(userId)")
    throw error
}
```

