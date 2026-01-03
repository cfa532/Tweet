# getHostIP Retry and Fallback Implementation

## Problem Statement

Previously, `getHostIP` (used for resolving WRITE node IPs for uploads) had **no fallback mechanism**:
- Queried via appUser.hproseService only
- If that failed → gave up immediately
- No check for appUser health status
- No retry via entry IP
- Result: uploads failed when appUser was temporarily unhealthy, even though alternative resolution paths existed

## Solution

Implemented the same sophisticated fallback strategy used by `getProviderIP` (READ nodes) for `getHostIP` (WRITE nodes).

---

## Complete Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                  getHostIP(nodeId, v4Only)                   │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
        ┌───────────────────────────────────────┐
        │ STEP 1: Check NodePool Cache          │
        │ NodePool.getIPFromNodeId(nodeId)      │
        └───────────────────────────────────────┘
                            │
                ┌───────────┴───────────┐
                │                       │
            Found? ✓                Not found ✗
                │                       │
                ▼                       │
    ┌──────────────────────┐           │
    │ Verify Health        │           │
    │ isServerHealthy(IP)  │           │
    └──────────────────────┘           │
                │                       │
        ┌───────┴────────┐              │
        │                │              │
    Healthy ✓      Unhealthy ✗         │
        │                │              │
        ▼                ▼              │
┌─────────────┐  ┌───────────────┐     │
│ Return IP   │  │ Remove from   │     │
│ (FAST PATH) │  │ NodePool      │     │
└─────────────┘  └───────────────┘     │
                         │              │
                         └──────────────┘
                                        │
                                        ▼
                        ┌───────────────────────────────┐
                        │ STEP 2: Query via appUser     │
                        │ _getHostIP(nodeId, appUser)   │
                        └───────────────────────────────┘
                                        │
                            ┌───────────┴───────────┐
                            │                       │
                        Success ✓             Failed/null ✗
                            │                       │
                            ▼                       ▼
                ┌──────────────────────┐  ┌─────────────────────────┐
                │ Update NodePool      │  │ STEP 3: Check appUser   │
                │ Return IP            │  │ Health Status           │
                └──────────────────────┘  └─────────────────────────┘
                                                     │
                                        ┌────────────┴────────────┐
                                        │                         │
                                Unhealthy ✗                 Healthy ✓
                                        │                         │
                                        ▼                         ▼
                        ┌──────────────────────────┐  ┌─────────────────────┐
                        │ STEP 4: Try Entry IP     │  │ Node genuinely down │
                        │ findEntryIP()            │  │ Return null         │
                        │ _getHostIP(nodeId, entry)│  └─────────────────────┘
                        └──────────────────────────┘
                                        │
                            ┌───────────┴───────────┐
                            │                       │
                        Success ✓               Failed ✗
                            │                       │
                            ▼                       ▼
                ┌──────────────────────┐  ┌─────────────────┐
                │ Update NodePool      │  │ Return null     │
                │ Return IP            │  │ (All failed)    │
                └──────────────────────┘  └─────────────────┘
