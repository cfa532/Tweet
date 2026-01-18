package us.fireshare.tweet.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetCacheManager

/**
 * Video playback command - similar to iOS NotificationCenter notifications
 */
sealed class VideoPlaybackCommand {
    data class ShouldPlayVideo(
        val tweetId: String,
        val videoMid: MimeiId,
        val videoIndex: Int,
        val isPrimary: Boolean
    ) : VideoPlaybackCommand()

    data class ShouldPauseVideo(val videoMid: MimeiId) : VideoPlaybackCommand()
    data class ShouldStopVideo(val videoMid: MimeiId) : VideoPlaybackCommand()
    object ShouldStopAllVideos : VideoPlaybackCommand()
}

/**
 * Video playback info for tracking individual videos
 * Similar to iOS VideoPlaybackInfo structure
 */
data class VideoPlaybackInfo(
    val tweetId: String,
    val videoMid: MimeiId,
    val index: Int
) {
    val identifier: String get() = "${tweetId}_$videoMid"
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as VideoPlaybackInfo
        return identifier == other.identifier
    }
    
    override fun hashCode(): Int {
        return identifier.hashCode()
    }
}

/**
 * Video playback coordinator that manages video playback across the app
 * Similar to iOS VideoPlaybackCoordinator
 * 
 * Behavior:
 * 1. Tracks visible videos in the feed (only videos with >= 50% visibility)
 * 2. Manages playback state for videos
 * 3. Coordinates video playback via notifications/events
 * 4. Switches to next video when current video is 50% off screen
 * 5. Selects primary video based on scroll direction (bottommost when scrolling down, topmost when scrolling up)
 */
object VideoPlaybackCoordinator {
    // Singleton state
    private var visibleVideos = mutableListOf<VideoPlaybackInfo>()
    private var allVideos = mutableListOf<VideoPlaybackInfo>()
    private var currentTweets = listOf<Tweet>() // Store current tweet list for retweet lookup
    private val videoMetaMap = mutableMapOf<String, VideoPlaybackInfo>()
    // Tweet cell tracking (iOS-style) - maps tweetId to cell bounds and visibility
    private val tweetCellBoundsMap = mutableMapOf<String, android.graphics.RectF>() // tweetId -> cell bounds in LazyColumn coordinates
    private val tweetVisibilityMap = mutableMapOf<String, Boolean>() // tweetId -> isVisible
    
    // Video visibility tracking - maps video identifier to visibility ratio (0.0 to 1.0)
    // This stores the MediaGrid's visibility ratio for each video
    private val videoVisibilityMap = mutableMapOf<String, Float>() // video identifier -> visibility ratio

    // Viewport dimensions for visibility calculations (updated dynamically)
    private var viewportWidth = 1080f // Default fallback
    private var viewportHeight = 2340f // Default fallback
    
    // Currently playing video identifier
    private var primaryVideoId: String? = null
    
    // Scroll direction tracking (true = scrolling down, false = scrolling up)
    private var scrollDirection: Boolean = true // Default to scrolling down
    private var previousContentOffset: Float = 0f
    
    // Playback debounce timer
    private var playbackDebounceJob: Job? = null
    private const val PLAYBACK_DEBOUNCE_MS = 100L
    
    // Visibility threshold (50% = 0.5)
    private const val VISIBILITY_THRESHOLD = 0.5f
    
    // PERF FIX: Batch visibility updates to reduce expensive filtering/sorting operations
    // Reduced to 100ms for more responsive playback during scrolling
    private var visibilityUpdateDebounceJob: Job? = null
    private const val VISIBILITY_UPDATE_DEBOUNCE_MS = 150L // Batch updates every 150ms
    private var pendingVisibilityUpdates = mutableSetOf<String>() // Track videos that need update

    // Command flow - similar to iOS NotificationCenter
    private val _playbackCommands = MutableSharedFlow<VideoPlaybackCommand>(replay = 1, extraBufferCapacity = 64)
    val playbackCommands: SharedFlow<VideoPlaybackCommand> = _playbackCommands.asSharedFlow()
    
