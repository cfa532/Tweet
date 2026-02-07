# HproseClientPool: 8 Clients Per URL (iOS Parity)

## Change Summary

Updated `HproseClientPool` to support **8 clients per URL** instead of 1 client per URL, matching the iOS implementation. This provides better concurrent request handling and improved performance under heavy load.

## What Changed

### Before (1 Client Per URL):
```kotlin
// Single client per URL
private val regularClients = ConcurrentHashMap<String, ClientInfo>()
private val uploadClients = ConcurrentHashMap<String, ClientInfo>()
```

**Limitations:**
- Only 1 client per URL → serialized requests to same server
- Under heavy concurrent load, requests would queue
- No load distribution across multiple connections

### After (8 Clients Per URL):
```kotlin
// Pool of up to 8 clients per URL
private data class ClientPool(
    val clients: MutableList<ClientInfo> = mutableListOf(),
    var nextClientIndex: Int = 0 // Round-robin index
)

private val regularClients = ConcurrentHashMap<String, ClientPool>()
private val uploadClients = ConcurrentHashMap<String, ClientPool>()
```

**Benefits:**
- Up to 8 clients per URL → parallel requests to same server
- Round-robin distribution across clients
- Better handling of concurrent requests
- Matches iOS implementation (8 clients per URL)

## Key Features

### 1. **Pool-Based Architecture**
Each URL now has a pool of up to 8 client instances:
```kotlin
URL: http://125.229.161.122:8080
  ├─ Client 1 (HproseService)
  ├─ Client 2 (HproseService)
  ├─ Client 3 (HproseService)
  ├─ Client 4 (HproseService)
  ├─ Client 5 (HproseService)
  ├─ Client 6 (HproseService)
  ├─ Client 7 (HproseService)
  └─ Client 8 (HproseService)
```

### 2. **Round-Robin Distribution**
Requests are distributed evenly across available clients:
```kotlin
// Request 1 → Client 1
// Request 2 → Client 2
// Request 3 → Client 3
// ...
// Request 9 → Client 1 (wraps around)
```

### 3. **Lazy Client Creation**
Clients are created on-demand up to the limit of 8:
- First request → Creates Client 1
- Second concurrent request → Creates Client 2
- Continues until 8 clients are created
- After that, round-robin across existing 8 clients

### 4. **Independent Pools**
Regular and upload clients maintain separate pools:
- **Regular clients:** 30-second timeout
- **Upload clients:** 50-minute timeout
- Each type can have up to 8 clients per URL

## Configuration

```kotlin
private const val CLIENTS_PER_URL = 8           // Number of clients per URL (matches iOS)
private const val MAX_URLS = 50                 // Maximum number of different URLs
private const val DEFAULT_CLIENT_TIMEOUT = 30_000        // 30 seconds
private const val UPLOAD_CLIENT_TIMEOUT = 3_000_000      // 50 minutes
private const val CLIENT_CLEANUP_INTERVAL_MS = 300_000   // 5 minutes
private const val CLIENT_MAX_IDLE_TIME_MS = 600_000      // 10 minutes
```

## Updated Methods

### `getRegularClient(baseUrl: String)`
- Gets existing pool or creates new one
- Uses round-robin to select client from pool
- Creates new client if pool not full (< 8 clients)
- Returns existing client if pool full

### `getUploadClient(baseUrl: String)`
- Same logic as regular client
- Uses upload timeout (50 minutes)
- Maintains separate pool from regular clients

### `releaseClient(baseUrl: String, isUploadClient: Boolean)`
- Decrements reference count for all clients in pool
- Prepares idle clients for cleanup

### `clearClient(baseUrl: String)`
- Removes entire client pool for a URL
- Clears all 8 clients at once

### `cleanupIdleClients()`
- Removes idle clients from pools (not entire pools)
- Only removes pool entry if all clients are removed
- Resets round-robin index after cleanup

## Performance Benefits

### Concurrent Request Handling
**Before (1 client):**
```
User A → Request 1 ───┐
User B → Request 2 ───┤→ Client 1 → Server (serialized)
User C → Request 3 ───┘
```

**After (8 clients):**
```
User A → Request 1 → Client 1 ┐
User B → Request 2 → Client 2 ├→ Server (parallel)
User C → Request 3 → Client 3 ┘
```

### Expected Improvements
- **Throughput:** Up to 8x for concurrent requests to same server
- **Latency:** Reduced queuing delays under heavy load
- **Scalability:** Better handling of multiple simultaneous users
- **Reliability:** Better connection pooling at HTTP level

## Memory Impact

### Memory Per URL
- **Before:** 1 client × memory per client
- **After:** Up to 8 clients × memory per client

### Total Memory
With typical usage (10 different URLs):
- **Before:** 10 URLs × 1 client = 10 clients
- **After:** 10 URLs × 8 clients = 80 clients (maximum)

**Note:** Clients are created lazily, so actual count depends on concurrent load.

### Mitigation
- Idle cleanup removes unused clients after 10 minutes
- Maximum 50 URLs tracked (400 clients max)
- Reference counting ensures active clients aren't removed

## iOS Parity

This change brings the Android client pool to feature parity with iOS:

| Feature | iOS | Android (Before) | Android (After) |
|---------|-----|------------------|-----------------|
| Clients per URL | 8 | 1 | 8 ✅ |
| Round-robin | ✅ | N/A | ✅ |
| Lazy creation | ✅ | N/A | ✅ |
| Idle cleanup | ✅ | ✅ | ✅ |

## Testing

### Build Status
✅ **Build successful** with no errors or warnings

### Verification Points
1. Multiple concurrent requests should use different clients
2. Round-robin should distribute load evenly
3. Idle clients should be cleaned up after 10 minutes
4. Pool should not exceed 8 clients per URL
5. Log messages should show client pool growth

### Expected Log Output
```
HproseClientPool: Created new regular client for node: http://125.229.161.122:8080 (1/8 clients)
HproseClientPool: Created new regular client for node: http://125.229.161.122:8080 (2/8 clients)
...
HproseClientPool: Reusing regular client for node: http://125.229.161.122:8080 (client 1/8, refs: 5)
```

## Migration Notes

### Backward Compatibility
✅ **Fully backward compatible** - no API changes required

### Existing Code
No changes needed in calling code:
```kotlin
// Same API as before
val client = HproseClientPool.getRegularClient(baseUrl)
```

### Automatic Migration
- First request to URL creates first client
- Subsequent concurrent requests create additional clients
- Existing single-client behavior preserved for low-load scenarios

## Files Modified

- `/app/src/main/java/us/fireshare/tweet/network/HproseClientPool.kt`

## Related Documentation

- [CONNECTION_POOLING_OPTIMIZATION_REPORT.md](CONNECTION_POOLING_OPTIMIZATION_REPORT.md)
- [TECHNICAL_ARCHITECTURE.md](TECHNICAL_ARCHITECTURE.md)

## Summary

This enhancement improves concurrent request handling by allowing up to 8 simultaneous connections per server URL, matching the iOS implementation. The change is transparent to existing code and provides significant performance benefits under heavy concurrent load.