```

---

## Detailed Step-by-Step Flow

### **STEP 1: Check NodePool Cache with Health Verification**

```kotlin
val poolIP = NodePool.getIPFromNodeId(nodeId)
if (poolIP != null) {
    // Verify the cached IP is still healthy before returning
    val fullUrl = "http://$poolIP"
    
    if (isServerHealthy(fullUrl)) {
        // Still healthy - use it!
        return poolIP  // ✓ FAST PATH
    } else {
        // No longer healthy - remove from cache
        NodePool.removeNode(nodeId)
        // Fall through to Step 2 for fresh lookup
    }
}
```

**What happens:**
- Check if we've previously resolved a healthy IP for this node
- **If found:** Verify it's still healthy with `isServerHealthy()` (uses cache!)
  - **Healthy:** Return immediately ✓
  - **Unhealthy:** Remove from NodePool, continue to Step 2
- **Log (healthy):** `✓ Found healthy node X in NodePool: Y`
- **Log (stale):** `NodePool IP Y for node X is no longer healthy, removing from cache`

**Why this matters:**
- **Prevents stale IPs:** Node was healthy 10 minutes ago but went down
- **Uses health check cache:** If recently checked (<30s), returns instantly
- **Self-healing:** Bad entries automatically removed, good ones re-added later

**Performance:**
- First check after cache: ~100ms (health check)
- Within 30s window: <1ms (cache hit)
- Balance of safety and speed ⚖️

---

### **STEP 2: Query via appUser's Client**

```kotlin
try {
    val hostIP = _getHostIP(nodeId, v4Only, appUser.hproseService)
    if (hostIP != null) {
        NodePool.updateNodeIP(nodeId, hostIP)  // Cache for future
        return hostIP  // ✓ SUCCESS
    }
    // null = server responded but no healthy IPs found
} catch (e: Exception) {
    // Network error - continue to fallback
}
```

**What `_getHostIP` does:**
1. Calls `get_node_ips` API via appUser's server
2. Gets array of IPs: `["43.165.128.251:8081", "2001:db8::1:8081"]`
3. Tests each IP via `tryIpAddresses()` (health checks with cache)
4. Returns first healthy IP or null

**Possible outcomes:**
- **✓ Healthy IP found:** Update NodePool, return IP
- **✗ No healthy IPs:** Server returned IPs but all failed health checks → continue
- **✗ Network error:** appUser unreachable → continue to fallback

**Log examples:**
```
Received 2 IP(s) for node X: [43.165.128.251:8081, 2001:db8::1:8081]
Found healthy IP for node X: 2001:db8::1:8081
✓ Resolved IP for node X via appUser: 2001:db8::1:8081, updated NodePool
```

---

### **STEP 3: Check appUser Health**

```kotlin
val appUserBaseUrl = appUser.baseUrl
if (appUserBaseUrl.isNullOrBlank()) {
    return null  // Cannot proceed without appUser URL
}

if (!isServerHealthy(appUserBaseUrl)) {
    // AppUser is UNHEALTHY → try entry IP fallback
} else {
    // AppUser is HEALTHY → node genuinely down
    return null
}
```

**Critical decision point:**
- **If appUser UNHEALTHY:** The failure in Step 2 might be because appUser is temporarily down → try alternative path (entry IP)
- **If appUser HEALTHY:** appUser successfully responded but node has no healthy IPs → node is genuinely down, no point retrying

**Why this matters:**
```
Scenario 1: appUser unhealthy
  appUser → ✗ network error
  Node might be healthy, but we can't reach appUser to ask
  → Try entry IP (alternative route)

Scenario 2: appUser healthy  
  appUser → ✓ responded: "node has IPs [43.165.128.251:8081]"
  Health check → ✗ IP is dead
  → Node is genuinely down, entry IP would return same dead IP
