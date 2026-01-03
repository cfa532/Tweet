# NodePool Differential Trust Strategy

## Overview

Different NodePool trust strategies for READ vs WRITE operations, optimized for their specific use cases.

---

## Implementation

### **`getHostIP()` - WRITE Operations (Uploads)**

**Strategy:** Verify health before using cached IP

```kotlin
// Step 1: Check NodePool and VERIFY health
val poolIP = NodePool.getIPFromNodeId(nodeId)
if (poolIP != null) {
    if (isServerHealthy("http://$poolIP")) {
        return poolIP  // ✓ Healthy - use it
    } else {
        NodePool.removeNode(nodeId)  // Remove stale IP
        // Continue to resolve fresh IP
    }
}
```

**Lines:** 1192-1207 in `HproseInstance.kt`

**Behavior:**
- ✅ Health check before using (HTTP HEAD request)
- ✅ Remove unhealthy IPs from pool
- ✅ Continue to fresh IP resolution if unhealthy
- ⏱️ Adds ~100ms latency (uses cache: 30s healthy, 5s unhealthy)

---

### **`fetchUser()` - READ Operations (Profiles/Tweets)**

**Strategy:** Trust cached IP without verification

```kotlin
// Try to get IP from NodePool - TRUST it
val poolIP = NodePool.getIPFromNode(user)
if (poolIP != null) {
    user.baseUrl = "http://$poolIP"
    user.clearHproseService()
    return  // ✓ Use immediately, no health check
}
```

**Lines:** 3152-3158 in `HproseInstance.kt` (`resolveAndUpdateBaseUrl()`)

**Behavior:**
- ✅ No health check (instant return)
- ✅ If fetch fails, retry mechanism handles it
- ✅ Retry resolves fresh IP via `getProviderIP()`
- ⚡ 0ms overhead (instant)

---

## Rationale

### **Why Verify for Uploads (`getHostIP`)?**

| Factor | Impact |
|--------|--------|
| **Use case** | Media uploads (videos, images, files) |
| **Cost of failure** | **HIGH** - wasted upload time, user frustration |
| **Upload size** | Can be 100MB+ (minutes of upload time) |
| **Health check cost** | ~100ms (negligible vs upload time) |
| **User experience** | Worth waiting 100ms to avoid uploading to dead node |

**Example:**
```
Without health check:
- Use cached IP: 0ms
- Upload 50MB video: 2 minutes
- Upload fails (node was down)
- Retry with fresh IP: another 2 minutes
- Total: 4+ minutes 😞

With health check:
- Health check: 100ms (detects node is down)
- Resolve fresh IP: 200ms
- Upload 50MB video: 2 minutes
- Success!
- Total: 2.3 minutes 😊
```

---

### **Why Trust for Reads (`fetchUser`)?**

| Factor | Impact |
|--------|--------|
| **Use case** | Loading profiles, tweets, follows |
| **Cost of failure** | **LOW** - fast retry, no data loss |
| **Data size** | Small (~5-10KB JSON) |
| **Health check cost** | 100ms (significant vs request time) |
| **User experience** | Instant response preferred, retry is fast |

**Example:**
```
With health check:
- Health check: 100ms
- Fetch user data: 50ms
- Total: 150ms

Without health check (trust):
- Fetch user data: 50ms (if IP healthy)
- Total: 50ms ✨ 3x faster!

If IP is dead:
- Fetch fails: 50ms
- Retry with fresh IP: 50ms
- Total: 100ms (still faster than health check + fetch)
```

---

## Performance Comparison

### **Upload Scenario (50MB video)**

| Strategy | Health Check | Upload | Retry | Total | Result |
|----------|-------------|---------|-------|-------|--------|
| **Verify (current)** | 100ms | 2min | - | **2m 0.1s** | ✅ Success |
| **Trust (if IP dead)** | - | 2min | 2min | **4m+** | ❌ Waste |

**Conclusion:** Verification worth it for uploads

### **Read Scenario (User profile)**

| Strategy | Health Check | Fetch | Retry | Total | Result |
|----------|-------------|-------|-------|-------|--------|
| **Verify** | 100ms | 50ms | - | **150ms** | ✅ Success |
| **Trust (IP healthy)** | - | 50ms | - | **50ms** | ✅ Success ⚡ |
| **Trust (IP dead)** | - | 50ms fail | 50ms | **100ms** | ✅ Success (still faster!) |

