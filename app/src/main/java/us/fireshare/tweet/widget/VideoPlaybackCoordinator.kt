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
        val playbackVideoId: String,
        val videoIndex: Int,
        val isPrimary: Boolean
    ) : VideoPlaybackCommand()

    data class ShouldPauseVideo(val playbackVideoId: String, val videoMid: MimeiId) : VideoPlaybackCommand()
    data class ShouldStopVideo(val playbackVideoId: String, val videoMid: MimeiId) : VideoPlaybackCommand()
    object ShouldStopAllVideos : VideoPlaybackCommand()
}

fun videoPlaybackIdentifier(videoMid: MimeiId, parentTweetId: MimeiId? = null): String =
    if (parentTweetId.isNullOrEmpty()) videoMid else "${parentTweetId}_$videoMid"

/**
 * Video playback info for tracking individual videos
 * Similar to iOS VideoPlaybackInfo structure
 */
data class VideoPlaybackInfo(
    val tweetId: String,
    val videoMid: MimeiId,
    val index: Int,
    val mediaType: MediaType = MediaType.Video,
    val playbackParentTweetId: MimeiId? = null,
    private val explicitIdentifier: String? = null
) {
    val identifier: String get() = explicitIdentifier ?: videoPlaybackIdentifier(videoMid, playbackParentTweetId)

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
 * 1. Tracks visible videos in the feed (loadable, playable, and continue-playing sets)
 * 2. Manages playback state for videos
 * 3. Coordinates video playback via notifications/events
 * 4. Starts videos at 50% visible and stops the current video below 50% visible (same
 *    threshold for both — see KEEP_PLAYING_VISIBILITY_THRESHOLD)
 * 5. Selects primary video based on scroll direction (topmost when scrolling down, bottommost when scrolling up)
 */
class VideoPlaybackCoordinator(
    /** When true, buildVideoList syncs with FullScreenPlayerManager. Only the shared instance should do this. */
    private val syncWithFullScreenPlayer: Boolean = false
) {
    companion object {
        /** Singleton instance for the main feed, similar to iOS VideoPlaybackCoordinator.shared */
        val shared = VideoPlaybackCoordinator(syncWithFullScreenPlayer = true)

        /**
         * Not private: VideoPreview's onGloballyPositioned callback references this directly
         * so its immediate-report-vs-throttle decision uses the same breakpoint as the
         * coordinator, instead of a separately hardcoded literal that can drift out of sync
         * (see VideoPreview.kt history — it had its own hardcoded 0.5f/0.7f pair here that
         * never got updated when KEEP_PLAYING_VISIBILITY_THRESHOLD below was unified to 0.5f).
         */
        internal const val VISIBILITY_THRESHOLD = 0.5f
        /**
         * Matches iOS FeedPlaybackTuning.videoContinueVisibilityRatio, which is deliberately
         * kept equal to the start threshold: "A higher continuation threshold causes
         * startup/layout samples around 50-70% visible to start, stop, then restart the same
         * onscreen video." This used to be 0.7f here, independently of VISIBILITY_THRESHOLD,
         * which reintroduced exactly that thrash on Android.
         */
        private const val KEEP_PLAYING_VISIBILITY_THRESHOLD = VISIBILITY_THRESHOLD
        private const val VISIBILITY_UPDATE_DEBOUNCE_MS = 150L
        private const val FOREGROUND_RECONCILE_DELAY_MS = 120L
        private const val VISIBILITY_LOSS_STOP_GRACE_MS = 300L
        /**
         * Matches iOS primarySelectionActiveDragDelay. Confirmation window used when a
         * primary candidate is identified while the user is still actively scrolling,
         * rather than after the scroll fully stops — see
         * [identifyPrimaryVideoDuringActiveScroll].
         */
        private const val ACTIVE_SCROLL_PRIMARY_SELECTION_DELAY_MS = 500L
    }

    // Single reusable coroutine scope to avoid allocating a new CoroutineScope on every event
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val managerPlaybackOwnerKey = System.identityHashCode(this)

    private var visibleVideos = mutableListOf<VideoPlaybackInfo>()
    private var allVideos = mutableListOf<VideoPlaybackInfo>()
    private var currentTweets = listOf<Tweet>()
    private val videoMetaMap = mutableMapOf<String, VideoPlaybackInfo>()
    private val tweetCellBoundsMap = mutableMapOf<String, android.graphics.RectF>()
    private val tweetVisibilityMap = mutableMapOf<String, Boolean>()
    private val videoVisibilityMap = mutableMapOf<String, Float>()
    private val videoGeometryMap = mutableMapOf<String, android.graphics.RectF>()
    private var loadVisibleVideoIds = emptySet<String>()
    private var continuePlaybackVideoIds = emptySet<String>()
    private var playableVideoIds = emptySet<String>()
    private var visibleTweetIds = emptySet<String>()
    // Videos that have finished playing — excluded from auto-play selection
    private val finishedVideoIds = mutableSetOf<String>()
    // Current primary excluded after it drops below the continue threshold.
    // This prevents a 50%-visible outgoing video from immediately reselecting itself.
    private var primaryBelowContinueIdentifier: String? = null
    private var userRequestedPrimaryIdentifier: String? = null

    private fun setPrimaryVideoId(identifier: String?) {
        val previous = primaryVideoId
        if (previous == identifier) return

        previous?.let { VideoManager.clearCoordinatorPrimaryVideo(it) }
        primaryVideoId = identifier
        identifier?.let {
            cancelPendingVisibilityLossStop(it)
            VideoManager.markCoordinatorPrimaryVideo(it)
        }
        updateManagerRetainedVideos()
    }

    private var viewportWidth = 1080f
    private var viewportHeight = 2340f
    private var viewportVisibleTop = 0f
    private var viewportVisibleBottom = viewportHeight

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
    private var pendingResumePrimaryId: String? = null
    /** Set by [requestPlay] to prevent debounced visibility updates from immediately stopping a user-initiated play. */
    private var userRequestedPlayAt: Long = 0L
    /** Set by [buildVideoList] to protect auto-play from visibility fluctuations during screen load. */
    private var videoListBuiltAt: Long = 0L
    private var hostResumedAt: Long = 0L
    var isFeedVisible: Boolean = true
        private set

    private var scrollDirection: Boolean = true
    private var previousContentOffset: Float = 0f
    private var isScrollInProgress: Boolean = false

    private var playbackDebounceJob: Job? = null
    private var playbackDebounceCandidateId: String? = null
    private var visibilityUpdateDebounceJob: Job? = null
    private var geometryReconcileJob: Job? = null
    private val pendingVisibilityLossStopJobs = mutableMapOf<String, Job>()

    // Command flow - similar to iOS NotificationCenter
    private val _playbackCommands = MutableSharedFlow<VideoPlaybackCommand>(replay = 0, extraBufferCapacity = 64)
    val playbackCommands: SharedFlow<VideoPlaybackCommand> = _playbackCommands.asSharedFlow()

    /**
     * Add embedded tweet videos to the video list when they become available
     * Called by TweetItem when embedded tweets are loaded
     */
    fun addEmbeddedTweetVideos(quotingTweetId: MimeiId, embeddedTweet: Tweet) {
        addVideosFromTweet(quotingTweetId, embeddedTweet, "embedded")
    }

    /**
     * Add retweet videos to the video list when the original tweet becomes available
     * Called by TweetItem when retweet original tweets are loaded
     */
    fun addRetweetVideos(retweetId: MimeiId, originalTweet: Tweet) {
        addVideosFromTweet(retweetId, originalTweet, "retweet")
    }

    /**
     * Directly insert videos from a lazily-loaded tweet (retweet original or quoted embed)
     * into the coordinator's tracking structures. Unlike the old approach that called
     * buildVideoList (which relies on getCachedTweetMemoryOnly and may still miss the tweet),
     * this inserts the already-available videos immediately.
     *
     * Called from IO thread (RetweetContent/QuotedTweetContent), so dispatch to Main
     * since allVideos/videoMetaMap are not thread-safe.
     */
    private fun addVideosFromTweet(parentTweetId: MimeiId, tweet: Tweet, source: String) {
        val videosToInsert = mutableListOf<VideoPlaybackInfo>()
        tweet.attachments?.forEachIndexed { index, attachment ->
            if (attachment.type == MediaType.Video || attachment.type == MediaType.HLS_VIDEO) {
                videosToInsert.add(
                    VideoPlaybackInfo(
                        tweetId = parentTweetId,
                        videoMid = attachment.mid,
                        index = index,
                        mediaType = attachment.type,
                        playbackParentTweetId = parentTweetId
                    )
                )
            }
        }
        if (videosToInsert.isEmpty()) return

        scope.launch {
            var added = 0
            videosToInsert.forEach { videoInfo ->
                if (!videoMetaMap.containsKey(videoInfo.identifier)) {
                    allVideos.add(videoInfo)
                    videoMetaMap[videoInfo.identifier] = videoInfo
                    added++
                }
            }
            if (added > 0) {
                sortAllVideosByFeedOrder()
                // If newly-added retweet/quote videos already have viewport data, reconcile
                // immediately. This mirrors iOS' atomic viewport snapshot and avoids waiting
                // for a later scroll/layout pass before a visible video can become primary.
                if (videoVisibilityMap.isNotEmpty()) {
                    val nowVisible = allVideos.filter { videoInfo ->
                        (videoVisibilityMap[videoInfo.identifier] ?: 0f) >= VISIBILITY_THRESHOLD
                    }
                    if (nowVisible.isNotEmpty()) {
                        rebuildVisibilitySetsFromRatios()
                    }
                }
            }
        }
    }

    /**
     * Build video list from tweets
     * Similar to iOS buildVideoList(from:tweets:pinnedTweets:)
     */
    fun buildVideoList(tweets: List<Tweet>, pinnedTweets: List<Tweet> = emptyList()) {
        currentTweets = pinnedTweets + tweets
        primaryBelowContinueIdentifier = null

        val videos = mutableListOf<VideoPlaybackInfo>()

        // Process pinned tweets first (they appear at the top)
        for (tweet in pinnedTweets) {
            tweet.attachments?.forEachIndexed { index, attachment ->
                if (attachment.type == MediaType.Video || attachment.type == MediaType.HLS_VIDEO) {
                    val videoInfo = VideoPlaybackInfo(
                        tweetId = tweet.mid,
                        videoMid = attachment.mid,
                        index = index,
                        mediaType = attachment.type,
                        playbackParentTweetId = tweet.mid
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
                    // Use memory-only lookup: buildVideoList runs on the main thread, so we
                    // must not call getCachedTweet (which may hit the database and block).
                    // addRetweetVideos() will add the video later when TweetItem fetches it.
                    val originalTweet = TweetCacheManager.getCachedTweetMemoryOnly(tweet.originalTweetId!!)

                    originalTweet?.attachments?.forEachIndexed { index, attachment ->
                        if (attachment.type == MediaType.Video || attachment.type == MediaType.HLS_VIDEO) {
                            val videoInfo = VideoPlaybackInfo(
                                tweetId = tweet.mid,
                                videoMid = attachment.mid,
                                index = index,
                                mediaType = attachment.type,
                                playbackParentTweetId = tweet.mid
                            )
                            videos.add(videoInfo)
                        }
                    }

                }
            } else {
                tweet.attachments?.forEachIndexed { index, attachment ->
                    if (attachment.type == MediaType.Video || attachment.type == MediaType.HLS_VIDEO) {
                        val videoInfo = VideoPlaybackInfo(
                            tweetId = tweet.mid,
                            videoMid = attachment.mid,
                            index = index,
                            mediaType = attachment.type,
                            playbackParentTweetId = tweet.mid
                        )
                        videos.add(videoInfo)
                    }
                }

                if (isQuotedTweet && tweet.originalTweetId != null) {
                    // Same: memory-only to avoid blocking the main thread with a DB query.
                    val embeddedTweet = TweetCacheManager.getCachedTweetMemoryOnly(tweet.originalTweetId!!)

                    embeddedTweet?.attachments?.forEachIndexed { index, attachment ->
                        if (attachment.type == MediaType.Video || attachment.type == MediaType.HLS_VIDEO) {
                            val videoInfo = VideoPlaybackInfo(
                                tweetId = tweet.mid,
                                videoMid = attachment.mid,
                                index = index,
                                mediaType = attachment.type,
                                playbackParentTweetId = tweet.mid
                            )
                            videos.add(videoInfo)
                        }
                    }
                }
            }
        }

        allVideos = videos
        videoMetaMap.clear()
        videos.forEach { videoInfo ->
            videoMetaMap[videoInfo.identifier] = videoInfo
        }
        if (primaryVideoId != null && !videoMetaMap.containsKey(primaryVideoId)) {
            setPrimaryVideoId(null)
        }
        if (pendingResumePrimaryId != null && !videoMetaMap.containsKey(pendingResumePrimaryId)) {
            pendingResumePrimaryId = null
        }
        val validIds = videoMetaMap.keys
        loadVisibleVideoIds = loadVisibleVideoIds.filter { it in validIds }.toSet()
        continuePlaybackVideoIds = continuePlaybackVideoIds.filter { it in validIds }.toSet()
        playableVideoIds = playableVideoIds.filter { it in validIds }.toSet()
        videoVisibilityMap.keys.retainAll(validIds)
        videoGeometryMap.keys.retainAll(validIds)

        if (syncWithFullScreenPlayer) {
            val videoListForFullScreen = videos.map { Pair(it.videoMid, it.mediaType) }
            FullScreenPlayerManager.updateVideoList(videoListForFullScreen, tweets)
        }

        // Protect auto-play from premature stopAllVideos caused by layout instability
        // during screen transitions (visibility can temporarily drop below threshold).
        videoListBuiltAt = System.currentTimeMillis()

        resumePendingPrimaryIfPossible()
        refreshViewportVisibilityFromGeometry()

        // Try to start playback using visibility data that arrived before the list was built.
        // Only attempt to start — never stop, since VideoPreview may not have reported yet.
        if (videos.isNotEmpty() && primaryVideoId == null && videoVisibilityMap.isNotEmpty()) {
            val nowVisible = videos.filter { videoInfo ->
                (videoVisibilityMap[videoInfo.identifier] ?: 0f) >= VISIBILITY_THRESHOLD
            }
            if (nowVisible.isNotEmpty()) {
                rebuildVisibilitySetsFromRatios()
            }
        }
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

    fun updateScrollActivity(isScrolling: Boolean) {
        if (isScrollInProgress == isScrolling) return

        isScrollInProgress = isScrolling
        if (isScrolling) {
            playbackDebounceJob?.cancel()
            playbackDebounceJob = null
            playbackDebounceCandidateId = null
            primaryVideoId?.let { cancelPendingVisibilityLossStop(it) }
            updateManagerRetainedVideos()
            MediaLog.d {
                "VideoPlaybackCoordinator[$managerPlaybackOwnerKey]: Scroll started; preserving primary=$primaryVideoId"
            }
            return
        }

        // Superseded by the immediate reconciliation below (reconcilePlaybackForCurrentVisibility
        // starts a primary right away once the scroll is no longer in progress), so any pending
        // active-scroll confirmation timer for a candidate would otherwise fire redundantly later.
        playbackDebounceJob?.cancel()
        playbackDebounceJob = null
        playbackDebounceCandidateId = null

        MediaLog.d {
            "VideoPlaybackCoordinator[$managerPlaybackOwnerKey]: Scroll stopped; reconciling primary=$primaryVideoId"
        }
        refreshViewportVisibilityFromGeometry()
        rebuildVisibilitySetsFromRatios()
        reconcilePlaybackForCurrentVisibility(reassertRetainedPrimary = true)
    }

    /**
     * Update viewport size (called by TweetListView)
     */
    fun updateViewportSize(width: Float, height: Float) {
        viewportWidth = width
        viewportHeight = height
        refreshViewportVisibilityFromGeometry()
    }

    /**
     * Register the latest on-screen video bounds. Playback is decided from a full
     * geometry snapshot in [refreshViewportVisibilityFromGeometry], matching the iOS
     * table-view coordinator pattern more closely than per-video play decisions.
     */
    fun updateVideoGeometry(
        videoMid: MimeiId,
        playbackVideoId: String,
        videoTop: Float,
        videoBottom: Float,
        visibleViewportTop: Float,
        visibleViewportBottom: Float
    ) {
        val identifier = playbackVideoId.ifBlank { videoPlaybackIdentifier(videoMid) }
        cancelPendingVisibilityLossStop(identifier)
        videoGeometryMap[identifier] = android.graphics.RectF(
            0f,
            videoTop,
            viewportWidth,
            videoBottom
        )
        viewportVisibleTop = visibleViewportTop
        viewportVisibleBottom = visibleViewportBottom
        scheduleGeometryReconcile()
    }

    fun removeVideoGeometry(videoMid: MimeiId, playbackVideoId: String) {
        val identifier = playbackVideoId.ifBlank { videoPlaybackIdentifier(videoMid) }
        videoGeometryMap.remove(identifier)
        videoVisibilityMap.remove(identifier)
        scheduleGeometryReconcile()
    }

    fun refreshViewportVisibilityFromGeometry() {
        if (videoGeometryMap.isEmpty()) {
            updateViewportVisibility(
                loadVisibleIdentifiers = emptySet(),
                continuePlaybackIdentifiers = emptySet(),
                playableIdentifiers = emptySet(),
                visibleTweetIdentifiers = tweetVisibilityMap
                    .filterValues { it }
                    .keys
                    .toSet()
            )
            return
        }

        val loadVisibleIdentifiers = mutableSetOf<String>()
        val continuePlaybackIdentifiers = mutableSetOf<String>()
        val playableIdentifiers = mutableSetOf<String>()

        videoGeometryMap.forEach { (identifier, bounds) ->
            val visibleTop = kotlin.math.max(viewportVisibleTop, bounds.top)
            val visibleBottom = kotlin.math.min(viewportVisibleBottom, bounds.bottom)
            val visibleHeight = kotlin.math.max(0f, visibleBottom - visibleTop)
            val totalHeight = kotlin.math.max(0f, bounds.height())
            val ratio = if (totalHeight > 0f) (visibleHeight / totalHeight).coerceIn(0f, 1f) else 0f

            videoVisibilityMap[identifier] = ratio
            if (ratio > 0f) loadVisibleIdentifiers.add(identifier)
            if (ratio >= KEEP_PLAYING_VISIBILITY_THRESHOLD) continuePlaybackIdentifiers.add(identifier)
            if (ratio >= VISIBILITY_THRESHOLD) playableIdentifiers.add(identifier)
        }

        updateViewportVisibility(
            loadVisibleIdentifiers = loadVisibleIdentifiers,
            continuePlaybackIdentifiers = continuePlaybackIdentifiers,
            playableIdentifiers = playableIdentifiers,
            visibleTweetIdentifiers = tweetVisibilityMap
                .filterValues { it }
                .keys
                .toSet()
        )
    }

    private fun scheduleGeometryReconcile() {
        geometryReconcileJob?.cancel()
        geometryReconcileJob = scope.launch {
            delay(16L)
            refreshViewportVisibilityFromGeometry()
        }
    }

    /**
     * Update video visibility based on the video's own visibility ratio.
     * Called by VideoPreview when its visibility changes.
     */
    fun updateVideoVisibility(videoMid: MimeiId, tweetId: String, playbackVideoId: String, visibilityRatio: Float) {
        val identifier = playbackVideoId.ifBlank { videoPlaybackIdentifier(videoMid) }
        val previousRatio = videoVisibilityMap[identifier] ?: 0f
        videoVisibilityMap[identifier] = visibilityRatio
        if (visibilityRatio > 0f) {
            cancelPendingVisibilityLossStop(identifier)
        }
        if (identifier == primaryBelowContinueIdentifier &&
            (visibilityRatio >= KEEP_PLAYING_VISIBILITY_THRESHOLD || visibilityRatio < VISIBILITY_THRESHOLD)
        ) {
            primaryBelowContinueIdentifier = null
        }

        val crossedLoadThreshold = (previousRatio > 0f) != (visibilityRatio > 0f)
        val crossedPlayableThreshold =
            (previousRatio >= VISIBILITY_THRESHOLD) != (visibilityRatio >= VISIBILITY_THRESHOLD)
        val crossedContinueThreshold =
            (previousRatio >= KEEP_PLAYING_VISIBILITY_THRESHOLD) !=
                (visibilityRatio >= KEEP_PLAYING_VISIBILITY_THRESHOLD)
        val currentPrimaryCrossedContinue =
            identifier == primaryVideoId && crossedContinueThreshold
        val noPrimaryBecamePlayable =
            primaryVideoId == null &&
                previousRatio < VISIBILITY_THRESHOLD &&
                visibilityRatio >= VISIBILITY_THRESHOLD

        if (crossedLoadThreshold ||
            crossedPlayableThreshold ||
            currentPrimaryCrossedContinue ||
            noPrimaryBecamePlayable
        ) {
            visibilityUpdateDebounceJob?.cancel()
            visibilityUpdateDebounceJob = null
            rebuildVisibilitySetsFromRatios()
            return
        }

        visibilityUpdateDebounceJob?.cancel()
        visibilityUpdateDebounceJob = scope.launch {
            delay(VISIBILITY_UPDATE_DEBOUNCE_MS)
            rebuildVisibilitySetsFromRatios()
        }
    }

    /**
     * iOS-style viewport update: callers may provide a complete snapshot of the videos that
     * are load-visible, continue-visible, and playable-visible. Android VideoPreview still
     * reports individual ratios, so [updateVideoVisibility] rebuilds and feeds the same path.
     */
    fun updateViewportVisibility(
        loadVisibleIdentifiers: Set<String>,
        continuePlaybackIdentifiers: Set<String>,
        playableIdentifiers: Set<String>,
        visibleTweetIdentifiers: Set<String> = emptySet()
    ) {
        val knownIds = videoMetaMap.keys
        val nextLoadVisible = loadVisibleIdentifiers.filter { it in knownIds }.toSet()
        val nextContinue = continuePlaybackIdentifiers.filter { it in knownIds }.toSet()
        val nextPlayable = playableIdentifiers.filter { it in knownIds }.toSet()
        val previousLoadVisible = loadVisibleVideoIds

        loadVisibleVideoIds = nextLoadVisible
        continuePlaybackVideoIds = nextContinue
        playableVideoIds = nextPlayable
        visibleTweetIds = visibleTweetIdentifiers
        refreshPrimaryBelowContinueExclusion(nextContinue, nextPlayable)
        nextLoadVisible.forEach { cancelPendingVisibilityLossStop(it) }

        (previousLoadVisible - nextLoadVisible).forEach { identifier ->
            if (isScrollInProgress && identifier == primaryVideoId) {
                cancelPendingVisibilityLossStop(identifier)
            } else {
                scheduleStopAfterVisibilityLoss(identifier)
            }
        }
        updateManagerRetainedVideos()

        visibleVideos = allVideos.filter { it.identifier in playableVideoIds }
            .sortedByFeedPosition()
            .toMutableList()

        reconcilePlaybackForCurrentVisibility()
        identifyPrimaryVideoDuringActiveScroll()
    }

    /**
     * Identify a primary candidate as soon as it crosses the visibility threshold during
     * active scrolling, instead of waiting for the scroll to fully stop. Matches iOS
     * identifyPrimaryVideoDuringActiveScroll(): identification happens immediately here, but
     * actually starting playback is withheld behind ACTIVE_SCROLL_PRIMARY_SELECTION_DELAY_MS,
     * committed only if the same candidate still qualifies once the delay elapses. A fast
     * scroll that keeps crossing new thresholds just keeps resetting the timer for whichever
     * candidate is current, rather than starting/stopping on every momentary crossing.
     *
     * Never preempts a healthy playing primary (requires primaryVideoId == null), so other
     * videos are never demoted to non-primary just because a new candidate appeared while
     * scrolling — they're only demoted once this candidate is confirmed and actually starts.
     */
    private fun identifyPrimaryVideoDuringActiveScroll() {
        if (!isScrollInProgress || isPaused || !isFeedVisible) return

        // reconcilePlaybackForCurrentVisibility — the only thing that would normally notice
        // the current primary scrolling off-screen and clear primaryVideoId — is entirely
        // skipped for the whole gesture while isScrollInProgress is true (`if
        // (isScrollInProgress) return` at its top). But once the primary is genuinely gone
        // (not just below the continue threshold — fully outside playableVideoIds, so it
        // can't come back this gesture), leaving primaryVideoId pointed at a now-invisible
        // video blocks this function's own `primaryVideoId == null` precondition below for
        // the rest of the scroll — the exact bug this function exists to avoid. Demote it
        // here so a new candidate can still be identified and confirmed before the scroll
        // stops.
        primaryVideoId?.let { primaryId ->
            if (primaryId !in playableVideoIds) {
                stopPrimaryVideo(primaryId)
                setPrimaryVideoId(null)
            }
        }

        if (primaryVideoId != null || visibleVideos.isEmpty()) return

        val candidate = identifyPrimaryVideo() ?: return

        // Don't reset an already-running confirmation timer for the same candidate — this
        // runs on every viewport visibility update during a drag, even when the candidate
        // hasn't changed. Resetting every call would mean the timer never fires until motion
        // stops completely, defeating the point of identifying a primary mid-scroll.
        if (playbackDebounceJob != null && playbackDebounceCandidateId == candidate.identifier) {
            return
        }

        playbackDebounceJob?.cancel()
        playbackDebounceCandidateId = candidate.identifier
        playbackDebounceJob = scope.launch {
            delay(ACTIVE_SCROLL_PRIMARY_SELECTION_DELAY_MS)
            playbackDebounceJob = null
            playbackDebounceCandidateId = null

            if (isPaused || !isFeedVisible || primaryVideoId != null) return@launch
            val confirmed = identifyPrimaryVideo() ?: return@launch
            if (confirmed.identifier != candidate.identifier) return@launch

            startPrimaryVideoPlayback(expectedIdentifier = confirmed.identifier)
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

    private fun rebuildVisibilitySetsFromRatios() {
        updateViewportVisibility(
            loadVisibleIdentifiers = videoVisibilityMap
                .filterValues { it > 0f }
                .keys
                .toSet(),
            continuePlaybackIdentifiers = videoVisibilityMap
                .filterValues { it >= KEEP_PLAYING_VISIBILITY_THRESHOLD }
                .keys
                .toSet(),
            playableIdentifiers = videoVisibilityMap
                .filterValues { it >= VISIBILITY_THRESHOLD }
                .keys
                .toSet(),
            visibleTweetIdentifiers = tweetVisibilityMap
                .filterValues { it }
                .keys
                .toSet()
        )
    }

    private fun refreshPrimaryBelowContinueExclusion(
        continueIdentifiers: Set<String>,
        playableIdentifiers: Set<String>
    ) {
        val excluded = primaryBelowContinueIdentifier ?: return
        if (excluded in continueIdentifiers || excluded !in playableIdentifiers) {
            primaryBelowContinueIdentifier = null
        }
    }

    private fun reconcilePlaybackForCurrentVisibility(
        replayCurrentPrimary: Boolean = false,
        reassertRetainedPrimary: Boolean = false
    ) {
        if (isPaused || !isFeedVisible) return
        if (VideoManager.isImageFullScreenActive()) return
        if (isScrollInProgress) {
            return
        }

        if (replayCurrentPrimary && resumePendingPrimaryIfPossible(requirePlayable = true)) {
            return
        }

        if (visibleVideos.isEmpty()) {
            if (pendingVisibilityLossStopJobs.isNotEmpty()) {
                return
            }
            stopAllVideosIfLayoutIsStable()
            return
        }

        primaryVideoId?.let { primaryId ->
            if (primaryId in continuePlaybackVideoIds || isProtectedUserRequestedPrimary(primaryId)) {
                if ((replayCurrentPrimary || reassertRetainedPrimary) && primaryId in playableVideoIds) {
                    emitPlayForPrimary(videoMetaMap[primaryId] ?: return@let)
                }
                return
            }

            if (replayCurrentPrimary && primaryId in playableVideoIds) {
                if (primaryBelowContinueIdentifier == primaryId) {
                    primaryBelowContinueIdentifier = null
                }
                emitPlayForPrimary(videoMetaMap[primaryId] ?: return@let)
                return
            }

            val primaryVisibility = videoVisibilityMap[primaryId] ?: 0f
            stopPrimaryVideo(primaryId)
            if (primaryId in playableVideoIds || primaryVisibility >= VISIBILITY_THRESHOLD) {
                primaryBelowContinueIdentifier = primaryId
            }
            setPrimaryVideoId(null)
        }

        if (primaryVideoId == null && visibleVideos.isNotEmpty()) {
            if (replayCurrentPrimary) {
                startPrimaryVideoPlayback(playDelayMs = 0L)
            } else {
                startPrimaryVideoPlayback(playDelayMs = 0L)
            }
        }
    }

    private fun stopAllVideosIfLayoutIsStable() {
        if (isScrollInProgress) {
            return
        }
        val now = System.currentTimeMillis()
        val timeSinceUserRequest = now - userRequestedPlayAt
        if (timeSinceUserRequest < 500L) {
            return
        }
        val timeSinceListBuilt = now - videoListBuiltAt
        if (timeSinceListBuilt < 500L) {
            return
        }
        val timeSinceHostResumed = now - hostResumedAt
        if (timeSinceHostResumed < 500L) {
            return
        }
        stopAllVideos()
    }

    /**
     * Matches iOS identifyPrimaryVideo(): iterate candidates ordered by scroll direction
     * (topmost-first when scrolling down, bottommost-first when scrolling up), skipping
     * finished videos outright. A video excluded only because it's the just-demoted
     * primaryBelowContinueIdentifier is remembered as a fallback rather than dropped — if
     * layout churn temporarily excluded the previous primary but it's visible enough again
     * and nothing else qualifies, re-select it instead of going idle.
     */
    private fun identifyPrimaryVideo(): VideoPlaybackInfo? {
        val orderedVideos = if (scrollDirection) visibleVideos else visibleVideos.asReversed()

        var belowContinueFallback: VideoPlaybackInfo? = null
        for (video in orderedVideos) {
            if (video.identifier in finishedVideoIds) continue
            if (video.identifier == primaryBelowContinueIdentifier) {
                if (belowContinueFallback == null) belowContinueFallback = video
                continue
            }
            return video
        }

        return belowContinueFallback
    }

    private fun List<VideoPlaybackInfo>.sortedByFeedPosition(): List<VideoPlaybackInfo> {
        val feedOrder = allVideos.mapIndexed { index, videoInfo -> videoInfo.identifier to index }.toMap()
        return sortedWith(
            compareBy<VideoPlaybackInfo>(
                { tweetCellBoundsMap[it.tweetId]?.top ?: Float.MAX_VALUE },
                { feedOrder[it.identifier] ?: Int.MAX_VALUE },
                { it.index }
            )
        )
    }

    private fun sortAllVideosByFeedOrder() {
        if (allVideos.size <= 1) return
        val tweetOrder = currentTweets.mapIndexed { index, tweet -> tweet.mid to index }.toMap()
        allVideos.sortWith(
            compareBy<VideoPlaybackInfo>(
                { tweetOrder[it.tweetId] ?: Int.MAX_VALUE },
                { it.index },
                { it.identifier }
            )
        )
    }

    private fun stopPrimaryVideo(identifier: String) {
        val info = videoMetaMap[identifier] ?: return
        cancelPendingVisibilityLossStop(identifier)
        scope.launch {
            _playbackCommands.emit(VideoPlaybackCommand.ShouldStopVideo(info.identifier, info.videoMid))
        }
    }

    private fun scheduleStopAfterVisibilityLoss(identifier: String) {
        if (pendingVisibilityLossStopJobs.containsKey(identifier)) return
        val info = videoMetaMap[identifier] ?: return
        pendingVisibilityLossStopJobs[identifier] = scope.launch {
            MediaLog.d {
                "VideoPlaybackCoordinator: Delaying visibility-loss stop for ${info.videoMid}"
            }
            delay(VISIBILITY_LOSS_STOP_GRACE_MS)
            pendingVisibilityLossStopJobs.remove(identifier)
            if (identifier in loadVisibleVideoIds || (videoVisibilityMap[identifier] ?: 0f) > 0f) {
                updateManagerRetainedVideos()
                MediaLog.d {
                    "VideoPlaybackCoordinator: Canceled visibility-loss stop for ${info.videoMid}; visible again"
                }
                return@launch
            }
            if (isScrollInProgress && identifier == primaryVideoId) {
                updateManagerRetainedVideos()
                MediaLog.d {
                    "VideoPlaybackCoordinator: Preserved primary ${info.videoMid} during active scroll"
                }
                return@launch
            }

            finishedVideoIds.remove(identifier)
            if (identifier == primaryBelowContinueIdentifier) {
                primaryBelowContinueIdentifier = null
            }
            if (identifier == primaryVideoId) {
                setPrimaryVideoId(null)
            }
            _playbackCommands.emit(VideoPlaybackCommand.ShouldStopVideo(info.identifier, info.videoMid))
            updateManagerRetainedVideos()
            MediaLog.d {
                "VideoPlaybackCoordinator: Visibility-loss stop emitted for ${info.videoMid}"
            }
        }
        updateManagerRetainedVideos()
    }

    private fun cancelPendingVisibilityLossStop(identifier: String) {
        pendingVisibilityLossStopJobs.remove(identifier)?.cancel()
        updateManagerRetainedVideos()
    }

    private fun clearPendingVisibilityLossStops() {
        pendingVisibilityLossStopJobs.values.forEach { it.cancel() }
        pendingVisibilityLossStopJobs.clear()
        updateManagerRetainedVideos()
    }

    private fun updateManagerRetainedVideos() {
        val retainedIdentifiers = loadVisibleVideoIds +
            pendingVisibilityLossStopJobs.keys +
            listOfNotNull(primaryVideoId)
        val retainedMids = retainedIdentifiers.mapNotNull { identifier ->
            videoMetaMap[identifier]?.videoMid
        }.toSet()
        VideoManager.setCoordinatorRetainedVideos(managerPlaybackOwnerKey, retainedMids)
    }

    private fun isProtectedUserRequestedPrimary(identifier: String): Boolean {
        val protected = userRequestedPrimaryIdentifier == identifier &&
            System.currentTimeMillis() - userRequestedPlayAt < 500L
        if (!protected && userRequestedPrimaryIdentifier == identifier) {
            userRequestedPrimaryIdentifier = null
        }
        return protected
    }

    private fun startPrimaryVideoPlayback(expectedIdentifier: String? = null, playDelayMs: Long = 0L) {
        if (isPaused) return
        if (visibleVideos.isEmpty()) {
            return
        }

        val primary = identifyPrimaryVideo() ?: return
        if (expectedIdentifier != null && primary.identifier != expectedIdentifier) return
        val previousPrimaryId = primaryVideoId
        setPrimaryVideoId(primary.identifier)
        if (pendingResumePrimaryId != primary.identifier) {
            pendingResumePrimaryId = null
        }

        scope.launch {
            if (previousPrimaryId != null && previousPrimaryId != primary.identifier) {
                val previousPrimary = videoMetaMap[previousPrimaryId]
                if (previousPrimary != null) {
                    _playbackCommands.emit(
                        VideoPlaybackCommand.ShouldStopVideo(previousPrimary.identifier, previousPrimary.videoMid)
                    )
                }
            }

            visibleVideos.forEach { videoInfo ->
                if (videoInfo.identifier != primary.identifier) {
                    _playbackCommands.emit(
                        VideoPlaybackCommand.ShouldPauseVideo(videoInfo.identifier, videoInfo.videoMid)
                    )
                }
            }

            if (playDelayMs > 0L) {
                delay(playDelayMs)
            }

            if (primaryVideoId != primary.identifier ||
                primary.identifier !in playableVideoIds ||
                (videoVisibilityMap[primary.identifier] ?: 0f) < VISIBILITY_THRESHOLD
            ) {
                return@launch
            }

            emitPlayCommand(primary)
        }
    }

    private fun emitPlayForPrimary(primary: VideoPlaybackInfo) {
        scope.launch {
            emitPlayCommand(primary)
        }
    }

    private suspend fun emitPlayCommand(primary: VideoPlaybackInfo) {
        _playbackCommands.emit(
            VideoPlaybackCommand.ShouldPlayVideo(
                tweetId = primary.tweetId,
                videoMid = primary.videoMid,
                playbackVideoId = primary.identifier,
                videoIndex = primary.index,
                isPrimary = true
            )
        )
    }

    /**
     * Force a specific video to become the primary (user tapped play button).
     * Stops the current primary and starts the requested video.
     */
    fun requestPlay(videoMid: MimeiId, tweetId: String, playbackVideoId: String) {
        val identifier = playbackVideoId.ifBlank { videoPlaybackIdentifier(videoMid) }
        var info = videoMetaMap[identifier]
        if (info == null) {
            // Video not yet in coordinator — register it on the fly so playback can proceed.
            // This happens when the original tweet for a retweet/quote hasn't been added via
            // addRetweetVideos/addEmbeddedTweetVideos yet (timing gap).
            info = VideoPlaybackInfo(
                tweetId = tweetId,
                videoMid = videoMid,
                index = 0,
                explicitIdentifier = identifier
            )
            allVideos.add(info)
            videoMetaMap[identifier] = info
            MediaLog.d { "VideoPlaybackCoordinator: requestPlay registered missing video '$identifier' on the fly" }
        }

        // Remove from finished set so it can play again
        finishedVideoIds.remove(identifier)
        finishedVideoIds.remove(info.identifier)
        // Protect from debounced stopAllVideos for 500ms
        userRequestedPlayAt = System.currentTimeMillis()

        val previousPrimaryId = primaryVideoId
        val primaryIdentifier = info.identifier
        setPrimaryVideoId(primaryIdentifier)
        userRequestedPrimaryIdentifier = primaryIdentifier

        scope.launch {
            if (previousPrimaryId != null && previousPrimaryId != primaryIdentifier) {
                val prev = videoMetaMap[previousPrimaryId]
                if (prev != null) {
                    _playbackCommands.emit(VideoPlaybackCommand.ShouldStopVideo(prev.identifier, prev.videoMid))
                }
            }
            _playbackCommands.emit(
                VideoPlaybackCommand.ShouldPlayVideo(
                    tweetId = tweetId,
                    videoMid = videoMid,
                    playbackVideoId = info.identifier,
                    videoIndex = info.index,
                    isPrimary = true
                )
            )
        }
        MediaLog.d { "VideoPlaybackCoordinator: User requested play for $videoMid" }
    }

    fun shouldAutoPlay(videoMid: MimeiId, tweetId: String, playbackVideoId: String): Boolean {
        val identifier = playbackVideoId.ifBlank { videoPlaybackIdentifier(videoMid) }
        return primaryVideoId == identifier
    }

    fun handleVideoFinished(videoMid: MimeiId, tweetId: String, playbackVideoId: String) {
        val identifier = playbackVideoId.ifBlank { videoPlaybackIdentifier(videoMid) }
        finishedVideoIds.add(identifier)

        if (primaryVideoId == identifier) {
            setPrimaryVideoId(null)
            // Emit stop so VideoPreview clears coordinatorWantsToPlay
            scope.launch {
                _playbackCommands.emit(VideoPlaybackCommand.ShouldStopVideo(identifier, videoMid))
            }
            MediaLog.d { "VideoPlaybackCoordinator: Video finished: $videoMid — stopping" }
        }
    }

    fun stopAllVideos() {
        playbackDebounceJob?.cancel()
        playbackDebounceJob = null
        playbackDebounceCandidateId = null
        clearPendingVisibilityLossStops()
        setPrimaryVideoId(null)
        pendingResumePrimaryId = null
        primaryBelowContinueIdentifier = null
        userRequestedPrimaryIdentifier = null
        VideoManager.clearCoordinatorRetainedVideos(managerPlaybackOwnerKey)
        scope.launch {
            _playbackCommands.emit(VideoPlaybackCommand.ShouldStopAllVideos)
        }
    }

    fun onHostPaused() {
        if (!isFeedVisible) return
        isFeedVisible = false
        playbackDebounceJob?.cancel()
        playbackDebounceJob = null
        playbackDebounceCandidateId = null
        visibilityUpdateDebounceJob?.cancel()
        visibilityUpdateDebounceJob = null
        geometryReconcileJob?.cancel()
        geometryReconcileJob = null
        pendingResumePrimaryId = primaryVideoId

        val idsToPause = (visibleVideos.map { it.identifier } + listOfNotNull(primaryVideoId)).toSet()
        scope.launch {
            idsToPause.forEach { identifier ->
                val info = videoMetaMap[identifier] ?: return@forEach
                _playbackCommands.emit(VideoPlaybackCommand.ShouldPauseVideo(info.identifier, info.videoMid))
            }
        }
        MediaLog.d {
            "VideoPlaybackCoordinator[$managerPlaybackOwnerKey]: Host paused; preserved primary=$primaryVideoId " +
                "visible=${visibleVideos.map { it.identifier }} load=$loadVisibleVideoIds playable=$playableVideoIds"
        }
    }

    fun onHostResumed() {
        isFeedVisible = true
        hostResumedAt = System.currentTimeMillis()
        visibilityUpdateDebounceJob?.cancel()
        visibilityUpdateDebounceJob = scope.launch {
            delay(FOREGROUND_RECONCILE_DELAY_MS)
            refreshViewportVisibilityFromGeometry()
            rebuildVisibilitySetsFromRatios()
            reconcilePlaybackForCurrentVisibility(replayCurrentPrimary = true)
        }
        MediaLog.d {
            "VideoPlaybackCoordinator[$managerPlaybackOwnerKey]: Host resumed; scheduling foreground reconciliation " +
                "primary=$primaryVideoId pending=$pendingResumePrimaryId videos=${videoMetaMap.size}"
        }
    }

    private fun resumePendingPrimaryIfPossible(requirePlayable: Boolean = false): Boolean {
        if (VideoManager.isImageFullScreenActive()) {
            MediaLog.d("VideoLoading") {
                "Coordinator[$managerPlaybackOwnerKey] resume skipped because image fullscreen is active"
            }
            return false
        }
        if (isPaused || !isFeedVisible) {
            MediaLog.d("VideoLoading") {
                "Coordinator[$managerPlaybackOwnerKey] resume skipped paused=$isPaused visible=$isFeedVisible " +
                    "primary=$primaryVideoId pending=$pendingResumePrimaryId"
            }
            return false
        }
        val resumeId = pendingResumePrimaryId ?: primaryVideoId ?: return false
        val info = videoMetaMap[resumeId] ?: run {
            MediaLog.d("VideoLoading") {
                "Coordinator[$managerPlaybackOwnerKey] resume skipped missing metadata resumeId=$resumeId " +
                    "primary=$primaryVideoId pending=$pendingResumePrimaryId videos=${videoMetaMap.size}"
            }
            return false
        }
        if (requirePlayable &&
            resumeId !in playableVideoIds &&
            (videoVisibilityMap[resumeId] ?: 0f) < VISIBILITY_THRESHOLD
        ) {
            MediaLog.d("VideoLoading") {
                "Coordinator[$managerPlaybackOwnerKey] resume skipped non-playable resumeId=$resumeId " +
                    "primary=$primaryVideoId pending=$pendingResumePrimaryId playable=$playableVideoIds"
            }
            return false
        }

        pendingResumePrimaryId = null
        primaryBelowContinueIdentifier = null
        setPrimaryVideoId(resumeId)
        emitPlayForPrimary(info)
        MediaLog.d {
            "VideoPlaybackCoordinator[$managerPlaybackOwnerKey]: Replaying foreground primary ${info.videoMid} " +
                "resumeId=$resumeId load=$loadVisibleVideoIds playable=$playableVideoIds"
        }
        return true
    }

    /**
     * Sync this coordinator's video list to FullScreenPlayerManager.
     * Called when the user taps a video for full-screen playback, so the full-screen player
     * uses the same video list as the current screen's coordinator (not the main feed's list).
     */
    fun syncToFullScreenPlayer() {
        val videoListForFullScreen = allVideos.map { Pair(it.videoMid, it.mediaType) }
        FullScreenPlayerManager.updateVideoList(videoListForFullScreen, currentTweets)
        MediaLog.d { "VideoPlaybackCoordinator: Synced ${videoListForFullScreen.size} videos to FullScreenPlayerManager" }
    }

    /**
     * Clear all coordinator state. Call when the screen using this coordinator is disposed.
     */
    fun clear() {
        playbackDebounceJob?.cancel()
        playbackDebounceJob = null
        playbackDebounceCandidateId = null
        visibilityUpdateDebounceJob?.cancel()
        visibilityUpdateDebounceJob = null
        geometryReconcileJob?.cancel()
        geometryReconcileJob = null
        clearPendingVisibilityLossStops()

        stopAllVideos()

        visibleVideos.clear()
        allVideos.clear()
        currentTweets = emptyList()
        videoMetaMap.clear()
        tweetCellBoundsMap.clear()
        tweetVisibilityMap.clear()
        videoVisibilityMap.clear()
        videoGeometryMap.clear()
        finishedVideoIds.clear()
        primaryBelowContinueIdentifier = null
        userRequestedPrimaryIdentifier = null
        loadVisibleVideoIds = emptySet()
        continuePlaybackVideoIds = emptySet()
        playableVideoIds = emptySet()
        visibleTweetIds = emptySet()
        previousContentOffset = 0f
        scrollDirection = true
        isScrollInProgress = false
        userRequestedPlayAt = 0L
        videoListBuiltAt = 0L
        hostResumedAt = 0L
        isFeedVisible = false

        MediaLog.d {
            "VideoPlaybackCoordinator[$managerPlaybackOwnerKey]: Cleared all state"
        }
    }
}
