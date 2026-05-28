package us.fireshare.tweet.widget

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.BuildConfig
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.datamodel.MediaItem
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.getMimeiKeyFromUrl

/**
 * Singleton manager for the independent fullscreen video player.
 * Handles automatic video progression and maintains tweet list context.
 */
object FullScreenPlayerManager {
    private const val FULLSCREEN_PROTECTED_NEIGHBOR_COUNT = 2
    private const val FULLSCREEN_MIN_BUFFER_MS = 30_000
    private const val FULLSCREEN_MAX_BUFFER_MS = 90_000
    private const val FULLSCREEN_BUFFER_FOR_PLAYBACK_MS = 1_500
    private const val FULLSCREEN_BUFFER_AFTER_REBUFFER_MS = 8_000
    private var exoPlayer: ExoPlayer? = null
    private val _playerFlow = MutableStateFlow<ExoPlayer?>(null)
    /** Observe this instead of polling getCurrentPlayer() every 100ms. */
    val playerFlow: StateFlow<ExoPlayer?> = _playerFlow.asStateFlow()
    private var currentVideoList: List<Pair<MimeiId, MediaType>>? = null
    private var explicitVideoUrlMap: Map<MimeiId, String> = emptyMap()
    private var videoBaseUrlMap: Map<MimeiId, String> = emptyMap() // Map videoMid to author's baseUrl
    private var currentVideoIndex: Int = 0
    private var onVideoChanged: ((MimeiId, Int) -> Unit)? = null
    private var onPlayerStateChanged: ((PlayerState) -> Unit)? = null
    private var applicationContext: Context? = null // Use Application context to avoid memory leaks
    private var isManualNavigation: Boolean = false // Flag to prevent double progression when user manually skips
    private var autoAdvanceListener: Player.Listener? = null // Track listener to remove before adding new one
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var preloadedNextPlayer: ExoPlayer? = null // Pre-buffered player for next video
    private var preloadedNextIndex: Int = -1 // Index of the preloaded video
    private var preloadedNextVideoMid: MimeiId? = null
    private var loadGeneration: Int = 0
    private var loadingVideoMid: MimeiId? = null
    private var ownedFullScreenVideoMid: MimeiId? = null
    
    /**
     * Initialize the singleton player instance
     */
    fun initialize(context: Context) {
        // Store Application context to avoid memory leaks
        this.applicationContext = context.applicationContext
        // No placeholder player — playCurrentVideo() will create the real one.
        // Creating a player with empty URL causes immediate Source error.
    }
    
    /**
     * Set the video list context and start playing from the specified index
     */
    fun setVideoList(videoList: List<Pair<MimeiId, MediaType>>, startIndex: Int) {
        Timber.d("FullScreenPlayerManager - Setting video list with ${videoList.size} videos, start index: $startIndex")
        if (videoList.isEmpty()) {
            cleanup()
            return
        }
        currentVideoList = videoList
        currentVideoIndex = startIndex.coerceIn(0, videoList.size - 1)
        protectCurrentWindow()
        scope.launch { playCurrentVideo() }
    }

    fun setVideoListFromMediaItems(mediaItems: List<MediaItem>, startMediaIndex: Int, startPlayback: Boolean = false) {
        val videoItems = mutableListOf<Pair<MimeiId, MediaType>>()
        val urlMap = mutableMapOf<MimeiId, String>()
        val tappedVideoMid = mediaItems.getOrNull(startMediaIndex)?.takeIf { isVideoType(it.type) }
            ?.url
            ?.getMimeiKeyFromUrl()

        mediaItems.forEach { mediaItem ->
            val mediaType = mediaItem.type ?: MediaType.Unknown
            if (isVideoType(mediaType)) {
                val videoMid = mediaItem.url.getMimeiKeyFromUrl()
                videoItems.add(videoMid to mediaType)
                urlMap[videoMid] = mediaItem.url
            }
        }

        if (videoItems.isEmpty()) return

        currentVideoList = videoItems
        explicitVideoUrlMap = urlMap
        videoBaseUrlMap = emptyMap()
        currentVideoIndex = tappedVideoMid?.let { mid ->
            videoItems.indexOfFirst { it.first == mid }
        }?.takeIf { it >= 0 } ?: startMediaIndex.coerceIn(0, videoItems.lastIndex)
        protectCurrentWindow()

        if (startPlayback) {
            scope.launch { playCurrentVideo() }
        }
    }

    fun hasVideo(videoMid: MimeiId): Boolean {
        return currentVideoList?.any { it.first == videoMid } == true
    }
    