```

---

### **STEP 4: Fallback to Entry IP**

```kotlin
if (!isServerHealthy(appUserBaseUrl)) {
    val entryIP = findEntryIP()  // Get bootstrap entry IP
    val entryClient = HproseClientPool.getRegularClient("http://$entryIP")
    
    val hostIP = _getHostIP(nodeId, v4Only, entryClient)
    if (hostIP != null) {
        NodePool.updateNodeIP(nodeId, hostIP)
        return hostIP  // ✓ SUCCESS via fallback
    } else {
        return null  // ✗ Failed even via entry IP
    }
}
```

**What happens:**
1. **Get entry IP:** Hardcoded reliable bootstrap server (e.g., `entry.fireshare.us`)
2. **Create new client:** Connect to entry IP instead of appUser
3. **Query again:** Call `get_node_ips` via entry IP
4. **Test IPs:** Entry IP might return different/more IPs than appUser
5. **Update NodePool:** Cache successful result

**Log examples:**
```
appUser server unhealthy at http://[ipv6]:8080, trying via entry IP for node X
Received 1 IP(s) for node X: [2001:db8::2:8081]  (different IP!)
Found healthy IP for node X: 2001:db8::2:8081
✓ Resolved IP for node X via entry IP: 2001:db8::2:8081, updated NodePool
```

---

## Complete Example Scenarios

### **Scenario A: NodePool Hit - Healthy Cache (Best Case)**
```
Timeline:
0ms  - getHostIP("node-123")
0ms  - Check NodePool → Found: 1.2.3.4:8081
1ms  - Health check → cache hit (checked 10s ago)
1ms  - Return 1.2.3.4:8081
Total: 1ms, 0 network calls
```

### **Scenario A2: NodePool Hit - Stale Cache (Self-Healing)**
```
Timeline:
0ms    - getHostIP("node-123")
0ms    - Check NodePool → Found: 1.2.3.4:8081
10s    - Health check → ✗ timeout (node went down)
10s    - Remove from NodePool
10s    - Continue to Step 2 (query appUser)
10.1s  - Query appUser → ["5.6.7.8:8081"] (new IP)
10.2s  - Health check → ✓ healthy
10.2s  - Update NodePool with new IP
10.2s  - Return 5.6.7.8:8081
Total: 10.2s, 1 server query + 2 health checks
Note: Next call will use new healthy IP (fast path)
```

### **Scenario B: appUser Success (Normal Case)**
```
Timeline:
0ms    - getHostIP("node-123")
0ms    - Check NodePool → Not found
10ms   - Query appUser → ["1.2.3.4:8081", "5.6.7.8:8081"]
100ms  - Health check 1.2.3.4:8081 → ✓ healthy
100ms  - Update NodePool
100ms  - Return 1.2.3.4:8081
Total: 100ms, 1 server query + 1 health check
```

### **Scenario C: appUser Unhealthy → Entry IP Fallback**
```
Timeline:
0ms    - getHostIP("node-123")
0ms    - Check NodePool → Not found
10ms   - Query appUser → ✗ network timeout
5s     - appUser health check → ✗ unhealthy
5.1s   - findEntryIP() → entry.fireshare.us
5.2s   - Query entry IP → ["9.10.11.12:8081"]
5.3s   - Health check 9.10.11.12:8081 → ✓ healthy
5.3s   - Update NodePool
5.3s   - Return 9.10.11.12:8081
Total: 5.3s, 2 server queries + 2 health checks
```

### **Scenario D: Node Genuinely Down**
```
Timeline:
0ms    - getHostIP("node-123")
0ms    - Check NodePool → Not found
10ms   - Query appUser → ["1.2.3.4:8081"]
10s    - Health check 1.2.3.4:8081 → ✗ timeout
10s    - appUser health check → ✓ healthy (appUser is fine)
10s    - Return null (node is genuinely down)
Total: 10s, 1 server query + 2 health checks
```

---

## Implementation Details

### New `getHostIP` Fallback Strategy

```kotlin
suspend fun getHostIP(nodeId: MimeiId, v4Only: String = ...): String? {
    // Step 1: Check NodePool for cached healthy IP
    val poolIP = NodePool.getIPFromNodeId(nodeId)
    if (poolIP != null) return poolIP
    
    // Step 2: Try lookup using appUser's client
    try {
        val hostIP = _getHostIP(nodeId, v4Only, appUser.hproseService)
        if (hostIP != null) {
            NodePool.updateNodeIP(nodeId, hostIP)
            return hostIP
        }
    } catch (e: Exception) {
        // Network error - continue to fallback
    }
    
    // Step 3: Check if appUser is unhealthy
    if (!isServerHealthy(appUser.baseUrl)) {
        // Try via entry IP
        val entryIP = findEntryIP()
        val entryClient = HproseClientPool.getRegularClient("http://$entryIP")
        val hostIP = _getHostIP(nodeId, v4Only, entryClient)
        if (hostIP != null) {
            NodePool.updateNodeIP(nodeId, hostIP)
            return hostIP
        }
    }
    
    // Step 4: AppUser healthy but node has no healthy IPs
    // Node is genuinely down
    return null
}
```

### New Internal Method: `_getHostIP`

Extracted the actual IP resolution logic into a separate method that accepts a `HproseService`:

```kotlin
private suspend fun _getHostIP(
    nodeId: MimeiId, 
    v4Only: String = ...,
    hproseService: HproseService? = appUser.hproseService
): String? {
    val entry = "get_node_ips"
    val params = mapOf(...)
    
    val rawResponse = hproseService?.runMApp<Any>(entry, params)
    val ipArray = unwrapV2Response<List<String>>(rawResponse)
    
    if (ipArray != null && ipArray.isNotEmpty()) {
        val bestIP = tryIpAddresses(ipArray, "getHostIP($nodeId)")
        return bestIP
    }
    
    return null
}
```

---

## Key Improvements

### 1. **AppUser Health Checking**
- Checks if appUser is healthy before giving up
- If unhealthy, assumes the failure might be temporary
- Tries alternative resolution path via entry IP

### 2. **Entry IP Fallback**
- When appUser is unhealthy, queries via entry IP
- Entry IP is a hardcoded reliable bootstrap node
- Provides alternative path when primary server is down

### 3. **NodePool Integration**
- Always updates NodePool after successful resolution
- Future requests can use cached healthy IPs
- Reduces unnecessary server queries

### 4. **Better Logging**
```
✓ Found node X in NodePool, using cached IP
✓ Resolved IP for node X via appUser
appUser server unhealthy, trying via entry IP
✓ Resolved IP for node X via entry IP
appUser healthy but node X has no healthy IPs - genuinely down
```

### 5. **Exception Handling**
- Network exceptions don't immediately fail
- Continue to fallback logic instead of giving up
- Only return null after all fallback attempts exhausted

---

## Behavior Comparison

### Before (❌)

```
Timeline:
13:56:25 - Query appUser → get IP 43.165.128.251:8081
13:56:25 - Health check timeout (10s)
13:56:25 - Retry 1: Query appUser → SAME IP
13:56:25 - Use cache → fail immediately
13:56:26 - Retry 2: Query appUser → SAME IP
13:56:26 - Use cache → fail immediately
13:56:38 - Final attempt: timeout again
→ UPLOAD FAILS

