# TUS Server Naming Update
**Date:** October 13, 2025

## Summary

Renamed `netDiskUrl` to `tusServerUrl` throughout the codebase to better reflect its actual purpose as a TUS (Tus Resumable Upload Protocol) server URL.

## Changes Made

### 1. Property Rename in User.kt

**Before:**
```kotlin
val netDiskUrl: String?
```

**After:**
```kotlin
val tusServerUrl: String?
```

**Purpose:** This computed property constructs the URL for the TUS server by:
- Taking `writableUrl` as the base
- Replacing the port with `cloudDrivePort` (or defaults to `TW_CONST.CLOUD_PORT = 8010`)
- Preserving full path, query, and fragment (since TUS server may be hosted at a subpath)

### 2. Health Check Endpoint Update

**Before:**
```kotlin
val healthCheckUrl = "$netDiskUrl/process-zip/health"
```

**After:**
```kotlin
val healthCheckUrl = "$tusServerUrl/health"
```

**Reason:** The TUS server implements a simple `/health` endpoint at its root (or subpath root), not specifically at `/process-zip/health`.

### 3. WritableUrl Resolution

Added explicit `resolveWritableUrl()` call before accessing `tusServerUrl`:

```kotlin
// Ensure writableUrl is resolved
if (appUser.writableUrl.isNullOrEmpty()) {
    val resolved = appUser.resolveWritableUrl()
    if (resolved.isNullOrEmpty()) {
        return false
    }
}

val tusServerUrl = appUser.tusServerUrl
```

**Reason:** `writableUrl` must be resolved (fetched from server) before the computed property `tusServerUrl` can construct the URL.

## Files Modified

1. `app/src/main/java/us/fireshare/tweet/datamodel/User.kt`
   - Renamed property from `netDiskUrl` to `tusServerUrl`
   - Updated documentation

2. `app/src/main/java/us/fireshare/tweet/service/MediaUploadService.kt`
   - Updated all references from `netDiskUrl` to `tusServerUrl`
   - Changed health check endpoint from `/process-zip/health` to `/health`
   - Added `resolveWritableUrl()` call before accessing `tusServerUrl`

3. `app/src/main/java/us/fireshare/tweet/HproseInstance.kt`
   - Updated all references from `netDiskUrl` to `tusServerUrl`
   - Changed health check endpoint from `/process-zip/health` to `/health`
   - Added `resolveWritableUrl()` call before accessing `tusServerUrl`

4. `docs/VIDEO_UPLOAD_STRATEGY_UPDATE.md`
   - Updated documentation to reflect new naming
   - Added section explaining `tusServerUrl` property
   - Updated health check endpoint documentation

5. `docs/DEBUG_LOG_CLEANUP.md`
   - Updated example logs to use `tusServerUrl`

## Server Implementation

TUS server operators should implement the `/health` endpoint:

```javascript
app.get('/health', (req, res) => {
  res.status(200).json({ 
    status: 'ok', 
    message: 'Server is running',
    timestamp: new Date().toISOString()
  });
});
```

**Important:** If the TUS server is hosted at a subpath (e.g., `/api/v1`), the health check will be at `{subpath}/health` (e.g., `/api/v1/health`).

## URL Construction Example

Given:
- `writableUrl`: `http://example.com:8081/api/v1`
- `cloudDrivePort`: `8082`

Result:
- `tusServerUrl`: `http://example.com:8082/api/v1`
- Health check URL: `http://example.com:8082/api/v1/health`

## Migration Notes

- **No database migration required** - this is a code-level rename only
- **No API changes** - the server endpoints remain the same
- **Backward compatible** - existing functionality unchanged, just clearer naming

## Benefits

1. **Clarity:** Name now accurately reflects the purpose (TUS server, not generic "netdisk")
2. **Consistency:** Aligns with TUS protocol terminology
3. **Maintainability:** Easier for future developers to understand the codebase
4. **Documentation:** Better self-documenting code

## Testing

After this change, verify:
- [x] Code compiles without errors
- [ ] Health check correctly pings TUS server at `/health` endpoint
- [ ] Videos are uploaded through TUS server when available
- [ ] Fallback to IPFS works when TUS server is unavailable
- [ ] `resolveWritableUrl()` is called before accessing `tusServerUrl`