    /**
     * Add embedded tweet videos to the video list when they become available
     * Called by TweetItem when embedded tweets are loaded
     */
    fun addEmbeddedTweetVideos(quotingTweetId: MimeiId, embeddedTweet: Tweet) {
        val videosToAdd = mutableListOf<VideoPlaybackInfo>()

        embeddedTweet.attachments?.forEachIndexed { index, attachment ->
            if (attachment.type == MediaType.Video || attachment.type == MediaType.HLS_VIDEO) {
                val videoInfo = VideoPlaybackInfo(
                    tweetId = quotingTweetId,  // Use quoting tweet's ID
                    videoMid = attachment.mid,
                    index = index
                )
                videosToAdd.add(videoInfo)
                Timber.d("VideoPlaybackCoordinator: Added embedded tweet video after load: identifier=${videoInfo.identifier}, quotingTweetId=${quotingTweetId}, embeddedTweetId=${embeddedTweet.mid}, videoMid=${attachment.mid}")
            }
        }

        if (videosToAdd.isNotEmpty()) {
            allVideos.addAll(videosToAdd)
            videosToAdd.forEach { videoInfo ->
                videoMetaMap[videoInfo.identifier] = videoInfo
            }

            // Update FullScreenPlayerManager with the new video list
            val videoListForFullScreen = allVideos.map { videoInfo ->
                val tweet = currentTweets.find { it.mid == videoInfo.tweetId }
                val attachment = tweet?.attachments?.getOrNull(videoInfo.index)
                val mediaType = attachment?.type ?: MediaType.Video
                Pair(videoInfo.videoMid, mediaType)
            }
            FullScreenPlayerManager.updateVideoList(videoListForFullScreen, currentTweets)

            Timber.d("VideoPlaybackCoordinator: Added ${videosToAdd.size} embedded tweet videos, total videos now: ${allVideos.size}")
        }
    }

