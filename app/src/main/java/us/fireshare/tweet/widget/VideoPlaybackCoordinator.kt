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
 * 5. Selects primary video based on scroll direction (topmost when scrolling down, bottommost when scrolling up)
 */
object VideoPlaybackCoordinator {
    // Singleton state
    private var visibleVideos = mutableListOf<VideoPlaybackInfo>()
    private var allVideos = mutableListOf<VideoPlaybackInfo>()
    private val videoMetaMap = mutableMapOf<String, VideoPlaybackInfo>()
    private val videoVisibilityMap = mutableMapOf<String, Boolean>()
    private val videoVisibilityRatioMap = mutableMapOf<String, Float>() // Store visibility ratio (0.0 to 1.0)
    private val videoPositionMap = mutableMapOf<String, Float>()
    
    // Currently playing video identifier
    private var primaryVideoId: String? = null
    
    // Scroll direction tracking (true = scrolling down, false = scrolling up)
    private var scrollDirection: Boolean = true // Default to scrolling down
    private var previousContentOffset: Float = 0f
    
    // Playback debounce timer
    private var playbackDebounceJob: Job? = null
    private const val PLAYBACK_DEBOUNCE_MS = 200L
    
    // Visibility threshold (50% = 0.5)
    private const val VISIBILITY_THRESHOLD = 0.5f
    
    // PERF FIX: Batch visibility updates to reduce expensive filtering/sorting operations
    private var visibilityUpdateDebounceJob: Job? = null
    private const val VISIBILITY_UPDATE_DEBOUNCE_MS = 150L // Batch updates every 150ms
    private var pendingVisibilityUpdates = mutableSetOf<String>() // Track videos that need update

    // Command flow - similar to iOS NotificationCenter
    private val _playbackCommands = MutableSharedFlow<VideoPlaybackCommand>(replay = 1, extraBufferCapacity = 64)
    val playbackCommands: SharedFlow<VideoPlaybackCommand> = _playbackCommands.asSharedFlow()
    
