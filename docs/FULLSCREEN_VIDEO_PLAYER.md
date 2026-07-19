# Fullscreen Video Playback

## Overview

Android fullscreen video playback is an independently managed playback surface. The current route is:

1. `MediaItemView` prepares the video-list context and navigates to `MediaViewer`.
2. `MediaBrowser` renders `IndependentFullScreenPlayer` for video media.
3. `IndependentFullScreenPlayer` owns the fullscreen UI and observes `FullScreenPlayerManager.playerFlow`.
4. `FullScreenPlayerManager` owns the active `ExoPlayer`, playlist progression, audio policy, recovery monitoring, and cleanup.
5. `VideoManager` suspends feed work and can transfer a prepared player out of feed ownership.

`IndependentFullScreenPlayer` is the active fullscreen implementation used by `MediaBrowser`. The overloads in `FullScreenVideoPlayer.kt` are legacy/alternate components and are not the primary `MediaBrowser` path described here.

## Ownership Model

Fullscreen playback must have one player owner. Android implements that rule in one of two ways:

- Claim a prepared player from `VideoManager` and move it into `FullScreenPlayerManager` ownership.
- Create a new fullscreen-owned player when no claimable player exists.

A claimed player is not left inside the feed cache. `VideoManager.takePlayerForFullScreen()`:

- removes the player from `videoPlayers`;
- cancels its preload work;
- removes active, visible, preloaded, and coordinator tracking;
- clears player-access and preload metadata;
- increments the player generation so stale feed composables re-evaluate;
- clears the old video surface; and
- pauses playback before returning the player to fullscreen.

This is an ownership transfer, not shared access. Feed and fullscreen controllers do not intentionally drive the same `ExoPlayer` at the same time.

## Entry Flow

When a user taps a video, `MediaItemView` builds `MediaViewerParams` and prepares the fullscreen list:

- Feed and comment video surfaces synchronize their coordinator list to `FullScreenPlayerManager`, then stop coordinator-driven playback.
- The main video in tweet detail is intentionally outside the comments coordinator, so it seeds fullscreen from the exact media payload.
- Feed/preload work is suspended for the selected video.
- The selected video is paused before navigation.
- Navigation opens `NavTweet.MediaViewer`.

`MediaBrowser` verifies that the manager's list contains the tapped video. If it does, that cross-surface list is used. Otherwise, it falls back to the video items carried in `MediaViewerParams`.

## Player Acquisition

`FullScreenPlayerManager.playCurrentVideo()` performs the following sequence:

1. Protect the current video and up to two neighboring videos from cleanup.
2. Mark the current media ID as fullscreen-owned.
3. Suspend feed preloading and pause competing feed players.
4. Pause any cached player stored under the current media ID.
5. Try `VideoManager.takePlayerForFullScreen(videoMid)`.
6. If a prepared player is claimed, prepare it and switch fullscreen to it.
7. Otherwise, resolve the media URL and create a new fullscreen player.

The fallback player uses fullscreen-specific buffering values:

- minimum buffer: 30 seconds;
- maximum buffer: 30 seconds;
- initial playback buffer: 1.5 seconds;
- rebuffer playback threshold: 8 seconds; and
- no feed-style video width, height, or bitrate cap.

HLS URLs are resolved before player creation so the fullscreen path does not repeatedly guess playlist filenames.

## Switching Videos

`FullScreenPlayerManager.switchToPlayer()` is the single ownership boundary for an active fullscreen player. It:

- clears the previous fullscreen media marker;
- marks the new media ID as fullscreen-owned;
- removes the old auto-advance listener;
- releases the old player when it differs from the incoming player;
- publishes the new player through `playerFlow`;
- forces `volume = 1f`;
- sets `playWhenReady = true`;
- installs the auto-advance listener;
- starts progress monitoring; and
- warms the next video's cache/metadata.

Only one fullscreen `ExoPlayer` is kept active. Warming the next item does not create a second fullscreen player.

## Audio Policy

Fullscreen playback is always audible unless the device or system audio state prevents output.

- A claimed player is set to `volume = 1f` before it becomes active.
- Every player is set to `volume = 1f` again at the final switch boundary.
- The feed's saved speaker-mute preference is not written by fullscreen.

The current implementation does not restore a claimed player's previous volume on exit. The fullscreen player is released during cleanup, and the feed later acquires its own player using the feed mute policy.

## Controls and Buffering UI