    /**
     * Build video list from tweets
     * Similar to iOS buildVideoList(from:tweets:pinnedTweets:)
     */
    suspend fun buildVideoList(tweets: List<Tweet>, pinnedTweets: List<Tweet> = emptyList()) {
        // Store tweet list for retweet lookup in updateVideoVisibility
        currentTweets = tweets + pinnedTweets
        
        val videos = mutableListOf<VideoPlaybackInfo>()
        
        // Process pinned tweets first (they appear at the top)
        for (tweet in pinnedTweets) {
            tweet.attachments?.forEachIndexed { index, attachment ->
                if (attachment.type == MediaType.Video || attachment.type == MediaType.HLS_VIDEO) {
                    val videoInfo = VideoPlaybackInfo(
                        tweetId = tweet.mid,
                        videoMid = attachment.mid,
                        index = index
                    )
                    videos.add(videoInfo)
                }
            }
        }
        
        // Process regular tweets IN ORDER - videos appear as many times as they appear in the feed
        for (tweet in tweets) {
            // Determine if this is a pure retweet (no own content, just forwarding)
            // A pure retweet has originalTweetId AND (no content text AND no attachments)
            // A quoted tweet has originalTweetId AND (has content text OR has attachments)
            val hasContentText = !tweet.content.isNullOrEmpty()
            val hasAttachments = tweet.attachments != null && tweet.attachments!!.isNotEmpty()
            val hasOwnContent = hasContentText || hasAttachments
            val hasOriginalTweet = tweet.originalTweetId != null
            val isPureRetweet = hasOriginalTweet && !hasOwnContent
            val isQuotedTweet = hasOriginalTweet && hasOwnContent

            if (isPureRetweet) {
                // PURE RETWEET: Get attachments from original tweet, use retweet's ID for positioning
                if (tweet.originalTweetId != null) {
                    // Try to get original tweet from cache only (non-blocking)
                    val originalTweet = TweetCacheManager.getCachedTweet(tweet.originalTweetId!!)

                    // Only add original tweet videos if they're already cached
                    // They will be added later when fetched asynchronously by TweetItem
                    originalTweet?.attachments?.forEachIndexed { index, attachment ->
                        if (attachment.type == MediaType.Video || attachment.type == MediaType.HLS_VIDEO) {
                            val videoInfo = VideoPlaybackInfo(
                                tweetId = tweet.mid,  // Use retweet's ID for positioning
                                videoMid = attachment.mid,
                                index = index
                            )
                            videos.add(videoInfo)
                            Timber.d("VideoPlaybackCoordinator: Added retweet video: identifier=${videoInfo.identifier}, tweetId=${tweet.mid}, originalTweetId=${tweet.originalTweetId}, videoMid=${attachment.mid}")
                        }
                    }

                    // If original tweet is not cached, log that we'll add it later when it's fetched
                    if (originalTweet == null) {
                        Timber.d("VideoPlaybackCoordinator: Original tweet ${tweet.originalTweetId} not cached yet for retweet ${tweet.mid}, will be added later when fetched by TweetItem")
                    }
                }
            } else {
                // REGULAR TWEET or QUOTED TWEET: Process the tweet's own attachments
                tweet.attachments?.forEachIndexed { index, attachment ->
                    if (attachment.type == MediaType.Video || attachment.type == MediaType.HLS_VIDEO) {
                        val videoInfo = VideoPlaybackInfo(
                            tweetId = tweet.mid,
                            videoMid = attachment.mid,
                            index = index
                        )
                        videos.add(videoInfo)
                    }
                }
                
                // For quoted tweets, also process embedded tweet's videos separately
                // Use quoting tweet's ID to distinguish from standalone original tweet
                if (isQuotedTweet && tweet.originalTweetId != null) {
                    // Try to get embedded tweet from cache first (non-blocking)
                    val embeddedTweet = TweetCacheManager.getCachedTweet(tweet.originalTweetId!!)

                    // Only add embedded tweet videos if they're already cached
                    // They will be added later when fetched asynchronously by TweetItem
                    embeddedTweet?.attachments?.forEachIndexed { index, attachment ->
                        if (attachment.type == MediaType.Video || attachment.type == MediaType.HLS_VIDEO) {
                            // Use quoting tweet's ID for tracking to distinguish from standalone original tweet
                            val videoInfo = VideoPlaybackInfo(
                                tweetId = tweet.mid,  // Use quoting tweet's ID
                                videoMid = attachment.mid,
                                index = index
                            )
                            videos.add(videoInfo)
                            Timber.d("VideoPlaybackCoordinator: Added embedded tweet video: identifier=${videoInfo.identifier}, quotingTweetId=${tweet.mid}, embeddedTweetId=${tweet.originalTweetId}, videoMid=${attachment.mid}")
                        }
                    }

                    // If embedded tweet is not cached, log that we'll add it later when it's fetched
                    if (embeddedTweet == null) {
                        Timber.d("VideoPlaybackCoordinator: Embedded tweet ${tweet.originalTweetId} not cached yet, will be added later when fetched by TweetItem")
                    }
                }
            }
        }
        
        allVideos = videos
        videos.forEach { videoInfo ->
            videoMetaMap[videoInfo.identifier] = videoInfo
        }
        
        // Share the video list with FullScreenPlayerManager to avoid duplicate tracking
        // This consolidates video tracking in one place, similar to iOS implementation
        val videoListForFullScreen = videos.map { videoInfo ->
            // Find the media type from the tweet attachments
            // We need to search through tweets to find the attachment type
            val tweet = (pinnedTweets + tweets).find { it.mid == videoInfo.tweetId }
            val attachment = tweet?.attachments?.getOrNull(videoInfo.index)
            val mediaType = attachment?.type ?: MediaType.Video
            
            Pair(videoInfo.videoMid, mediaType)
        }
        FullScreenPlayerManager.updateVideoList(videoListForFullScreen, tweets)
        
        Timber.d("VideoPlaybackCoordinator: Built video list with ${videos.size} videos and shared with FullScreenPlayerManager")
    }
    
    /**
     * Update scroll direction (called by scroll listener)
     */
    fun updateScrollDirection(currentOffset: Float) {
        if (previousContentOffset != 0f) {
            scrollDirection = currentOffset > previousContentOffset
        }
        previousContentOffset = currentOffset
    }

