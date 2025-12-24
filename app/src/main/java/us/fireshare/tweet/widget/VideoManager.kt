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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import timber.log.Timber
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified VideoManager handles all video-related functionality:
 * - ExoPlayer instance management and lifecycle
 * - Visibility-based loading control (stop videos scrolled past)
 * - Smart preloading based on scroll position
 * - Memory leak prevention (not aggressive cleanup)
 * - Full-screen video support
 * - Disk caching for video segments
 * 
 * Memory Management Philosophy:
 * - Focus on preventing leaks rather than limiting player count
 * - Allow many concurrent players (20+) as long as no leaks exist
 * - Only cleanup truly inactive players (not referenced by any Composable)
 * - Proper resource cleanup in releasePlayer() to prevent surface/buffer leaks
 */
@OptIn(UnstableApi::class)
object VideoManager {

    // ===== PLAYER MANAGEMENT =====
    // Thread-safe map to store ExoPlayer instances by video mid
    private val videoPlayers = ConcurrentHashMap<MimeiId, ExoPlayer>()

    // Track which videos are currently being used
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

    // ===== VISIBILITY-BASED LOADING CONTROL =====

    /**
     * Mark a video as visible (user is currently viewing it)
     * This allows the video to continue loading and playing
     */
    fun markVideoVisible(videoMid: MimeiId) {
        visibleVideos.add(videoMid)
        markVideoActive(videoMid)
    }