    /**
     * Update video list from VideoPlaybackCoordinator (consolidated tracking)
     * Similar to iOS FullScreenVideoManager.updateVideoList()
     * This allows VideoPlaybackCoordinator to share its video list with FullScreenPlayerManager
     */
    fun updateVideoList(videoList: List<Pair<MimeiId, MediaType>>, tweets: List<Tweet>) {
        Timber.d("FullScreenPlayerManager - Updating video list from VideoPlaybackCoordinator: ${videoList.size} videos, ${tweets.size} tweets")
        
        // Build map from videoMid to author's baseUrl
        videoBaseUrlMap = buildBaseUrlMap(videoList, tweets)
        explicitVideoUrlMap = emptyMap()
        
        // Only update the list if we don't have a current list or if the current video is no longer in the new list
        val currentMid = getCurrentVideoMid()
        if (currentVideoList == null || currentMid?.let { currentVideoMid ->
            !videoList.any { it.first == currentVideoMid }
        } == true) {
            // Current video not in new list, update but don't auto-play
            currentVideoList = videoList
            if (currentVideoIndex >= videoList.size) {
                currentVideoIndex = 0
            }
        } else {
            // Current video still in list, just update the list reference
            currentVideoList = videoList
            currentMid?.let { currentVideoMid ->
                val updatedIndex = videoList.indexOfFirst { it.first == currentVideoMid }
                if (updatedIndex >= 0) {
                    currentVideoIndex = updatedIndex
                }
            }
        }
        protectCurrentWindow()
    }
    
    /**
     * Build a map from videoMid to author's baseUrl by finding the tweet containing each video
     */
    private fun buildBaseUrlMap(videoList: List<Pair<MimeiId, MediaType>>, tweets: List<Tweet>): Map<MimeiId, String> {
        val baseUrlMap = mutableMapOf<MimeiId, String>()
        
        for ((videoMid, _) in videoList) {
            val tweet = findTweetContainingVideo(videoMid, tweets)
            val baseUrl = tweet?.author?.baseUrl ?: HproseInstance.appUser.baseUrl
            if (baseUrl != null) {
                baseUrlMap[videoMid] = baseUrl
            }
        }
        
        return baseUrlMap
    }
    
    /**
     * Find the tweet containing the given video, checking both own attachments and original tweet's attachments (for retweets/quotes)
     */
    private fun findTweetContainingVideo(videoMid: MimeiId, tweets: List<Tweet>): Tweet? {
        for (tweet in tweets) {
            // Check if video is in this tweet's own attachments
            if (tweet.attachments?.any { it.mid == videoMid } == true) {
                return tweet
            }
            // Check if video is in this tweet's original tweet (for retweets/quotes)
            tweet.originalTweetId?.let { originalId ->
                val originalTweet = tweets.find { it.mid == originalId }
                if (originalTweet?.attachments?.any { it.mid == videoMid } == true) {
                    return originalTweet // Use original tweet's author baseUrl
                }
            }
        }
        return null
    }
    
    /**
     * Play the next video in the list
     */
    fun playNextVideo() {
        val videoList = currentVideoList ?: return
        Timber.d("FullScreenPlayerManager - Playing next video, current index: $currentVideoIndex, total videos: ${videoList.size}")
        
        // Mark that this change was initiated by the user/gesture
        isManualNavigation = true
        
        if (currentVideoIndex < videoList.size - 1) {
            val nextIndex = currentVideoIndex + 1
            val (nextVideoMid, nextMediaType) = videoList[nextIndex]
            Timber.d("FullScreenPlayerManager - Moving from index $currentVideoIndex to $nextIndex, next video: $nextVideoMid, type: $nextMediaType")
            currentVideoIndex = nextIndex
            protectCurrentWindow()
            scope.launch { playCurrentVideo() }
        } else {
            // End of list - stop playing
            Timber.d("FullScreenPlayerManager - End of list, stopping playback")
            exoPlayer?.stop()
        }
    }
    
    /**
     * Play the previous video in the list
     */
    fun playPreviousVideo() {
        val videoList = currentVideoList ?: return
        Timber.d("FullScreenPlayerManager - Playing previous video, current index: $currentVideoIndex")
        
        // Mark that this change was initiated by the user/gesture
        isManualNavigation = true
        
        if (currentVideoIndex > 0) {
            currentVideoIndex--
        } else {
            // Beginning of list - loop to end
            Timber.d("FullScreenPlayerManager - Beginning of list, looping to end")
            currentVideoIndex = videoList.size - 1
        }
        protectCurrentWindow()
        scope.launch { playCurrentVideo() }
    }