    /**
     * Update viewport size (called by TweetListView)
     */
    fun updateViewportSize(width: Float, height: Float) {
        viewportWidth = width
        viewportHeight = height
        Timber.d("VideoPlaybackCoordinator: Updated viewport size: ${width.toInt()}x${height.toInt()}")
    }
    
    /**
     * Update video visibility based on MediaGrid visibility
     * Called by MediaGrid when its visibility changes
     * 
     * @param videoMid The video's mid identifier
     * @param tweetId The parent tweet ID (for identifying the video in the list)
     * @param visibilityRatio The visibility ratio of the MediaGrid (0.0 to 1.0)
     */
    fun updateVideoVisibility(videoMid: MimeiId, tweetId: String, visibilityRatio: Float) {
        val identifier = "${tweetId}_$videoMid"
        val previousRatio = videoVisibilityMap[identifier] ?: 0f
        videoVisibilityMap[identifier] = visibilityRatio
        
        // Check if this visibility change affects primary video immediately during scroll
        // This makes playback more responsive even while scrolling
        val crossesThreshold = (previousRatio < VISIBILITY_THRESHOLD && visibilityRatio >= VISIBILITY_THRESHOLD) ||
                               (previousRatio >= VISIBILITY_THRESHOLD && visibilityRatio < VISIBILITY_THRESHOLD)
        
        if (crossesThreshold && visibleVideos.isNotEmpty()) {
            // Immediately check for primary video change when threshold is crossed
            checkPrimaryVideoDuringScroll()
        }
        
        // PERF FIX: Debounced update to batch multiple visibility updates together
        visibilityUpdateDebounceJob?.cancel()
        visibilityUpdateDebounceJob = CoroutineScope(Dispatchers.Main).launch {
            delay(VISIBILITY_UPDATE_DEBOUNCE_MS)
            updateVisibleVideos()
            checkAndSwitchVideoIfNeeded()
        }
    }
    
    /**
     * Immediately check and set primary video during scroll when visibility threshold is crossed
     * This makes playback start immediately even while scrolling, not waiting for debounce
     */
    private fun checkPrimaryVideoDuringScroll() {
        // Build current visible videos list based on current visibility ratios
        val currentVisible = allVideos.filter { videoInfo ->
            val visibilityRatio = videoVisibilityMap[videoInfo.identifier] ?: 0f
            visibilityRatio >= VISIBILITY_THRESHOLD
        }.sortedBy { videoInfo ->
            tweetCellBoundsMap[videoInfo.tweetId]?.top ?: Float.MAX_VALUE
        }
        
        if (currentVisible.isNotEmpty()) {
            val correctPrimary = if (scrollDirection) {
                // Scrolling DOWN: return topmost (first in sorted list - lowest Y)
                currentVisible.firstOrNull()
            } else {
                // Scrolling UP: return bottommost (last in sorted list - highest Y)
                currentVisible.lastOrNull()
            }
            
            if (correctPrimary != null && correctPrimary.identifier != primaryVideoId) {
                Timber.d("VideoPlaybackCoordinator: Detected primary video change during scroll to: ${correctPrimary.videoMid}")
                // Immediately start playback for the new primary video
                val previousPrimaryId = primaryVideoId
                primaryVideoId = correctPrimary.identifier
                
                CoroutineScope(Dispatchers.Main).launch {
                    // Stop previous primary if different
                    if (previousPrimaryId != null && previousPrimaryId != correctPrimary.identifier) {
                        val previousPrimary = videoMetaMap[previousPrimaryId]
                        if (previousPrimary != null) {
                            _playbackCommands.emit(VideoPlaybackCommand.ShouldStopVideo(previousPrimary.videoMid))
                        }
                    }
                    
                    // Start new primary video immediately
                    _playbackCommands.emit(
                        VideoPlaybackCommand.ShouldPlayVideo(
                            tweetId = correctPrimary.tweetId,
                            videoMid = correctPrimary.videoMid,
                            videoIndex = correctPrimary.index,
                            isPrimary = true
                        )
                    )
                }
            }
        }
    }
    