    /**
     * Mark a video as not visible (user has scrolled past it)
     * This stops loading the video to save resources
     */
    fun markVideoNotVisible(videoMid: MimeiId) {
        visibleVideos.remove(videoMid)
        markVideoInactive(videoMid)

        // Don't pause the video if it's currently in full-screen mode
        if (!isVideoInFullScreen(videoMid)) {
            // Cancel any ongoing preload/network loading for this video
            cancelPreload(videoMid)
            // Pause the video but don't stop it completely to avoid state issues
            videoPlayers[videoMid]?.let { player ->
                try {
                    player.playWhenReady = false
                    // Don't call stop() as it clears the media source and causes issues
                    // when the video becomes visible again. Just pause playback.
                } catch (e: Exception) {
                    Timber.e("VideoManager - Error pausing video: $e")
                }
            }
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
     * Get or create an ExoPlayer instance for a video
     * Only creates new players for visible or preloading videos
     */
    fun getVideoPlayer(context: Context, videoMid: MimeiId, videoUrl: String, videoType: MediaType? = null): ExoPlayer {
        // Mark as preloaded if it was in the preload queue
        val wasPreloading = preloadQueue.contains(videoMid)
        preloadedVideos.add(videoMid)
        preloadQueue.remove(videoMid)

        val isReusing = videoPlayers.containsKey(videoMid)

        return videoPlayers.getOrPut(videoMid) {
            try {
                val player = createExoPlayer(context, videoUrl, videoType ?: MediaType.Video)
                player
            } catch (e: Exception) {
                Timber.tag("VideoManager").e("Player creation failed: ${e.message}")
                // If creation fails, remove from map and rethrow
                videoPlayers.remove(videoMid)
                throw e
            }
        }.also { player ->
            // Reset player state when reusing an existing player
            if (isReusing) {
                resetPlayerState(player)
            }
        }
    }

    /**
     * Reset player state to ensure proper playback when reused
     */
    private fun resetPlayerState(player: ExoPlayer) {
        try {
            // Don't stop if player is already ready - just pause
            if (player.playbackState == Player.STATE_READY) {
                player.playWhenReady = false
                return
            }

            // Stop playback and reset to beginning
            player.stop()
            player.seekTo(0)
            player.playWhenReady = false

            // Clear any error state
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
        } catch (e: Exception) {
            Timber.e("VideoManager - Error resetting player state: $e")
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
        videoPlayers[videoMid]?.let { player ->
            player.playWhenReady = false
        }
    }

    /**
     * Resume a specific video
     */
    fun resumeVideo(videoMid: MimeiId, shouldPlay: Boolean = true) {
        videoPlayers[videoMid]?.let { player ->
            player.playWhenReady = shouldPlay
        }
    }

    /**
     * Release all video players
     * Note: This method must be called on the main thread
     */
    fun releaseAllVideos() {
        Timber.tag("VideoManager").d("🧹 RELEASING ALL VIDEOS: playerCount: ${videoPlayers.size}, activeCount: ${activeVideos.size}")
        
        // Ensure we're on the main thread
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            Timber.tag("VideoManager").e("❌ WRONG THREAD: releaseAllVideos() called on wrong thread. Current: ${Thread.currentThread().name}, Expected: main")
            throw IllegalStateException("VideoManager.releaseAllVideos() must be called on the main thread")
        }

        videoPlayers.values.forEach { player ->
            try {
                // Stop playback before releasing
                player.stop()
                player.release()
                Timber.tag("VideoManager").d("✅ PLAYER RELEASED: Successfully released player")
            } catch (e: Exception) {
                Timber.tag("VideoManager").e("❌ PLAYER RELEASE ERROR: $e")
            }
        }
        videoPlayers.clear()
        activeVideos.clear()
        visibleVideos.clear()
        preloadedVideos.clear()
        Timber.tag("VideoManager").d("✅ ALL VIDEOS RELEASED: Cleared all video collections")
    }

    // ===== PRELOADING =====

    /**
     * Preload a video in the background
     * Only preloads videos that are not visible to avoid resource waste
     */
    @kotlin.OptIn(DelicateCoroutinesApi::class)
    fun preloadVideo(context: Context, videoMid: MimeiId, videoUrl: String, videoType: MediaType? = null) {
        // Don't preload if video is already visible
        if (isVideoVisible(videoMid)) {
            return
        }

        // No player count limit - let system memory warnings handle memory pressure

        if (videoPlayers.containsKey(videoMid) || preloadedVideos.contains(videoMid)) {
            return // Already cached or preloaded
        }

        if (!preloadQueue.contains(videoMid)) {
            preloadQueue.add(videoMid)

            // Start preloading in background
            val job = GlobalScope.launch(Dispatchers.Main) {
                try {
                    // Throttle concurrent player creation on main thread
                    preloadSemaphore.acquire()
                    if (!isActive) return@launch
                    val player = createExoPlayer(context, videoUrl, videoType ?: MediaType.Video)
                    // Add to cache on main thread
                    // No player count limit - let system memory warnings handle memory pressure
                    if (!isActive) {
                        try { player.release() } catch (_: Exception) {}
                        return@launch
                    }
                    videoPlayers[videoMid] = player
                    preloadedVideos.add(videoMid)
                    preloadQueue.remove(videoMid)
                    Timber.tag("preloadVideo").d("Successfully preloaded $videoMid")
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
     * Check if a video is preloaded
     */
    fun isVideoPreloaded(videoMid: MimeiId): Boolean {
        return preloadedVideos.contains(videoMid) || videoPlayers.containsKey(videoMid)
    }

    // ===== FULL-SCREEN MANAGEMENT =====

    /**
     * Get the dedicated full screen video player
     */
    fun getFullScreenPlayer(context: Context): ExoPlayer {
        if (fullScreenPlayer == null) {
            Timber.d("VideoManager - Creating dedicated full screen player")
            fullScreenPlayer = ExoPlayer.Builder(context).build()
        }
        return fullScreenPlayer!!
    }

    /**
     * Load a video into the full screen player
     * Uses the same cache-aware data source factory as createExoPlayer for optimal performance
     * For HLS videos: tries master.m3u8 first, then playlist.m3u8 if that fails
     */
    fun loadVideo(context: Context, videoUrl: String, videoType: MediaType? = null) {
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
                        TODO()
                    }

                    Player.STATE_IDLE -> {
                        TODO()
                    }

                    Player.STATE_READY -> {
                        TODO()
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
    fun getCachedVideoPlayer(videoMid: MimeiId): ExoPlayer? = videoPlayers[videoMid]

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
        val player = videoPlayers[videoMid] ?: return false

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
                // Stop playback and clear media sources to prevent leaks
                player.stop()
                player.clearMediaItems()
                
                // Clear video surface to prevent surface leaks
                player.clearVideoSurface()
                
                // Release the player completely
                player.release()
                
                Timber.tag("VideoManager").d("✅ PLAYER RELEASED: videoMid: $videoMid")
            } catch (e: Exception) {
                Timber.tag("VideoManager").w("⚠️ Error releasing player for $videoMid: ${e.message}")
            }
        }
        
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
        return "Cached videos: ${getCachedVideoCount()}, Active videos: ${getActiveVideoCount()}, Visible: ${visibleVideos.size}, Preloaded: ${preloadedVideos.size}"
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
        return videoPlayers[videoMid]?.also {
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