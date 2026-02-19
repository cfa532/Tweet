package us.fireshare.tweet.widget

import androidx.compose.runtime.compositionLocalOf
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
 * CompositionLocal providing the active VideoPlaybackCoordinator.
 * Defaults to [VideoPlaybackCoordinator.shared] (main feed coordinator).
 * TweetDetailScreen overrides this with a per-instance coordinator for comment videos.
 */
val LocalVideoCoordinator = compositionLocalOf<VideoPlaybackCoordinator> { VideoPlaybackCoordinator.shared }

/**
 * Video playback coordinator that manages video playback within a single scrollable list.
 * Similar to iOS VideoPlaybackCoordinator.
 *
 * Following the iOS pattern, each scrollable context (feed, detail screen, profile)
 * uses its own coordinator instance so they don't interfere with each other.
 * The main feed uses [shared]; detail screens create per-instance coordinators.
 *
 * Behavior:
 * 1. Tracks visible videos in the feed (only videos with >= 50% visibility)
 * 2. Manages playback state for videos
 * 3. Coordinates video playback via notifications/events
 * 4. Switches to next video when current video is 50% off screen
 * 5. Selects primary video based on scroll direction (bottommost when scrolling down, topmost when scrolling up)
 */
class VideoPlaybackCoordinator(
    /** When true, buildVideoList syncs with FullScreenPlayerManager. Only the shared instance should do this. */
    private val syncWithFullScreenPlayer: Boolean = false
) {
    companion object {
        /** Singleton instance for the main feed, similar to iOS VideoPlaybackCoordinator.shared */
        val shared = VideoPlaybackCoordinator(syncWithFullScreenPlayer = true)

        private const val PLAYBACK_DEBOUNCE_MS = 100L
        private const val VISIBILITY_THRESHOLD = 0.5f
        private const val VISIBILITY_UPDATE_DEBOUNCE_MS = 150L
    }

    // Single reusable coroutine scope to avoid allocating a new CoroutineScope on every event
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var visibleVideos = mutableListOf<VideoPlaybackInfo>()
    private var allVideos = mutableListOf<VideoPlaybackInfo>()
    private var currentTweets = listOf<Tweet>()
    private val videoMetaMap = mutableMapOf<String, VideoPlaybackInfo>()
    private val tweetCellBoundsMap = mutableMapOf<String, android.graphics.RectF>()
    private val tweetVisibilityMap = mutableMapOf<String, Boolean>()
    private val videoVisibilityMap = mutableMapOf<String, Float>()
    // Videos that have finished playing — excluded from auto-play selection
    private val finishedVideoIds = mutableSetOf<String>()

    private var viewportWidth = 1080f
    private var viewportHeight = 2340f

    /** When true, the coordinator suppresses auto-play selection.
     *  Used in TweetDetailScreen to prevent comment videos from playing
     *  while the main tweet video is still visible. */
    var isPaused: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    stopAllVideos()
                }
            }
        }

    private var primaryVideoId: String? = null

    private var scrollDirection: Boolean = true
    private var previousContentOffset: Float = 0f

    private var playbackDebounceJob: Job? = null
    private var visibilityUpdateDebounceJob: Job? = null

    // Command flow - similar to iOS NotificationCenter
    private val _playbackCommands = MutableSharedFlow<VideoPlaybackCommand>(replay = 0, extraBufferCapacity = 64)
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
                    tweetId = quotingTweetId,
                    videoMid = attachment.mid,
                    index = index
                )
                videosToAdd.add(videoInfo)
            }
        }

        if (videosToAdd.isNotEmpty()) {
            scope.launch {
                buildVideoList(currentTweets, emptyList())
            }
        }
    }

    /**
     * Add retweet videos to the video list when the original tweet becomes available
     * Called by TweetItem when retweet original tweets are loaded
     */
    fun addRetweetVideos(retweetId: MimeiId, originalTweet: Tweet) {
        val videosToAdd = mutableListOf<VideoPlaybackInfo>()

        originalTweet.attachments?.forEachIndexed { index, attachment ->
            if (attachment.type == MediaType.Video || attachment.type == MediaType.HLS_VIDEO) {
                val videoInfo = VideoPlaybackInfo(
                    tweetId = retweetId,
                    videoMid = attachment.mid,
                    index = index
                )
                videosToAdd.add(videoInfo)
            }
        }

        if (videosToAdd.isNotEmpty()) {
            scope.launch {
                buildVideoList(currentTweets, emptyList())
            }
        }
    }

    /**
     * Build video list from tweets
     * Similar to iOS buildVideoList(from:tweets:pinnedTweets:)
     */
    suspend fun buildVideoList(tweets: List<Tweet>, pinnedTweets: List<Tweet> = emptyList()) {
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

        // Process regular tweets IN ORDER
        for (tweet in tweets) {
            val hasContentText = !tweet.content.isNullOrEmpty()
            val hasAttachments = tweet.attachments != null && tweet.attachments!!.isNotEmpty()
            val hasOwnContent = hasContentText || hasAttachments
            val hasOriginalTweet = tweet.originalTweetId != null
            val isPureRetweet = hasOriginalTweet && !hasOwnContent
            val isQuotedTweet = hasOriginalTweet && hasOwnContent

            if (isPureRetweet) {
                if (tweet.originalTweetId != null) {
                    val originalTweet = TweetCacheManager.getCachedTweet(tweet.originalTweetId!!)

                    originalTweet?.attachments?.forEachIndexed { index, attachment ->
                        if (attachment.type == MediaType.Video || attachment.type == MediaType.HLS_VIDEO) {
                            val videoInfo = VideoPlaybackInfo(
                                tweetId = tweet.mid,
                                videoMid = attachment.mid,
                                index = index
                            )
                            videos.add(videoInfo)
                        }
                    }

                    if (originalTweet == null) {
                        Timber.d("VideoPlaybackCoordinator: Original tweet ${tweet.originalTweetId} not cached yet for retweet ${tweet.mid}, will be added later when fetched by TweetItem")
                    }
                }
            } else {
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

                if (isQuotedTweet && tweet.originalTweetId != null) {
                    val embeddedTweet = TweetCacheManager.getCachedTweet(tweet.originalTweetId!!)

                    embeddedTweet?.attachments?.forEachIndexed { index, attachment ->
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
            }
        }

        allVideos = videos
        videos.forEach { videoInfo ->
            videoMetaMap[videoInfo.identifier] = videoInfo
        }

        if (syncWithFullScreenPlayer) {
            val videoListForFullScreen = videos.map { videoInfo ->
                val tweet = (pinnedTweets + tweets).find { it.mid == videoInfo.tweetId }
                val attachment = tweet?.attachments?.getOrNull(videoInfo.index)
                val mediaType = attachment?.type ?: MediaType.Video
                Pair(videoInfo.videoMid, mediaType)
            }
            FullScreenPlayerManager.updateVideoList(videoListForFullScreen, tweets)
        }

        Timber.d("VideoPlaybackCoordinator: Built video list with ${videos.size} videos")
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
    }

    /**
     * Update video visibility based on the video's own visibility ratio.
     * Called by VideoPreview when its visibility changes.
     */
    fun updateVideoVisibility(videoMid: MimeiId, tweetId: String, visibilityRatio: Float) {
        val identifier = "${tweetId}_$videoMid"
        val previousRatio = videoVisibilityMap[identifier] ?: 0f
        videoVisibilityMap[identifier] = visibilityRatio

        val crossesThreshold = (previousRatio < VISIBILITY_THRESHOLD && visibilityRatio >= VISIBILITY_THRESHOLD) ||
                               (previousRatio >= VISIBILITY_THRESHOLD && visibilityRatio < VISIBILITY_THRESHOLD)

        if (crossesThreshold) {
            checkPrimaryVideoDuringScroll()
        }

        visibilityUpdateDebounceJob?.cancel()
        visibilityUpdateDebounceJob = scope.launch {
            delay(VISIBILITY_UPDATE_DEBOUNCE_MS)
            updateVisibleVideos()
            checkAndSwitchVideoIfNeeded()
        }
    }

    private fun checkPrimaryVideoDuringScroll() {
        if (isPaused) return
        // If current primary is still visible above threshold, keep it playing
        if (primaryVideoId != null) {
            val currentVisibility = videoVisibilityMap[primaryVideoId] ?: 0f
            if (currentVisibility >= VISIBILITY_THRESHOLD) {
                return
            }
        }

        val source = if (visibleVideos.isNotEmpty()) visibleVideos else allVideos
        val currentVisible = source.filter { videoInfo ->
            videoInfo.identifier !in finishedVideoIds &&
            (videoVisibilityMap[videoInfo.identifier] ?: 0f) >= VISIBILITY_THRESHOLD
        }

        val sortedVisible = if (currentVisible.isNotEmpty()) {
            currentVisible.sortedBy { videoInfo ->
                tweetCellBoundsMap[videoInfo.tweetId]?.top ?: Float.MAX_VALUE
            }
        } else {
            emptyList()
        }

        if (sortedVisible.isNotEmpty()) {
            val correctPrimary = if (scrollDirection) {
                sortedVisible.firstOrNull()
            } else {
                sortedVisible.lastOrNull()
            }

            if (correctPrimary != null && correctPrimary.identifier != primaryVideoId) {
                Timber.d("VideoPlaybackCoordinator: Detected primary video change during scroll to: ${correctPrimary.videoMid}")
                val previousPrimaryId = primaryVideoId
                primaryVideoId = correctPrimary.identifier

                scope.launch {
                    if (previousPrimaryId != null && previousPrimaryId != correctPrimary.identifier) {
                        val previousPrimary = videoMetaMap[previousPrimaryId]
                        if (previousPrimary != null) {
                            _playbackCommands.emit(VideoPlaybackCommand.ShouldStopVideo(previousPrimary.videoMid))
                        }
                    }

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
     */
    fun updateTweetCellPosition(
        tweetId: String,
        cellTopY: Float,
        cellHeight: Float,
        isVisible: Boolean
    ) {
        val cellBounds = android.graphics.RectF(
            0f,
            cellTopY,
            viewportWidth,
            cellTopY + cellHeight
        )
        tweetCellBoundsMap[tweetId] = cellBounds
        tweetVisibilityMap[tweetId] = isVisible
    }

    private fun updateVisibleVideos() {
        val previousVisibleIds = visibleVideos.map { it.identifier }.toSet()

        visibleVideos = allVideos.filter { videoInfo ->
            val visibilityRatio = videoVisibilityMap[videoInfo.identifier] ?: 0f
            visibilityRatio >= VISIBILITY_THRESHOLD
        }.sortedBy { videoInfo ->
            tweetCellBoundsMap[videoInfo.tweetId]?.top ?: Float.MAX_VALUE
        }.toMutableList()

        if (visibleVideos.isNotEmpty()) {
            Timber.d("VideoPlaybackCoordinator: Visible videos order: ${visibleVideos.joinToString(", ") { videoInfo ->
                val bounds = tweetCellBoundsMap[videoInfo.tweetId]
                val pos = bounds?.top ?: Float.MAX_VALUE
                val visibility = videoVisibilityMap[videoInfo.identifier] ?: 0f
                "${videoInfo.videoMid.substring(0, minOf(8, videoInfo.videoMid.length))}@${pos.toInt()}[${(visibility * 100).toInt()}%]"
            }}")
        }

        if (visibleVideos.isEmpty()) {
            stopAllVideos()
            return
        }

        val currentVisibleIds = visibleVideos.map { it.identifier }.toSet()
        val videosToStop = previousVisibleIds - currentVisibleIds
        videosToStop.forEach { identifier ->
            // Reset finished state so the video can play again when scrolled back
            finishedVideoIds.remove(identifier)
            val info = videoMetaMap[identifier]
            if (info != null) {
                scope.launch {
                    _playbackCommands.emit(VideoPlaybackCommand.ShouldStopVideo(info.videoMid))
                }
            }
        }

        if (primaryVideoId != null && primaryVideoId !in currentVisibleIds) {
            primaryVideoId = null
        }

        // Only pick a new primary when there isn't one.
        // Don't switch away from a playing primary that is still visible.
        if (primaryVideoId == null && visibleVideos.isNotEmpty()) {
            startPlaybackWithDebounce()
        }
    }

    private fun identifyPrimaryVideo(): VideoPlaybackInfo? {
        val candidates = visibleVideos.filter { it.identifier !in finishedVideoIds }
        if (candidates.isEmpty()) return null

        return if (scrollDirection) {
            candidates.firstOrNull()
        } else {
            candidates.lastOrNull()
        }
    }

    private fun checkAndSwitchVideoIfNeeded() {
        val primaryId = primaryVideoId ?: return
        val primaryVideo = visibleVideos.find { it.identifier == primaryId } ?: return

        val visibilityRatio = videoVisibilityMap[primaryId] ?: 0f

        if (visibilityRatio < VISIBILITY_THRESHOLD) {
            val newPrimary = identifyPrimaryVideo()
            if (newPrimary != null && newPrimary.identifier != primaryId) {
                scope.launch {
                    _playbackCommands.emit(VideoPlaybackCommand.ShouldStopVideo(primaryVideo.videoMid))

                    visibleVideos.forEach { videoInfo ->
                        if (videoInfo.identifier != newPrimary.identifier) {
                            _playbackCommands.emit(VideoPlaybackCommand.ShouldPauseVideo(videoInfo.videoMid))
                        }
                    }

                    delay(50L)

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

    private fun startPlaybackWithDebounce() {
        playbackDebounceJob?.cancel()

        playbackDebounceJob = scope.launch {
            delay(PLAYBACK_DEBOUNCE_MS)

            if (visibleVideos.isNotEmpty() && primaryVideoId == null) {
                startPrimaryVideoPlayback()
            }
        }
    }

    private fun startPrimaryVideoPlayback() {
        if (isPaused) return
        if (visibleVideos.isEmpty()) {
            Timber.w("VideoPlaybackCoordinator: No visible videos to play")
            return
        }

        val primary = identifyPrimaryVideo() ?: return
        val previousPrimaryId = primaryVideoId
        primaryVideoId = primary.identifier

        scope.launch {
            if (previousPrimaryId != null && previousPrimaryId != primary.identifier) {
                val previousPrimary = videoMetaMap[previousPrimaryId]
                if (previousPrimary != null) {
                    Timber.d("VideoPlaybackCoordinator: Stopping previous primary video: ${previousPrimary.videoMid}")
                    _playbackCommands.emit(VideoPlaybackCommand.ShouldStopVideo(previousPrimary.videoMid))
                }
            }

            visibleVideos.forEach { videoInfo ->
                if (videoInfo.identifier != primary.identifier) {
                    _playbackCommands.emit(VideoPlaybackCommand.ShouldPauseVideo(videoInfo.videoMid))
                }
            }

            delay(50L)

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

    fun shouldAutoPlay(videoMid: MimeiId, tweetId: String): Boolean {
        val identifier = "${tweetId}_$videoMid"
        return primaryVideoId == identifier && visibleVideos.isNotEmpty()
    }

    fun handleVideoFinished(videoMid: MimeiId, tweetId: String) {
        val identifier = "${tweetId}_$videoMid"
        finishedVideoIds.add(identifier)

        if (primaryVideoId == identifier) {
            primaryVideoId = null
            // Emit stop so VideoPreview clears coordinatorWantsToPlay
            scope.launch {
                _playbackCommands.emit(VideoPlaybackCommand.ShouldStopVideo(videoMid))
            }
            Timber.d("VideoPlaybackCoordinator: Video finished: $videoMid — stopping")
        }
    }

    fun stopAllVideos() {
        playbackDebounceJob?.cancel()
        playbackDebounceJob = null
        primaryVideoId = null
        scope.launch {
            _playbackCommands.emit(VideoPlaybackCommand.ShouldStopAllVideos)
        }
        Timber.d("VideoPlaybackCoordinator: Stopped all videos")
    }

    /**
     * Clear all coordinator state. Call when the screen using this coordinator is disposed.
     */
    fun clear() {
        playbackDebounceJob?.cancel()
        playbackDebounceJob = null
        visibilityUpdateDebounceJob?.cancel()
        visibilityUpdateDebounceJob = null

        stopAllVideos()

        visibleVideos.clear()
        allVideos.clear()
        currentTweets = emptyList()
        videoMetaMap.clear()
        tweetCellBoundsMap.clear()
        tweetVisibilityMap.clear()
        videoVisibilityMap.clear()
        finishedVideoIds.clear()
        previousContentOffset = 0f
        scrollDirection = true

        Timber.d("VideoPlaybackCoordinator: Cleared all state")
    }
}
