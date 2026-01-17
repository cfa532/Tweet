# Memory Leak Blacklist Candidates

## 🔴 CRITICAL - Fix Immediately

### 1. Preview Bitmap Leak (ImageCacheManager.kt:415-434)
- **Risk**: 500KB-2MB per failed progressive load
- **Trigger**: Any failed image > 50KB during scrolling
- **Blacklist Priority**: P0

### 2. ByteArray Memory Retention (ImageCacheManager.kt:407-468)
- **Risk**: 1MB-10MB per failed download
- **Trigger**: Network errors, OOM during download
- **Blacklist Priority**: P0

### 3. MediaMetadataRetriever Leak (ChatScreen.kt:1148-1158)
- **Risk**: 5-10MB native memory per failed thumbnail
- **Trigger**: Video preview failures in chat
- **Blacklist Priority**: P0

---

## ⚠️ HIGH - Fix Soon

### 4. Orphaned downloadResults Entries (ImageCacheManager.kt:563-616)
- **Risk**: 2-5MB per orphaned bitmap, accumulates over time
- **Trigger**: Failed downloads + app crashes before cleanup
- **Blacklist Priority**: P1

### 5. Failed Bitmap Decode Leak (ImageCacheManager.kt:352-357, 446-451)
- **Risk**: 1-5MB per corrupt/invalid image
- **Trigger**: Corrupt image data from network
- **Blacklist Priority**: P1

---

## 📋 MEDIUM - Monitor

### 6. Repeated OutOfMemoryError (ImageCacheManager.kt:358-361, 452-455)
- **Risk**: Cascading OOM errors
- **Trigger**: Low memory + heavy image loading
- **Blacklist Priority**: P2

### 7. Cancellation Race Condition (ImageCacheManager.kt:620-645)
- **Risk**: Minor counter inconsistencies
- **Trigger**: Rapid scroll cancellations
- **Blacklist Priority**: P3

---

## 📊 Quick Stats

| Priority | Issues | Total Risk | Estimated Leak/Session |
|----------|--------|------------|------------------------|
| P0       | 3      | CRITICAL   | 50-150MB               |
| P1       | 2      | HIGH       | 20-50MB                |
| P2-P3    | 2      | MEDIUM     | 5-20MB                 |
| **TOTAL**| **7**  | **HIGH**   | **75-220MB**          |

---

## 🎯 Recommended Actions

1. ✅ Apply fixes from `MEMORY_LEAK_ANALYSIS.md` Issues #1, #2, #3
2. ⚠️ Add LeakCanary to debug builds
3. 📊 Add memory monitoring to production (Firebase Performance)
4. 🧪 Run memory stress tests before release

---

**Last Updated**: January 10, 2026  
**Files Analyzed**: ImageCacheManager.kt, ChatScreen.kt, VideoManager.kt  
**Status**: ACTIVE - Awaiting Implementation