`IndependentFullScreenPlayer` uses Media3's native `PlayerView` controls:

- `useController = true`;
- `controllerAutoShow = false`;
- tapping the player surface lets `PlayerView` show or hide its controls;
- tapping play or pause is handled by the native controller;
- controller visibility drives the tweet-information overlay; and
- the overlay includes close, next, and previous actions.

The information overlay starts visible and its Compose state is set to hidden after three seconds. Native `PlayerView` retains its own controller timeout behavior; its visibility callback updates the same overlay state whenever the native controller shows or hides. The player uses `SHOW_BUFFERING_WHEN_PLAYING`, so the native buffering indicator is tied to active playback rather than a normal paused state.

## Gestures and Presentation

Fullscreen presentation:

- hides system bars;
- allows device rotation while active;
- restores system bars on exit; and
- locks orientation back to portrait when disposed.

Vertical gestures provide navigation and dismissal:

- a large downward drag closes fullscreen;
- an upward drag advances to the next video when multiple videos are available;
- a single-video fullscreen closes after a qualifying vertical gesture; and
- smaller downward drags snap back.

The video follows the drag with translation and scales down to a minimum of `0.8f` for visual feedback.

## Playlist Behavior

The fullscreen playlist can come from the active video coordinator or from the exact media payload passed through navigation.

- `playNextVideo()` advances until the end of the list; reaching the end stops playback.
- `playPreviousVideo()` moves backward and wraps from the first item to the last.
- Natural playback completion automatically calls `playNextVideo()` unless a manual navigation transition is already in progress.
- The manager protects the current item and two neighbors on each side from cleanup.
- Only the next video's cache and metadata are warmed.

## Slow-Network Recovery

Slow IPFS buffering is treated as normal while playback or buffered data continues to advance.

The progress monitor checks every two seconds. It waits while either playback position or buffered position makes progress. If the player intends to play but neither value advances for 15 seconds, the manager nudges the existing player by seeking to its current position, preparing it if idle, and restoring `playWhenReady = true`.

The monitor does not recreate a player merely because it is buffering. Player creation is reserved for the initial fallback path, video changes, or explicit player-error recovery elsewhere in the playback stack.

## App Lifecycle

When the app enters `ON_PAUSE` or `ON_STOP`, fullscreen records whether playback should resume, then pauses the active player.

On `ON_START` or `ON_RESUME`, playback resumes only when it was active before the lifecycle pause and the video has not ended.

## Exit and Cleanup

Disposing `IndependentFullScreenPlayer` pauses the active player and calls `FullScreenPlayerManager.cleanup()`.

Cleanup:

- removes the auto-advance listener;
- pauses and stops the player;
- clears its video surface and media items;
- releases the player;
- cancels progress monitoring;
- clears `playerFlow`;
- clears fullscreen ownership and protected-video markers;
- clears playlist, URL, index, callback, and loading state; and
- invalidates pending loads by incrementing the load generation.

The fullscreen player is not returned to the feed. When navigation exposes the feed again, feed composables reacquire or create players under normal feed ownership and apply the saved feed mute preference.

## Source Map

- `app/src/main/java/us/fireshare/tweet/tweet/MediaItemView.kt`: tap handling, list synchronization, feed stop, and navigation.
- `app/src/main/java/us/fireshare/tweet/widget/MediaBrowser.kt`: media-route resolution and `IndependentFullScreenPlayer` presentation.
- `app/src/main/java/us/fireshare/tweet/widget/IndependentFullScreenPlayer.kt`: native controls, overlay, gestures, lifecycle, rotation, and disposal.
- `app/src/main/java/us/fireshare/tweet/widget/FullScreenPlayerManager.kt`: exclusive player ownership, loading, audio, playlist, monitoring, and cleanup.
- `app/src/main/java/us/fireshare/tweet/widget/VideoManager.kt`: feed suspension, player claiming, generation invalidation, and fullscreen protection.

## Invariants

When changing fullscreen playback, preserve these invariants:

1. A fullscreen player has one active owner.
2. A claimed player is removed from feed tracking before fullscreen uses it.
3. Feed and preload activity do not compete with the active fullscreen target.
4. Fullscreen playback sets its own audible volume without changing the feed mute preference.
5. Pausing through native controls does not represent buffering.
6. Stale asynchronous loads cannot replace the current fullscreen video.
7. Cleanup releases fullscreen resources and clears ownership markers.
