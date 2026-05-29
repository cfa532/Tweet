# Engineering Notes

This document preserves high-value engineering context from historical optimization and fix passes.

Use it as background context. For current behavior, treat core docs (architecture, video pipeline, network, memory) as source of truth.

## 1) Performance Strategy

### Core observation

Long-session regressions were driven more by **resource accumulation** (players, observers, timers, network work) than by one-off layout issues.

### Practical strategy

- Coordinate playback decisions centrally (what should play now).
- Coordinate loading/preload decisions centrally (what should load now).
- Keep preload windows small and directional.
- Cancel stale/off-screen work aggressively.
- Prioritize foreground user intent over background preloads.

### Signals of healthy behavior

- stable memory over long browsing sessions
- fewer stalls under weak network conditions
- reduced timer/observer fan-out
- fewer duplicate network requests

## 2) Memory and Cleanup Fix Themes

Recurring fix themes across memory-related documents:

- Ensure full player teardown (not pause-only) in cleanup paths.
- Remove completed/failed async tasks from tracking maps.
- Guarantee temporary file cleanup in failure/cancellation paths.
- Tune cache release behavior to avoid clear/reload thrash.
- Protect high-value UI assets (for example avatars) during partial cache release.

## 3) Tweet List and Media Grid Optimization Themes

Recurring themes from TweetListView and MediaGrid notes:

- replace repeated O(n^2) indexing/lookup with O(1) patterns where possible
- reduce redundant recomposition/state churn
- stabilize item heights/aspect ratios to avoid layout jumps
- defer or batch expensive work during rapid scroll

## 4) Video / HLS / Bitrate Evolution Themes

Historical notes repeatedly covered:

- HLS/progressive decision and fallback behavior
- bitrate normalization and variant selection consistency
- fullscreen playback isolation and lifecycle handling
- ExoPlayer resilience under codec/stream edge cases

These details should be reflected in current canonical docs instead of split across many one-off fix notes.

## 5) Upgrade / Versioning Documentation Consolidation

Multiple older docs covered similar upgrade/version topics (flow, quick references, validation, fixes).
Current docs should prefer one main flow doc plus one implementation detail doc.

## 6) Recommended Reading Order (Current)

1. `./QUICK_START_GUIDE.md`
2. `./TECHNICAL_ARCHITECTURE.md`
3. `./VIDEO_LOADING_ALGORITHM.md`
4. `./PERFORMANCE_AND_MEMORY_REVIEW.md`
5. `./NETWORK_CONSOLIDATION_2025.md`
6. `./UNIFIED_UPGRADE_FLOW.md`