    /**
     * Update tweet cell position and visibility (called by TweetItem's onGloballyPositioned)
     * This replaces the old video-based tracking with cell-based tracking like iOS
     * NOTE: This is kept for backward compatibility but video visibility now comes from MediaGrid
     */
    fun updateTweetCellPosition(
        tweetId: String,
        cellTopY: Float,
        cellHeight: Float,
        isVisible: Boolean
    ) {
        // Store cell bounds in LazyColumn coordinates (equivalent to iOS cell.frame in tableView coordinates)
        val cellBounds = android.graphics.RectF(
            0f, // Left edge (full width)
            cellTopY, // Top Y in LazyColumn coordinates
            viewportWidth, // Right edge (use current viewport width)
            cellTopY + cellHeight // Bottom Y
        )
        tweetCellBoundsMap[tweetId] = cellBounds
        tweetVisibilityMap[tweetId] = isVisible
    }

    /**
     * Update visible videos based on MediaGrid visibility
     * Only includes videos with >= 50% visibility (based on MediaGrid visibility ratio)
     */
    private fun updateVisibleVideos() {
        val previousVisibleIds = visibleVideos.map { it.identifier }.toSet()

        // Filter videos to only those with >= 50% visibility from MediaGrid
        visibleVideos = allVideos.filter { videoInfo ->
            val visibilityRatio = videoVisibilityMap[videoInfo.identifier] ?: 0f
            visibilityRatio >= VISIBILITY_THRESHOLD
        }.sortedBy { videoInfo ->
            // Sort by cell top Y position for primary video selection
            tweetCellBoundsMap[videoInfo.tweetId]?.top ?: Float.MAX_VALUE
        }.toMutableList()
        
        // Debug log to verify ordering and visibility
        if (visibleVideos.isNotEmpty()) {
            val orderDebug = visibleVideos.joinToString(", ") { videoInfo ->
                val bounds = tweetCellBoundsMap[videoInfo.tweetId]
                val pos = bounds?.top ?: Float.MAX_VALUE
                val visibility = videoVisibilityMap[videoInfo.identifier] ?: 0f
                "${videoInfo.videoMid.substring(0, minOf(8, videoInfo.videoMid.length))}@${pos.toInt()}[${(visibility * 100).toInt()}%]"
            }
            Timber.d("VideoPlaybackCoordinator: Visible videos (>=50%) order (scrollDirection=${if (scrollDirection) "DOWN" else "UP"}): $orderDebug")
        }

        if (visibleVideos.isEmpty()) {
            stopAllVideos()
            return
        }

        // Stop videos no longer visible or below threshold
        val currentVisibleIds = visibleVideos.map { it.identifier }.toSet()
        val videosToStop = previousVisibleIds - currentVisibleIds
        videosToStop.forEach { identifier ->
            val info = videoMetaMap[identifier]
            if (info != null) {
                CoroutineScope(Dispatchers.Main).launch {
                    _playbackCommands.emit(VideoPlaybackCommand.ShouldStopVideo(info.videoMid))
                }
            }
        }

        // If we have a primary video that's no longer visible, clear it
        if (primaryVideoId != null && primaryVideoId !in currentVisibleIds) {
            primaryVideoId = null
        }

        // Always ensure we have the correct primary video based on current visible videos
        if (visibleVideos.isNotEmpty()) {
            val correctPrimary = identifyPrimaryVideo()
            if (correctPrimary != null && correctPrimary.identifier != primaryVideoId) {
                Timber.d("VideoPlaybackCoordinator: Updating primary video to: ${correctPrimary.videoMid}")
                // Start playback immediately for the new primary video (this will pause the previous one)
                startPrimaryVideoPlayback()
            }
        }

        // Start playback if we don't have a primary video
        if (primaryVideoId == null && visibleVideos.isNotEmpty()) {
            startPlaybackWithDebounce()
        }
    }
    
    /**
     * Identify primary video based on scroll direction (iOS implementation)
     * Since Android already filters videos to only include those >=50% visible,
     * we can simplify to just pick the topmost/bottommost from visible videos
     */
    private fun identifyPrimaryVideo(): VideoPlaybackInfo? {
        if (visibleVideos.isEmpty()) return null

        // Since updateVisibleVideos already filters to >=50% visible videos,
        // we can just pick the topmost/bottommost based on scroll direction
        return if (scrollDirection) {
            // Scrolling DOWN: return topmost (first in sorted list - lowest Y)
            visibleVideos.firstOrNull()
        } else {
            // Scrolling UP: return bottommost (last in sorted list - highest Y)
            visibleVideos.lastOrNull()
        }
    }
    
