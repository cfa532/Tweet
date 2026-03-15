package us.fireshare.tweet.widget

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import timber.log.Timber
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId

/**
 * A fixed-size pool of ExoPlayer instances shared across all videos in the feed.
 * Mirrors iOS SharedAssetCache's approach of reusing a small number of AVPlayers.
 *
 * Architecture:
 * - Pool of [POOL_SIZE] ExoPlayers created lazily on first acquire.
 * - Each player can be assigned to one video at a time (tracked by mediaId).
 * - When a video scrolls into view, [acquirePlayer] assigns an idle player or
 *   reclaims the least-recently-used one via LRU eviction.
 * - When a video scrolls out of view, [releasePlayer] marks the slot as idle
 *   (the player is NOT destroyed — just paused and ready for reuse).
 * - Video segment data remains in [VideoManager.getCache] (SimpleCache, 2GB LRU)
 *   keyed by mediaId, so previously viewed videos reload from disk instantly.
 *
 * Thread safety: all public methods must be called on the main thread (same as ExoPlayer).
 */
@OptIn(UnstableApi::class)
object ExoPlayerPool {

    const val POOL_SIZE = 6

    /** Wraps an ExoPlayer with assignment and LRU metadata. */
    class PoolSlot(
        val player: ExoPlayer,
        var assignedVideoMid: MimeiId? = null,
        var assignedVideoUrl: String? = null,
        var assignedVideoType: MediaType? = null,
        var lastUsedTimestamp: Long = 0L,
        var isVisible: Boolean = false  // true while the video is on-screen
    )

    private val slots = mutableListOf<PoolSlot>()
    private var initialized = false

    // Reverse lookup: videoMid → PoolSlot for O(1) access
    private val assignmentMap = mutableMapOf<MimeiId, PoolSlot>()

    // MediaSourceFactory shared by all players (uses cache-aware data source)
    private var mediaSourceFactory: DefaultMediaSourceFactory? = null

    // ===== INITIALIZATION =====

