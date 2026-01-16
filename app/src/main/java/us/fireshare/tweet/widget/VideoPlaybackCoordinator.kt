package us.fireshare.tweet.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.Tweet

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
 * 1. Tracks visible videos in the feed
 * 2. Manages playback state for videos
 * 3. Coordinates video playback via notifications/events
 */
object VideoPlaybackCoordinator {
    // Singleton state
    private var visibleVideos = mutableListOf<VideoPlaybackInfo>()
    private var allVideos = mutableListOf<VideoPlaybackInfo>()
    private var visibleTweetIds = mutableSetOf<String>()
    
    // Currently playing video identifier
    private var primaryVideoId: String? = null
    
    // Playback debounce timer
    private var playbackDebounceJob: Job? = null
    private const val PLAYBACK_DEBOUNCE_MS = 200L
    
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
            
            if (isPureRetweet && tweet.originalTweetId != null) {
                // PURE RETWEET: Get attachments from original tweet, use retweet's ID for positioning
                // Note: For now, we'll process the retweet's own attachments if any
                // In a full implementation, you might want to fetch the original tweet
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
            } else {
                // REGULAR TWEET: Process the tweet's own attachments
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
        }
        
        allVideos = videos
        
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
     * Update visible tweets
     * Similar to iOS updateVisibleTweets(_:)
     */
    fun updateVisibleTweets(tweetIds: Set<String>) {
        visibleTweetIds = tweetIds.toMutableSet()
        updateVisibleVideos()
    }
    
    /**
     * Update visible videos based on visible tweet IDs
     */
    private fun updateVisibleVideos() {
        visibleVideos = allVideos.filter { visibleTweetIds.contains(it.tweetId) }.toMutableList()
        
        // Stop videos that are no longer visible
        primaryVideoId?.let { currentPrimary ->
            val primaryStillVisible = visibleVideos.any { it.identifier == currentPrimary }
            if (!primaryStillVisible) {
                stopAllVideos()
            }
        }
        
        // If no videos visible, stop all
        if (visibleVideos.isEmpty()) {
            stopAllVideos()
            return
        }
        
        // Start playback with debounce if we have visible videos and no primary
        if (primaryVideoId == null && visibleVideos.isNotEmpty()) {
            startPlaybackWithDebounce()
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
     */
    private fun startPrimaryVideoPlayback() {
        if (visibleVideos.isEmpty()) {
            Timber.w("VideoPlaybackCoordinator: No visible videos to play")
            return
        }
        
        // Get the first visible video (topmost)
        val primary = visibleVideos.first()
        primaryVideoId = primary.identifier
        
        Timber.d("VideoPlaybackCoordinator: Starting playback for primary video: ${primary.videoMid}")
        
        // Playback is handled by individual VideoPreview components
        // They will check if they should autoplay based on coordinator state
    }
    
    /**
     * Check if a video should autoplay
     * Similar to iOS coordinator checking if video should play
     */
    fun shouldAutoPlay(videoMid: MimeiId, tweetId: String): Boolean {
        val identifier = "${tweetId}_$videoMid"
        
        // Only autoplay if this is the primary video
        return primaryVideoId == identifier && visibleVideos.isNotEmpty()
    }
    
    /**
     * Handle video finished
     * Similar to iOS handleVideoFinished(_:)
     */
    fun handleVideoFinished(videoMid: MimeiId, tweetId: String) {
        val identifier = "${tweetId}_$videoMid"
        
        if (primaryVideoId == identifier) {
            // Move to next video
            val currentIndex = visibleVideos.indexOfFirst { it.identifier == identifier }
            if (currentIndex >= 0 && currentIndex < visibleVideos.size - 1) {
                val nextVideo = visibleVideos[currentIndex + 1]
                primaryVideoId = nextVideo.identifier
                Timber.d("VideoPlaybackCoordinator: Moving to next video: ${nextVideo.videoMid}")
            } else {
                // No more videos, stop playback
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
        Timber.d("VideoPlaybackCoordinator: Stopped all videos")
    }
    
    /**
     * Clear all state
     */
    fun clear() {
        stopAllVideos()
        visibleVideos.clear()
        allVideos.clear()
        visibleTweetIds.clear()
    }
}