    /**
     * Build video list from tweets
     * Similar to iOS buildVideoList(from:tweets:pinnedTweets:)
     */
    fun buildVideoList(tweets: List<Tweet>, pinnedTweets: List<Tweet> = emptyList()) {
        val videos = mutableListOf<VideoPlaybackInfo>()
        val seenVideoIdentifiers = mutableSetOf<String>()
        
        // Process pinned tweets first (they appear at the top)
        for (tweet in pinnedTweets) {
            tweet.attachments?.forEachIndexed { index, attachment ->
                if (attachment.type == MediaType.Video || attachment.type == MediaType.HLS_VIDEO) {
                    val videoInfo = VideoPlaybackInfo(
                        tweetId = tweet.mid,
                        videoMid = attachment.mid,
                        index = index
                    )
                    
                    if (!seenVideoIdentifiers.contains(videoInfo.identifier)) {
                        videos.add(videoInfo)
                        seenVideoIdentifiers.add(videoInfo.identifier)
                    }
                }
            }
        }
        
        // Process regular tweets
        for (tweet in tweets) {
            // Check if this is a pure retweet (no own content, just forwarding)
            val hasTweetContent = tweet.attachments != null && tweet.attachments!!.isNotEmpty()
            val hasOriginalTweet = tweet.originalTweetId != null
            val isPureRetweet = hasOriginalTweet && !hasTweetContent

            val attachmentsToProcess = if (isPureRetweet && tweet.originalTweetId != null) {
                // Retweet: track by retweet id + video mid
                // Use cached original tweet attachments if available
                TweetCacheManager.getCachedTweet(tweet.originalTweetId!!)?.attachments ?: tweet.attachments
            } else {
                tweet.attachments
            }

            attachmentsToProcess?.forEachIndexed { index, attachment ->
                if (attachment.type == MediaType.Video || attachment.type == MediaType.HLS_VIDEO) {
                    val videoInfo = VideoPlaybackInfo(
                        tweetId = tweet.mid,
                        videoMid = attachment.mid,
                        index = index
                    )

                    if (!seenVideoIdentifiers.contains(videoInfo.identifier)) {
                        videos.add(videoInfo)
                        seenVideoIdentifiers.add(videoInfo.identifier)
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
     * Update individual video visibility (called by VideoPreview's onGloballyPositioned)
     * @param visibilityRatio: 0.0 = completely out of view, 1.0 = fully visible
     */
    fun updateVideoVisibility(
        videoMid: MimeiId,
        tweetId: String,
        isVisible: Boolean,
        topY: Float? = null,
        visibilityRatio: Float? = null
    ) {
        val identifier = "${tweetId}_$videoMid"
        val previousVisibility = videoVisibilityMap[identifier] ?: false
        val previousRatio = videoVisibilityRatioMap[identifier] ?: 0f

        if (videoMetaMap[identifier] == null) {
            videoMetaMap[identifier] = VideoPlaybackInfo(tweetId = tweetId, videoMid = videoMid, index = 0)
        }

        topY?.let { videoPositionMap[identifier] = it }
        visibilityRatio?.let { videoVisibilityRatioMap[identifier] = it }
        
        // Update visibility map (for backward compatibility)
        videoVisibilityMap[identifier] = isVisible
        
        // PERF FIX: Only trigger debounced update if visibility changed or ratio changed significantly
        // This reduces expensive filtering/sorting operations during scrolling
        if (previousVisibility != isVisible || 
            (visibilityRatio != null && kotlin.math.abs(previousRatio - visibilityRatio) > 0.1f)) {
            // Mark this video as needing update
            pendingVisibilityUpdates.add(identifier)
            
            // Cancel existing debounce job
            visibilityUpdateDebounceJob?.cancel()
            
            // Debounce updates to batch multiple visibility changes together
            visibilityUpdateDebounceJob = CoroutineScope(Dispatchers.Main).launch {
                delay(VISIBILITY_UPDATE_DEBOUNCE_MS)
                
                // Process all pending updates together
                if (pendingVisibilityUpdates.isNotEmpty()) {
                    updateVisibleVideos()
                    checkAndSwitchVideoIfNeeded()
                    pendingVisibilityUpdates.clear()
                }
            }
        }
    }

    /**
     * Update visible videos based on video visibility reports
     * Only includes videos that are at least 50% visible
     */
    private fun updateVisibleVideos() {
        val previousVisibleIds = visibleVideos.map { it.identifier }.toSet()

        // Filter videos by 50% visibility threshold
        visibleVideos = videoMetaMap.values.filter { videoInfo ->
            val visibilityRatio = videoVisibilityRatioMap[videoInfo.identifier] ?: 0f
            visibilityRatio >= VISIBILITY_THRESHOLD
        }.sortedBy { videoInfo ->
            videoPositionMap[videoInfo.identifier] ?: Float.MAX_VALUE
        }.toMutableList()

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

        // Identify primary video based on scroll direction
        val primaryIdentifier = identifyPrimaryVideo()?.identifier

        // If primary not visible or changed, reset and reselect
        if (primaryVideoId != null) {
            if (primaryVideoId !in currentVisibleIds || primaryVideoId != primaryIdentifier) {
                primaryVideoId = null
            }
        }

        if (primaryVideoId == null) {
            startPlaybackWithDebounce()
        }
    }
    
    /**
     * Identify primary video based on scroll direction
     * Scrolling DOWN: topmost video (lowest Y)
     * Scrolling UP: bottommost video (highest Y)
     */
    private fun identifyPrimaryVideo(): VideoPlaybackInfo? {
        if (visibleVideos.isEmpty()) return null
        
        return if (scrollDirection) {
            // Scrolling DOWN: return topmost (first in sorted list)
            visibleVideos.first()
        } else {
            // Scrolling UP: return bottommost (last in sorted list)
            visibleVideos.last()
        }
    }
    
    /**
     * Check if current primary video is 50% off screen and switch to next video if needed
     */
    private fun checkAndSwitchVideoIfNeeded() {
        val primaryId = primaryVideoId ?: return
        val primaryVideo = visibleVideos.find { it.identifier == primaryId } ?: return
        
        val visibilityRatio = videoVisibilityRatioMap[primaryId] ?: 1f
        
        // If video is 50% or less visible, switch to appropriate video based on scroll direction
        if (visibilityRatio <= VISIBILITY_THRESHOLD) {
            val newPrimary = identifyPrimaryVideo()
            if (newPrimary != null && newPrimary.identifier != primaryId) {
                // Pause current video
                CoroutineScope(Dispatchers.Main).launch {
                    _playbackCommands.emit(VideoPlaybackCommand.ShouldPauseVideo(primaryVideo.videoMid))
                }
                
                // Switch to new primary video
                primaryVideoId = newPrimary.identifier
                
                CoroutineScope(Dispatchers.Main).launch {
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
                Timber.d("VideoPlaybackCoordinator: Switched from ${primaryVideo.videoMid} to ${newPrimary.videoMid} ($direction, visibility: ${visibilityRatio * 100}%%)")
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
        primaryVideoId = primary.identifier
        
        val direction = if (scrollDirection) "topmost (scrolling DOWN)" else "bottommost (scrolling UP)"
        Timber.d("VideoPlaybackCoordinator: Starting playback for primary video: ${primary.videoMid} ($direction)")

        // Pause other visible videos and play primary
        CoroutineScope(Dispatchers.Main).launch {
            visibleVideos.forEach { videoInfo ->
                if (videoInfo.identifier == primary.identifier) {
                    _playbackCommands.emit(
                        VideoPlaybackCommand.ShouldPlayVideo(
                            tweetId = videoInfo.tweetId,
                            videoMid = videoInfo.videoMid,
                            videoIndex = videoInfo.index,
                            isPrimary = true
                        )
                    )
                } else {
                    _playbackCommands.emit(VideoPlaybackCommand.ShouldPauseVideo(videoInfo.videoMid))
                }
            }
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
        videoMetaMap.clear()
        videoVisibilityMap.clear()
        videoVisibilityRatioMap.clear()
        videoPositionMap.clear()
        previousContentOffset = 0f
        scrollDirection = true
    }
}