**Conclusion:** Trust is faster for reads, even with retry

---

## Code Flow Comparison

### **Upload Flow with Verification**

```
resolveWritableUrl()
    ↓
getHostIP(writeNodeId)
    ↓
Check NodePool → Found: 1.2.3.4:8081
    ↓
Health check (100ms) → ✗ Dead
    ↓
Remove from NodePool
    ↓
Query appUser → get IPs
    ↓
Health check → ✓ 5.6.7.8:8081 is healthy
    ↓
Update NodePool with new IP
    ↓
Return 5.6.7.8:8081
    ↓
Upload succeeds ✅
```

### **Read Flow with Trust**

```
fetchUser(userId)
    ↓
resolveAndUpdateBaseUrl() - Attempt 1
    ↓
Check NodePool → Found: 2.3.4.5:8080
    ↓
Use immediately (no health check)
    ↓
Fetch user data
    ├→ Success: Return user ✅ (fast!)
    └→ Fail: Retry attempt 2
            ↓
            Resolve fresh IP via getProviderIP()
            ↓
            Update NodePool
            ↓
            Fetch succeeds ✅
```

---

## Self-Healing Behavior

### **Both Strategies Include:**

1. **NodePool Updates**
   - Successfully resolved IPs are added to pool
   - Unhealthy IPs removed from pool (getHostIP only)

2. **Automatic Recovery**
   - Failed operations trigger IP re-resolution
   - Fresh healthy IPs replace stale ones

3. **Health Check Caching**
   - Successful checks: 30s TTL
   - Failed checks: 5s TTL
   - Reduces redundant health checks

---

## When Each Strategy Applies

### **`getHostIP` (Verify Health)**
- ✅ `resolveWritableUrl()` → Media uploads
- ✅ `uploadToIPFS()` → File uploads
- ✅ `uploadVideo()` → Video processing
- ✅ Any WRITE operation to hostIds[0]

### **`fetchUser` (Trust Cache)**
- ✅ `fetchUser()` → Load profiles
- ✅ Loading tweet authors → Fast rendering
- ✅ Follow lists → Quick display
- ✅ Search results → Instant response
- ✅ Any READ operation to hostIds[1]

---

## Logging Examples

### **`getHostIP` (Verify)**
```
✓ Found healthy node write-node-123 in NodePool: 1.2.3.4:8081
[1.2.3.4:8081] Using cached result: ✓ healthy (age: 5234ms)
```

**or if unhealthy:**
```
NodePool IP 1.2.3.4:8081 for node write-node-123 is no longer healthy, removing from cache
Removed node write-node-123 from pool (was: [1.2.3.4:8081])
Received 1 IP(s) for node write-node-123: [5.6.7.8:8081]
Found healthy IP for node write-node-123: 5.6.7.8:8081
Updated node write-node-123 - replaced IPs with: 5.6.7.8:8081
```

### **`fetchUser` (Trust)**
```
📡 ATTEMPT 1/2 - Using IP from NodePool: 2.3.4.5:8080 for userId: user-456 (trusted)
```

**or if fetch fails:**
```
📡 ATTEMPT 1/2 - Using IP from NodePool: 2.3.4.5:8080 (trusted)
❌ Network error calling get_user
📡 ATTEMPT 2/2 - Resolving provider IP for userId: user-456, reason: retry attempt
✓ Resolved IP: 6.7.8.9:8080
Updated node read-node-456 - replaced IPs with: 6.7.8.9:8080
```

---

## Summary

| Aspect | `getHostIP` (WRITE) | `fetchUser` (READ) |
|--------|-------------------|-------------------|
| **Strategy** | Verify health | Trust cache |
| **Health check** | Yes (~100ms) | No (0ms) |
| **Remove unhealthy** | Yes | No (retry handles it) |
| **Best case** | 100ms | 0ms ⚡ |
| **Worst case** | 100ms + fresh IP | Failed fetch + fresh IP |
| **Optimized for** | Upload reliability | Read speed |
| **Self-healing** | Proactive (removes bad IPs) | Reactive (retry resolves) |

**Result:** Each operation uses the strategy best suited to its requirements! 🎯✨

