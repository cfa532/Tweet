package us.fireshare.tweet.tweet

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetCacheManager
import us.fireshare.tweet.navigation.SharedViewModel
import us.fireshare.tweet.viewmodel.TweetListViewModel
import us.fireshare.tweet.widget.LocalVideoCoordinator
import us.fireshare.tweet.widget.ImageCacheManager
import us.fireshare.tweet.widget.PreloadDirection
import us.fireshare.tweet.widget.VideoManager
import us.fireshare.tweet.widget.VideoPlaybackCoordinator
import us.fireshare.tweet.widget.inferMediaTypeFromAttachment
import java.util.Collections
import kotlin.math.abs

enum class ScrollDirection {
    UP, DOWN, NONE
}

data class ScrollState(
    val isScrolling: Boolean,
    val direction: ScrollDirection
)

/**
 * In-memory store for scroll positions, keyed by context string.
 * Persists scroll positions across navigation as long as the app process is alive.
 */
object ScrollPositionStore {
    private val positions = mutableMapOf<String, Pair<Int, Int>>()

    fun save(context: String, firstVisibleItemIndex: Int, scrollOffset: Int) {
        positions[context] = Pair(firstVisibleItemIndex, scrollOffset)
    }

    fun restore(context: String): Pair<Int, Int> {
        return positions[context] ?: Pair(0, 0)
    }
}

/**
 * Skeleton loader for tweets while initial data is loading
 */
@Composable
private fun TweetSkeletonLoader(modifier: Modifier = Modifier, count: Int = 3) {
    Column(modifier = modifier.fillMaxWidth()) {
        repeat(count) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column {
                    // Avatar and header skeleton
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        )
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(8.dp))
                        Column {
                            Box(
                                modifier = Modifier
                                    .size(width = 120.dp, height = 12.dp)
                                    .padding(vertical = 4.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                                    )
                            )
                            Box(
                                modifier = Modifier
                                    .size(width = 80.dp, height = 10.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                                    )
                            )
                        }
                    }
                    // Content skeleton
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .size(height = 60.dp, width = 1.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                            )
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
            )
        }
    }
}

/**
 * TweetListView: Self-contained Android Material3 style tweet list with built-in pagination, 
 * pull-to-refresh, infinite scroll, and loading indicators.
 *
 * @param tweets List of tweets to display (from ViewModel)
 * @param fetchTweets Function to fetch tweets for a specific page number
 * @param scrollBehavior Optional TopAppBar scroll behavior
 * @param contentPadding Padding for the list content
 * @param showPrivateTweets Whether to show private tweets
 * @param modifier Modifier for the component
 * @param parentEntry Optional NavBackStackEntry for navigation context
 * @param onIsAtLastTweetChange Callback when isAtLastTweet state changes (for external gesture detection)
 * @param onTriggerLoadMore Callback to trigger manual loadmore (for external gesture detection)
 */