    /**
     * Play the video at the current index
     */
    private suspend fun playCurrentVideo() {
        val videoList = currentVideoList ?: return
        val (videoMid, mediaType) = videoList[currentVideoIndex]

        Timber.d("FullScreenPlayerManager - Playing video at index $currentVideoIndex: $videoMid, type: $mediaType")
        protectCurrentWindow()
        VideoManager.markVideoInFullScreen(videoMid)
        VideoManager.stopAllPreloading()
        VideoManager.pauseVideo(videoMid)

        if (loadingVideoMid == videoMid && exoPlayer?.currentMediaItem != null) {
            Timber.d("FullScreenPlayerManager - Ignoring duplicate load for current video: $videoMid")
            exoPlayer?.playWhenReady = true
            return
        }

        // 1. Check if we already preloaded this video
        if (preloadedNextPlayer != null &&
            preloadedNextIndex == currentVideoIndex &&
            preloadedNextVideoMid == videoMid
        ) {
            Timber.d("FullScreenPlayerManager - Using preloaded player for index $currentVideoIndex")
            val preloaded = preloadedNextPlayer!!
            preloadedNextPlayer = null
            preloadedNextIndex = -1
            preloadedNextVideoMid = null
            prepareClaimedPlayer(preloaded)
            switchToPlayer(videoMid, preloaded)
            onVideoChanged?.invoke(videoMid, currentVideoIndex)
            return
        }
        if (preloadedNextPlayer != null && preloadedNextIndex <= currentVideoIndex) {
            preloadedNextPlayer?.release()
            preloadedNextPlayer = null
            preloadedNextIndex = -1
            preloadedNextVideoMid = null
        }

        // 2. Claim a feed/preload player if one is already prepared for this video.
        VideoManager.takePlayerForFullScreen(videoMid)?.let { claimedPlayer ->
            Timber.d("FullScreenPlayerManager - Using prepared feed/preload player for $videoMid")
            prepareClaimedPlayer(claimedPlayer)
            switchToPlayer(videoMid, claimedPlayer)
            onVideoChanged?.invoke(videoMid, currentVideoIndex)
            return
        }

        // 3. Create a new player with resolved HLS URL
        val explicitUrl = explicitVideoUrlMap[videoMid]
        val baseUrl = videoBaseUrlMap[videoMid] ?: HproseInstance.appUser.baseUrl ?: "http://${BuildConfig.BASE_URL}"
        val videoUrl = explicitUrl ?: HproseInstance.getMediaUrl(videoMid, baseUrl)
        if (videoUrl != null) {
            Timber.d("FullScreenPlayerManager - Loading video: $videoUrl, type: $mediaType")
            loadVideo(videoMid, videoUrl, mediaType)
        } else {
            Timber.w("FullScreenPlayerManager - Could not generate video URL for attachment: $videoMid")
        }
    }