    /**
     * Check if current primary video is 50% off screen and switch to next video if needed
     * Uses MediaGrid visibility ratio instead of cell bounds
     */
    private fun checkAndSwitchVideoIfNeeded() {
        val primaryId = primaryVideoId ?: return
        val primaryVideo = visibleVideos.find { it.identifier == primaryId } ?: return
        
        // Get visibility ratio from MediaGrid
        val visibilityRatio = videoVisibilityMap[primaryId] ?: 0f
        
        // If video is less than 50% visible, switch to appropriate video based on scroll direction
        if (visibilityRatio < VISIBILITY_THRESHOLD) {
            val newPrimary = identifyPrimaryVideo()
            if (newPrimary != null && newPrimary.identifier != primaryId) {
                // Stop current primary video and pause all other visible videos
                CoroutineScope(Dispatchers.Main).launch {
                    // Stop the current primary video (use Stop for immediate effect)
                    _playbackCommands.emit(VideoPlaybackCommand.ShouldStopVideo(primaryVideo.videoMid))
                    
                    // Pause all other visible videos (including the new primary temporarily, then we'll play it)
                    visibleVideos.forEach { videoInfo ->
                        if (videoInfo.identifier != newPrimary.identifier) {
                            _playbackCommands.emit(VideoPlaybackCommand.ShouldPauseVideo(videoInfo.videoMid))
                        }
                    }
                    
                    // Add a small delay to ensure stop/pause commands are processed before starting new video
                    delay(50L)
                    
                    // Switch to new primary video and play it
                    primaryVideoId = newPrimary.identifier
                    _playbackCommands.emit(
                        VideoPlaybackCommand.ShouldPlayVideo(
                            tweetId = newPrimary.tweetId,
                            videoMid = newPrimary.videoMid,
                            videoIndex = newPrimary.index,
                            isPrimary = true
                        )
                    )
                }
                
                val direction = if (scrollDirection) "next (scrolling DOWN)" else "previous (scrolling UP)"
                Timber.d("VideoPlaybackCoordinator: Switched from ${primaryVideo.videoMid} to ${newPrimary.videoMid} ($direction, MediaGrid visibility: ${(visibilityRatio * 100).toInt()}%)")
            }
        }
    }
    
    /**
     * Start playback with debounce timer
     * Similar to iOS debounce timer logic
     */
    private fun startPlaybackWithDebounce() {
        // Cancel existing debounce job
        playbackDebounceJob?.cancel()
        
        playbackDebounceJob = CoroutineScope(Dispatchers.Main).launch {
            delay(PLAYBACK_DEBOUNCE_MS)
            
            if (visibleVideos.isNotEmpty() && primaryVideoId == null) {
                startPrimaryVideoPlayback()
            }
        }
    }
    