@RequiresApi(Build.VERSION_CODES.R)
@Composable
@OptIn(ExperimentalMaterialApi::class, ExperimentalMaterial3Api::class)
fun TweetListView(
    tweets: List<Tweet>,
    fetchTweets: suspend (Int) -> List<Tweet?>, // Changed to suspend function
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = 96.dp), // FIX: Increased from 60dp to 96dp to prevent action buttons being covered by bottom nav bar (72dp height + 24dp padding)
    showPrivateTweets: Boolean = false,
    parentEntry: NavBackStackEntry? = null,
    onScrollStateChange: ((ScrollState) -> Unit)? = null,
    currentUserId: MimeiId? = null, // Add current user ID to detect user changes
    onTweetUnavailable: ((MimeiId) -> Unit)? = null, // Callback when tweet becomes unavailable
    headerContent: (@Composable () -> Unit)? = null, // Optional header content
    onIsAtLastTweetChange: ((Boolean) -> Unit)? = null, // Callback for external gesture detection
    onTriggerLoadMore: (() -> Unit)? = null, // Callback to trigger manual loadmore
    context: String = "default", // Context to scope scroll position persistence
    onVideoIndexedListChange: ((List<Pair<MimeiId, MediaType>>) -> Unit)? = null, // Callback when video list changes
    isInitialLoading: Boolean = false, // External loading state (for ProfileScreen)
    scrollToTopTrigger: Int = 0, // Increment to trigger scroll-to-top from parent
    pinnedTweets: List<Tweet> = emptyList(), // Pinned tweets to include in video navigation
    onScrolledToTop: (() -> Unit)? = null, // Callback after scroll-to-top completes (e.g. reset navbar/toolbar)
    onPullRefresh: (() -> Unit)? = null

) {
    // Inject SharedViewModel to get TweetListViewModel
    val sharedViewModel: SharedViewModel = hiltViewModel()
    
    // Create our own TweetListViewModel instance
    // Use activity scope if parentEntry is null, otherwise use parentEntry for proper lifecycle
    val activity = LocalActivity.current as ComponentActivity
    val androidContext = LocalContext.current
    val tweetListViewModel = if (parentEntry != null) {
        hiltViewModel<TweetListViewModel>(viewModelStoreOwner = parentEntry, key = context)
    } else {
        hiltViewModel<TweetListViewModel>(viewModelStoreOwner = activity, key = context)
    }

    // Set our TweetListViewModel instance to SharedViewModel. It will be used by MediaBrowser to
    // play full screen videos in order.
    sharedViewModel.tweetListViewModel = tweetListViewModel

    // Use the coordinator from CompositionLocal (main feed = shared, profile = per-screen instance)
    // so clearing on dispose only affects this list and does not stop the other screen's playback.
    val coordinator = LocalVideoCoordinator.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, coordinator) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> coordinator.onHostPaused()
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME -> coordinator.onHostResumed()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            coordinator.onHostResumed()
        }
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }

    // Create video-indexed list that maintains feed order and handles retweets properly
    var videoIndexedList by remember { mutableStateOf<List<Pair<MimeiId, MediaType>>>(emptyList()) }
    val processedTweetIds = remember { Collections.synchronizedSet(mutableSetOf<MimeiId>()) }
    var lastProcessedTweetCount by remember { mutableIntStateOf(0) }
    
    // Internal state management
    var isRefreshingAtTop by remember { mutableStateOf(false) }
    var isRefreshingAtBottom by remember { mutableStateOf(false) }
    var lastUserId by remember { mutableStateOf<MimeiId?>(null) }
    var pendingLoadMorePage by remember { mutableIntStateOf(-1) }
    var isInitializingData by remember { mutableStateOf(false) }
    // Track whether the user has actually scrolled, to prevent preloading on initial layout
    var hasUserScrolled by remember { mutableStateOf(false) }
    var lastNoMoreTweetsShown by remember { mutableLongStateOf(0L) }

    // Restore scroll position from in-memory store
    val initialScrollPosition = remember { ScrollPositionStore.restore(context) }
    val savedScrollPosition = remember { mutableStateOf(initialScrollPosition) }

    // Pagination states
    var lastLoadedPage by rememberSaveable { mutableIntStateOf(-1) }
    var serverDepleted by rememberSaveable { mutableStateOf(false) }
    // Track pages currently being fetched to prevent duplicate in-flight requests
    val inFlightPages = remember { mutableSetOf<Int>() }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialScrollPosition.first,
        initialFirstVisibleItemScrollOffset = initialScrollPosition.second
    )
    val coroutineScope = rememberCoroutineScope()

    // Track active jobs for cleanup - using map to easily manage by ID
    val activeJobs = remember { mutableMapOf<String, Job>() }

    // Track LazyColumn viewport size for VideoPlaybackCoordinator (guard redundant updates)
    var lastViewportWidth by remember { mutableIntStateOf(0) }
    var lastViewportHeight by remember { mutableIntStateOf(0) }
    var lastCleanupTime by remember { mutableLongStateOf(0L) }
    var lastLoadMoreTrigger by remember { mutableLongStateOf(0L) }
    val loadMoreDebounceMs = 300L // 300ms debounce to prevent rapid triggers during same scroll
    
    // Scroll to top when parent requests it (e.g., app icon tap in main feed)
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) {
            listState.scrollToItem(0, 0)
            savedScrollPosition.value = Pair(0, 0)
            ScrollPositionStore.save(context, 0, 0)
        }
    }

    // Restore scroll position when list is populated (same-session navigation).
    // We must wait for LazyColumn to complete first layout before scrollToItem(), otherwise
    // the scroll is wrong or the list jumps. iOS uses DispatchQueue.main.async (no fixed delay)
    // or 0.1s for load-more scroll; we wait one frame to avoid the visible jump that a 100ms
    // delay caused.
    var scrollRestoredForSession by remember { mutableStateOf(false) }
    LaunchedEffect(tweets.size, context) {
        if (scrollRestoredForSession || tweets.isEmpty()) return@LaunchedEffect
        val (index, offset) = ScrollPositionStore.restore(context)
        if (index <= 0) return@LaunchedEffect
        val maxIndex = (tweets.size * 2) + 4
        if (index >= maxIndex) return@LaunchedEffect
        withFrameMillis { } // one frame so first layout is done (avoids 100ms visible jump)
        if (!scrollRestoredForSession) {
            scrollRestoredForSession = true
            listState.scrollToItem(index.coerceIn(0, maxIndex), offset)
        }
    }
    
    // PERF FIX: Helper to add job with periodic cleanup instead of every-time cleanup
    fun addJob(id: String, job: Job) {
        // Periodic cleanup every 5 seconds instead of on every add (95% reduction)
        val now = System.currentTimeMillis()
        if (now - lastCleanupTime > 5000) {
            activeJobs.entries.removeAll { !it.value.isActive }
            lastCleanupTime = now
        }
        
        // Cancel and replace if same ID exists
        activeJobs[id]?.cancel()
        activeJobs[id] = job
    }
    
    // Create scroll-to-top function (use instant scroll, not animated,
    // because animateScrollToItem falls short when a collapsing toolbar
    // consumes part of the scroll distance via nestedScroll)
    val scrollToTop: suspend () -> Unit = {
        listState.scrollToItem(0, 0)
        onScrolledToTop?.invoke()
    }
    
    // Cleanup coroutines on dispose
    DisposableEffect(Unit) {
        onDispose {
            // Save scroll position to in-memory store before disposing
            ScrollPositionStore.save(
                context,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
            activeJobs.values.forEach { it.cancel() }
            activeJobs.clear()
            // Preserve coordinator state across detail navigation so returning to the list can
            // resume the same visible video immediately. The coordinator is still scoped by
            // LocalVideoCoordinator, so other lists do not share this state.
            coordinator.onHostPaused()
            // Defer inactive player cleanup so the incoming screen has time to mark its
            // players as active/visible. Synchronous cleanup here races with navigation
            // and releases players that the next screen just created but hasn't registered yet.
            VideoManager.cleanupInactivePlayersDeferred()
            ImageCacheManager.clearDirectionalImagePreloads()
            // BUG FIX: Always clear loading states on dispose to prevent stuck spinners
            isRefreshingAtBottom = false
            isRefreshingAtTop = false
            pendingLoadMorePage = -1
            lastLoadMoreTrigger = 0L
            Timber.tag("TweetListView").d("Cleared loading states on dispose")
        }
    }

    // EFFECT 1: Data initialization and user changes (non-blocking)
    LaunchedEffect(currentUserId) {
        if (currentUserId != lastUserId) {
            lastUserId = currentUserId
            lastLoadedPage = -1
            serverDepleted = false
            inFlightPages.clear()
            hasUserScrolled = false

            // PERF FIX: Clear processedTweetIds to prevent memory leak on user change
            processedTweetIds.clear()
            lastProcessedTweetCount = 0

            // If tweets already loaded (e.g., by ViewModel.initialize()), just infer state.
            // Skip redundant fetchTweets(0) — the ViewModel already handles initial loading.
            if (tweets.isNotEmpty()) {
                serverDepleted = tweets.size < TW_CONST.PAGE_SIZE
                lastLoadedPage = 0
            }
            // When tweets are empty, ViewModel.initialize()/reset() will populate them,
            // and EFFECT 2 will set lastLoadedPage when tweets arrive.
        }
    }

    // EFFECT 2: Video list creation and ViewModel updates (incremental)
    LaunchedEffect(tweets.size, isInitialLoading, currentUserId) {
        // Initialize lastLoadedPage if needed
        if (lastLoadedPage == -1 && tweets.isNotEmpty()) {
            lastLoadedPage = 0
        }
        
        // Update ViewModel with current tweets
        tweetListViewModel.setTweetList(tweets)
        
        // Create or update video list when loading is complete
        if (!isInitialLoading && !isInitializingData && tweets.isNotEmpty()) {
            // Keep autoplay coordination ready immediately. The video-indexed list below is
            // for fullscreen/navigation and may wait on retweet fetches; visible regular
            // tweet videos should not wait for that slower work before they can autoplay.
            coordinator.buildVideoList(tweets, pinnedTweets = pinnedTweets)

            val newTweets = tweets.filter { it.mid !in processedTweetIds }
            val newTweetIds = newTweets.map { it.mid }.toSet()
            val newTweetsAreTailAppend = newTweets.isNotEmpty() &&
                tweets.takeLast(newTweets.size).all { it.mid in newTweetIds }
            val needsFullRebuild = processedTweetIds.isEmpty() ||
                lastProcessedTweetCount > tweets.size ||
                (newTweets.isNotEmpty() && !newTweetsAreTailAppend)
            
            val videoJob = launch(Dispatchers.IO) {
                try {
                    if (needsFullRebuild) {
                        Timber.tag("TweetListView").d("Full video list rebuild for ${tweets.size} tweets")
                        processedTweetIds.clear()
                        val newVideoList = createVideoIndexedListAsync(tweets)
                        tweets.forEach { processedTweetIds.add(it.mid) }

                        withContext(Dispatchers.Main) {
                            videoIndexedList = newVideoList
                            lastProcessedTweetCount = tweets.size
                            tweetListViewModel.setVideoIndexedList(newVideoList)
                            onVideoIndexedListChange?.invoke(newVideoList)
                            coordinator.buildVideoList(tweets, pinnedTweets = pinnedTweets)
                        }
                    } else if (newTweets.isNotEmpty()) {
                        Timber.tag("TweetListView").d("Incremental video list update: ${newTweets.size} new tweets")
                        val newVideos = createVideoIndexedListAsync(newTweets)
                        newTweets.forEach { processedTweetIds.add(it.mid) }

                        withContext(Dispatchers.Main) {
                            videoIndexedList = videoIndexedList + newVideos
                            lastProcessedTweetCount = tweets.size
                            tweetListViewModel.setVideoIndexedList(videoIndexedList)
                            onVideoIndexedListChange?.invoke(videoIndexedList)
                            coordinator.buildVideoList(tweets, pinnedTweets = pinnedTweets)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            lastProcessedTweetCount = tweets.size
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag("TweetListView").e(e, "Video list creation error")
                }
            }
            addJob("videoList-${tweets.size}", videoJob)
        }
    }

    // Derived states for pagination - optimized with throttling
    var isAtLastTweet by remember { mutableStateOf(false) }
    var isNearBottom by remember { mutableStateOf(false) }
    var nearBottomPreloadToken by remember { mutableLongStateOf(0L) }
    var lastNearBottomPreloadScrollSignature by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val minimumTweetsBeforeStoppingPagination = 4
    val maxAutoLoadedExtraPages = 200

    // Pre-filter visible tweets to avoid per-item branching inside LazyColumn
    val visibleTweets = remember(tweets, showPrivateTweets) {
        if (showPrivateTweets) tweets else tweets.filter { !it.isPrivate }
    }
    val visibleTweetIndexById = remember(visibleTweets) {
        visibleTweets.mapIndexed { index, tweet -> tweet.mid to index }.toMap()
    }

    suspend fun fetchPagesUsingIosPaginationRule(
        startPage: Int,
        logTag: String
    ): Int {
        var currentPage = startPage
        var autoLoadedExtraPages = 0
        var loadedValidCount = 0
        val expectedTweetIds = tweets.map { it.mid }.toMutableSet()
        var expectedTweetCount = expectedTweetIds.size

        while (!serverDepleted) {
            Timber.tag(logTag).d("Fetching page=$currentPage")
            val result = fetchTweets(currentPage)
            val validTweets = result.filterNotNull()
            val validCount = validTweets.size
            val responseWasFull = result.size >= TW_CONST.PAGE_SIZE

            loadedValidCount += validCount
            expectedTweetCount += validTweets.count { expectedTweetIds.add(it.mid) }
            lastLoadedPage = currentPage
            serverDepleted = !responseWasFull

            val shouldLoadNextPage = responseWasFull &&
                (validCount == 0 || expectedTweetCount < minimumTweetsBeforeStoppingPagination)

            if (!shouldLoadNextPage) {
                Timber.tag(logTag).d(
                    "Pagination stopped at page=$currentPage, raw=${result.size}, valid=$validCount, loadedValid=$loadedValidCount, expectedTweets=$expectedTweetCount, depleted=$serverDepleted"
                )
                break
            }

            autoLoadedExtraPages++
            if (autoLoadedExtraPages >= maxAutoLoadedExtraPages) {
                Timber.tag(logTag).w(
                    "Stopped auto-loading after $autoLoadedExtraPages extra pages from page=$startPage"
                )
                break
            }

            if (validCount == 0) {
                Timber.tag(logTag).d(
                    "Page=$currentPage was full but empty; trying next page"
                )
            } else {
                Timber.tag(logTag).d(
                    "Page=$currentPage had $validCount valid tweets; continuing to reach $minimumTweetsBeforeStoppingPagination total tweets"
                )
            }
            currentPage++
        }

        Timber.tag(logTag).d(
            "Pagination fetched loadedValid=$loadedValidCount, lastLoadedPage=$lastLoadedPage, depleted=$serverDepleted"
        )
        return loadedValidCount
    }

    // EFFECT 3: Unified scroll tracking + pagination in a single snapshotFlow collector
    LaunchedEffect(listState, visibleTweets) {
        var previousFirstVisibleItem = listState.firstVisibleItemIndex
        var previousScrollOffset = listState.firstVisibleItemScrollOffset
        var lastSaveTime = 0L
        var lastDirection = ScrollDirection.NONE
        var lastNonIdleDirection = ScrollDirection.DOWN
        var lastScrollingState = false
        // Direction stability tracking to prevent flickering
        var pendingDirection = ScrollDirection.NONE
        var directionStableCount = 0
        val stabilityThreshold = 3 // Require 3 consecutive frames to confirm direction change
        var wasAtTop = true // Track top-of-list transitions for onScrolledToTop
        var lastMediaPreloadIndexes = emptySet<Int>()
        var lastMediaPreloadDirection = PreloadDirection.NONE
        var lastMediaPreloadScrolling: Boolean? = null
        var lastMediaPreloadTime = 0L

	        snapshotFlow {
	            Triple(
	                listState.firstVisibleItemIndex,
	                listState.firstVisibleItemScrollOffset,
	                listState.isScrollInProgress
	            )
	        }
	            .collect { (firstVisibleItem, scrollOffset, isScrolling) ->
                val now = System.currentTimeMillis()
                // --- Scroll direction tracking ---
                val indexDelta = firstVisibleItem - previousFirstVisibleItem
                val offsetDelta = scrollOffset - previousScrollOffset

                val detectedDirection = if (!isScrolling) {
                    ScrollDirection.NONE
                } else if (abs(indexDelta) > 1 || abs(offsetDelta) > 50) {
                    when {
                        indexDelta < 0 || offsetDelta < 0 -> ScrollDirection.UP
                        indexDelta > 0 || offsetDelta > 0 -> ScrollDirection.DOWN
                        else -> lastDirection
                    }
                } else {
                    lastDirection
                }

                // Direction stability check: only change direction after N consecutive frames
                val confirmedDirection = if (detectedDirection == pendingDirection) {
                    directionStableCount++
                    if (directionStableCount >= stabilityThreshold) detectedDirection else lastDirection
                } else {
                    pendingDirection = detectedDirection
                    directionStableCount = 1
                    lastDirection
                }
                val scrollingStarted = isScrolling && !lastScrollingState

                // Throttle VideoPlaybackCoordinator scroll direction updates
                if (isScrolling && (abs(indexDelta) >= 2 || abs(offsetDelta) > 200)) {
                    val currentOffset = firstVisibleItem * 1000f + scrollOffset
                    coordinator.updateScrollDirection(currentOffset)
                }

                // Only invoke callback if state actually changed
                if (confirmedDirection != lastDirection || isScrolling != lastScrollingState) {
                    onScrollStateChange?.invoke(ScrollState(isScrolling, confirmedDirection))
                    lastDirection = confirmedDirection
                    lastScrollingState = isScrolling
                }
                if (confirmedDirection != ScrollDirection.NONE) {
                    lastNonIdleDirection = confirmedDirection
                }
                if (scrollingStarted) {
                    VideoManager.stopAllPreloading(releasePreloadedPlayers = true)
                }

                val mediaPreloadDirection = when (if (confirmedDirection == ScrollDirection.NONE) lastNonIdleDirection else confirmedDirection) {
                    ScrollDirection.UP -> PreloadDirection.UP
                    ScrollDirection.DOWN -> PreloadDirection.DOWN
                    ScrollDirection.NONE -> PreloadDirection.NONE
                }
                val visibleTweetIndexes = listState.layoutInfo.visibleItemsInfo
                    .mapNotNull { item -> visibleTweetIndexById[item.key as? String] }
                    .toSet()
                val preloadStateChanged = visibleTweetIndexes != lastMediaPreloadIndexes ||
                    mediaPreloadDirection != lastMediaPreloadDirection ||
                    isScrolling != lastMediaPreloadScrolling
                val preloadThrottleMs = if (isScrolling) 250L else 0L
                if (visibleTweetIndexes.isNotEmpty() &&
                    preloadStateChanged &&
                    now - lastMediaPreloadTime >= preloadThrottleMs
                ) {
                    ImageCacheManager.updateDirectionalImagePreloads(
                        context = androidContext,
                        visibleTweetIndexes = visibleTweetIndexes,
                        direction = mediaPreloadDirection,
                        tweets = visibleTweets,
                        includeOppositeOnStop = !isScrolling
                    )
                    VideoManager.updateDirectionalVideoPreloads(
                        context = androidContext,
                        visibleTweetIndexes = visibleTweetIndexes,
                        direction = mediaPreloadDirection,
                        tweets = visibleTweets,
                        startPreloading = !isScrolling,
                        feedContext = context
                    )
                    lastMediaPreloadIndexes = visibleTweetIndexes
                    lastMediaPreloadDirection = mediaPreloadDirection
                    lastMediaPreloadScrolling = isScrolling
                    lastMediaPreloadTime = now
                }

                // iOS-style playback coordination: after each list geometry/scroll pass,
                // reconcile video playback once from the registered media-cell bounds.
                coordinator.refreshViewportVisibilityFromGeometry()

                // Detect when list arrives at the absolute top.
                // Index 0 alone is not enough when headerContent is tall (e.g. profile + pinned tweets),
                // because we can still be scrolled within that first item.
                val isAtTop = firstVisibleItem == 0 && scrollOffset == 0 && !isScrolling
                if (isAtTop && !wasAtTop) {
                    onScrolledToTop?.invoke()
                }
                wasAtTop = isAtTop

                previousFirstVisibleItem = firstVisibleItem
                previousScrollOffset = scrollOffset

                // Throttled scroll position saving (1 sec during scroll, immediate on stop)
                val shouldSave = !isRefreshingAtTop && (!isScrolling || (now - lastSaveTime > 1000))
                if (shouldSave && (firstVisibleItem != savedScrollPosition.value.first ||
                                   scrollOffset != savedScrollPosition.value.second)) {
                    savedScrollPosition.value = Pair(firstVisibleItem, scrollOffset)
                    ScrollPositionStore.save(context, firstVisibleItem, scrollOffset)
                    lastSaveTime = now
                }

                // Track that user has interacted with the list
                if (isScrolling && !hasUserScrolled) {
                    hasUserScrolled = true
                }

                // --- Pagination checks (only when scroll stops) ---
                if (isScrolling) return@collect

                val layoutInfo = listState.layoutInfo
                val lastIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                val totalItems = layoutInfo.totalItemsCount

                val wasAtBottom = isAtLastTweet
                isAtLastTweet = lastIndex == totalItems - 1
                isNearBottom = lastIndex >= totalItems - 5 && lastIndex < totalItems - 1
                if (isNearBottom) {
                    val scrollSignature = Pair(firstVisibleItem, scrollOffset)
                    if (hasUserScrolled && lastNearBottomPreloadScrollSignature != scrollSignature) {
                        lastNearBottomPreloadScrollSignature = scrollSignature
                        nearBottomPreloadToken++
                    }
                } else {
                    lastNearBottomPreloadScrollSignature = null
                }

                if (isAtLastTweet != wasAtBottom) {
                    Timber.tag("TweetListView-Position").d("isAtLastTweet=$isAtLastTweet")
                    onIsAtLastTweetChange?.invoke(isAtLastTweet)
                }

                // Load-more trigger when at bottom (only after user has scrolled)
                if (!isAtLastTweet) return@collect
                if (!hasUserScrolled) return@collect
                if (serverDepleted) return@collect
                if (isRefreshingAtBottom) return@collect
                if (tweets.isEmpty()) return@collect
                if (pendingLoadMorePage != -1) return@collect

                val timeSinceLastTrigger = now - lastLoadMoreTrigger
                val timeSinceLastMessage = now - lastNoMoreTweetsShown

                Timber.tag("TweetListView-LoadMore").d("""
                    Load More Check: tweets.size=${tweets.size}, sinceLastTrigger=${timeSinceLastTrigger}ms, sinceLastMessage=${timeSinceLastMessage}ms
                """.trimIndent())

                if (timeSinceLastMessage <= 2000) return@collect
                if (timeSinceLastTrigger < loadMoreDebounceMs) return@collect

                val nextPage = lastLoadedPage + 1
                if (nextPage in inFlightPages) return@collect // Deduplicate in-flight request
                pendingLoadMorePage = nextPage
                lastLoadMoreTrigger = now
                inFlightPages.add(nextPage)

                Timber.tag("TweetListView-LoadMore").d("TRIGGERING: page=$nextPage")

                isRefreshingAtBottom = true
                val spinnerShowTime = System.currentTimeMillis()

                val timeoutJob = coroutineScope.launch {
                    delay(10000)
                    Timber.tag("TweetListView-LoadMore").w("TIMEOUT: forcing spinner to hide")
                    isRefreshingAtBottom = false
                    pendingLoadMorePage = -1
                    inFlightPages.remove(nextPage)
                }

                val loadJob = coroutineScope.launch {
                    var validTweetsLoaded = 0
                    try {
                        withContext(Dispatchers.IO) {
                            validTweetsLoaded = fetchPagesUsingIosPaginationRule(
                                startPage = nextPage,
                                logTag = "TweetListView-LoadMore"
                            )
                        }
                    } catch (e: Exception) {
                        Timber.tag("TweetListView-LoadMore").e(e, "Error loading more")
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main) {
                            timeoutJob.cancel()

                            val spinnerDuration = System.currentTimeMillis() - spinnerShowTime
                            if (spinnerDuration < 500L) {
                                delay(500L - spinnerDuration)
                            }

                            isRefreshingAtBottom = false
                            pendingLoadMorePage = -1
                            inFlightPages.remove(nextPage)

                            if (validTweetsLoaded == 0) {
                                lastNoMoreTweetsShown = System.currentTimeMillis()
                                delay(2000)
                            }
                        }
                    }
                }

                addJob("loadMore-$nextPage", loadJob)
            }
    }


    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshingAtTop,
        onRefresh = {
            val refreshJob = coroutineScope.launch {
                isRefreshingAtTop = true
                try {
                    withContext(Dispatchers.IO) {
                        serverDepleted = false
                        lastLoadedPage = -1
                        inFlightPages.clear()
                        fetchPagesUsingIosPaginationRule(
                            startPage = 0,
                            logTag = "TweetListView-PullRefresh"
                        )
                    }
                    onPullRefresh?.invoke()
                    listState.scrollToItem(0, 0)
                    savedScrollPosition.value = Pair(0, 0)
                } catch (e: Exception) {
                    Timber.tag("TweetListView").e(e, "Error during pull refresh")
                } finally {
                    isRefreshingAtTop = false
                }
            }
            addJob("pullRefresh", refreshJob)
        }
    )

    // EFFECT 4: Preload next page once per near-bottom scroll position.
    // Do not key this to lastLoadedPage; otherwise a successful preload can
    // immediately trigger the next page while the user has not moved.
    LaunchedEffect(nearBottomPreloadToken, serverDepleted) {
        if (nearBottomPreloadToken > 0 && isNearBottom && hasUserScrolled && !serverDepleted && tweets.size >= 4 && pendingLoadMorePage == -1) {
            val nextPage = lastLoadedPage + 1
            if (nextPage in inFlightPages) return@LaunchedEffect // Deduplicate in-flight request
            pendingLoadMorePage = nextPage
            inFlightPages.add(nextPage)
            
            val preloadJob = coroutineScope.launch {
                isRefreshingAtBottom = true
                val spinnerShowTime = System.currentTimeMillis()
                try {
                    withContext(Dispatchers.IO) {
                        fetchPagesUsingIosPaginationRule(
                            startPage = nextPage,
                            logTag = "TweetListView-Preload"
                        )
                    }
                } catch (e: Exception) {
                    Timber.tag("TweetListView").e(e, "Preload error")
                } finally {
                    withContext(NonCancellable + Dispatchers.Main) {
                        val spinnerDuration = System.currentTimeMillis() - spinnerShowTime
                        if (spinnerDuration < 500L) {
                            delay(500L - spinnerDuration)
                        }
                        isRefreshingAtBottom = false
                        pendingLoadMorePage = -1
                        inFlightPages.remove(nextPage)
                    }
                }
            }
            addJob("preload-$nextPage", preloadJob)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pullRefresh(pullRefreshState)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .let { if (scrollBehavior != null) it.nestedScroll(scrollBehavior.nestedScrollConnection) else it }
                .onSizeChanged { size ->
                    // Only update VideoPlaybackCoordinator when size actually changes
                    if (size.width != lastViewportWidth || size.height != lastViewportHeight) {
                        lastViewportWidth = size.width
                        lastViewportHeight = size.height
                        coordinator.updateViewportSize(
                            size.width.toFloat(),
                            size.height.toFloat()
                        )
                        coordinator.refreshViewportVisibilityFromGeometry()
                    }
                },
            state = listState,
            contentPadding = contentPadding,
        ) {
            // Header content (if provided) - use key to make it stable
            headerContent?.let { header ->
                item(key = "header") {
                    header()
                }
            }

            // Show skeleton loader during initial loading
            if (tweets.isEmpty() && (isInitializingData || isInitialLoading)) {
                item(key = "skeleton_loader") {
                    TweetSkeletonLoader(count = 5)
                }
            }
            // Show empty state if no tweets and not loading
            else if (tweets.isEmpty() && !isRefreshingAtTop) {
                item(key = "empty_state") {
                    EmptyStateContent(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        message = stringResource(R.string.no_tweets_found),
                        icon = Icons.Default.Home
                    )
                }
            } else {
                // Keep tweet + divider in a single lazy item to reduce slot churn on long regular feeds.
                itemsIndexed(
                    items = visibleTweets,
                    key = { _, tweet -> tweet.mid },
                    contentType = { _, _ -> "tweet_with_divider" }
                ) { index, tweet ->
                    parentEntry?.let {
                        TweetItem(
                            tweet = tweet,
                            parentEntry = it,
                            onTweetUnavailable = onTweetUnavailable,
                            context = context,
                            currentUserId = currentUserId,
                            onScrollToTop = scrollToTop
                        )
                    }

                    if (index < visibleTweets.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier
                                .padding(top = 4.dp, bottom = 8.dp)
                                .padding(horizontal = 1.dp),
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                        )
                    }
                }
            }

            // Loading spinner at bottom with animation
            item(key = "loading_spinner") {
                AnimatedVisibility(
                    visible = isRefreshingAtBottom,
                    enter = fadeIn(animationSpec = tween(300)) + slideInVertically(
                        animationSpec = tween(300),
                        initialOffsetY = { it / 2 }
                    ),
                    exit = fadeOut(animationSpec = tween(200)) + slideOutVertically(
                        animationSpec = tween(200),
                        targetOffsetY = { it / 2 }
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
            
            // Show "no more tweets" message persistently when server is depleted
            if (serverDepleted && tweets.isNotEmpty()) {
                item(key = "no_more_tweets") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.no_more_tweets),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        PullRefreshIndicator(
            refreshing = isRefreshingAtTop,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
            backgroundColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * Data class to hold video information with its position in the feed
 */
data class VideoInfo(
    val mid: MimeiId,
    val mediaType: MediaType,
    val feedIndex: Int,
    val tweetTimestamp: Long
)

/**
 * Creates a video-indexed list asynchronously, fetching retweet data in parallel with batching.
 * Limits concurrent requests to 10 to prevent network congestion.
 * Returns a list of pairs: (MimeiId, MediaType) sorted by tweet timestamp (newest first).
 */
private suspend fun createVideoIndexedListAsync(tweets: List<Tweet>): List<Pair<MimeiId, MediaType>> = withContext(Dispatchers.IO) {
    val videoInfoList = mutableListOf<VideoInfo>()
    val batchSize = 10
    var globalIndex = 0  // PERF FIX: Use counter instead of indexOf for O(1) lookup
    
    // Process tweets in batches to limit concurrent network requests
    tweets.chunked(batchSize).forEach { batch ->
        // Capture indices for this batch
        val batchStartIndex = globalIndex
        val retweetFetches = batch.mapIndexed { batchIndex, tweet ->
            val currentIndex = batchStartIndex + batchIndex  // PERF FIX: O(1) instead of O(n)
            async {
                val hasVideo = tweet.attachments?.any { attachment ->
                    val mediaType = inferMediaTypeFromAttachment(attachment)
                    mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO
                } == true
                
                // Read original tweet if this is a pure retweet without videos. The feed
                // only needs attachment metadata for indexing; avoid refreshTweet here
                // because it triggers the heavier cross-node sync path used by detail view.
                val tweetToCheck = if (!hasVideo && tweet.originalTweetId != null && tweet.originalAuthorId != null && 
                    tweet.content.isNullOrEmpty() && tweet.attachments.isNullOrEmpty()) {
                    try {
                        TweetCacheManager.getCachedTweet(tweet.originalTweetId!!)
                            ?: HproseInstance.fetchTweet(tweet.originalTweetId!!, tweet.originalAuthorId!!)
                            ?: tweet
                    } catch (e: Exception) {
                        Timber.tag("TweetListView").e(e, "Failed to fetch retweet")
                        tweet
                    }
                } else {
                    tweet
                }
                
                Triple(currentIndex, tweet, tweetToCheck)
            }
        }
        
        globalIndex += batch.size  // Update global index for next batch
        
        // Wait for this batch to complete
        val results = retweetFetches.awaitAll()
        
        // PERF FIX: Cache media type inference (call once instead of 3×)
        // Process results and build video list
        results.forEach { (feedIndex, originalTweet, tweetToCheck) ->
            // Determine if this is a quoted tweet (has originalTweetId AND has own content)
            val hasContentText = !originalTweet.content.isNullOrEmpty()
            val hasAttachments = originalTweet.attachments != null && originalTweet.attachments!!.isNotEmpty()
            val hasOwnContent = hasContentText || hasAttachments
            val isQuotedTweet = originalTweet.originalTweetId != null && hasOwnContent
            
            // Cache media types for all attachments (single pass)
            val attachmentsWithTypes = tweetToCheck.attachments?.map { attachment ->
                Pair(attachment.mid, inferMediaTypeFromAttachment(attachment))
            } ?: emptyList()
            
            // Filter for video attachments only
            val videoAttachments = attachmentsWithTypes.filter { (_, mediaType) ->
                mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO
            }
            
            // Add quoting tweet's own videos to the list
            videoAttachments.forEach { (mid, mediaType) ->
                videoInfoList.add(VideoInfo(
                    mid = mid,
                    mediaType = mediaType,
                    feedIndex = feedIndex,
                    tweetTimestamp = originalTweet.timestamp
                ))
            }
            
            // For quoted tweets, also add embedded tweet's videos right after the quoting tweet's videos
            // This ensures embedded videos appear at the quoting tweet's position in the feed,
            // so the next video after an embedded video is the video behind the quoting tweet
            if (isQuotedTweet && originalTweet.originalTweetId != null) {
                // Try to get embedded tweet from cache (non-blocking)
                val embeddedTweet = TweetCacheManager.getCachedTweet(originalTweet.originalTweetId!!)
                
                // Only add embedded tweet videos if they're already cached
                // They will be added later when fetched asynchronously by TweetItem
                embeddedTweet?.attachments?.forEach { attachment ->
                    val mediaType = inferMediaTypeFromAttachment(attachment)
                    if (mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO) {
                        // Use quoting tweet's feedIndex and timestamp so embedded video appears
                        // at the quoting tweet's position, not the original tweet's position
                        videoInfoList.add(VideoInfo(
                            mid = attachment.mid,
                            mediaType = mediaType,
                            feedIndex = feedIndex,  // Use quoting tweet's position
                            tweetTimestamp = originalTweet.timestamp  // Use quoting tweet's timestamp
                        ))
                    }
                }
            }
        }
    }
    
    // Sort by feed position so fullscreen video order matches the on-screen tweet order.
    // (Sorting by tweetTimestamp breaks bookmarks/favorites whose display order is not timestamp-based.)
    videoInfoList
        .sortedBy { it.feedIndex }
        .map { Pair(it.mid, it.mediaType) }
}

/**
 * Empty state content for when there are no tweets to display
 */
@Composable
fun EmptyStateContent(
    modifier: Modifier = Modifier,
    message: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .padding(bottom = 16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp
            )
        }
    }
}
