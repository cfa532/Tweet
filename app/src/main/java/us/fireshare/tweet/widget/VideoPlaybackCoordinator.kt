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
 * 1. Tracks visible videos in the feed
 * 2. Manages playback state for videos
 * 3. Coordinates video playback via notifications/events
 */
object VideoPlaybackCoordinator {
    // Singleton state
    private var visibleVideos = mutableListOf<VideoPlaybackInfo>()
    private var allVideos = mutableListOf<VideoPlaybackInfo>()
    private val videoMetaMap = mutableMapOf<String, VideoPlaybackInfo>()
    private val videoVisibilityMap = mutableMapOf<String, Boolean>()
    private val videoPositionMap = mutableMapOf<String, Float>()
    
    // Currently playing video identifier
    private var primaryVideoId: String? = null
    
    // Playback debounce timer
    private var playbackDebounceJob: Job? = null
    private const val PLAYBACK_DEBOUNCE_MS = 200L

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
     * Update individual video visibility (called by VideoPreview's onGloballyPositioned)
     */
    fun updateVideoVisibility(
        videoMid: MimeiId,
        tweetId: String,
        isVisible: Boolean,
        topY: Float? = null
    ) {
        val identifier = "${tweetId}_$videoMid"
        val previousVisibility = videoVisibilityMap[identifier] ?: false

        if (videoMetaMap[identifier] == null) {
            videoMetaMap[identifier] = VideoPlaybackInfo(tweetId = tweetId, videoMid = videoMid, index = 0)
        }

        topY?.let { videoPositionMap[identifier] = it }
        if (previousVisibility != isVisible) {
            videoVisibilityMap[identifier] = isVisible
            Timber.d("VideoPlaybackCoordinator: Video $videoMid (identifier: $identifier, tweet: $tweetId) visibility changed: $previousVisibility -> $isVisible (topY=$topY)")
            updateVisibleVideos()
        }
    }

    /**
     * Update visible videos based on video visibility reports
     */
    private fun updateVisibleVideos() {
        val previousVisibleIds = visibleVideos.map { it.identifier }.toSet()

        visibleVideos = videoMetaMap.values.filter { videoInfo ->
            videoVisibilityMap[videoInfo.identifier] == true
        }.sortedBy { videoInfo ->
            videoPositionMap[videoInfo.identifier] ?: Float.MAX_VALUE
        }.toMutableList()

        if (visibleVideos.isEmpty()) {
            stopAllVideos()
            return
        }

        // Stop videos no longer visible
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

        val topmostIdentifier = visibleVideos.first().identifier

        // If primary not visible or not topmost, reset and reselect
        if (primaryVideoId != null) {
            if (primaryVideoId !in currentVisibleIds || primaryVideoId != topmostIdentifier) {
                primaryVideoId = null
            }
        }

        if (primaryVideoId == null) {
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
        videoPositionMap.clear()
    }
}

