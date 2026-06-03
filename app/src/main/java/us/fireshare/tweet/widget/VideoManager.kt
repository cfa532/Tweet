package us.fireshare.tweet.widget

import android.content.Context
import android.graphics.Bitmap
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
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.Tweet
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

    // ===== COROUTINE SCOPE =====
    // Coroutine scope for video loading that can be cancelled when needed
    private val videoLoadingScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ===== PLAYER MANAGEMENT =====
    // Thread-safe map to store ExoPlayer instances by video mid
    private val videoPlayers = ConcurrentHashMap<MimeiId, ExoPlayer>()

    // Compose-observable generation counter: incremented whenever a player is force-recreated.
    // VideoPreview uses this as a remember() key so it picks up the new player instance.
    val playerGenerations = mutableStateMapOf<MimeiId, Int>()
    val preloadGenerations = mutableStateMapOf<MimeiId, Int>()

    // Track which videos are currently being used
    private val activeVideos = ConcurrentHashMap<MimeiId, Int>()
    
    // ===== MEMORY MANAGEMENT =====
    // Cache access synchronization to prevent concurrent access issues
    private val cacheLock = Any()

    // ===== VISIBILITY TRACKING =====
    // Track which videos are currently visible (user is viewing them)
    private val visibleVideos = mutableSetOf<MimeiId>()
    private val visibleVideoCounts = ConcurrentHashMap<MimeiId, Int>()

    // Track which videos are being preloaded
    private val preloadingVideos = mutableSetOf<MimeiId>()

    // ===== FULL-SCREEN MANAGEMENT =====
    private var fullScreenPlayer: ExoPlayer? = null
    private var currentVideoUrl: String? = null
    private var autoReplayListener: Player.Listener? = null
    private var hlsFallbackListener: HLSFallbackListener? = null
    private var currentFullScreenVideoMid: MimeiId? = null
    private val fullScreenProtectedVideos = mutableSetOf<MimeiId>()
    
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
    private val currentDirectionalPreloadVideos = mutableSetOf<MimeiId>()
    private val playerAccessTimestamps = ConcurrentHashMap<MimeiId, Long>()

    // ===== CONFIGURATION =====
    private const val DIRECTIONAL_VIDEO_PRELOAD_COUNT = 4
    private const val DIRECTIONAL_VIDEO_PRELOAD_SCAN_TWEETS = 25
    private const val MAX_CONCURRENT_PRELOADS = 2
    private const val PRELOAD_MIN_BUFFER_MS = 3_000
    private const val PRELOAD_MAX_BUFFER_MS = 12_000
    private const val PRELOAD_BUFFER_FOR_PLAYBACK_MS = 500
    private const val PRELOAD_BUFFER_FOR_REBUFFER_MS = 1_000
    private const val MAX_WARM_PRELOADED_PLAYERS = 2
    private const val MAX_FEED_PLAYER_CACHE_SIZE = 10
    private const val MEMORY_PRESSURE_PLAYER_CACHE_SIZE = 6
    private const val MIN_FREE_HEAP_FOR_WARM_PRELOAD_BYTES = 64L * 1024L * 1024L

    // ===== MEMORY MONITORING =====
    // Removed custom memory monitoring - now relies on system warnings only

    // Concurrency control for preloading to avoid main-thread contention
    private val preloadSemaphore: Semaphore = Semaphore(MAX_CONCURRENT_PRELOADS)

    // Track active preload jobs by video mid for cancellation
    private val preloadJobs = ConcurrentHashMap<MimeiId, Job>()
    private val posterJobs = ConcurrentHashMap<MimeiId, Job>()
    val posterBitmaps = mutableStateMapOf<MimeiId, Bitmap>()

    // Saved playback positions so videos resume from where they were left
    // (populated in releasePlayer before the ExoPlayer is destroyed)
    private val savedPositions = ConcurrentHashMap<MimeiId, Long>()

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
     * Mark a video as visible (user is currently viewing it)
     * This allows the video to continue loading and playing
     */
    fun markVideoVisible(videoMid: MimeiId) {
        visibleVideoCounts[videoMid] = visibleVideoCounts.getOrDefault(videoMid, 0) + 1
        visibleVideos.add(videoMid)
        touchPlayer(videoMid)
        markVideoActive(videoMid)
    }

    /**
     * Mark a video as not visible (user has scrolled past it)
     * This stops loading the video to save resources, but only if no other
     * composable is actively using the same player (e.g., DetailView sharing
     * the same ExoPlayer while the feed item is being disposed).
     */
    fun markVideoNotVisible(videoMid: MimeiId) {
        val currentVisibleCount = visibleVideoCounts.getOrDefault(videoMid, 0)
        val newVisibleCount = (currentVisibleCount - 1).coerceAtLeast(0)
        if (newVisibleCount == 0) {
            visibleVideoCounts.remove(videoMid)
            visibleVideos.remove(videoMid)
        } else {
            visibleVideoCounts[videoMid] = newVisibleCount
        }
        markVideoInactive(videoMid)

        // Don't stop the video if it's currently in full-screen mode
        // or if another composable is still actively using this player
        val stillVisible = visibleVideoCounts.getOrDefault(videoMid, 0) > 0
        val stillActive = activeVideos.getOrDefault(videoMid, 0) > 0
        if (!isVideoInFullScreen(videoMid) && !stillVisible && !stillActive) {
            // Cancel any ongoing preload/network loading for this video
            cancelPreload(videoMid)
            val shouldKeepAsDirectionalPreload = isCurrentDirectionalPreload(videoMid)
            if (!shouldKeepAsDirectionalPreload) {
                releasePlayer(videoMid)
                cleanupInactivePlayers()
                return
            }

            // Current directional preloads may stay warm. Stop the player to release
            // active loading work while keeping it available for near-immediate reuse.
            videoPlayers[videoMid]?.let { player ->
                try {
                    // Save position BEFORE stopping so scroll-back can resume from here.
                    val pos = player.currentPosition
                    val dur = player.duration
                    if (pos > 0) {
                        savedPositions[videoMid] = if (dur > 0 && pos.toFloat() / dur > 0.9f) 0L else pos
                    }
                    player.clearVideoSurface()
                    player.stop()
                } catch (e: Exception) {
                    Timber.e("VideoManager - Error stopping video: $e")
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
        updateDirectionalVideoPreloads(
            context = context,
            visibleTweetIndexes = setOf(currentTweetIndex),
            direction = PreloadDirection.DOWN,
            tweets = tweets,
            startPreloading = true,
            fallbackBaseUrl = baseUrl
        )
    }

    fun updateDirectionalVideoPreloads(
        context: Context,
        visibleTweetIndexes: Set<Int>,
        direction: PreloadDirection,
        tweets: List<Tweet>,
        startPreloading: Boolean,
        fallbackBaseUrl: String = ""
    ) {
        val loadVisibleVideos = collectVideosAtIndexes(
            tweetIndexes = visibleTweetIndexes,
            tweets = tweets,
            fallbackBaseUrl = fallbackBaseUrl
        )
        val directionalVideos = collectDirectionalVideos(
            visibleTweetIndexes = visibleTweetIndexes,
            direction = direction,
            tweets = tweets,
            maxVideos = DIRECTIONAL_VIDEO_PRELOAD_COUNT
        )
        val preloadTargets = (loadVisibleVideos + directionalVideos)
            .distinctBy { it.mid }
            .take(DIRECTIONAL_VIDEO_PRELOAD_COUNT + loadVisibleVideos.size)
        val protectedIds = preloadTargets.map { it.mid }.toSet()

        synchronized(currentDirectionalPreloadVideos) {
            currentDirectionalPreloadVideos.clear()
            currentDirectionalPreloadVideos.addAll(protectedIds)
        }

        cancelStaleVideoPreloads()

        if (!startPreloading || direction == PreloadDirection.NONE) return

        cleanupInactivePlayers()
        enforcePlayerCacheLimit()

        preloadTargets.forEach { video ->
            if (!isVideoPreloaded(video.mid) &&
                !isVideoVisible(video.mid) &&
                !preloadingVideos.contains(video.mid)
            ) {
                val mediaUrl = if (video.url.isNotBlank()) {
                    video.url
                } else {
                    us.fireshare.tweet.HproseInstance.getMediaUrl(
                        video.mid,
                        video.baseUrl.ifBlank { fallbackBaseUrl }
                    ) ?: return@forEach
                }
                preloadVideo(context, video.mid, mediaUrl, video.type, warmPlayer = true)
            } else if (videoPlayers.containsKey(video.mid)) {
                ensureVideoPoster(context, video.mid, video.url, video.type, null)
            }
        }
    }

    private data class DirectionalVideo(
        val mid: MimeiId,
        val type: MediaType,
        val baseUrl: String,
        val url: String
    )

    private fun collectVideosAtIndexes(
        tweetIndexes: Set<Int>,
        tweets: List<Tweet>,
        fallbackBaseUrl: String
    ): List<DirectionalVideo> {
        if (tweets.isEmpty() || tweetIndexes.isEmpty()) return emptyList()

        val seen = mutableSetOf<MimeiId>()
        val result = mutableListOf<DirectionalVideo>()
        tweetIndexes.sorted().forEach { index ->
            val tweet = tweets.getOrNull(index) ?: return@forEach
            val baseUrl = tweet.author?.baseUrl.orEmpty()
            tweet.attachments.orEmpty().forEach { attachment ->
                if (attachment.type != MediaType.Video && attachment.type != MediaType.HLS_VIDEO) return@forEach
                if (!seen.add(attachment.mid)) return@forEach
                val url = if (!attachment.url.isNullOrBlank()) {
                    attachment.url!!
                } else {
                    us.fireshare.tweet.HproseInstance.getMediaUrl(
                        attachment.mid,
                        baseUrl.ifBlank { fallbackBaseUrl }
                    ) ?: return@forEach
                }
                result.add(DirectionalVideo(attachment.mid, attachment.type, baseUrl, url))
            }
        }
        return result
    }

    private fun collectDirectionalVideos(
        visibleTweetIndexes: Set<Int>,
        direction: PreloadDirection,
        tweets: List<Tweet>,
        maxVideos: Int
    ): List<DirectionalVideo> {
        if (tweets.isEmpty() || visibleTweetIndexes.isEmpty() || direction == PreloadDirection.NONE) {
            return emptyList()
        }

        val anchor = when (direction) {
            PreloadDirection.UP -> visibleTweetIndexes.minOrNull() ?: return emptyList()
            PreloadDirection.DOWN -> visibleTweetIndexes.maxOrNull() ?: return emptyList()
            PreloadDirection.NONE -> return emptyList()
        }.coerceIn(0, tweets.lastIndex)

        val indices: Iterable<Int> = when (direction) {
            PreloadDirection.UP -> (anchor - 1 downTo 0).asIterable()
            PreloadDirection.DOWN -> (anchor + 1 until tweets.size).asIterable()
            PreloadDirection.NONE -> emptyList()
        }

        val seen = mutableSetOf<MimeiId>()
        val result = mutableListOf<DirectionalVideo>()
        var tweetsVisited = 0
        for (index in indices) {
            val tweet = tweets.getOrNull(index) ?: continue
            tweetsVisited++
            val baseUrl = tweet.author?.baseUrl.orEmpty()
            val attachments = tweet.attachments.orEmpty()
            for (attachment in attachments) {
                if (attachment.type != MediaType.Video && attachment.type != MediaType.HLS_VIDEO) continue
                if (!seen.add(attachment.mid)) continue
                val url = us.fireshare.tweet.HproseInstance.getMediaUrl(attachment.mid, baseUrl) ?: continue
                result.add(DirectionalVideo(attachment.mid, attachment.type, baseUrl, url))
                if (result.size >= maxVideos) return result
            }
            if (tweetsVisited >= DIRECTIONAL_VIDEO_PRELOAD_SCAN_TWEETS) break
        }
        return result
    }

    private fun cancelStaleVideoPreloads() {
        val protectedPreloads = synchronized(currentDirectionalPreloadVideos) {
            currentDirectionalPreloadVideos.toSet()
        }

        preloadJobs.keys
            .filterNot { isVideoCancellationProtected(it, protectedPreloads) }
            .forEach { cancelPreload(it) }

        val stalePreloadedPlayers = videoPlayers.keys.filter { videoMid ->
            preloadedVideos.contains(videoMid) &&
                !isVideoCancellationProtected(videoMid, protectedPreloads)
        }

        stalePreloadedPlayers.forEach { videoMid ->
            releasePlayer(videoMid)
        }
    }

    private fun isVideoCancellationProtected(
        videoMid: MimeiId,
        directionalPreloads: Set<MimeiId>
    ): Boolean {
        if (activeVideos.containsKey(videoMid)) return true
        if (visibleVideos.contains(videoMid)) return true
        if (directionalPreloads.contains(videoMid)) return true
        if (isVideoProtectedForFullScreen(videoMid)) return true
        if (currentFullScreenVideoMid == videoMid) return true
        val player = videoPlayers[videoMid]
        return player?.isPlaying == true || player?.playWhenReady == true
    }

    private fun isCurrentDirectionalPreload(videoMid: MimeiId): Boolean {
        return synchronized(currentDirectionalPreloadVideos) {
            currentDirectionalPreloadVideos.contains(videoMid)
        }
    }

    private fun touchPlayer(videoMid: MimeiId) {
        playerAccessTimestamps[videoMid] = System.currentTimeMillis()
    }

    private fun isPlayerReleaseProtected(videoMid: MimeiId, protectDirectionalPreloads: Boolean = true): Boolean {
        if (activeVideos.containsKey(videoMid)) return true
        if (visibleVideos.contains(videoMid)) return true
        if (protectDirectionalPreloads && isCurrentDirectionalPreload(videoMid)) return true
        if (isVideoInFullScreen(videoMid)) return true
        if (isVideoProtectedForFullScreen(videoMid)) return true
        val player = videoPlayers[videoMid]
        return player?.isPlaying == true || player?.playWhenReady == true
    }

    private fun enforcePlayerCacheLimit(reserveSlots: Int = 0) {
        val targetSize = if (isHeapTightForWarmPreload()) {
            MEMORY_PRESSURE_PLAYER_CACHE_SIZE
        } else {
            MAX_FEED_PLAYER_CACHE_SIZE
        }
        val allowedSize = (targetSize - reserveSlots).coerceAtLeast(0)
        if (videoPlayers.size <= allowedSize) return

        val releaseCount = videoPlayers.size - allowedSize
        val releaseCandidates = videoPlayers.keys
            .filterNot { isPlayerReleaseProtected(it) }
            .sortedBy { playerAccessTimestamps[it] ?: 0L }
            .take(releaseCount)

        if (releaseCandidates.isNotEmpty()) {
            Timber.tag("VideoManager").d(
                "🧹 CACHE LIMIT: Releasing ${releaseCandidates.size} players, size=${videoPlayers.size}, target=$allowedSize"
            )
            releaseCandidates.forEach { releasePlayer(it) }
        }
    }

    private fun releasePlayersForMemoryPressure() {
        val releaseCandidates = videoPlayers.keys
            .filterNot { isPlayerReleaseProtected(it, protectDirectionalPreloads = false) }
            .sortedBy { playerAccessTimestamps[it] ?: 0L }

        if (releaseCandidates.isNotEmpty()) {
            Timber.tag("VideoManager").w(
                "🧹 MEMORY PRESSURE: Releasing ${releaseCandidates.size} invisible players"
            )
            releaseCandidates.forEach { releasePlayer(it) }
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
        synchronized(currentDirectionalPreloadVideos) {
            currentDirectionalPreloadVideos.clear()
        }
        Timber.d("VideoManager - Stopped all preloading")
    }

    /**
     * Fullscreen is the user's active playback target. Drop background preload work and
     * release stale preloaded players so weak IPFS links are not shared with off-screen videos.
     */
    fun suspendFeedActivityForFullScreen(protecting: MimeiId) {
        markVideoInFullScreen(protecting)
        stopAllPreloading()

        val protectedForFullScreen = synchronized(fullScreenProtectedVideos) {
            fullScreenProtectedVideos.toSet()
        } + protecting

        val stalePreloadedPlayers = videoPlayers.keys.filter { videoMid ->
            preloadedVideos.contains(videoMid) &&
                videoMid !in protectedForFullScreen &&
                activeVideos.getOrDefault(videoMid, 0) == 0
        }

        stalePreloadedPlayers.forEach { videoMid ->
            releasePlayer(videoMid)
        }

        videoPlayers.forEach { (videoMid, player) ->
            if (videoMid != protecting && videoMid !in protectedForFullScreen) {
                try {
                    player.playWhenReady = false
                    player.pause()
                } catch (e: Exception) {
                    Timber.tag("VideoManager").w("Error pausing feed player for fullscreen: ${e.message}")
                }
            }
        }

        Timber.tag("VideoManager").d(
            "Suspended feed activity for fullscreen $protecting; released ${stalePreloadedPlayers.size} preloaded players"
        )
    }

    // ===== PLAYER MANAGEMENT =====

    /**
     * Get or create an ExoPlayer instance for a video.
     *
     * @param resolvedHlsUrl Pre-resolved HLS URL from [HlsUrlResolver]; when non-null it is
     *   passed directly to [createExoPlayer], skipping the master/playlist guessing entirely.
     */
    fun getVideoPlayer(
        context: Context,
        videoMid: MimeiId,
        videoUrl: String,
        videoType: MediaType? = null,
        resolvedHlsUrl: String? = null
    ): ExoPlayer {
        if (preloadQueue.remove(videoMid) || synchronized(currentDirectionalPreloadVideos) {
                currentDirectionalPreloadVideos.contains(videoMid)
            }
        ) {
            preloadedVideos.add(videoMid)
        }

        val isReusing = videoPlayers.containsKey(videoMid)

        // When offline, only reuse existing players — don't create new ones that trigger network
        if (!us.fireshare.tweet.HproseInstance.isOnline.value && !isReusing) {
            Timber.tag("VideoManager").d("Offline: creating placeholder player for $videoMid (no network fetch)")
            val player = ExoPlayer.Builder(context).build()
            videoPlayers[videoMid] = player
            return player
        }

        return videoPlayers.getOrPut(videoMid) {
            try {
                if (isHeapTightForWarmPreload()) {
                    releasePlayersForMemoryPressure()
                }
                enforcePlayerCacheLimit(reserveSlots = 1)
                Timber.tag("VideoPlaybackDebug").d(
                    "Creating player videoMid=$videoMid type=$videoType resolvedHls=${resolvedHlsUrl != null}"
                )
                val player = createExoPlayer(
                    context,
                    videoUrl,
                    videoType ?: MediaType.Video,
                    resolvedHlsUrl = resolvedHlsUrl
                )
                player
            } catch (oom: OutOfMemoryError) {
                Timber.tag("VideoManager").e("Player creation OOM for $videoMid; releasing unprotected feed players and retrying once")
                releasePlayersForMemoryPressure()
                val player = createExoPlayer(
                    context,
                    videoUrl,
                    videoType ?: MediaType.Video,
                    resolvedHlsUrl = resolvedHlsUrl
                )
                player
            } catch (e: Exception) {
                Timber.tag("VideoManager").e("Player creation failed: ${e.message}")
                videoPlayers.remove(videoMid)
                throw e
            }
        }.also { player ->
            touchPlayer(videoMid)
            // Pop saved position before resetPlayerState can overwrite with seekTo(0)
            val savedPos = savedPositions.remove(videoMid)
            Timber.tag("VideoPlaybackDebug").d(
                "Acquire player videoMid=$videoMid reused=$isReusing state=${playerStateName(player.playbackState)} " +
                    "pos=${player.currentPosition}ms buffered=${player.bufferedPosition}ms duration=${player.duration}ms " +
                    "playWhenReady=${player.playWhenReady} isPlaying=${player.isPlaying} savedPos=${savedPos ?: -1L}"
            )
            if (isReusing) {
                resetPlayerState(videoMid, player)
            }
            if (savedPos != null && savedPos > 0) {
                player.seekTo(savedPos)
                Timber.tag("VideoManager").d("Restored position ${savedPos}ms for $videoMid")
            }
        }
    }

    /**
     * Reset player state to ensure proper playback when reused
     */
    private fun resetPlayerState(videoMid: MimeiId, player: ExoPlayer) {
        try {
            Timber.tag("VideoPlaybackDebug").d(
                "Reset player videoMid=$videoMid before state=${playerStateName(player.playbackState)} " +
                    "pos=${player.currentPosition}ms buffered=${player.bufferedPosition}ms playWhenReady=${player.playWhenReady}"
            )
            when (player.playbackState) {
                Player.STATE_READY, Player.STATE_BUFFERING -> {
                    // Don't stop — just pause to preserve already-buffered data
                    player.playWhenReady = false
                }
                Player.STATE_IDLE -> {
                    player.playWhenReady = false
                    if (player.mediaItemCount > 0) {
                        player.prepare()
                    }
                }
                Player.STATE_ENDED -> {
                    player.playWhenReady = false
                    player.seekTo(0)
                }
            }
            Timber.tag("VideoPlaybackDebug").d(
                "Reset player videoMid=$videoMid after state=${playerStateName(player.playbackState)} " +
                    "pos=${player.currentPosition}ms buffered=${player.bufferedPosition}ms playWhenReady=${player.playWhenReady}"
            )
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
        enforcePlayerCacheLimit()
    }

    /**
     * Pause a specific video
     */
    fun pauseVideo(videoMid: MimeiId) {
        videoPlayers[videoMid]?.let { player ->
            player.playWhenReady = false
            player.pause()
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
     * Stop all video players to prevent network requests when offline.
     * Players are kept alive so they can resume when back online.
     */
    fun stopAllVideos() {
        Timber.tag("VideoManager").d("Stopping all ${videoPlayers.size} video players (offline)")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
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
                player.clearVideoSurface()
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
        visibleVideoCounts.clear()
        preloadedVideos.clear()
        playerAccessTimestamps.clear()
        preloadGenerations.clear()
        preloadJobs.values.forEach { it.cancel() }
        preloadJobs.clear()
        preloadingVideos.clear()
        preloadQueue.clear()
        posterJobs.values.forEach { it.cancel() }
        posterJobs.clear()
        posterBitmaps.clear()
        savedPositions.clear()
        synchronized(currentDirectionalPreloadVideos) {
            currentDirectionalPreloadVideos.clear()
        }
        synchronized(fullScreenProtectedVideos) {
            fullScreenProtectedVideos.clear()
        }
        Timber.tag("VideoManager").d("✅ ALL VIDEOS RELEASED: Cleared all video collections")
    }

    // ===== PRELOADING =====

    /**
     * Warm video metadata or a bounded hidden player in the background.
     *
     * By default this intentionally avoids creating an ExoPlayer because broad hidden-player
     * preloads can allocate MediaCodec instances. Tweet-list directional preloading opts into
     * [warmPlayer] for only the next small scroll-direction window, using reduced buffers.
     */
    fun preloadVideo(
        context: Context,
        videoMid: MimeiId,
        videoUrl: String,
        videoType: MediaType? = null,
        warmPlayer: Boolean = false
    ) {
        if (!us.fireshare.tweet.HproseInstance.isOnline.value) return
        // Don't preload if video is already visible
        if (isVideoVisible(videoMid) && !warmPlayer) {
            return
        }

        if (videoPlayers.containsKey(videoMid) || preloadedVideos.contains(videoMid)) {
            return // Already cached or preloaded
        }

        if (!preloadQueue.contains(videoMid)) {
            preloadQueue.add(videoMid)

            val job = videoLoadingScope.launch {
                var semaphoreAcquired = false
                try {
                    if (us.fireshare.tweet.HproseInstance.isReliabilityBlacklistedMedia(videoMid)) {
                        Timber.tag("preloadVideo").d("Skip blacklisted media preload: $videoMid")
                        preloadQueue.remove(videoMid)
                        return@launch
                    }

                    preloadingVideos.add(videoMid)
                    preloadSemaphore.acquire()
                    semaphoreAcquired = true
                    if (!isActive) return@launch

                    val resolvedHlsUrl = if (videoType == MediaType.HLS_VIDEO) {
                        HlsUrlResolver.resolve(context, videoUrl)
                    } else null

                    if (warmPlayer && canCreateWarmPreload(videoMid)) {
                        val player = createExoPlayer(
                            context = context,
                            url = videoUrl,
                            mediaType = videoType ?: MediaType.Video,
                            resolvedHlsUrl = resolvedHlsUrl,
                            minBufferMs = PRELOAD_MIN_BUFFER_MS,
                            maxBufferMs = PRELOAD_MAX_BUFFER_MS,
                            bufferForPlaybackMs = PRELOAD_BUFFER_FOR_PLAYBACK_MS,
                            bufferForPlaybackAfterRebufferMs = PRELOAD_BUFFER_FOR_REBUFFER_MS
                        )
                        if (!isActive) {
                            try {
                                player.clearVideoSurface()
                                player.release()
                            } catch (_: Exception) { }
                            return@launch
                        }
                        player.playWhenReady = false
                        videoPlayers[videoMid] = player
                        touchPlayer(videoMid)
                        preloadedVideos.add(videoMid)
                        preloadGenerations[videoMid] = (preloadGenerations[videoMid] ?: 0) + 1
                        ensureVideoPoster(context, videoMid, videoUrl, videoType, resolvedHlsUrl)
                        Timber.tag("preloadVideo").d("Prepared warm player for $videoMid")
                    } else {
                        Timber.tag("preloadVideo").d("Warmed video metadata for $videoMid")
                    }

                    preloadQueue.remove(videoMid)
                } catch (oom: OutOfMemoryError) {
                    preloadQueue.remove(videoMid)
                    Timber.tag("VideoManager").e("Warm video preload ran out of memory for $videoMid; releasing unprotected feed players")
                    releasePlayersForMemoryPressure()
                } catch (e: Exception) {
                    preloadQueue.remove(videoMid)
                    Timber.e("VideoManager - Failed to warm video preload: $videoMid, error: ${e.message}")
                } finally {
                    if (semaphoreAcquired) preloadSemaphore.release()
                    preloadingVideos.remove(videoMid)
                    preloadJobs.remove(videoMid)
                }
            }
            preloadJobs[videoMid] = job
        }
    }

    private fun canCreateWarmPreload(videoMid: MimeiId): Boolean {
        if (videoPlayers.containsKey(videoMid)) return false
        if (isHeapTightForWarmPreload()) {
            releasePlayersForMemoryPressure()
            return false
        }
        enforcePlayerCacheLimit(reserveSlots = 1)
        val warmPreloadCount = preloadedVideos.count { videoPlayers.containsKey(it) }
        return warmPreloadCount < MAX_WARM_PRELOADED_PLAYERS
    }

    private fun isHeapTightForWarmPreload(): Boolean {
        val runtime = Runtime.getRuntime()
        val usedBytes = runtime.totalMemory() - runtime.freeMemory()
        val remainingBytes = runtime.maxMemory() - usedBytes
        return remainingBytes < MIN_FREE_HEAP_FOR_WARM_PRELOAD_BYTES
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

    fun ensureVideoPoster(
        context: Context,
        videoMid: MimeiId,
        videoUrl: String?,
        videoType: MediaType?,
        resolvedHlsUrl: String?
    ) {
        if (videoUrl.isNullOrBlank()) return
        if (posterBitmaps.containsKey(videoMid)) return
        if (posterJobs[videoMid]?.isActive == true) return
        if (isRemotePosterGenerationUnsupported(videoUrl, videoType, resolvedHlsUrl)) {
            return
        }

        posterJobs[videoMid] = videoLoadingScope.launch {
            val poster = withContext(Dispatchers.IO) {
                createPosterBitmap(context, videoUrl, videoType, resolvedHlsUrl)
            }
            if (poster != null && !poster.isRecycled) {
                posterBitmaps[videoMid] = poster
            }
            posterJobs.remove(videoMid)
        }
    }

    private fun createPosterBitmap(
        context: Context,
        videoUrl: String,
        videoType: MediaType?,
        resolvedHlsUrl: String?
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            val sourceUrl = when {
                !resolvedHlsUrl.isNullOrBlank() -> resolvedHlsUrl
                videoType == MediaType.HLS_VIDEO -> {
                    val baseUrl = if (videoUrl.endsWith("/")) videoUrl else "$videoUrl/"
                    "${baseUrl}master.m3u8"
                }
                else -> videoUrl
            }

            if (isRemotePosterGenerationUnsupported(sourceUrl, videoType, resolvedHlsUrl)) {
                return null
            }

            if (sourceUrl.startsWith("http://") || sourceUrl.startsWith("https://")) {
                retriever.setDataSource(sourceUrl, emptyMap())
            } else {
                retriever.setDataSource(context, Uri.parse(sourceUrl))
            }

            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            val maxDimension = 640
            val largerDimension = maxOf(frame.width, frame.height)
            if (largerDimension <= maxDimension) {
                frame
            } else {
                val scale = maxDimension.toFloat() / largerDimension.toFloat()
                Bitmap.createScaledBitmap(
                    frame,
                    (frame.width * scale).toInt().coerceAtLeast(1),
                    (frame.height * scale).toInt().coerceAtLeast(1),
                    true
                ).also {
                    if (it !== frame) frame.recycle()
                }
            }
        } catch (e: Exception) {
            Timber.tag("VideoManager").d("Poster generation failed for $videoUrl: ${e.message}")
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) { }
        }
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

        val resumePosition = player.currentPosition.coerceAtLeast(0L)
        val wasPlayWhenReady = player.playWhenReady
        Timber.tag("VideoPlaybackDebug").d(
            "Attempting recovery videoMid=$videoMid type=$videoType software=$forceSoftwareDecoder " +
                "state=${playerStateName(player.playbackState)} pos=${resumePosition}ms buffered=${player.bufferedPosition}ms " +
                "duration=${player.duration}ms playWhenReady=${player.playWhenReady} isPlaying=${player.isPlaying} " +
                "mediaItems=${player.mediaItemCount}"
        )

        try {
            // Keep the last known position for progressive streams. Restarting from zero
            // during a transient retry makes playback look like it is looping backward.
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
            if (resumePosition > 0) {
                player.setMediaSource(mediaSource, resumePosition)
            } else {
                player.setMediaSource(mediaSource)
            }
            player.prepare()
            player.playWhenReady = wasPlayWhenReady

            Timber.tag("VideoPlaybackDebug").d(
                "Recovery prepared videoMid=$videoMid resume=${resumePosition}ms state=${playerStateName(player.playbackState)} " +
                    "playWhenReady=${player.playWhenReady}"
            )

            return true
        } catch (e: Exception) {
            // Only log recovery failures at debug level to avoid noise during trials
            Timber.tag("VideoPlaybackDebug").e(e, "Recovery failed videoMid=$videoMid pos=${resumePosition}ms")
            return false
        }
    }
    
    /**
     * Clean up truly inactive video players (not being used by any Composable)
     * This prevents memory leaks by releasing players that are no longer referenced
     */
    fun cleanupInactivePlayers() {
        val inactivePlayers = videoPlayers.keys.filter { videoMid ->
            !activeVideos.containsKey(videoMid) &&
                !visibleVideos.contains(videoMid) &&
                !isVideoInFullScreen(videoMid) &&
                !isVideoProtectedForFullScreen(videoMid) &&
                !isCurrentDirectionalPreload(videoMid)
        }

        if (inactivePlayers.isNotEmpty()) {
            Timber.tag("VideoManager").d("🧹 CLEANUP: Releasing ${inactivePlayers.size} inactive players")
            inactivePlayers.forEach { videoMid ->
                releasePlayer(videoMid)
            }
        }
    }

    /**
     * Deferred version of [cleanupInactivePlayers]. Waits 500ms before cleaning up
     * so that incoming screens have time to mark their players as active/visible.
     * This prevents a race condition during navigation where the outgoing screen's
     * onDispose releases players that the incoming screen just created.
     */
    fun cleanupInactivePlayersDeferred() {
        videoLoadingScope.launch {
            delay(500L)
            cleanupInactivePlayers()
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
                // Save position before release (only if not already saved by markVideoNotVisible).
                if (!savedPositions.containsKey(videoMid)) {
                    val pos = player.currentPosition
                    val dur = player.duration
                    if (pos > 0) {
                        savedPositions[videoMid] = if (dur > 0 && pos.toFloat() / dur > 0.9f) 0L else pos
                    }
                }

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
        visibleVideoCounts.remove(videoMid)
        preloadedVideos.remove(videoMid)
        playerAccessTimestamps.remove(videoMid)
        preloadGenerations.remove(videoMid)
        preloadQueue.remove(videoMid)
        preloadingVideos.remove(videoMid)
        preloadJobs.remove(videoMid)?.cancel()
        posterJobs.remove(videoMid)?.cancel()
        posterBitmaps.remove(videoMid)
        synchronized(currentDirectionalPreloadVideos) {
            currentDirectionalPreloadVideos.remove(videoMid)
        }
        synchronized(fullScreenProtectedVideos) {
            fullScreenProtectedVideos.remove(videoMid)
        }
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
            visibleVideoCounts.remove(videoMid)
            preloadedVideos.remove(videoMid)
            playerAccessTimestamps.remove(videoMid)
            preloadGenerations.remove(videoMid)
            preloadQueue.remove(videoMid)
            preloadingVideos.remove(videoMid)
            preloadJobs.remove(videoMid)?.cancel()
            posterJobs.remove(videoMid)?.cancel()
            posterBitmaps.remove(videoMid)
            savedPositions.remove(videoMid)
            synchronized(currentDirectionalPreloadVideos) {
                currentDirectionalPreloadVideos.remove(videoMid)
            }

            // Create a completely new player with software decoder to avoid MediaCodec failures
            val newPlayer = createExoPlayer(context, videoUrl, videoType ?: MediaType.Video, forceSoftwareDecoder = true)
            videoPlayers[videoMid] = newPlayer
            touchPlayer(videoMid)
            // Notify Compose that VideoPreview should re-read the player for this video.
            playerGenerations[videoMid] = (playerGenerations[videoMid] ?: 0) + 1

            Timber.tag("VideoManager").d("✅ PLAYER FORCE RECREATED WITH SOFTWARE DECODER: videoMid: $videoMid")
            return true
            
        } catch (e: Exception) {
            Timber.tag("VideoManager").e("❌ FORCE RECREATION FAILED: videoMid: $videoMid, error: ${e.message}")
            // Clean up any partial state
            videoPlayers.remove(videoMid)
            visibleVideos.remove(videoMid)
            visibleVideoCounts.remove(videoMid)
            preloadedVideos.remove(videoMid)
            playerAccessTimestamps.remove(videoMid)
            preloadGenerations.remove(videoMid)
            preloadQueue.remove(videoMid)
            preloadingVideos.remove(videoMid)
            preloadJobs.remove(videoMid)?.cancel()
            posterJobs.remove(videoMid)?.cancel()
            posterBitmaps.remove(videoMid)
            synchronized(currentDirectionalPreloadVideos) {
                currentDirectionalPreloadVideos.remove(videoMid)
            }
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
     * Move a prepared feed/preload player into the independent full-screen manager.
     *
     * The player is removed from feed ownership without releasing it, preserving its
     * prepared HLS playlist, buffered media, and decoder state for fullscreen playback.
     */
    fun takePlayerForFullScreen(videoMid: MimeiId): ExoPlayer? {
        val player = videoPlayers.remove(videoMid) ?: return null
        cancelPreload(videoMid)
        activeVideos.remove(videoMid)
        visibleVideos.remove(videoMid)
        visibleVideoCounts.remove(videoMid)
        preloadedVideos.remove(videoMid)
        playerAccessTimestamps.remove(videoMid)
        preloadGenerations.remove(videoMid)
        synchronized(currentDirectionalPreloadVideos) {
            currentDirectionalPreloadVideos.remove(videoMid)
        }
        playerGenerations[videoMid] = (playerGenerations[videoMid] ?: 0) + 1
        currentFullScreenVideoMid = videoMid
        try {
            player.clearVideoSurface()
            player.playWhenReady = false
        } catch (e: Exception) {
            Timber.tag("takePlayerForFullScreen").w("Error detaching player for $videoMid: ${e.message}")
        }
        Timber.tag("takePlayerForFullScreen").d("Handed off prepared player for $videoMid to full-screen")
        return player
    }

    /**
     * Mark a video as owned by an independent full-screen player.
     */
    fun markVideoInFullScreen(videoMid: MimeiId) {
        if (currentFullScreenVideoMid != videoMid) {
            playerGenerations[videoMid] = (playerGenerations[videoMid] ?: 0) + 1
        }
        currentFullScreenVideoMid = videoMid
    }

    fun protectVideosForFullScreen(videoMids: Collection<MimeiId>) {
        synchronized(fullScreenProtectedVideos) {
            fullScreenProtectedVideos.clear()
            fullScreenProtectedVideos.addAll(videoMids)
        }
    }

    fun clearFullScreenProtectedVideos() {
        synchronized(fullScreenProtectedVideos) {
            fullScreenProtectedVideos.clear()
        }
    }

    private fun isVideoProtectedForFullScreen(videoMid: MimeiId): Boolean {
        return synchronized(fullScreenProtectedVideos) {
            fullScreenProtectedVideos.contains(videoMid)
        }
    }

    /**
     * Return video player from full-screen mode back to preview
     */
    fun returnFromFullScreen(videoMid: MimeiId) {
        videoPlayers[videoMid]?.let {
            Timber.tag("returnFromFullScreen").d("Returning player for $videoMid from full-screen")
            clearVideoInFullScreen(videoMid)
        }
    }

    /**
     * Clear full-screen ownership without disturbing a newer full-screen video.
     */
    fun clearVideoInFullScreen(videoMid: MimeiId) {
        if (currentFullScreenVideoMid == videoMid) {
            currentFullScreenVideoMid = null
            playerGenerations[videoMid] = (playerGenerations[videoMid] ?: 0) + 1
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
        visibleVideoCounts.clear()
        preloadingVideos.clear()
        preloadedVideos.clear()
        playerAccessTimestamps.clear()
        preloadGenerations.clear()
        preloadQueue.clear()
        synchronized(currentDirectionalPreloadVideos) {
            currentDirectionalPreloadVideos.clear()
        }
        synchronized(fullScreenProtectedVideos) {
            fullScreenProtectedVideos.clear()
        }
        preloadJobs.values.forEach { it.cancel() }
        preloadJobs.clear()
        posterJobs.values.forEach { it.cancel() }
        posterJobs.clear()
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
                if (error.isExpectedNetworkPlaybackIssue()) {
                    Timber.w("VideoManager - HLS attempts hit network issues for URL: $videoUrl")
                } else {
                    Timber.e("VideoManager - All HLS attempts failed for URL: $videoUrl")
                    Timber.e("VideoManager - Final error: ${error.message}")
                }
            }
        }
    }

    private fun isRemotePosterGenerationUnsupported(
        videoUrl: String,
        videoType: MediaType?,
        resolvedHlsUrl: String?
    ): Boolean {
        val isRemote = videoUrl.startsWith("http://") || videoUrl.startsWith("https://")
        val usesRemoteHls = videoType == MediaType.HLS_VIDEO || !resolvedHlsUrl.isNullOrBlank()
        val usesRemoteProgressive = resolvedHlsUrl.isNullOrBlank() && videoType != MediaType.HLS_VIDEO
        return isRemote && (usesRemoteHls || usesRemoteProgressive)
    }

    private fun playerStateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }
}
