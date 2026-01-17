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
                if (tweet.originalTweetId != null && tweet.originalAuthorId != null) {
                    // Try cache first, then fetch if needed
                    var originalTweet = TweetCacheManager.getCachedTweet(tweet.originalTweetId!!)
                    
                    // If not in cache, try to fetch it
                    if (originalTweet == null) {
                        try {
                            originalTweet = us.fireshare.tweet.HproseInstance.refreshTweet(tweet.originalTweetId!!, tweet.originalAuthorId!!)
                        } catch (e: Exception) {
                            Timber.w("VideoPlaybackCoordinator: Failed to fetch original tweet for retweet: ${tweet.mid}, error: ${e.message}")
                        }
                    }
                    
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
                    if (originalTweet == null) {
                        Timber.w("VideoPlaybackCoordinator: Original tweet not found for retweet: ${tweet.mid}")
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
                    var embeddedTweet = TweetCacheManager.getCachedTweet(tweet.originalTweetId!!)
                    
                    // If not in cache, try to fetch it
                    if (embeddedTweet == null && tweet.originalAuthorId != null) {
                        try {
                            embeddedTweet = us.fireshare.tweet.HproseInstance.refreshTweet(tweet.originalTweetId!!, tweet.originalAuthorId!!)
                        } catch (e: Exception) {
                            Timber.w("VideoPlaybackCoordinator: Failed to fetch embedded tweet: ${tweet.originalTweetId}, error: ${e.message}")
                        }
                    }
                    
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
        // CRITICAL FIX: If tweetId is an original tweet ID, check if this video is actually in a retweet
        // and use the retweet ID instead to match buildVideoList behavior
        var actualTweetId: String = tweetId
        var identifier = "${tweetId}_$videoMid"
        
        // Check if this identifier doesn't exist, which might mean we're using the wrong tweet ID
        if (videoMetaMap[identifier] == null) {
            // First, try to find if this video is already in allVideos (by videoMid)
            // This is the source of truth - buildVideoList creates entries with the correct retweet ID
            val matchingVideo = allVideos.find { it.videoMid == videoMid }
            if (matchingVideo != null) {
                // Found the video in allVideos - use its identifier (which has the correct retweet ID)
                actualTweetId = matchingVideo.tweetId
                identifier = matchingVideo.identifier
                if (matchingVideo.tweetId != tweetId) {
                    Timber.d("VideoPlaybackCoordinator: Corrected identifier from ${tweetId}_$videoMid to $identifier (found existing video with retweet ID: $actualTweetId)")
                }
            } else {
                // Video not found in allVideos - search for retweet containing this video
                // buildVideoList always uses retweet ID, so if tweetId is original, we need to find the retweet
                val retweet = currentTweets.find { retweetTweet ->
                    val retweetOriginalId = retweetTweet.originalTweetId
                    // Check if this retweet's originalTweetId matches the incoming tweetId
                    retweetOriginalId != null && retweetOriginalId == tweetId &&
                    // Check if this retweet contains the video (either in its own attachments or original tweet's attachments)
                    (retweetTweet.attachments?.any { it.mid == videoMid } == true ||
                     TweetCacheManager.getCachedTweet(retweetOriginalId)?.attachments?.any { it.mid == videoMid } == true)
                }
                
                if (retweet != null) {
                    // Found retweet containing this video - use retweet ID
                    actualTweetId = retweet.mid
                    identifier = "${retweet.mid}_$videoMid"
                    Timber.d("VideoPlaybackCoordinator: Corrected identifier from ${tweetId}_$videoMid to $identifier (found retweet: ${retweet.mid}, originalTweetId: $tweetId)")
                    
                    // Create the entry with correct identifier
                    if (videoMetaMap[identifier] == null) {
                        videoMetaMap[identifier] = VideoPlaybackInfo(tweetId = actualTweetId, videoMid = videoMid, index = 0)
                        allVideos.add(videoMetaMap[identifier]!!)
                    }
                } else {
                    // Check if tweetId itself is a retweet in currentTweets that contains this video
                    val tweetInList = currentTweets.find { it.mid == tweetId }
                    if (tweetInList != null) {
                        val originalId = tweetInList.originalTweetId
                        if (originalId != null) {
                            // tweetId is a retweet - check if it contains this video
                            val originalTweet = TweetCacheManager.getCachedTweet(originalId)
                            val hasVideo = tweetInList.attachments?.any { it.mid == videoMid } == true ||
                                          originalTweet?.attachments?.any { it.mid == videoMid } == true
                            
                            if (hasVideo) {
                                // tweetId is already the retweet ID and contains the video - use it directly
                                actualTweetId = tweetId
                                identifier = "${tweetId}_$videoMid"
                                Timber.d("VideoPlaybackCoordinator: tweetId=$tweetId is a retweet containing video $videoMid, using it directly")
                                
                                if (videoMetaMap[identifier] == null) {
                                    videoMetaMap[identifier] = VideoPlaybackInfo(tweetId = actualTweetId, videoMid = videoMid, index = 0)
                                    allVideos.add(videoMetaMap[identifier]!!)
                                }
                            } else {
                                // Retweet doesn't contain this video - create entry but warn
                                Timber.w("VideoPlaybackCoordinator: Creating new videoMetaMap entry for identifier=$identifier (tweetId=$tweetId, videoMid=$videoMid) - retweet doesn't contain this video!")
                                videoMetaMap[identifier] = VideoPlaybackInfo(tweetId = tweetId, videoMid = videoMid, index = 0)
                                allVideos.add(videoMetaMap[identifier]!!)
                            }
                        } else {
                            // tweetId is a regular tweet (not a retweet) - check if it contains the video
                            val hasVideo = tweetInList.attachments?.any { it.mid == videoMid } == true
                            if (hasVideo) {
                                // Use it directly as it's a regular tweet containing the video
                                actualTweetId = tweetId
                                identifier = "${tweetId}_$videoMid"
                                Timber.d("VideoPlaybackCoordinator: tweetId=$tweetId is a regular tweet containing video $videoMid, using it directly")
                                
                                if (videoMetaMap[identifier] == null) {
                                    videoMetaMap[identifier] = VideoPlaybackInfo(tweetId = actualTweetId, videoMid = videoMid, index = 0)
                                    allVideos.add(videoMetaMap[identifier]!!)
                                }
                            } else {
                                // Regular tweet doesn't contain this video - create entry but warn
                                Timber.w("VideoPlaybackCoordinator: Creating new videoMetaMap entry for identifier=$identifier (tweetId=$tweetId, videoMid=$videoMid) - regular tweet doesn't contain this video!")
                                videoMetaMap[identifier] = VideoPlaybackInfo(tweetId = tweetId, videoMid = videoMid, index = 0)
                                allVideos.add(videoMetaMap[identifier]!!)
                            }
                        }
                    } else {
                        // tweetId is not in currentTweets - create new entry but warn
                        Timber.w("VideoPlaybackCoordinator: Creating new videoMetaMap entry for identifier=$identifier (tweetId=$tweetId, videoMid=$videoMid) - tweet not found in currentTweets!")
                        videoMetaMap[identifier] = VideoPlaybackInfo(tweetId = tweetId, videoMid = videoMid, index = 0)
                        allVideos.add(videoMetaMap[identifier]!!)
                    }
                }
            }
        }
        
        val previousVisibility = videoVisibilityMap[identifier] ?: false
        val previousRatio = videoVisibilityRatioMap[identifier] ?: 0f

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

        // Filter videos by 50% visibility threshold and ensure they have valid positions
        // Exclude videos without position data (Float.MAX_VALUE) unless they're already the primary video
        visibleVideos = videoMetaMap.values.filter { videoInfo ->
            val visibilityRatio = videoVisibilityRatioMap[videoInfo.identifier] ?: 0f
            val hasValidPosition = videoPositionMap[videoInfo.identifier] != null && 
                                  videoPositionMap[videoInfo.identifier] != Float.MAX_VALUE
            val isCurrentPrimary = videoInfo.identifier == primaryVideoId
            val hasVisibilityData = videoVisibilityRatioMap.containsKey(videoInfo.identifier) || 
                                   videoPositionMap.containsKey(videoInfo.identifier)
            
            val passesFilter = visibilityRatio >= VISIBILITY_THRESHOLD && (hasValidPosition || isCurrentPrimary)
            
            // Debug: Log videos that fail the filter
            if (!passesFilter && hasVisibilityData) {
                Timber.w("VideoPlaybackCoordinator: Video filtered out: ${videoInfo.identifier.substring(0, minOf(20, videoInfo.identifier.length))}, ratio=$visibilityRatio, hasPos=$hasValidPosition, isPrimary=$isCurrentPrimary")
            }
            
            passesFilter
        }.sortedBy { videoInfo ->
            videoPositionMap[videoInfo.identifier] ?: Float.MAX_VALUE
        }.toMutableList()
        
        // Debug log to verify ordering
        if (visibleVideos.isNotEmpty()) {
            val orderDebug = visibleVideos.joinToString(", ") { videoInfo ->
                val pos = videoPositionMap[videoInfo.identifier] ?: Float.MAX_VALUE
                val ratio = videoVisibilityRatioMap[videoInfo.identifier] ?: 0f
                val ratioStr = if (videoVisibilityRatioMap.containsKey(videoInfo.identifier)) "${(ratio * 100).toInt()}%" else "N/A"
                val inMap = if (videoMetaMap.containsKey(videoInfo.identifier)) "✓" else "✗"
                "${videoInfo.videoMid.substring(0, minOf(8, videoInfo.videoMid.length))}@${pos.toInt()}[${ratioStr}]${inMap}"
            }
            Timber.d("VideoPlaybackCoordinator: Visible videos order (scrollDirection=${if (scrollDirection) "DOWN" else "UP"}): $orderDebug")
            
            // Also log all videos in videoMetaMap to see if there are mismatches
            val allVideosDebug = videoMetaMap.values.joinToString(", ") { videoInfo ->
                val pos = videoPositionMap[videoInfo.identifier] ?: Float.MAX_VALUE
                val ratio = videoVisibilityRatioMap[videoInfo.identifier] ?: 0f
                val ratioStr = if (videoVisibilityRatioMap.containsKey(videoInfo.identifier)) "${(ratio * 100).toInt()}%" else "N/A"
                "${videoInfo.videoMid.substring(0, minOf(8, videoInfo.videoMid.length))}[${videoInfo.tweetId.substring(0, minOf(8, videoInfo.tweetId.length))}]@${pos.toInt()}[${ratioStr}]"
            }
            Timber.d("VideoPlaybackCoordinator: All videos in videoMetaMap: $allVideosDebug")
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

        // Identify primary video based on scroll direction
        val newPrimary = identifyPrimaryVideo()
        val newPrimaryIdentifier = newPrimary?.identifier

        // Only reset primary if it's no longer visible, not just because sorting changed slightly
        if (primaryVideoId != null) {
            val currentPrimaryIsVisible = primaryVideoId in currentVisibleIds
            if (!currentPrimaryIsVisible) {
                // Current primary is no longer visible, switch to new primary
                primaryVideoId = null
            } else if (newPrimaryIdentifier != null && newPrimaryIdentifier != primaryVideoId) {
                // Check if new primary is significantly better positioned than current
                val currentPrimaryPos = videoPositionMap[primaryVideoId] ?: Float.MAX_VALUE
                val newPrimaryPos = videoPositionMap[newPrimaryIdentifier] ?: Float.MAX_VALUE
                
                val shouldSwitch = if (scrollDirection) {
                    // Scrolling DOWN: switch if new is significantly higher (lower Y)
                    newPrimaryPos < currentPrimaryPos - 50f // 50px threshold
                } else {
                    // Scrolling UP: switch if new is significantly lower (higher Y)
                    newPrimaryPos > currentPrimaryPos + 50f // 50px threshold
                }
                
                if (shouldSwitch) {
                    Timber.d("VideoPlaybackCoordinator: Switching primary from ${primaryVideoId} to ${newPrimaryIdentifier} (scrollDirection=${if (scrollDirection) "DOWN" else "UP"}, currentPos=${currentPrimaryPos.toInt()}, newPos=${newPrimaryPos.toInt()})")
                    primaryVideoId = null // Will trigger reselection
                }
            }
        }

        if (primaryVideoId == null) {
            startPlaybackWithDebounce()
        }
    }
    
    /**
     * Identify primary video based on scroll direction
     * Scrolling DOWN: bottommost video (highest Y) - new content entering from bottom
     * Scrolling UP: topmost video (lowest Y) - new content entering from top
     */
    private fun identifyPrimaryVideo(): VideoPlaybackInfo? {
        if (visibleVideos.isEmpty()) return null
        
        return if (scrollDirection) {
            // Scrolling DOWN: return bottommost (last in sorted list - highest Y)
            // because new videos are entering the viewport from the bottom
            visibleVideos.last()
        } else {
            // Scrolling UP: return topmost (first in sorted list - lowest Y)
            // because new videos are entering the viewport from the top
            visibleVideos.first()
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
        
        val direction = if (scrollDirection) "bottommost (scrolling DOWN)" else "topmost (scrolling UP)"
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
        currentTweets = emptyList()
        videoMetaMap.clear()
        videoVisibilityMap.clear()
        videoVisibilityRatioMap.clear()
        videoPositionMap.clear()
        previousContentOffset = 0f
        scrollDirection = true
    }
}