    /**
     * Start primary video playback
     * Similar to iOS startPrimaryVideoPlayback()
     * Selects video based on scroll direction (topmost when scrolling down, bottommost when scrolling up)
     */
    private fun startPrimaryVideoPlayback() {
        if (visibleVideos.isEmpty()) {
            Timber.w("VideoPlaybackCoordinator: No visible videos to play")
            return
        }
        
        // Get primary video based on scroll direction
        val primary = identifyPrimaryVideo() ?: visibleVideos.first()
        val previousPrimaryId = primaryVideoId
        primaryVideoId = primary.identifier
        
        val direction = if (scrollDirection) "topmost (scrolling DOWN)" else "bottommost (scrolling UP)"
        Timber.d("VideoPlaybackCoordinator: Starting playback for primary video: ${primary.videoMid} ($direction)")

        // Pause other visible videos and play primary
        // Also explicitly stop the previous primary video if it exists and is different
        CoroutineScope(Dispatchers.Main).launch {
            // First, stop the previous primary video (use Stop instead of Pause for immediate effect)
            if (previousPrimaryId != null && previousPrimaryId != primary.identifier) {
                val previousPrimary = videoMetaMap[previousPrimaryId]
                if (previousPrimary != null) {
                    Timber.d("VideoPlaybackCoordinator: Stopping previous primary video: ${previousPrimary.videoMid}")
                    _playbackCommands.emit(VideoPlaybackCommand.ShouldStopVideo(previousPrimary.videoMid))
                }
            }
            
            // Pause all visible videos except the new primary
            visibleVideos.forEach { videoInfo ->
                if (videoInfo.identifier != primary.identifier) {
                    _playbackCommands.emit(VideoPlaybackCommand.ShouldPauseVideo(videoInfo.videoMid))
                }
            }
            
            // Add a small delay to ensure pause/stop commands are processed before starting new video
            // This prevents multiple videos from playing simultaneously
            delay(50L)
            
            // Finally, start the new primary video
            _playbackCommands.emit(
                VideoPlaybackCommand.ShouldPlayVideo(
                    tweetId = primary.tweetId,
                    videoMid = primary.videoMid,
                    videoIndex = primary.index,
                    isPrimary = true
                )
            )
        }
    }
    
    /**
     * Check if a video should autoplay
     * Similar to iOS coordinator checking if video should play
     */
    fun shouldAutoPlay(videoMid: MimeiId, tweetId: String): Boolean {
        val identifier = "${tweetId}_$videoMid"
        return primaryVideoId == identifier && visibleVideos.isNotEmpty()
    }
    
    /**
     * Handle video finished
     * Similar to iOS handleVideoFinished(_:)
     * Advances to next/previous video based on scroll direction
     */
    fun handleVideoFinished(videoMid: MimeiId, tweetId: String) {
        val identifier = "${tweetId}_$videoMid"
        
        if (primaryVideoId == identifier) {
            val currentIndex = visibleVideos.indexOfFirst { it.identifier == identifier }
            if (currentIndex >= 0) {
                val nextVideo = if (scrollDirection) {
                    // Scrolling DOWN: move to next video (higher index)
                    if (currentIndex < visibleVideos.size - 1) {
                        visibleVideos[currentIndex + 1]
                    } else null
                } else {
                    // Scrolling UP: move to previous video (lower index)
                    if (currentIndex > 0) {
                        visibleVideos[currentIndex - 1]
                    } else null
                }
                
                if (nextVideo != null) {
                    primaryVideoId = nextVideo.identifier
                    val direction = if (scrollDirection) "next (scrolling DOWN)" else "previous (scrolling UP)"
                    Timber.d("VideoPlaybackCoordinator: Moving to $direction video: ${nextVideo.videoMid}")
                    CoroutineScope(Dispatchers.Main).launch {
                        _playbackCommands.emit(
                            VideoPlaybackCommand.ShouldPlayVideo(
                                tweetId = nextVideo.tweetId,
                                videoMid = nextVideo.videoMid,
                                videoIndex = nextVideo.index,
                                isPrimary = true
                            )
                        )
                    }
                } else {
                    // No more videos in scroll direction, stop playback
                    stopAllVideos()
                }
            } else {
                // Current video not found, stop playback
                stopAllVideos()
            }
        }
    }
    
    /**
     * Stop all videos
     * Similar to iOS stopAllVideos()
     */
    fun stopAllVideos() {
        playbackDebounceJob?.cancel()
        playbackDebounceJob = null
        primaryVideoId = null
        CoroutineScope(Dispatchers.Main).launch {
            _playbackCommands.emit(VideoPlaybackCommand.ShouldStopAllVideos)
        }
        Timber.d("VideoPlaybackCoordinator: Stopped all videos")
    }
    
    /**
     * Clear all state
     */
    fun clear() {
        stopAllVideos()
        visibleVideos.clear()
        allVideos.clear()
        currentTweets = emptyList()
        videoMetaMap.clear()
        tweetCellBoundsMap.clear()
        tweetVisibilityMap.clear()
        videoVisibilityMap.clear()
        primaryVideoId = null
        previousContentOffset = 0f
        scrollDirection = true
        Timber.d("VideoPlaybackCoordinator: Cleared all state")
    }
}