    /**
     * Preload the next video in the list so it has time to buffer before the user swipes.
     */
    private fun preloadNextVideo() {
        val videoList = currentVideoList ?: return
        val nextIndex = currentVideoIndex + 1
        if (nextIndex >= videoList.size) return

        // Don't re-preload if already preloaded for this index
        if (preloadedNextIndex == nextIndex &&
            preloadedNextVideoMid == videoList[nextIndex].first &&
            preloadedNextPlayer != null
        ) return

        // Release any stale preloaded player
        preloadedNextPlayer?.release()
        preloadedNextPlayer = null
        preloadedNextIndex = -1
        preloadedNextVideoMid = null

        val (nextVideoMid, nextMediaType) = videoList[nextIndex]
        val ctx = applicationContext ?: return

        // Check if feed already has a player for this video (no need to preload)
        if (VideoManager.isVideoPreloaded(nextVideoMid)) {
            Timber.d("FullScreenPlayerManager - Next video $nextVideoMid already in feed, skip preload")
            return
        }

        scope.launch {
            try {
                val expectedCurrentIndex = currentVideoIndex
                val baseUrl = videoBaseUrlMap[nextVideoMid]
                    ?: HproseInstance.appUser.baseUrl
                    ?: "http://${BuildConfig.BASE_URL}"
                val videoUrl = explicitVideoUrlMap[nextVideoMid]
                    ?: HproseInstance.getMediaUrl(nextVideoMid, baseUrl)
                    ?: return@launch

                // Resolve HLS URL on IO thread to avoid blocking main
                val resolvedHlsUrl = if (nextMediaType == MediaType.HLS_VIDEO) {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        HlsUrlResolver.resolve(ctx, videoUrl)
                    }
                } else null

                // createExoPlayer must run on Main thread (ExoPlayer requirement)
                Timber.d("FullScreenPlayerManager - Preloading next video index $nextIndex: $nextVideoMid")
                val player = createExoPlayer(
                    ctx,
                    videoUrl,
                    nextMediaType,
                    resolvedHlsUrl = resolvedHlsUrl,
                    minBufferMs = FULLSCREEN_MIN_BUFFER_MS,
                    maxBufferMs = FULLSCREEN_MAX_BUFFER_MS,
                    bufferForPlaybackMs = FULLSCREEN_BUFFER_FOR_PLAYBACK_MS,
                    bufferForPlaybackAfterRebufferMs = FULLSCREEN_BUFFER_AFTER_REBUFFER_MS
                )
                // Don't start playback — just let it buffer
                player.playWhenReady = false

                val stillNext = currentVideoIndex == expectedCurrentIndex &&
                    currentVideoList?.getOrNull(nextIndex)?.first == nextVideoMid
                if (!stillNext) {
                    player.release()
                    Timber.d("FullScreenPlayerManager - Released stale next preload for $nextVideoMid")
                    return@launch
                }

                preloadedNextPlayer = player
                preloadedNextIndex = nextIndex
                preloadedNextVideoMid = nextVideoMid
                Timber.d("FullScreenPlayerManager - Preloaded next video ready to use")
            } catch (e: Exception) {
                Timber.e("FullScreenPlayerManager - Failed to preload next video: ${e.message}")
            }
        }
    }

    /**
     * Switch to a new player, disposing the old one properly.
     */
    private fun switchToPlayer(videoMid: MimeiId, newPlayer: ExoPlayer) {
        ownedFullScreenVideoMid?.let { VideoManager.clearVideoInFullScreen(it) }
        VideoManager.markVideoInFullScreen(videoMid)
        ownedFullScreenVideoMid = videoMid
        val old = exoPlayer
        if (old != null && old !== newPlayer) {
            autoAdvanceListener?.let { old.removeListener(it) }
            old.release()
        }

        exoPlayer = newPlayer
        _playerFlow.value = newPlayer
        isManualNavigation = false

        newPlayer.volume = 1f
        newPlayer.playWhenReady = true
        addAutoAdvanceListener(newPlayer)
        preloadNextVideo()
    }

    /**
     * Load a video into the player (creates a new ExoPlayer)
     */
    private suspend fun loadVideo(videoMid: MimeiId, videoUrl: String, mediaType: MediaType) {
        val ctx = applicationContext ?: return
        loadGeneration += 1
        val generation = loadGeneration
        loadingVideoMid = videoMid

        try {
            // Resolve HLS URL to avoid trial-and-error (master.m3u8 → playlist.m3u8)
            val resolvedHlsUrl = if (mediaType == MediaType.HLS_VIDEO) {
                withContext(Dispatchers.IO) {
                    HlsUrlResolver.resolve(ctx, videoUrl)
                }
            } else null

            if (generation != loadGeneration || loadingVideoMid != videoMid) {
                Timber.d("FullScreenPlayerManager - Ignoring stale load completion for $videoMid")
                return
            }

            Timber.d("FullScreenPlayerManager - Creating new ExoPlayer (resolvedHls=${resolvedHlsUrl != null})")
            val newPlayer = createExoPlayer(
                ctx,
                videoUrl,
                mediaType,
                resolvedHlsUrl = resolvedHlsUrl,
                minBufferMs = FULLSCREEN_MIN_BUFFER_MS,
                maxBufferMs = FULLSCREEN_MAX_BUFFER_MS,
                bufferForPlaybackMs = FULLSCREEN_BUFFER_FOR_PLAYBACK_MS,
                bufferForPlaybackAfterRebufferMs = FULLSCREEN_BUFFER_AFTER_REBUFFER_MS
            )

            if (generation != loadGeneration || loadingVideoMid != videoMid) {
                newPlayer.release()
                Timber.d("FullScreenPlayerManager - Released stale player for $videoMid")
                return
            }

            switchToPlayer(videoMid, newPlayer)
            onVideoChanged?.invoke(videoMid, currentVideoIndex)

            Timber.d("FullScreenPlayerManager - Video player created and ready, starting playback")
        } catch (e: Exception) {
            Timber.e("FullScreenPlayerManager - Error loading video: ${e.message}")
        }
    }

    /**
     * Add listener for auto-advance on video end.
     * Removes any previously added auto-advance listener first to prevent accumulation.
     */
    private fun addAutoAdvanceListener(player: ExoPlayer) {
        // Remove previous listener from the old player (if different) or same player
        autoAdvanceListener?.let { oldListener ->
            // Remove from current player in case it's the same player being re-adopted
            player.removeListener(oldListener)
            // Also remove from the previous exoPlayer if it was different
            exoPlayer?.takeIf { it !== player }?.removeListener(oldListener)
        }

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Timber.d("FullScreenPlayerManager - Playback state changed: $playbackState")
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        Timber.d("FullScreenPlayerManager - Video ended")
                        if (!isManualNavigation) {
                            Timber.d("FullScreenPlayerManager - Auto-playing next video after completion")
                            playNextVideo()
                        } else {
                            Timber.d("FullScreenPlayerManager - Manual navigation flag cleared after end")
                            isManualNavigation = false
                        }
                    }
                    Player.STATE_READY -> {
                        Timber.d("FullScreenPlayerManager - Video ready to play")
                    }
                    Player.STATE_BUFFERING -> {
                        Timber.d("FullScreenPlayerManager - Video buffering")
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Timber.e("FullScreenPlayerManager - Player error: ${error.message}")
            }
        }
        autoAdvanceListener = listener
        player.addListener(listener)
    }

    private fun protectCurrentWindow() {
        val videoList = currentVideoList ?: return
        if (videoList.isEmpty()) return

        val start = (currentVideoIndex - FULLSCREEN_PROTECTED_NEIGHBOR_COUNT).coerceAtLeast(0)
        val end = (currentVideoIndex + FULLSCREEN_PROTECTED_NEIGHBOR_COUNT).coerceAtMost(videoList.lastIndex)
        val protectedMids = (start..end).map { videoList[it].first }
        VideoManager.protectVideosForFullScreen(protectedMids)
    }

    private fun isVideoType(mediaType: MediaType?): Boolean {
        return mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO
    }

    private fun prepareClaimedPlayer(player: ExoPlayer) {
        try {
            when (player.playbackState) {
                Player.STATE_IDLE -> player.prepare()
                Player.STATE_ENDED -> {
                    player.seekTo(0)
                    player.prepare()
                }
            }
            player.volume = 1f
            player.playWhenReady = false
        } catch (e: Exception) {
            Timber.w("FullScreenPlayerManager - Error preparing claimed player: ${e.message}")
        }
    }
    
    /**
     * Get the current ExoPlayer instance
     */
    fun getCurrentPlayer(): ExoPlayer? = exoPlayer
    
    /**
     * Get the current video mid
     */
    fun getCurrentVideoMid(): MimeiId? = currentVideoList?.getOrNull(currentVideoIndex)?.first
    
    /**
     * Get the current video index
     */
    fun getCurrentIndex(): Int = currentVideoIndex
    
    /**
     * Get the total number of videos in the current list
     */
    fun getTotalVideos(): Int = currentVideoList?.size ?: 0
    
    /**
     * Get the current video list (from VideoPlaybackCoordinator)
     */
    fun getVideoList(): List<Pair<MimeiId, MediaType>>? = currentVideoList
    
    /**
     * Set callback for when video changes
     */
    fun setOnVideoChanged(callback: (MimeiId, Int) -> Unit) {
        onVideoChanged = callback
    }
    
    /**
     * Set callback for player state changes
     */
    fun setOnPlayerStateChanged(callback: (PlayerState) -> Unit) {
        onPlayerStateChanged = callback
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        Timber.d("FullScreenPlayerManager - Cleaning up resources")
        // Release preloaded player
        preloadedNextPlayer?.release()
        preloadedNextPlayer = null
        preloadedNextIndex = -1
        preloadedNextVideoMid = null

        exoPlayer?.let { player ->
            autoAdvanceListener?.let { player.removeListener(it) }
            player.playWhenReady = false
            player.pause()
            player.release()
        }
        autoAdvanceListener = null
        exoPlayer = null
        _playerFlow.value = null
        ownedFullScreenVideoMid?.let { VideoManager.clearVideoInFullScreen(it) }
        VideoManager.clearFullScreenProtectedVideos()
        ownedFullScreenVideoMid = null
        currentVideoList = null
        explicitVideoUrlMap = emptyMap()
        videoBaseUrlMap = emptyMap()
        currentVideoIndex = 0
        loadGeneration += 1
        loadingVideoMid = null
        applicationContext = null
        onVideoChanged = null
        onPlayerStateChanged = null
    }
    
    /**
     * Check if the player is initialized
     */
    fun isInitialized(): Boolean = exoPlayer != null
}

/**
 * Player state for callbacks
 */
data class PlayerState(
    val isPlaying: Boolean,
    val currentPosition: Long,
    val duration: Long,
    val bufferedPosition: Long
)
