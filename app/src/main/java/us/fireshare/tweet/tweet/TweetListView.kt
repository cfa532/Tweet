package us.fireshare.tweet.tweet

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.annotation.RequiresApi
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import us.fireshare.tweet.navigation.SharedViewModel
import us.fireshare.tweet.viewmodel.TweetListViewModel
import us.fireshare.tweet.widget.inferMediaTypeFromAttachment
import us.fireshare.tweet.widget.rememberTweetVideoPreloader

enum class ScrollDirection {
    UP, DOWN, NONE
}

data class ScrollState(
    val isScrolling: Boolean,
    val direction: ScrollDirection
)

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
    contentPadding: PaddingValues = PaddingValues(bottom = 60.dp),
    showPrivateTweets: Boolean = false,
    parentEntry: NavBackStackEntry? = null,
    onScrollStateChange: ((ScrollState) -> Unit)? = null,
    currentUserId: MimeiId? = null, // Add current user ID to detect user changes
    onTweetUnavailable: ((MimeiId) -> Unit)? = null, // Callback when tweet becomes unavailable
    headerContent: (@Composable () -> Unit)? = null, // Optional header content
    onIsAtLastTweetChange: ((Boolean) -> Unit)? = null, // Callback for external gesture detection
    onTriggerLoadMore: (() -> Unit)? = null, // Callback to trigger manual loadmore
    restoreScrollPosition: Boolean = true, // Control whether to restore scroll position
    context: String = "default", // Context to determine where this list is shown
    onVideoIndexedListChange: ((List<Pair<MimeiId, MediaType>>) -> Unit)? = null, // Callback when video list changes
    isInitialLoading: Boolean = false, // External loading state (for ProfileScreen)
    onScrollToTop: (suspend () -> Unit)? = null // Callback to scroll to top programmatically
) {
    // Inject SharedViewModel to get TweetListViewModel
    val sharedViewModel: SharedViewModel = hiltViewModel()
    
    // Create our own TweetListViewModel instance
    // Use activity scope if parentEntry is null, otherwise use parentEntry for proper lifecycle
    val activity = LocalActivity.current as ComponentActivity
    val tweetListViewModel = if (parentEntry != null) {
        hiltViewModel<TweetListViewModel>(viewModelStoreOwner = parentEntry, key = context)
    } else {
        hiltViewModel<TweetListViewModel>(viewModelStoreOwner = activity, key = context)
    }

    // Set our TweetListViewModel instance to SharedViewModel. It will be used by MediaBrowser to
    // play full screen videos in order.
    sharedViewModel.tweetListViewModel = tweetListViewModel

    // Create video-indexed list that maintains feed order and handles retweets properly
    var videoIndexedList by remember { mutableStateOf<List<Pair<MimeiId, MediaType>>>(emptyList()) }
    
    // Internal state management
    var isRefreshingAtTop by remember { mutableStateOf(false) }
    var isRefreshingAtBottom by remember { mutableStateOf(false) }
    var lastLoadedPage by rememberSaveable { mutableIntStateOf(-1) }
    var lastUserId by remember { mutableStateOf<MimeiId?>(null) }
    var serverDepleted by rememberSaveable { mutableStateOf(false) }
    var pendingLoadMorePage by remember { mutableIntStateOf(-1) }
    var isInitializingData by remember { mutableStateOf(false) }

    // Remember scroll position across recompositions and configuration changes
    val savedScrollPosition = rememberSaveable { mutableStateOf(Pair(0, 0)) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (restoreScrollPosition) savedScrollPosition.value.first else 0,
        initialFirstVisibleItemScrollOffset = if (restoreScrollPosition) savedScrollPosition.value.second else 0
    )
    val coroutineScope = rememberCoroutineScope()
    
    // Track active jobs for cleanup
    val activeJobs = remember { mutableListOf<Job>() }
    
    // Create scroll-to-top function
    val scrollToTop: suspend () -> Unit = {
        listState.animateScrollToItem(0)
    }
    
    // Cleanup coroutines on dispose
    DisposableEffect(Unit) {
        onDispose {
            activeJobs.forEach { it.cancel() }
            activeJobs.clear()
        }
    }

    // EFFECT 1: Data initialization and user changes (non-blocking)
    LaunchedEffect(currentUserId) {
        if (currentUserId != lastUserId) {
            lastUserId = currentUserId
            lastLoadedPage = -1
            serverDepleted = false

            // If tweets already loaded, infer state
            if (tweets.isNotEmpty()) {
                serverDepleted = tweets.size < TW_CONST.PAGE_SIZE
                lastLoadedPage = 0
                return@LaunchedEffect
            }

            // Initialize data asynchronously without blocking
            isInitializingData = true
            val initJob = launch(Dispatchers.IO) {
                try {
                    val result = fetchTweets(0)
                    if (result.size < TW_CONST.PAGE_SIZE) {
                        serverDepleted = true
                    }
                    lastLoadedPage = 0
                } catch (e: Exception) {
                    Timber.tag("TweetListView").e(e, "Initialization error")
                    serverDepleted = true
                } finally {
                    isInitializingData = false
                }
            }
            activeJobs.add(initJob)
        }
    }

    // EFFECT 2: Video list creation and ViewModel updates
    LaunchedEffect(tweets.size, isInitialLoading, currentUserId) {
        // Initialize lastLoadedPage if needed
        if (lastLoadedPage == -1 && tweets.isNotEmpty()) {
            lastLoadedPage = 0
        }
        
        // Update ViewModel with current tweets
        tweetListViewModel.setTweetList(tweets)
        
        // Create video list when loading is complete
        if (!isInitialLoading && !isInitializingData && tweets.isNotEmpty()) {
            val videoJob = launch(Dispatchers.IO) {
                try {
                    val newVideoList = createVideoIndexedListAsync(tweets)
                    withContext(Dispatchers.Main) {
                        videoIndexedList = newVideoList
                        tweetListViewModel.setVideoIndexedList(newVideoList)
                        onVideoIndexedListChange?.invoke(newVideoList)
                    }
                } catch (e: Exception) {
                    Timber.tag("TweetListView").e(e, "Video list creation error")
                }
            }
            activeJobs.add(videoJob)
        }
    }

    // EFFECT 3: Scroll tracking with debouncing
    LaunchedEffect(listState) {
        var previousFirstVisibleItem = listState.firstVisibleItemIndex
        var previousScrollOffset = listState.firstVisibleItemScrollOffset
        var lastSaveTime = 0L

        snapshotFlow {
            Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
            .collect { (firstVisibleItem, scrollOffset) ->
                val isScrolling = listState.isScrollInProgress
                
                // Determine scroll direction
                val indexDelta = firstVisibleItem - previousFirstVisibleItem
                val offsetDelta = scrollOffset - previousScrollOffset
                val direction = when {
                    !isScrolling -> ScrollDirection.NONE
                    indexDelta < -1 || (indexDelta == 0 && offsetDelta < -30) -> ScrollDirection.UP
                    indexDelta > 1 || (indexDelta == 0 && offsetDelta > 30) -> ScrollDirection.DOWN
                    else -> ScrollDirection.NONE
                }

                previousFirstVisibleItem = firstVisibleItem
                previousScrollOffset = scrollOffset
                onScrollStateChange?.invoke(ScrollState(isScrolling, direction))
                
                // Debounced scroll position save (every 200ms)
                val now = System.currentTimeMillis()
                if (now - lastSaveTime > 200) {
                    savedScrollPosition.value = Pair(firstVisibleItem, scrollOffset)
                    lastSaveTime = now
                }
            }
    }

    // Use VideoLoadingManager to preload videos from upcoming tweets
    val currentVisibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val baseUrl = if (tweets.isNotEmpty() && currentVisibleIndex >= 0 && currentVisibleIndex < tweets.size) {
        tweets[currentVisibleIndex].author?.baseUrl ?: ""
    } else {
        ""
    }
    rememberTweetVideoPreloader(
        tweets = tweets,
        currentVisibleIndex = currentVisibleIndex,
        baseUrl = baseUrl
    )

    // Derived states for pagination
    val isAtLastTweet by remember(listState, tweets) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            val totalItems = layoutInfo.totalItemsCount
            lastVisibleItem != null && lastVisibleItem.index == totalItems - 1
        }
    }

    val isNearBottom by remember(listState, tweets) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            val totalItems = layoutInfo.totalItemsCount
            lastVisibleItem != null && lastVisibleItem.index >= totalItems - 5 && lastVisibleItem.index < totalItems - 1
        }
    }

    // Notify caller about position
    LaunchedEffect(isAtLastTweet) {
        onIsAtLastTweetChange?.invoke(isAtLastTweet)
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
                        fetchTweets(0)
                    }
                    listState.scrollToItem(0, 0)
                    savedScrollPosition.value = Pair(0, 0)
                } catch (e: Exception) {
                    Timber.tag("TweetListView").e(e, "Error during pull refresh")
                } finally {
                    isRefreshingAtTop = false
                }
            }
            activeJobs.add(refreshJob)
        }
    )

    // EFFECT 4: Preload next page when near bottom
    LaunchedEffect(isNearBottom, serverDepleted, lastLoadedPage) {
        if (isNearBottom && !serverDepleted && tweets.size >= 4 && pendingLoadMorePage == -1) {
            val nextPage = lastLoadedPage + 1
            pendingLoadMorePage = nextPage
            
            val preloadJob = launch(Dispatchers.IO) {
                try {
                    Timber.tag("TweetListView").d("Preloading page: $nextPage")
                    val result = fetchTweets(nextPage)
                    val validCount = result.count { it != null }
                    
                    if (validCount > 0) {
                        lastLoadedPage = nextPage
                        Timber.tag("TweetListView").d("Preloaded $validCount tweets from page $nextPage")
                    } else if (result.size < TW_CONST.PAGE_SIZE) {
                        serverDepleted = true
                        Timber.tag("TweetListView").d("Server depleted during preload at page $nextPage")
                    }
                } catch (e: Exception) {
                    Timber.tag("TweetListView").e(e, "Preload error")
                } finally {
                    pendingLoadMorePage = -1
                }
            }
            activeJobs.add(preloadJob)
        }
    }
    
    // EFFECT 5: Load more when at last tweet
    LaunchedEffect(isAtLastTweet, isRefreshingAtBottom, serverDepleted, lastLoadedPage) {
        if (isAtLastTweet && !isRefreshingAtBottom && !serverDepleted && tweets.isNotEmpty() && pendingLoadMorePage == -1) {
            val nextPage = lastLoadedPage + 1
            pendingLoadMorePage = nextPage
            isRefreshingAtBottom = true
            
            val loadJob = launch(Dispatchers.IO) {
                try {
                    var currentPage = nextPage
                    var foundValidTweets = false
                    
                    Timber.tag("TweetListView").d("Loading more, starting at page: $currentPage")
                    
                    while (!foundValidTweets && !serverDepleted && currentPage < lastLoadedPage + 5) {
                        val result = fetchTweets(currentPage)
                        val validCount = result.count { it != null }
                        
                        Timber.tag("TweetListView").d("Page $currentPage: ${result.size} tweets, $validCount valid")
                        
                        if (validCount > 0) {
                            foundValidTweets = true
                            lastLoadedPage = currentPage
                            serverDepleted = false
                        } else if (result.size < TW_CONST.PAGE_SIZE) {
                            serverDepleted = true
                        } else {
                            currentPage++
                        }
                    }
                    
                    if (!foundValidTweets) {
                        Timber.tag("TweetListView").d("No valid tweets found after checking pages $nextPage to $currentPage")
                    }
                } catch (e: Exception) {
                    Timber.tag("TweetListView").e(e, "Load more error")
                } finally {
                    withContext(Dispatchers.Main) {
                        isRefreshingAtBottom = false
                        pendingLoadMorePage = -1
                    }
                }
            }
            activeJobs.add(loadJob)
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
                .let { if (scrollBehavior != null) it.nestedScroll(scrollBehavior.nestedScrollConnection) else it },
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
                itemsIndexed(
                    items = tweets,
                    key = { _, tweet -> tweet.mid },
                    contentType = { _, _ -> "tweet" }  // Help Compose reuse compositions efficiently
                ) { index, tweet ->
                    if (showPrivateTweets || !tweet.isPrivate) {
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

                        // Add divider after each tweet item (except the last one)
                        // Use index instead of indexOf for O(1) performance
                        if (index < tweets.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier
                                    .padding(bottom = 8.dp)
                                    .padding(horizontal = 1.dp),
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                            )
                        }
                    }
                }
            }

            // Loading spinner at bottom - use key to make it stable
            // Use fixed-height container to prevent layout shifts
            if (isRefreshingAtBottom) {
                item(key = "loading_spinner") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp, bottom = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 4.dp
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
 * Creates a video-indexed list asynchronously, fetching retweet data in parallel.
 * Returns a list of pairs: (MimeiId, MediaType) sorted by tweet timestamp (newest first).
 */
private suspend fun createVideoIndexedListAsync(tweets: List<Tweet>): List<Pair<MimeiId, MediaType>> = withContext(Dispatchers.IO) {
    val videoInfoList = mutableListOf<VideoInfo>()
    
    // Fetch all retweets in parallel
    val retweetFetches = tweets.mapIndexed { index, tweet ->
        async {
            val hasVideo = tweet.attachments?.any { attachment ->
                val mediaType = inferMediaTypeFromAttachment(attachment)
                mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO
            } == true
            
            // Fetch original tweet if this is a pure retweet without videos
            val tweetToCheck = if (!hasVideo && tweet.originalTweetId != null && tweet.originalAuthorId != null && 
                tweet.content.isNullOrEmpty() && tweet.attachments.isNullOrEmpty()) {
                try {
                    HproseInstance.refreshTweet(tweet.originalTweetId!!, tweet.originalAuthorId!!) ?: tweet
                } catch (e: Exception) {
                    Timber.tag("TweetListView").e(e, "Failed to fetch retweet")
                    tweet
                }
            } else {
                tweet
            }
            
            Triple(index, tweet, tweetToCheck)
        }
    }
    
    // Wait for all fetches to complete
    val results = retweetFetches.awaitAll()
    
    // Process results and build video list
    results.forEach { (feedIndex, originalTweet, tweetToCheck) ->
        val hasVideo = tweetToCheck.attachments?.any { attachment ->
            val mediaType = inferMediaTypeFromAttachment(attachment)
            mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO
        } == true
        
        if (hasVideo) {
            tweetToCheck.attachments?.forEach { attachment ->
                val mediaType = inferMediaTypeFromAttachment(attachment)
                if (mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO) {
                    videoInfoList.add(VideoInfo(
                        mid = attachment.mid,
                        mediaType = mediaType,
                        feedIndex = feedIndex,
                        tweetTimestamp = originalTweet.timestamp
                    ))
                }
            }
        }
    }
    
    // Sort and convert
    videoInfoList
        .sortedByDescending { it.tweetTimestamp }
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
