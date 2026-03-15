package us.fireshare.tweet.widget

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import androidx.compose.runtime.mutableStateMapOf
import timber.log.Timber
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified VideoManager handles all video-related functionality:
 * - Delegates feed video playback to [ExoPlayerPool] (fixed pool of 6 shared ExoPlayers)
 * - Visibility-based loading control (stop videos scrolled past)
 * - Smart preloading (HLS URL resolution + disk cache warming, no player creation)
 * - Full-screen video support (separate dedicated player)
 * - Disk caching for video segments via SimpleCache (2GB LRU, keyed by mediaId)
 *
 * Architecture (matching iOS SharedAssetCache):
 * - 6 ExoPlayers are created once and reused across all feed videos
 * - When a video scrolls into view, a player is acquired from the pool
 * - When a video scrolls out, the player is returned (paused, not destroyed)
 * - LRU eviction reclaims the oldest non-visible player when the pool is full
 * - SimpleCache persists downloaded segments by mediaId, independent of player lifecycle
 */
@OptIn(UnstableApi::class)
object VideoManager {

    // ===== COROUTINE SCOPE =====
    // Coroutine scope for video loading that can be cancelled when needed
    private val videoLoadingScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ===== PLAYER MANAGEMENT =====
    // Legacy per-video map kept only for full-screen and recovery paths.
    // Feed videos use ExoPlayerPool instead.
    private val videoPlayers = ConcurrentHashMap<MimeiId, ExoPlayer>()

    // Compose-observable generation counter: incremented whenever a player is force-recreated.
    // VideoPreview uses this as a remember() key so it picks up the new player instance.
    val playerGenerations = mutableStateMapOf<MimeiId, Int>()

    // Track which videos are currently being used (reference counting for legacy paths)
    private val activeVideos = ConcurrentHashMap<MimeiId, Int>()
    
    // ===== MEMORY MANAGEMENT =====
    // Cache access synchronization to prevent concurrent access issues
    private val cacheLock = Any()

    // ===== VISIBILITY TRACKING =====
    // Track which videos are currently visible (user is viewing them)
    private val visibleVideos = mutableSetOf<MimeiId>()

    // Track which videos are being preloaded
    private val preloadingVideos = mutableSetOf<MimeiId>()

    // ===== FULL-SCREEN MANAGEMENT =====
    private var fullScreenPlayer: ExoPlayer? = null
    private var currentVideoUrl: String? = null
    private var autoReplayListener: Player.Listener? = null
    private var hlsFallbackListener: HLSFallbackListener? = null
    private var currentFullScreenVideoMid: MimeiId? = null
    
    // ===== HLS FALLBACK LISTENER TRACKING =====
    // Track HLS fallback listeners per player to allow proper cleanup
    private val hlsFallbackListeners = ConcurrentHashMap<ExoPlayer, HLSFallbackListener>()

    // ===== CACHE MANAGEMENT =====
    private var videoCache: SimpleCache? = null
    private const val CACHE_SIZE_BYTES = 2000L * 1024 * 1024 // 2GB cache size
    private const val VIDEO_CACHE_DIR = "video_cache"

    // ===== SEQUENTIAL PLAYBACK =====
    private val videoPlaylist = mutableListOf<MimeiId>()
    private var currentPlaylistIndex = -1
    private var isSequentialPlaybackEnabled = false

    // ===== PRELOAD MANAGEMENT =====
    private val preloadedVideos = mutableSetOf<MimeiId>()
    private val preloadQueue = mutableListOf<MimeiId>()

    // ===== CONFIGURATION =====
    private const val PRELOAD_AHEAD_COUNT = 3 // Number of upcoming tweets to preload videos from
    private const val PRELOAD_DELAY_MS =
        500L // Delay before starting preload to avoid excessive loading
    private const val MAX_NEW_PRELOADS_PER_CYCLE = 2 // Cap how many new preloads per cycle
    private const val MAX_CONCURRENT_PRELOADS = 3 // Allow up to 3 concurrent player creations

    // ===== MEMORY MONITORING =====
    // Removed custom memory monitoring - now relies on system warnings only

    // Concurrency control for preloading to avoid main-thread contention
    private val preloadSemaphore: Semaphore = Semaphore(MAX_CONCURRENT_PRELOADS)

    // Track active preload jobs by video mid for cancellation
    private val preloadJobs = ConcurrentHashMap<MimeiId, Job>()

    // ===== CACHE MANAGEMENT =====