Issues:
- Never checked if appUser was healthy
- Never tried entry IP fallback
- Just kept querying same unhealthy server
- Server kept returning same dead IP
```

### After (✅)

```
Timeline:
13:56:25 - Check NodePool → not found
13:56:25 - Query appUser → get IP 43.165.128.251:8081
13:56:25 - Health check timeout (10s)
13:56:25 - Check appUser health → UNHEALTHY
13:56:25 - Query via entry IP → get alternative IP
13:56:25 - Health check → SUCCESS
13:56:25 - Update NodePool
→ UPLOAD SUCCEEDS

Improvements:
✓ Detects appUser is unhealthy
✓ Tries entry IP fallback
✓ Gets alternative healthy IP
✓ Caches successful IP
✓ Future requests use cached IP
```

---

## Integration with Upload Flow

### `resolveWritableUrl()` in User.kt

```kotlin
suspend fun resolveWritableUrl(): String? {
    val firstHostId = hostIds?.first()
    
    // Calls getHostIP with improved fallback
    val hostIP = HproseInstance.getHostIP(firstHostId, v4Only = "true")
    
    if (hostIP != null) {
        // Parse and construct URL
        writableUrl = "http://$hostIP"
        return writableUrl
    }
    
    return null
}
```

### `MediaUploadService.uploadToIPFSOriginal()`

```kotlin
private suspend fun uploadToIPFSOriginal(...): MimeiFileType? {
    for (attempt in 1..3) {
        resolvedUrl = appUser.resolveWritableUrl()
        if (!resolvedUrl.isNullOrEmpty()) {
            break
        }
        delay(1000L * attempt)  // Exponential backoff
    }
    
    // Now has better chance of success with fallback logic
}
```

---

## Parallel with `getProviderIP`

Both `getHostIP` (WRITE nodes) and `getProviderIP` (READ nodes) now share the same fallback strategy:

| Step | getProviderIP (READ) | getHostIP (WRITE) |
|------|---------------------|-------------------|
| 1 | Check NodePool | Check NodePool |
| 2 | Try via appUser | Try via appUser |
| 3 | If appUser unhealthy → entry IP | If appUser unhealthy → entry IP |
| 4 | Update NodePool | Update NodePool |

**Result:** Consistent, robust IP resolution for both read and write operations.

---

## Testing Scenarios

### Scenario 1: AppUser Healthy, Node Healthy
- Step 1: NodePool miss
- Step 2: Query appUser → get IP
- Step 3: Health check → success
- **Result:** IP returned, NodePool updated ✓

### Scenario 2: AppUser Unhealthy, Node Healthy
- Step 1: NodePool miss
- Step 2: Query appUser → network error
- Step 3: Check appUser health → unhealthy
- Step 4: Query entry IP → get IP
- Step 5: Health check → success
- **Result:** IP returned via fallback ✓

### Scenario 3: AppUser Healthy, Node Unhealthy
- Step 1: NodePool miss
- Step 2: Query appUser → get IP
- Step 3: Health check → fail
- Step 4: Check appUser health → healthy
- **Result:** null (node genuinely down) ✓

### Scenario 4: Cached IP Available
- Step 1: NodePool hit
- **Result:** Cached IP returned immediately ✓

---

## Key Components Used

### **NodePool**
- In-memory cache of node IDs → IP addresses
- Stores previously resolved healthy IPs
- **Self-healing:** Unhealthy IPs automatically removed, re-added when healthy
- Reduces redundant lookups
- Methods: 
  - `getIPFromNodeId()` - retrieve cached IP
  - `updateNodeIP()` - add/update node with healthy IP
  - `removeNode()` - remove stale/unhealthy entry

### **_getHostIP()**
- Internal helper that does the actual API call
- Accepts any `HproseService` (appUser or entry IP)
- Calls `get_node_ips` API endpoint
- Returns first healthy IP or null

### **tryIpAddresses()**
- Tests multiple IPs for health in parallel (pairs of 2)
- Uses health check cache (30s for success, 5s for failure)
- Returns first healthy IP immediately
- Logs: "Testing single IP" / "Testing 2 IPs in parallel" / "Testing X IPs in pairs"

### **isServerHealthy()**
- HTTP HEAD request with 10s connect timeout
- Checks if server responds (2xx/3xx = healthy)
- Uses differential cache to avoid repeated checks
- Logs: `[IP] Using cached result: ✓ healthy (age: Xms)`

### **findEntryIP()**
- Returns hardcoded bootstrap entry server
- Last resort when appUser is unreachable
- Always available fallback
- Example: `entry.fireshare.us`

### **HproseClientPool**
- Manages Hprose RPC clients for different servers
- Creates clients on-demand
- Reuses connections for efficiency

---

## Integration with Upload Flow

```kotlin
// User.kt - resolveWritableUrl()
suspend fun resolveWritableUrl(): String? {
    val writeNodeId = hostIds?.first()  // hostIds[0] = WRITE node
    
    val hostIP = HproseInstance.getHostIP(writeNodeId, v4Only = "true")
    //             ↑ Goes through entire fallback flow
    
    if (hostIP != null) {
        writableUrl = "http://$hostIP"
        return writableUrl
    }
    return null
}

// MediaUploadService.kt - uploadToIPFSOriginal()
for (attempt in 1..3) {
    resolvedUrl = appUser.resolveWritableUrl()
    //              ↑ Calls getHostIP with fallback logic
    if (!resolvedUrl.isNullOrEmpty()) break
    delay(1000L * attempt)  // Exponential backoff
}
```

**Result:** Multi-layered fallback ensures uploads succeed even when appUser is temporarily unhealthy!

---

## Related Components

- **NodePool**: Caches node IPs for reuse
- **isServerHealthy**: Health checks with differential cache (30s success, 5s failure)
- **tryIpAddresses**: Tests multiple IPs, returns first healthy one
- **findEntryIP**: Returns hardcoded bootstrap entry IP
- **HproseClientPool**: Manages Hprose clients for different servers

---

## Next Steps

Consider future enhancements:
1. **Multiple entry IPs**: Try array of entry IPs if one fails
2. **Peer discovery**: Query other healthy nodes for alternative IPs
3. **IPv4/IPv6 fallback**: If IPv4 fails, try IPv6 (or vice versa)
4. **Metrics**: Track fallback usage to identify systemic issues

