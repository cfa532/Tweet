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
import kotlinx.coroutines.withContext
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
 * - Memory management and cleanup
 * - Full-screen video support
 * - Disk caching for video segments
 */
@OptIn(UnstableApi::class)
object VideoManager {

    // ===== PLAYER MANAGEMENT =====
    // Thread-safe map to store ExoPlayer instances by video mid
    private val videoPlayers = ConcurrentHashMap<MimeiId, ExoPlayer>()

    // Track which videos are currently being used
    private val activeVideos = ConcurrentHashMap<MimeiId, Int>()

    // ===== VISIBILITY TRACKING =====
    // Track which videos are currently visible (user is viewing them)
    private val visibleVideos = mutableSetOf<MimeiId>()

    // Track which videos are being preloaded
    private val preloadingVideos = mutableSetOf<MimeiId>()

    // ===== FULL-SCREEN MANAGEMENT =====
    private var fullScreenPlayer: ExoPlayer? = null
    private var currentVideoUrl: String? = null
    private var autoReplayListener: Player.Listener? = null
    private var currentFullScreenVideoMid: MimeiId? = null

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
        if (videoCache == null) {
            val cacheDir = File(context.cacheDir, VIDEO_CACHE_DIR)
            val evictor = LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES)
            val databaseProvider = StandaloneDatabaseProvider(context)
            videoCache = SimpleCache(cacheDir, evictor, databaseProvider)
        }
        return videoCache ?: throw IllegalStateException("Video cache was not initialized")
    }

    /**
     * Clear video cache
     */
    fun clearVideoCache(context: Context) {
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
        Timber.d("VideoManager - Video marked visible: $videoMid")
    }

    /**
     * Mark a video as not visible (user has scrolled past it)
     * This stops loading the video to save resources
     */
    fun markVideoNotVisible(videoMid: MimeiId) {
        visibleVideos.remove(videoMid)
        markVideoInactive(videoMid)
        Timber.d("VideoManager - Video marked not visible: $videoMid")

        // Don't pause the video if it's currently in full-screen mode
        if (!isVideoInFullScreen(videoMid)) {
            // Cancel any ongoing preload/network loading for this video
            cancelPreload(videoMid)
            // Stop buffering/loading to free up resources
            videoPlayers[videoMid]?.let { player ->
                try {
                    player.playWhenReady = false
                    player.stop()
                } catch (e: Exception) {
                    Timber.d("VideoManager - Error stopping non-visible video $videoMid: $e")
                }
            }
        } else {
            Timber.d("VideoManager - Not pausing video $videoMid because it's in full-screen mode")
        }
    }

    /**
     * Check if a video is currently visible
     */
    fun isVideoVisible(videoMid: MimeiId): Boolean = visibleVideos.contains(videoMid)

    /**
     * Get currently visible videos
     */
    fun getVisibleVideos(): Set<MimeiId> = visibleVideos.toSet()

    /**
     * Get currently preloading videos
     */
    fun getPreloadingVideos(): Set<MimeiId> = preloadingVideos.toSet()

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
                    it.type == us.fireshare.tweet.datamodel.MediaType.Video ||
                            it.type == us.fireshare.tweet.datamodel.MediaType.HLS_VIDEO
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

                            preloadVideo(context, attachment.mid, mediaUrl)
                            addedThisCycle++
                            Timber.d("VideoManager - Preloading video: ${attachment.mid} from tweet $i")
                        } catch (e: Exception) {
                            Timber.e("VideoManager - Failed to preload video: ${attachment.mid}, error: ${e.message}")
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
    fun getVideoPlayer(context: Context, videoMid: MimeiId, videoUrl: String): ExoPlayer {
        // No player count limit - let system memory warnings handle memory pressure

        // Mark as preloaded if it was in the preload queue
        preloadedVideos.add(videoMid)
        preloadQueue.remove(videoMid)

        val isReusing = videoPlayers.containsKey(videoMid)

        return videoPlayers.getOrPut(videoMid) {
            Timber.tag("getVideoPlayer").d("Creating new player for $videoMid")

            try {
                val player = createExoPlayer(context, videoUrl, MediaType.Video)
                player
            } catch (e: Exception) {
                Timber.tag("getVideoPlayer")
                    .e("VideoManager - Error creating ExoPlayer for video: $videoMid")
                // If creation fails, remove from map and rethrow
                videoPlayers.remove(videoMid)
                throw e
            }
        }.also { player ->
            // Reset player state when reusing an existing player
            if (isReusing) {
                Timber.tag("getVideoPlayer").d("Resetting reused player")
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
    }

    /**
     * Pause a specific video
     */
    fun pauseVideo(videoMid: MimeiId) {
        videoPlayers[videoMid]?.let { player ->
            player.playWhenReady = false
            Timber.d("VideoManager - Paused video: $videoMid")
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
     * Release a specific video player
     * Note: This method must be called on the main thread
     */
    fun releaseVideo(videoMid: MimeiId) {
        // Ensure we're on the main thread
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            Timber.e("VideoManager - releaseVideo() called on wrong thread. Current: ${Thread.currentThread().name}, Expected: main")
            throw IllegalStateException("VideoManager.releaseVideo() must be called on the main thread")
        }

        videoPlayers.remove(videoMid)?.let { player ->
            try {
                // Stop playback before releasing
                player.stop()
                player.release()
                activeVideos.remove(videoMid)
                visibleVideos.remove(videoMid)
                preloadedVideos.remove(videoMid)
            } catch (e: Exception) {
                Timber.e("VideoManager - Error releasing video $videoMid: $e")
            }
        }
    }

    /**
     * Release all video players
     * Note: This method must be called on the main thread
     */
    fun releaseAllVideos() {
        // Ensure we're on the main thread
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            Timber.e("VideoManager - releaseAllVideos() called on wrong thread. Current: ${Thread.currentThread().name}, Expected: main")
            throw IllegalStateException("VideoManager.releaseAllVideos() must be called on the main thread")
        }

        videoPlayers.values.forEach { player ->
            try {
                // Stop playback before releasing
                player.stop()
                player.release()
            } catch (e: Exception) {
                Timber.e("VideoManager - Error releasing player: $e")
            }
        }
        videoPlayers.clear()
        activeVideos.clear()
        visibleVideos.clear()
        preloadedVideos.clear()
    }

    // ===== PRELOADING =====

    /**
     * Preload a video in the background
     * Only preloads videos that are not visible to avoid resource waste
     */
    @kotlin.OptIn(DelicateCoroutinesApi::class)
    fun preloadVideo(context: Context, videoMid: MimeiId, videoUrl: String) {
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
                    val player = createExoPlayer(context, videoUrl, MediaType.Video)
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
     */
    fun loadVideo(context: Context, videoUrl: String) {
        if (currentVideoUrl == videoUrl) {
            return
        }

        currentVideoUrl = videoUrl

        val player = getFullScreenPlayer(context)

        try {
            player.stop()
            // Create a simple media source - this is a placeholder
            // In practice, you'd want to use the same media source creation as VideoManager
            val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context)
            val mediaSourceFactory =
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
            val mediaSource = mediaSourceFactory.createMediaSource(
                androidx.media3.common.MediaItem.fromUri(videoUrl)
            )
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
     * Get the number of active videos
     */
    fun getActiveVideoCount(): Int = activeVideos.size

    /**
     * Get the active count for a specific video
     */
    fun getVideoActiveCount(videoMid: MimeiId): Int = activeVideos.getOrDefault(videoMid, 0)

    /**
     * Force cleanup of all inactive videos
     * This can be called when videos stop loading to recover from congestion
     */
    fun forceCleanupInactiveVideos() {
        val inactiveVideos = videoPlayers.keys.filter { !activeVideos.containsKey(it) }
        if (inactiveVideos.isNotEmpty()) {
            Timber.w("VideoManager - Force cleaning up ${inactiveVideos.size} inactive videos")
            inactiveVideos.forEach { videoMid ->
                releaseVideo(videoMid)
            }
        }
    }

    // Note: clearSignificantInactiveVideos() removed as modern Android (API 34+) 
    // only sends UI_HIDDEN and BACKGROUND memory levels

    /**
     * Attempt to recover a video that has stopped loading with thorough reset
     */
    fun attemptVideoRecovery(context: Context, videoMid: MimeiId, videoUrl: String): Boolean {
        val player = videoPlayers[videoMid] ?: return false

        Timber.d("VideoManager - Attempting thorough recovery for video: $videoMid")

        try {
            // Thorough reset: stop, clear, and reset player state
            player.stop()
            player.clearMediaItems()
            player.seekTo(0)
            
            // Reset playback state
            player.playWhenReady = false
            player.pause()

            // Create a new media source with extended timeouts and retry configuration
            val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(45000) // 45 seconds connection timeout for thorough retry
                .setReadTimeoutMs(45000)    // 45 seconds read timeout for thorough retry
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("TweetApp/1.0")

            val dataSourceFactory =
                androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)
            val mediaSourceFactory =
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
            val mediaSource = mediaSourceFactory.createMediaSource(
                androidx.media3.common.MediaItem.fromUri(videoUrl)
            )

            // Set the new media source and prepare
            player.setMediaSource(mediaSource)
            player.prepare()

            Timber.d("VideoManager - Thorough recovery attempted for video: $videoMid")
            return true
        } catch (e: Exception) {
            // Only log recovery failures at debug level to avoid noise during trials
            Timber.d("VideoManager - Thorough recovery failed for video: $videoMid, error: ${e.message}")
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
        return videoPlayers[videoMid]?.also { player ->
            Timber.tag("transferToFullScreen").d("Transferring player for $videoMid to full-screen")
            currentFullScreenVideoMid = videoMid
        }
    }

    /**
     * Return video player from full-screen mode back to preview
     */
    fun returnFromFullScreen(videoMid: MimeiId) {
        videoPlayers[videoMid]?.let { player ->
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

            retriever.release()

            if (width > 0 && height > 0) {
                width.toFloat() / height.toFloat()
            } else {
                16f / 9f // Default aspect ratio
            }
        } catch (e: Exception) {
            Timber.e("VideoManager - Error getting video aspect ratio: ${e.message}")
            16f / 9f // Default aspect ratio on error
        }
    }
}