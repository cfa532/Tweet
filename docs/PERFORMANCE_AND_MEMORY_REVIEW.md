# Performance and Memory Review

**Date:** February 2025  
**Scope:** Tweet Android app (Kotlin, Compose, Hilt) — performance, memory usage, and lifecycle.

---

## 1. Summary

The codebase has already been through several optimization passes (see `PERFORMANCE_ANALYSIS.md`, `TWEETLISTVIEW_CRITICAL_FIXES.md`, `MEMORY_LEAK_ANALYSIS.md`, `ALL_MEMORY_FIXES_SUMMARY.md`). This review consolidates findings and adds **actionable improvements**, including two fixes applied in this pass:

- **TweetViewModel:** `onCleared()` now releases all ExoPlayers and clears `playbackPositions` to avoid leaks when leaving tweet detail/item screens.
- **TweetListView:** On dispose, calls `VideoPlaybackCoordinator.shared.clear()` and `VideoManager.cleanupInactivePlayers()` so leaving the feed frees inactive video players promptly.

---

## 2. Performance

### 2.1 Tweet feed (TweetListView) — already optimized

- **Throttled scroll work:** Scroll position save is throttled (1s during scroll, immediate on stop); video preloader uses `derivedStateOf` rounded to every 3 items.
- **O(n) indexing:** Tweet/video list building avoids O(n²) (no `indexOf` in loops).
- **Job cleanup:** `activeJobs` is cleared on dispose; periodic cleanup every 5s reduces churn.
- **Incremental video list:** New tweets are appended to the video list instead of full rebuild when possible.

**Recommendation:** Keep an eye on `LaunchedEffect` keys (e.g. `listState`, `tweets.size`, pagination flags). Avoid keys that change every frame (e.g. raw `layoutInfo`) to prevent unnecessary effect restarts. Current keys are already reasonable.

### 2.2 Other list UIs

- **TweetDetailScreen, ChatScreen, UserListView, SearchScreen, ReplyEditorBox:** Use `LazyColumn`/`LazyRow` with `rememberLazyListState` and `key =` on items. Scroll position restore is implemented where documented (e.g. TweetDetailScreen, UserListView).
- **SystemSettings:** Long `LazyColumn`; consider `key` on items if items are dynamic.

**Recommendation:** When adding new lists, use stable `key` (e.g. entity id) and restore scroll from a store or `rememberSaveable` where appropriate.

### 2.3 Image loading (ImageCacheManager)

- **Bounded:** Memory cache (200 entries, 100MB), disk cache, `MAX_DOWNLOAD_RESULTS = 20`, semaphore for concurrent downloads.
- **Progressive loading:** Low-res preview then full load.
- **Memory guard:** Blocks new downloads when heap usage is high (e.g. 60% of max).

**Recommendation:** Ensure all error and cancellation paths recycle bitmaps and clear large buffers (already covered in `ALL_MEMORY_FIXES_SUMMARY.md`). Run a quick audit after any new image-loading code.

### 2.4 Video (VideoManager, VideoPreview, VideoPlaybackCoordinator)

- **Preload:** Capped (`PRELOAD_AHEAD_COUNT`, `MAX_CONCURRENT_PRELOADS`, semaphore).
- **Disk cache:** 2GB SimpleCache with LRU eviction.
- **Cleanup:** `cleanupInactivePlayers()` releases players not in `activeVideos`/`visibleVideos`. Called from `markVideoInactive()` when `videoPlayers.size > 10 && videoPlayers.size % 5 == 0`, and **now also from TweetListView’s `DisposableEffect` on dispose** so leaving the feed triggers cleanup.

**Recommendation:** Profile under rapid scroll and tab switching to confirm player count stays bounded and no frame drops. `releaseAllVideos()` is available for full cleanup if a screen needs it.

---

## 3. Memory

### 3.1 Fixes applied in this review

| Area | Change |
|------|--------|
| **TweetViewModel** | Override `onCleared()`: call `releaseAllPlayers()` (and clear `playbackPositions`) so audio/video ExoPlayers and state are released when the ViewModel is cleared (e.g. leaving tweet detail or item). |
| **TweetListView** | In `DisposableEffect(Unit)` `onDispose`: call `VideoPlaybackCoordinator.shared.clear()` and `VideoManager.cleanupInactivePlayers()` so leaving the feed releases inactive video players and coordinator state. |