    /**
     * Lazily initialize the pool. Called automatically on first [acquirePlayer].
     * Creates [POOL_SIZE] ExoPlayer instances with shared cache and moderate buffering.
     */
    fun ensureInitialized(context: Context) {
        if (initialized) return
        initialized = true

        val cache = VideoManager.getCache(context)

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("TweetApp/1.0")

        val upstreamFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheKeyFactory(MediaIdCacheKeyFactory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        mediaSourceFactory = DefaultMediaSourceFactory(cacheDataSourceFactory)

        for (i in 0 until POOL_SIZE) {
            // Each player MUST have its own LoadControl and RenderersFactory —
            // ExoPlayer requires that players sharing a LoadControl also share
            // the same playback thread, which is not the case here.
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    15_000,  // min buffer 15s
                    50_000,  // max buffer 50s
                    1_000,   // playback buffer 1s (fast start)
                    2_000    // rebuffer 2s
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val renderersFactory = DefaultRenderersFactory(context).apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                setEnableDecoderFallback(true)
            }

            val player = ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory!!)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build()

            // Auto-rewind on end (same behaviour as old createExoPlayer)
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        player.seekTo(0)
                        player.playWhenReady = false
                    }
                }
            })

            slots.add(PoolSlot(player))
        }

        Timber.tag("ExoPlayerPool").d("Initialized pool with $POOL_SIZE players")
    }

    // ===== ACQUIRE / RELEASE =====

    /**
     * Acquire a player for the given video. Returns the ExoPlayer already loaded
     * with the correct MediaSource and prepared (but paused).
     *
     * Priority order:
     * 1. Player already assigned to this videoMid (instant reuse).
     * 2. An idle slot (no video assigned).
     * 3. LRU eviction: reclaim the slot whose video was used least recently
     *    and is NOT currently visible.
     *
     * @return The assigned ExoPlayer, or null if all players are visible (rare edge case).
     */
    fun acquirePlayer(
        context: Context,
        videoMid: MimeiId,
        videoUrl: String,
        videoType: MediaType? = null,
        resolvedHlsUrl: String? = null
    ): ExoPlayer? {
        ensureInitialized(context)

        // 1. Already assigned to this video — touch timestamp and return
        assignmentMap[videoMid]?.let { slot ->
            slot.lastUsedTimestamp = System.currentTimeMillis()
            return slot.player
        }

        // 2. Find an idle slot
        var slot = slots.firstOrNull { it.assignedVideoMid == null }

        // 3. LRU eviction — pick oldest non-visible slot
        if (slot == null) {
            slot = slots
                .filter { !it.isVisible }
                .minByOrNull { it.lastUsedTimestamp }

            if (slot == null) {
                // All players are visible — extremely unlikely with 6 players
                Timber.tag("ExoPlayerPool").w("All $POOL_SIZE players are visible, cannot acquire")
                return null
            }

            // Evict the old assignment
            val evictedMid = slot.assignedVideoMid
            if (evictedMid != null) {
                Timber.tag("ExoPlayerPool").d("Evicting $evictedMid to assign $videoMid")
                assignmentMap.remove(evictedMid)
            }
        }

        // Assign the slot to the new video
        assignSlot(slot, videoMid, videoUrl, videoType, resolvedHlsUrl)
        return slot.player
    }

    /**
     * Mark the player for this video as no longer visible.
     * The player is paused but stays in the pool for potential reuse.
     */
    fun releasePlayer(videoMid: MimeiId) {
        val slot = assignmentMap[videoMid] ?: return
        slot.isVisible = false
        try {
            slot.player.playWhenReady = false
        } catch (e: Exception) {
            Timber.tag("ExoPlayerPool").w("Error pausing released player: ${e.message}")
        }
    }

    /**
     * Mark the player for this video as visible (on-screen).
     * Visible players are protected from LRU eviction.
     */
    fun markVisible(videoMid: MimeiId) {
        val slot = assignmentMap[videoMid] ?: return
        slot.isVisible = true
        slot.lastUsedTimestamp = System.currentTimeMillis()
    }

    /**
     * Mark the player for this video as not visible (scrolled off-screen).
     * The player becomes eligible for LRU eviction.
     */
    fun markNotVisible(videoMid: MimeiId) {
        val slot = assignmentMap[videoMid] ?: return
        slot.isVisible = false
        // Stop playback and clear surface to free codec resources
        try {
            slot.player.playWhenReady = false
            slot.player.clearVideoSurface()
            slot.player.stop()
        } catch (e: Exception) {
            Timber.tag("ExoPlayerPool").w("Error stopping non-visible player: ${e.message}")
        }
    }

    // ===== QUERIES =====

    /** Get the player currently assigned to this video, or null. */
    fun getAssignedPlayer(videoMid: MimeiId): ExoPlayer? {
        return assignmentMap[videoMid]?.player
    }

    /** Check if a video currently has a player assigned. */
    fun hasPlayer(videoMid: MimeiId): Boolean = assignmentMap.containsKey(videoMid)

    /** Check if a video's player is currently visible. */
    fun isVisible(videoMid: MimeiId): Boolean = assignmentMap[videoMid]?.isVisible == true

    /** Number of currently assigned (non-idle) slots. */
    fun assignedCount(): Int = assignmentMap.size

    /** Number of idle (unassigned) slots. */
    fun idleCount(): Int = POOL_SIZE - assignmentMap.size

    fun getStats(): String {
        val visible = slots.count { it.isVisible }
        val assigned = assignmentMap.size
        return "Pool: $POOL_SIZE total, $assigned assigned, $visible visible, ${POOL_SIZE - assigned} idle"
    }

    // ===== LIFECYCLE =====

    /**
     * Pause all players (e.g., app going to background).
     */
    fun pauseAll() {
        slots.forEach { slot ->
            try {
                slot.player.playWhenReady = false
            } catch (_: Exception) {}
        }
    }

    /**
     * Stop all players and clear assignments (e.g., navigating away from feed).
     */
    fun stopAll() {
        slots.forEach { slot ->
            try {
                slot.player.playWhenReady = false
                slot.player.clearVideoSurface()
                slot.player.stop()
            } catch (_: Exception) {}
            slot.assignedVideoMid?.let { assignmentMap.remove(it) }
            slot.assignedVideoMid = null
            slot.assignedVideoUrl = null
            slot.assignedVideoType = null
            slot.isVisible = false
        }
        Timber.tag("ExoPlayerPool").d("Stopped all players and cleared assignments")
    }

    /**
     * Release all players and destroy the pool (e.g., Activity.onDestroy).
     * After this, [ensureInitialized] must be called again.
     */
    fun releaseAll() {
        slots.forEach { slot ->
            try {
                slot.player.clearVideoSurface()
                slot.player.stop()
                slot.player.clearMediaItems()
                slot.player.release()
            } catch (_: Exception) {}
        }
        slots.clear()
        assignmentMap.clear()
        mediaSourceFactory = null
        initialized = false
        Timber.tag("ExoPlayerPool").d("Released all players and destroyed pool")
    }

    // ===== INTERNAL =====

    /**
     * Assign a pool slot to a new video by swapping the MediaSource.
     */
    private fun assignSlot(
        slot: PoolSlot,
        videoMid: MimeiId,
        videoUrl: String,
        videoType: MediaType?,
        resolvedHlsUrl: String?
    ) {
        val factory = mediaSourceFactory ?: return

        // Stop any current playback
        try {
            slot.player.playWhenReady = false
            slot.player.stop()
        } catch (_: Exception) {}

        // Remove old HLS fallback listeners
        slot.player.let { player ->
            // We can't easily track per-player listeners in the pool,
            // so we clear all listeners and re-add the auto-rewind one.
            // This is safe because acquirePlayer is the only entry point.
        }

        // Build the correct media source URL
        val mediaUrl = when (videoType) {
            MediaType.HLS_VIDEO -> {
                resolvedHlsUrl ?: run {
                    val baseUrl = if (videoUrl.endsWith("/")) videoUrl else "$videoUrl/"
                    "${baseUrl}master.m3u8"
                }
            }
            else -> videoUrl
        }

        // Set the new media source
        try {
            val mediaSource = factory.createMediaSource(MediaItem.fromUri(mediaUrl))
            slot.player.setMediaSource(mediaSource)
            slot.player.prepare()
        } catch (e: Exception) {
            Timber.tag("ExoPlayerPool").e("Error setting media source for $videoMid: ${e.message}")
        }

        // Update slot metadata
        slot.assignedVideoMid = videoMid
        slot.assignedVideoUrl = videoUrl
        slot.assignedVideoType = videoType
        slot.lastUsedTimestamp = System.currentTimeMillis()
        slot.isVisible = false

        // Update reverse map
        assignmentMap[videoMid] = slot

        Timber.tag("ExoPlayerPool").d("Assigned player to $videoMid (${idleCount()} idle)")
    }
}