    /**
     * Get the shared video cache for both progressive and HLS videos
     */
    fun getCache(context: Context): Cache {
        synchronized(cacheLock) {
            if (videoCache == null) {
                val cacheDir = File(context.cacheDir, VIDEO_CACHE_DIR)
                val evictor = LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES)
                val databaseProvider = StandaloneDatabaseProvider(context)
                videoCache = SimpleCache(cacheDir, evictor, databaseProvider)
            }
            return videoCache ?: throw IllegalStateException("Video cache was not initialized")
        }
    }

    /**
     * Clear video cache
     */
    fun clearVideoCache(context: Context) {
        synchronized(cacheLock) {
            try {
                val cache = getCache(context)
                cache.release()
                videoCache = null

                val videoCacheDir = File(context.cacheDir, VIDEO_CACHE_DIR)
                if (videoCacheDir.exists()) {
                    videoCacheDir.deleteRecursively()
                }

                Timber.d("VideoManager - Video cache cleared")
            } catch (e: Exception) {
                Timber.e("VideoManager - Error clearing video cache. ${e.message}")
            }
        }
    }

    /**
     * Get cache statistics
     */
    fun getCacheStats(context: Context): String {
        val cache = getCache(context)
        val cacheSize = cache.cacheSpace
        val maxCacheSize = CACHE_SIZE_BYTES
        val usedPercentage = (cacheSize * 100 / maxCacheSize).toInt()

        return "${cacheSize / (1024 * 1024)}MB / ${maxCacheSize / (1024 * 1024)}MB ($usedPercentage%)"
    }

    /**
     * Cache a local video file from Uri directly by mid
     * This is used when sending a video - cache it immediately without downloading
     * 
     * @param context Android context
     * @param mid The media ID to cache under
     * @param uri The local file Uri
     */
    suspend fun cacheLocalVideoFile(context: Context, mid: MimeiId, uri: Uri) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Create a cache directory specific to this video
                val videoCacheDir = File(context.cacheDir, "$VIDEO_CACHE_DIR/$mid")
                if (!videoCacheDir.exists()) {
                    videoCacheDir.mkdirs()
                }
                
                // Copy the local video file to our cache directory
                val cachedFile = File(videoCacheDir, "local_video.mp4")
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    cachedFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                
                Timber.d("VideoManager - Cached local video file for mid: $mid")
            } catch (e: Exception) {
                Timber.e("VideoManager - Error caching local video file: ${e.message}")
            }
        }
    }

    /**
     * Check if a local video file is cached
     * 
     * @param context Android context
     * @param mid The media ID
     * @return The cached file if it exists, null otherwise
     */
    fun getCachedLocalVideoFile(context: Context, mid: MimeiId): File? {
        val videoCacheDir = File(context.cacheDir, "$VIDEO_CACHE_DIR/$mid")
        val cachedFile = File(videoCacheDir, "local_video.mp4")
        return if (cachedFile.exists()) cachedFile else null
    }

    // ===== VISIBILITY-BASED LOADING CONTROL =====

    /**
     * Mark a video as visible (user is currently viewing it).
     * Delegates to ExoPlayerPool for feed videos.
     */
    fun markVideoVisible(videoMid: MimeiId) {
        visibleVideos.add(videoMid)
        ExoPlayerPool.markVisible(videoMid)
        markVideoActive(videoMid)
    }

    /**
     * Mark a video as not visible (user has scrolled past it).
     * Delegates to ExoPlayerPool which pauses and frees codec resources.
     */
    fun markVideoNotVisible(videoMid: MimeiId) {
        visibleVideos.remove(videoMid)
        markVideoInactive(videoMid)

        // Don't stop the video if it's currently in full-screen mode
        if (!isVideoInFullScreen(videoMid)) {
            cancelPreload(videoMid)
            ExoPlayerPool.markNotVisible(videoMid)
        }
    }

    /**
     * Check if a video is currently visible
     */
    fun isVideoVisible(videoMid: MimeiId): Boolean = visibleVideos.contains(videoMid)

    // ===== SMART PRELOADING =====

    /**
     * Preload videos from upcoming tweets based on current scroll position
     * Only preloads videos that are not already cached and not currently visible
     */
    fun preloadUpcomingVideos(
        context: Context,
        currentTweetIndex: Int,
        tweets: List<us.fireshare.tweet.datamodel.Tweet>,
        baseUrl: String
    ) {
        val coroutineScope = CoroutineScope(Dispatchers.IO)

        coroutineScope.launch {
            // Add delay to avoid excessive preloading during rapid scrolling
            delay(PRELOAD_DELAY_MS)

            // Calculate range of tweets to preload from
            val startIndex = currentTweetIndex + 1
            val endIndex = kotlin.math.min(startIndex + PRELOAD_AHEAD_COUNT, tweets.size)

            var addedThisCycle = 0

            for (i in startIndex until endIndex) {
                val tweet = tweets[i]
                val videoAttachments = tweet.attachments?.filter {
                    it.type == MediaType.Video ||
                            it.type == MediaType.HLS_VIDEO
                } ?: emptyList()

                for (attachment in videoAttachments) {
                    if (addedThisCycle >= MAX_NEW_PRELOADS_PER_CYCLE) {
                        // Reached per-cycle limit; stop adding more in this cycle
                        continue
                    }
                    // Only preload if not already cached, not visible, and not being preloaded
                    if (!isVideoPreloaded(attachment.mid) &&
                        !isVideoVisible(attachment.mid) &&
                        !preloadingVideos.contains(attachment.mid)
                    ) {

                        preloadingVideos.add(attachment.mid)

                        try {
                            val mediaUrl = us.fireshare.tweet.HproseInstance.getMediaUrl(
                                attachment.mid,
                                baseUrl
                            ).toString()

                            preloadVideo(context, attachment.mid, mediaUrl, attachment.type)
                            addedThisCycle++
                        } catch (e: Exception) {
                            Timber.e("VideoManager - Failed to preload video: ${e.message}")
                        } finally {
                            preloadingVideos.remove(attachment.mid)
                        }
                    }
                }
            }
        }
    }

    /**
     * Stop preloading all videos
     */
    fun stopAllPreloading() {
        // Cancel all active preload jobs
        preloadJobs.values.forEach { job ->
            try {
                job.cancel()
            } catch (_: Exception) { }
        }
        preloadJobs.clear()
        preloadingVideos.clear()
        Timber.d("VideoManager - Stopped all preloading")
    }

    // ===== PLAYER MANAGEMENT =====

    /**
     * Get an ExoPlayer for a video from the shared pool.
     * The pool manages a fixed set of players, reusing them via LRU eviction.
     * Video segment data stays in SimpleCache (2GB disk LRU) independent of player lifecycle.
     *
     * @param resolvedHlsUrl Pre-resolved HLS URL from [HlsUrlResolver]; when non-null it is
     *   passed directly to the pool, skipping the master/playlist guessing entirely.
     */
    fun getVideoPlayer(
        context: Context,
        videoMid: MimeiId,
        videoUrl: String,
        videoType: MediaType? = null,
        resolvedHlsUrl: String? = null
    ): ExoPlayer {
        // Mark as preloaded if it was in the preload queue
        preloadedVideos.add(videoMid)
        preloadQueue.remove(videoMid)

        // When offline and no player assigned, return a bare placeholder
        if (!us.fireshare.tweet.HproseInstance.isOnline.value && !ExoPlayerPool.hasPlayer(videoMid)) {
            Timber.tag("VideoManager").d("Offline: creating placeholder player for $videoMid")
            val player = ExoPlayer.Builder(context).build()
            videoPlayers[videoMid] = player  // legacy map for offline fallback
            return player
        }

        // Delegate to the shared pool
        val player = ExoPlayerPool.acquirePlayer(
            context, videoMid, videoUrl, videoType, resolvedHlsUrl
        )
        if (player != null) {
            return player
        }

        // Fallback: all pool players are visible (extremely rare with 6 players).
        // Create a temporary player via legacy path.
        Timber.tag("VideoManager").w("Pool full — fallback to legacy player for $videoMid")
        return videoPlayers.getOrPut(videoMid) {
            createExoPlayer(context, videoUrl, videoType ?: MediaType.Video, resolvedHlsUrl = resolvedHlsUrl)
        }
    }

    /**
     * Mark a video as active (being used by a composable)
     */
    fun markVideoActive(videoMid: MimeiId) {
        val currentCount = activeVideos.getOrDefault(videoMid, 0)
        activeVideos[videoMid] = currentCount + 1
    }

    /**
     * Mark a video as inactive (no longer being used by a composable)
     */
    fun markVideoInactive(videoMid: MimeiId) {
        val currentCount = activeVideos.getOrDefault(videoMid, 0)
        if (currentCount > 0) {
            val newCount = currentCount - 1
            if (newCount == 0) {
                activeVideos.remove(videoMid)
            } else {
                activeVideos[videoMid] = newCount
            }
        }
        
        // Clean up inactive players periodically to prevent leaks
        // Only do this occasionally to avoid performance impact
        if (videoPlayers.size > 10 && videoPlayers.size % 5 == 0) {
            cleanupInactivePlayers()
        }
    }

    /**
     * Pause a specific video
     */
    fun pauseVideo(videoMid: MimeiId) {
        val player = ExoPlayerPool.getAssignedPlayer(videoMid) ?: videoPlayers[videoMid]
        player?.playWhenReady = false
    }

    /**
     * Resume a specific video
     */
    fun resumeVideo(videoMid: MimeiId, shouldPlay: Boolean = true) {
        val player = ExoPlayerPool.getAssignedPlayer(videoMid) ?: videoPlayers[videoMid]
        player?.playWhenReady = shouldPlay
    }

    /**
     * Stop all video players to prevent network requests when offline.
     * Players are kept alive so they can resume when back online.
     */
    fun stopAllVideos() {
        Timber.tag("VideoManager").d("Stopping all video players (offline)")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            ExoPlayerPool.stopAll()
            // Also stop any legacy fallback players
            videoPlayers.values.forEach { player ->
                try {
                    player.stop()
                } catch (e: Exception) {
                    Timber.tag("VideoManager").w("Error stopping player: ${e.message}")
                }
            }
            fullScreenPlayer?.stop()
        }
    }

    /**
     * Release all video players (pool + any legacy fallback players).
     * Note: This method must be called on the main thread.
     */
    fun releaseAllVideos() {
        Timber.tag("VideoManager").d("🧹 RELEASING ALL VIDEOS")

        // Ensure we're on the main thread
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            Timber.tag("VideoManager").e("❌ WRONG THREAD: releaseAllVideos() called on wrong thread")
            throw IllegalStateException("VideoManager.releaseAllVideos() must be called on the main thread")
        }

        // Release the shared pool
        ExoPlayerPool.releaseAll()

        // Release any legacy fallback players
        videoPlayers.values.forEach { player ->
            try {
                player.clearVideoSurface()
                player.stop()
                player.release()
            } catch (e: Exception) {
                Timber.tag("VideoManager").e("❌ PLAYER RELEASE ERROR: $e")
            }
        }
        videoPlayers.clear()
        activeVideos.clear()
        visibleVideos.clear()
        preloadedVideos.clear()
        Timber.tag("VideoManager").d("✅ ALL VIDEOS RELEASED")
    }

    // ===== PRELOADING =====

    /**
     * Preload a video in the background.
     *
     * With the shared player pool, preloading does NOT create an ExoPlayer.
     * Instead it resolves the HLS URL (cached to disk) so that when the video
     * scrolls into view and acquires a pool player, loading is instant.
     * SimpleCache handles disk-level segment caching automatically.
     */
    fun preloadVideo(context: Context, videoMid: MimeiId, videoUrl: String, videoType: MediaType? = null) {
        if (!us.fireshare.tweet.HproseInstance.isOnline.value) return
        if (isVideoVisible(videoMid)) return
        if (preloadedVideos.contains(videoMid)) return

        if (!preloadQueue.contains(videoMid)) {
            preloadQueue.add(videoMid)

            val job = videoLoadingScope.launch {
                try {
                    preloadSemaphore.acquire()
                    if (!isActive) return@launch

                    // Resolve the correct HLS URL and cache it to disk.
                    // This is the main value of preloading — when the video later
                    // acquires a pool player, it gets the correct URL instantly.
                    if (videoType == MediaType.HLS_VIDEO) {
                        HlsUrlResolver.resolve(context, videoUrl)
                    }

                    if (!isActive) return@launch
                    preloadedVideos.add(videoMid)
                    preloadQueue.remove(videoMid)
                    Timber.tag("preloadVideo").d("Preloaded HLS URL for $videoMid")
                } catch (e: Exception) {
                    preloadQueue.remove(videoMid)
                    Timber.e("VideoManager - Failed to preload video: $videoMid, error: ${e.message}")
                } finally {
                    preloadSemaphore.release()
                    preloadJobs.remove(videoMid)
                }
            }
            preloadJobs[videoMid] = job
        }
    }

    /**
     * Cancel an active preload for a given video mid
     */
    private fun cancelPreload(videoMid: MimeiId) {
        preloadJobs.remove(videoMid)?.let { job ->
            try {
                job.cancel()
            } catch (_: Exception) { }
        }
        preloadingVideos.remove(videoMid)
        preloadQueue.remove(videoMid)
    }

    /**
     * Check if a video is preloaded (HLS URL resolved + segments warming in cache)
     * or already has a player assigned in the pool.
     */
    fun isVideoPreloaded(videoMid: MimeiId): Boolean {
        return preloadedVideos.contains(videoMid) ||
               ExoPlayerPool.hasPlayer(videoMid) ||
               videoPlayers.containsKey(videoMid)
    }

    // ===== FULL-SCREEN MANAGEMENT =====

    /**
     * Get the dedicated full screen video player
     */
    @OptIn(UnstableApi::class)
    fun getFullScreenPlayer(context: Context): ExoPlayer {
        if (fullScreenPlayer == null) {
            Timber.d("VideoManager - Creating dedicated full screen player")
            // Use cache-aware data source and aggressive buffering for smooth fullscreen playback
            val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(30000)
                .setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("TweetApp/1.0")
            val upstreamFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)
            val cache = getCache(context)
            val cacheDataSourceFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setCacheKeyFactory(MediaIdCacheKeyFactory())
                .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cacheDataSourceFactory)

            val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    15_000,   // min buffer (15s) - reduced from 50s to save RAM
                    50_000,   // max buffer (50s) - reduced from 120s to save ~100MB RAM
                    1_000,    // buffer for playback (1s) - faster start
                    2_000     // buffer after rebuffer (2s)
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            fullScreenPlayer = ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .build()
        }
        return fullScreenPlayer!!
    }

    /**
     * Load a video into the full screen player
     * Uses the same cache-aware data source factory as createExoPlayer for optimal performance
     * For HLS videos: tries master.m3u8 first, then playlist.m3u8 if that fails
     */
    fun loadVideo(context: Context, videoUrl: String, videoType: MediaType? = null) {
        if (!us.fireshare.tweet.HproseInstance.isOnline.value) return
        if (currentVideoUrl == videoUrl) {
            return
        }

        currentVideoUrl = videoUrl

        val player = getFullScreenPlayer(context)

        try {
            player.stop()
            
            // Remove any existing HLS fallback listener
            hlsFallbackListeners[player]?.let { existingListener ->
                player.removeListener(existingListener)
                hlsFallbackListeners.remove(player)
            }
            
            // Use the same cache-aware data source factory as createExoPlayer
            val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(30000) // 30 seconds connection timeout
                .setReadTimeoutMs(30000)    // 30 seconds read timeout
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("TweetApp/1.0")

            val upstreamFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)
            val cache = getCache(context)
            val cacheDataSourceFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setCacheKeyFactory(MediaIdCacheKeyFactory()) // Use media ID as cache key
                .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            // Use DefaultMediaSourceFactory backed by CacheDataSource which handles HLS and progressive
            val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cacheDataSourceFactory)
            
            // Add HLS fallback listener for HLS videos
            if (videoType == MediaType.HLS_VIDEO) {
                val fallbackListener = HLSFallbackListener(videoUrl, player, mediaSourceFactory)
                player.addListener(fallbackListener)
                hlsFallbackListeners[player] = fallbackListener
            }
            
            // Create media source based on video type (same logic as createExoPlayer)
            val mediaSource = when (videoType) {
                MediaType.HLS_VIDEO -> {
                    // For HLS videos: start with master.m3u8
                    val baseUrl = if (videoUrl.endsWith("/")) videoUrl else "$videoUrl/"
                    val masterUrl = "${baseUrl}master.m3u8"
                    Timber.d("VideoManager - Creating HLS media source with master URL: $masterUrl")
                    mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(masterUrl))
                }
                MediaType.Video -> {
                    // For progressive videos: play URL directly
                    Timber.d("VideoManager - Creating progressive media source with URL: $videoUrl")
                    mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(videoUrl))
                }
                else -> {
                    // Default to progressive video for unknown types
                    Timber.d("VideoManager - Unknown media type '$videoType', defaulting to progressive video: $videoUrl")
                    mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(videoUrl))
                }
            }
            
            player.setMediaSource(mediaSource)
            player.prepare()
        } catch (e: Exception) {
            Timber.e("VideoManager - Error loading video: $e")
        }
    }

    /**
     * Start playback with auto-replay
     */
    fun startPlayback(autoReplay: Boolean = true) {
        val player = fullScreenPlayer ?: return

        // Remove existing auto-replay listener if any
        autoReplayListener?.let { listener ->
            player.removeListener(listener)
        }

        // Set up auto-replay listener
        autoReplayListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        if (autoReplay) {
                            Timber.d("VideoManager - Video ended, auto-replaying")
                            player.seekTo(0)
                            player.playWhenReady = true
                        }
                    }

                    Player.STATE_BUFFERING -> {
                        // Buffering is normal, no action needed
                    }

                    Player.STATE_IDLE -> {
                        // Player idle, no action needed
                    }

                    Player.STATE_READY -> {
                        // Player ready, no action needed
                    }
                }
            }
        }

        player.addListener(autoReplayListener!!)
        player.playWhenReady = true
    }

    /**
     * Release full screen player
     */
    fun releaseFullScreenPlayer() {
        fullScreenPlayer?.release()
        fullScreenPlayer = null
        currentVideoUrl = null
        autoReplayListener = null
    }

    // ===== MEMORY MANAGEMENT =====

    /**
     * Get the number of cached video players
     */
    fun getCachedVideoCount(): Int = videoPlayers.size

    /**
     * Get a cached video player if it exists (without creating a new one)
     * Used for frame capture during sharing
     */
    fun getCachedVideoPlayer(videoMid: MimeiId): ExoPlayer? =
        ExoPlayerPool.getAssignedPlayer(videoMid) ?: videoPlayers[videoMid]

    /**
     * Get the number of active videos
     */
    fun getActiveVideoCount(): Int = activeVideos.size

    /**
     * Get the active count for a specific video
     */
    fun getVideoActiveCount(videoMid: MimeiId): Int = activeVideos.getOrDefault(videoMid, 0)

    // Note: forceCleanupInactiveVideos() removed as it's no longer needed
    // Modern memory management relies on system memory warnings only

    // Note: clearSignificantInactiveVideos() removed as modern Android (API 34+) 
    // only sends UI_HIDDEN and BACKGROUND memory levels

    /**
     * Attempt to recover a video that has stopped loading with thorough reset
     * Properly recreates the media source using the same logic as createExoPlayer
     * For HLS videos: tries master.m3u8, then playlist.m3u8
     * For regular videos: plays the URL directly
     * @param forceSoftwareDecoder If true, forces software decoder usage to avoid MediaCodec failures
     */
    fun attemptVideoRecovery(context: Context, videoMid: MimeiId, videoUrl: String, videoType: MediaType? = null, forceSoftwareDecoder: Boolean = false): Boolean {
        val player = ExoPlayerPool.getAssignedPlayer(videoMid) ?: videoPlayers[videoMid] ?: return false

        Timber.d("VideoManager - Attempting thorough recovery for video: $videoMid (software: $forceSoftwareDecoder)")

        try {
            // Thorough reset: stop, clear, and reset player state
            player.stop()
            player.clearMediaItems()
            player.seekTo(0)
            
            // Reset playback state
            player.playWhenReady = false
            player.pause()

            // If we need to force software decoder, we need to recreate the entire player
            // because we can't change the renderer factory of an existing player
            if (forceSoftwareDecoder) {
                Timber.d("VideoManager - Force software decoder requested, recreating player for video: $videoMid")
                return forceRecreatePlayer(context, videoMid, videoUrl, videoType)
            }

            // Recreate the media source using the same logic as createExoPlayer
            val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(45000) // 45 seconds connection timeout for thorough retry
                .setReadTimeoutMs(45000)    // 45 seconds read timeout for thorough retry
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("TweetApp/1.0")

            val upstreamFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)
            val cache = getCache(context)
            val cacheDataSourceFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setCacheKeyFactory(MediaIdCacheKeyFactory()) // Use media ID as cache key
                .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            // Use DefaultMediaSourceFactory backed by CacheDataSource which handles both HLS and progressive
            val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cacheDataSourceFactory)
            
            // Remove any existing HLS fallback listener for this player
            hlsFallbackListeners[player]?.let { existingListener ->
                player.removeListener(existingListener)
                hlsFallbackListeners.remove(player)
            }
            
            // Add HLS fallback listener for HLS videos
            if (videoType == MediaType.HLS_VIDEO) {
                val fallbackListener = HLSFallbackListener(videoUrl, player, mediaSourceFactory)
                player.addListener(fallbackListener)
                hlsFallbackListeners[player] = fallbackListener
            }
            
            val mediaSource = when (videoType) {
                MediaType.HLS_VIDEO -> {
                    // For HLS videos: try master.m3u8 first
                    val baseUrl = if (videoUrl.endsWith("/")) videoUrl else "$videoUrl/"
                    val masterUrl = "${baseUrl}master.m3u8"
                    Timber.d("VideoManager - Creating HLS media source with master URL: $masterUrl")
                    mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(masterUrl))
                }
                MediaType.Video -> {
                    // For progressive videos: play the URL directly
                    Timber.d("VideoManager - Creating progressive media source with URL: $videoUrl")
                    mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(videoUrl))
                }
                else -> {
                    // Default to progressive video for unknown types
                    Timber.d("VideoManager - Unknown media type '$videoType', defaulting to progressive video: $videoUrl")
                    mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(videoUrl))
                }
            }

            // Set the new media source and prepare
            player.setMediaSource(mediaSource)
            player.prepare()

            return true
        } catch (e: Exception) {
            // Only log recovery failures at debug level to avoid noise during trials
            Timber.d("VideoManager - Thorough recovery failed for video: $videoMid, error: ${e.message}")
            return false
        }
    }
    
    /**
     * Clean up truly inactive video players (not being used by any Composable)
     * This prevents memory leaks by releasing players that are no longer referenced
     */
    fun cleanupInactivePlayers() {
        val inactivePlayers = videoPlayers.keys.filter { videoMid ->
            !activeVideos.containsKey(videoMid) && !visibleVideos.contains(videoMid)
        }
        
        if (inactivePlayers.isNotEmpty()) {
            Timber.tag("VideoManager").d("🧹 CLEANUP: Releasing ${inactivePlayers.size} inactive players")
            inactivePlayers.forEach { videoMid ->
                releasePlayer(videoMid)
            }
        }
    }
    
    /**
     * Properly release a video player and clean up all associated resources
     * This includes stopping playback, clearing media sources, and releasing buffers
     * Focus on preventing memory leaks rather than aggressive cleanup
     */
    private fun releasePlayer(videoMid: MimeiId) {
        val player = videoPlayers.remove(videoMid)
        if (player != null) {
            try {
                // Detach surface FIRST to prevent MediaCodec IllegalStateException
                // during flush/stop when the surface is already destroyed.
                player.clearVideoSurface()

                // Stop playback and clear media sources to prevent leaks
                player.stop()
                player.clearMediaItems()

                // Release the player completely
                player.release()

                Timber.tag("VideoManager").d("✅ PLAYER RELEASED: videoMid: $videoMid")
            } catch (e: Exception) {
                Timber.tag("VideoManager").w("⚠️ Error releasing player for $videoMid: ${e.message}")
            }
        }

        // Notify any live VideoPreview composable that the player was released so it
        // re-runs its remember(videoMid, playerGeneration) block and gets a fresh player.
        // Without this, VideoPreview holds the stale released player indefinitely — the
        // same mechanism forceRecreatePlayer() uses for MediaCodec failures.
        playerGenerations[videoMid] = (playerGenerations[videoMid] ?: 0) + 1

        // Clean up tracking data
        activeVideos.remove(videoMid)
        visibleVideos.remove(videoMid)
        preloadedVideos.remove(videoMid)
        preloadQueue.remove(videoMid)
    }
    
    /**
     * Force recreate an ExoPlayer instance for a video (used for MediaCodec failures)
     * This completely destroys the old player and creates a new one with software decoder
     */
    fun forceRecreatePlayer(context: Context, videoMid: MimeiId, videoUrl: String, videoType: MediaType? = null): Boolean {
        Timber.tag("VideoManager").w("🔄 FORCE RECREATING PLAYER: videoMid: $videoMid due to MediaCodec failure")
        
        try {
            // Get the old player and release it completely
            val oldPlayer = videoPlayers[videoMid]
            oldPlayer?.let { player ->
                try {
                    player.clearVideoSurface()
                    player.stop()
                    player.clearMediaItems()
                    player.release()
                } catch (e: Exception) {
                    Timber.tag("VideoManager").w("Error releasing old player: ${e.message}")
                }
            }
            
            // Remove from all tracking maps
            videoPlayers.remove(videoMid)
            visibleVideos.remove(videoMid)
            preloadedVideos.remove(videoMid)
            preloadQueue.remove(videoMid)
            
            // Create a completely new player with software decoder to avoid MediaCodec failures
            val newPlayer = createExoPlayer(context, videoUrl, videoType ?: MediaType.Video, forceSoftwareDecoder = true)
            videoPlayers[videoMid] = newPlayer
            // Notify Compose that VideoPreview should re-read the player for this video.
            playerGenerations[videoMid] = (playerGenerations[videoMid] ?: 0) + 1

            Timber.tag("VideoManager").d("✅ PLAYER FORCE RECREATED WITH SOFTWARE DECODER: videoMid: $videoMid")
            return true
            
        } catch (e: Exception) {
            Timber.tag("VideoManager").e("❌ FORCE RECREATION FAILED: videoMid: $videoMid, error: ${e.message}")
            // Clean up any partial state
            videoPlayers.remove(videoMid)
            visibleVideos.remove(videoMid)
            preloadedVideos.remove(videoMid)
            preloadQueue.remove(videoMid)
            return false
        }
    }

    /**
     * Get cache statistics
     */
    fun getCacheStats(): String {
        return "${ExoPlayerPool.getStats()}, Preloaded: ${preloadedVideos.size}"
    }

    // Player count checking removed - now relies entirely on system memory warnings

    /**
     * Get detailed memory statistics
     */
    fun getMemoryStats(): String {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val maxMemory = runtime.maxMemory()

        return "Memory: ${usedMemory / (1024 * 1024)}MB used, ${freeMemory / (1024 * 1024)}MB free, ${totalMemory / (1024 * 1024)}MB total, ${maxMemory / (1024 * 1024)}MB max"
    }

    // ===== SEQUENTIAL PLAYBACK =====

    /**
     * Set up sequential playback for a list of videos
     */
    fun setupSequentialPlayback(videoMids: List<MimeiId>) {
        videoPlaylist.clear()
        videoPlaylist.addAll(videoMids)
        currentPlaylistIndex = if (videoMids.isNotEmpty()) 0 else -1
        isSequentialPlaybackEnabled = videoMids.isNotEmpty()

        Timber.d("VideoManager - Sequential playback setup: ${videoMids.size} videos")
        Timber.d("VideoManager - Playlist: $videoMids")

        // Start playing the first video if available
        if (currentPlaylistIndex >= 0) {
            val firstVideo = videoPlaylist[currentPlaylistIndex]
            videoPlayers[firstVideo]?.let { player ->
                player.playWhenReady = true
            }
        }
    }

    /**
     * Stop sequential playback
     */
    fun stopSequentialPlayback() {
        isSequentialPlaybackEnabled = false
        currentPlaylistIndex = -1
        videoPlaylist.clear()
    }

    /**
     * Handle video completion for sequential playback
     */
    fun onVideoCompleted(completedVideoMid: MimeiId) {
        if (!isSequentialPlaybackEnabled || videoPlaylist.isEmpty()) {
            return
        }

        // Find the completed video in the playlist
        val completedIndex = videoPlaylist.indexOf(completedVideoMid)
        if (completedIndex == -1 || completedIndex != currentPlaylistIndex) {
            return
        }

        // Move to next video
        currentPlaylistIndex++
        if (currentPlaylistIndex < videoPlaylist.size) {
            val nextVideo = videoPlaylist[currentPlaylistIndex]
            videoPlayers[nextVideo]?.let { player ->
                player.playWhenReady = true
            }
        } else {
            // Playlist completed
            stopSequentialPlayback()
        }
    }

    /**
     * Transfer video player to full-screen mode
     * This allows seamless transition from preview to full-screen without losing position
     */
    fun transferToFullScreen(videoMid: MimeiId): ExoPlayer? {
        val player = ExoPlayerPool.getAssignedPlayer(videoMid) ?: videoPlayers[videoMid]
        return player?.also {
            Timber.tag("transferToFullScreen").d("Transferring player for $videoMid to full-screen")
            currentFullScreenVideoMid = videoMid
        }
    }

    /**
     * Return video player from full-screen mode back to preview
     */
    fun returnFromFullScreen(videoMid: MimeiId) {
        videoPlayers[videoMid]?.let {
            Timber.tag("returnFromFullScreen").d("Returning player for $videoMid from full-screen")
            currentFullScreenVideoMid = null
        }
    }

    /**
     * Check if a video is currently in full-screen mode
     */
    fun isVideoInFullScreen(videoMid: MimeiId): Boolean {
        return currentFullScreenVideoMid == videoMid
    }

    /**
     * Clear all tracking data (useful for testing or reset)
     */
    fun clear() {
        visibleVideos.clear()
        preloadingVideos.clear()
        preloadedVideos.clear()
        preloadQueue.clear()
        Timber.d("VideoManager - Cleared all tracking data")
    }

    // ===== VIDEO METADATA UTILITIES =====

    /**
     * Get video aspect ratio using MediaMetadataRetriever
     * This method was moved from SimplifiedVideoCacheManager
     */
    fun getVideoAspectRatio(context: Context, uri: Uri): Float {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)

            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0

            // Check for rotation metadata (some videos have rotation info)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0

            retriever.release()

            if (width > 0 && height > 0) {
                // Calculate aspect ratio considering video rotation
                val aspectRatio = when (rotation) {
                    90, 270 -> {
                        // For 90/270 degree rotations, swap width and height for correct aspect ratio
                        height.toFloat() / width.toFloat()
                    }
                    else -> {
                        // For normal orientation, use width/height
                        width.toFloat() / height.toFloat()
                    }
                }
                
                Timber.tag("VideoManager").d("Video aspect ratio calculated: $aspectRatio (${width}x${height}, rotation: ${rotation}°) for URI: $uri")
                aspectRatio
            } else {
                Timber.tag("VideoManager").w("Could not determine video dimensions for URI: $uri, using default 16:9")
                16f / 9f // Default aspect ratio
            }
        } catch (e: Exception) {
            Timber.tag("VideoManager").e(e, "Error getting video aspect ratio for URI: $uri, using default 16:9")
            16f / 9f // Default aspect ratio on error
        }
    }

    /**
     * Get video resolution (width x height) using MediaMetadataRetriever
     * Returns a Pair<Int, Int> representing (width, height)
     * Considers video rotation for proper orientation
     */
    fun getVideoResolution(context: Context, uri: Uri): Pair<Int, Int>? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)

            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0

            // Check for rotation metadata (some videos have rotation info)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0

            retriever.release()

            if (width > 0 && height > 0) {
                // Return dimensions considering video rotation
                val (finalWidth, finalHeight) = when (rotation) {
                    90, 270 -> {
                        // For 90/270 degree rotations, swap width and height
                        Pair(height, width)
                    }
                    else -> {
                        // For normal orientation, use original dimensions
                        Pair(width, height)
                    }
                }
                
                Timber.tag("VideoManager").d("Video resolution: ${finalWidth}x${finalHeight} (original: ${width}x${height}, rotation: ${rotation}°) for URI: $uri")
                Pair(finalWidth, finalHeight)
            } else {
                Timber.tag("VideoManager").w("Could not determine video resolution for URI: $uri")
                null
            }
        } catch (e: Exception) {
            Timber.tag("VideoManager").e(e, "Error getting video resolution for URI: $uri")
            null
        }
    }

    /**
     * Get video duration in milliseconds
     * @param context Android context
     * @param uri Video URI
     * @return Duration in milliseconds, or null if extraction fails
     */
    fun getVideoDuration(context: Context, uri: Uri): Long? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)

            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()

            retriever.release()

            durationMs?.let {
                Timber.tag("VideoManager").d("Video duration: ${it}ms (${it / 1000}s) for URI: $uri")
                it
            } ?: run {
                Timber.tag("VideoManager").w("Could not extract video duration from URI: $uri")
                null
            }
        } catch (e: Exception) {
            Timber.tag("VideoManager").e(e, "Error getting video duration for URI: $uri")
            null
        }
    }

    /**
     * Get video bitrate (in bits per second) using MediaMetadataRetriever
     * Returns null if bitrate cannot be extracted
     */
    fun getVideoBitrate(context: Context, uri: Uri): Int? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)

            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull()

            retriever.release()

            bitrate?.let {
                Timber.tag("VideoManager").d("Video bitrate: ${it}bps (${it / 1000}k) for URI: $uri")
                it
            } ?: run {
                Timber.tag("VideoManager").w("Could not extract video bitrate from URI: $uri")
                null
            }
        } catch (e: Exception) {
            Timber.tag("VideoManager").e(e, "Error getting video bitrate for URI: $uri")
            null
        }
    }

    /**
     * Calculate video resolution value for normalization/routing purposes
     * Resolution is defined as:
     * - Landscape (width >= height): Resolution = HEIGHT (e.g., 1280×720 = 720p)
     * - Portrait (height > width): Resolution = WIDTH (e.g., 720×1280 = 720p)
     * 
     * @param videoResolution Pair of (width, height) from getVideoResolution
     * @return Resolution value in pixels, or null if resolution is invalid
     */
    fun getVideoResolutionValue(videoResolution: Pair<Int, Int>?): Int? {
        if (videoResolution == null) return null
        
        val (width, height) = videoResolution
        if (width <= 0 || height <= 0) return null
        
        // Landscape: resolution = HEIGHT
        // Portrait: resolution = WIDTH
        return if (width >= height) {
            height // Landscape
        } else {
            width // Portrait
        }
    }

    /**
     * Private listener class to handle HLS fallback from master.m3u8 to playlist.m3u8
     * Tries master.m3u8 first, then playlist.m3u8 if that fails (sequential, not simultaneous)
     */
    private class HLSFallbackListener(
        private val videoUrl: String,
        private val player: ExoPlayer,
        private val mediaSourceFactory: androidx.media3.exoplayer.source.DefaultMediaSourceFactory
    ) : Player.Listener {
        private var hasTriedPlaylist = false

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // Only handle HLS fallback once
            if (!hasTriedPlaylist) {
                hasTriedPlaylist = true
                Timber.d("VideoManager - HLS master.m3u8 failed, trying playlist.m3u8 fallback for URL: $videoUrl")

                // Construct playlist URL
                val baseUrl = if (videoUrl.endsWith("/")) videoUrl else "$videoUrl/"
                val playlistUrl = "${baseUrl}playlist.m3u8"

                // Try playlist.m3u8 fallback
                val fallbackMediaSource = mediaSourceFactory.createMediaSource(
                    androidx.media3.common.MediaItem.fromUri(playlistUrl)
                )
                
                // Set the fallback media source and prepare
                player.setMediaSource(fallbackMediaSource)
                player.prepare()
                
                Timber.d("VideoManager - Set fallback media source for playlist.m3u8: $playlistUrl")
            } else {
                // Final failure - no more fallbacks
                Timber.e("VideoManager - All HLS attempts failed for URL: $videoUrl")
                Timber.e("VideoManager - Final error: ${error.message}")
            }
        }
    }
}