### 3.2 Bounded caches (no change needed)

- **ImageCacheManager:** Memory count/size, download result cap, semaphore.
- **VideoManager:** Disk cache size, preload limits, cleanup of inactive players.
- **HproseClientPool:** Per-URL and total URL limits, idle cleanup.

### 3.3 Unbounded or growth-prone structures

| Component | Storage | Risk | Recommendation |
|-----------|---------|------|-----------------|
| **TweetCacheManager** | `memoryCache`, `userStateFlows` | Grow with tweets/users over long sessions | Optional: add max size + LRU (or time-based) eviction for in-memory maps; document as “unbounded by design” if acceptable. |
| **HlsUrlResolver** | `memCache` (ConcurrentHashMap) | Keyed by base URL; in practice small | Optional: cap size (e.g. 200 entries) if many distinct video servers. |
| **VideoManager** | `videoPlayers`, `activeVideos`, etc. | Bounded by cleanup; now also cleaned on feed dispose | Monitor; consider calling `cleanupInactivePlayers()` on other list disposals (e.g. ChatScreen) if needed. |

### 3.4 Lifecycle and leaks

- **TweetViewModel ExoPlayers:** Previously only released from MediaBrowser; now also released in `onCleared()`, so no leak when leaving tweet detail/item.
- **VideoManager:** `markVideoInactive` + `cleanupInactivePlayers()` plus TweetListView dispose keeps player count in check.
- **ImageCacheManager:** Ensure bitmap recycle and buffer clear on all paths (see existing memory docs).

---

## 4. Coroutines and Compose

- **Scopes:** ViewModels use `viewModelScope`; ImageCacheManager and VideoManager use dedicated scopes (SupervisorJob + IO/Main). Cancellation is respected.
- **Dispatchers:** IO for network/disk; Main for UI. No blocking of Main in hot paths.
- **Compose:** `remember`/`rememberSaveable`/`derivedStateOf` used appropriately in TweetListView and other screens. `LaunchedEffect` keys are generally stable; avoid depending on high-frequency state (e.g. raw scroll offset) in keys.

---

## 5. Suggested order of work (future)

1. **Validate with Android Profiler:** After this pass, confirm TweetViewModel and VideoManager release behavior (no growth in ExoPlayer count when opening/closing many tweet details; feed leave reduces player count).
2. **TweetCacheManager:** If metrics show in-memory tweet/user growth over long sessions, add a cap or eviction policy for `memoryCache` and/or `userStateFlows`.
3. **HlsUrlResolver:** If you ever support a very large number of distinct video base URLs, add a max size for `memCache`.
4. **Other list screens:** If ChatScreen or other LazyColumn screens hold many videos, consider calling `VideoManager.cleanupInactivePlayers()` (and coordinator clear if applicable) in their dispose logic.

---

## 6. Related docs

- `PERFORMANCE_ANALYSIS.md` — TweetListView analysis
- `TWEETLISTVIEW_CRITICAL_FIXES.md` — O(n²) fix, scroll save throttling
- `OPTIMIZATION_SUMMARY.md` — Video preload, job cleanup, incremental lists
- `MEMORY_LEAK_ANALYSIS.md`, `ALL_MEMORY_FIXES_SUMMARY.md` — Image/video/chat memory fixes
- `MEMORY_OPTIMIZATION_GUIDE.md` — Video upload, ZipCompressor
- `ANDROID_PERFORMANCE_MONITORING_GUIDE.md` — Profiler usage

---

## 7. Files touched in this review

| File | Change |
|------|--------|
| `app/.../viewmodel/TweetViewModel.kt` | `onCleared()` calls `releaseAllPlayers()`; `releaseAllPlayers()` also clears `playbackPositions`. |
| `app/.../tweet/TweetListView.kt` | `DisposableEffect` onDispose calls `VideoPlaybackCoordinator.shared.clear()` and `VideoManager.cleanupInactivePlayers()`; added `VideoManager` import. |
