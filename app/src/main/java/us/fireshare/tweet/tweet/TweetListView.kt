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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    isInitialLoading: Boolean = false // External loading state (for ProfileScreen)
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
    var isInitialLoadingComplete by remember { mutableStateOf(false) }
    
    // Debug logging and state tracking
    val previousTweetsSize = remember { mutableIntStateOf(tweets.size) }
    val previousUserId = remember { mutableStateOf(currentUserId) }

    // Consolidated LaunchedEffect for tweets-related operations
    LaunchedEffect(tweets, tweets.size, currentUserId) {
        // Debug logging for parameter changes
        if (tweets.size != previousTweetsSize.intValue || currentUserId != previousUserId.value) {
            Timber.tag("TweetListView")
                .d("TweetListView parameters changed: tweets=${tweets.size}->${previousTweetsSize.intValue}, userId=$currentUserId->${previousUserId.value}")
            previousTweetsSize.intValue = tweets.size
            previousUserId.value = currentUserId
        }
        
        // Track tweets list size changes
        Timber.tag("TweetListView").d("Tweets list size changed: ${tweets.size} tweets")
        
        // Update SharedViewModel's tweetListViewModel with the current tweets
        tweetListViewModel.setTweetList(tweets)
        Timber.tag("TweetListView").d("Updated SharedViewModel's tweetListViewModel with our instance and ${tweets.size} tweets")
    }

    // Internal state management
    var isRefreshingAtTop by remember { mutableStateOf(false) }
    var isRefreshingAtBottom by remember { mutableStateOf(false) }
    var lastLoadedPage by rememberSaveable { mutableIntStateOf(-1) } // Use rememberSaveable to persist across recompositions
    var lastUserId by remember { mutableStateOf<MimeiId?>(null) } // Initialize to null to trigger on first composition
    var serverDepleted by rememberSaveable { mutableStateOf(false) } // Use rememberSaveable to persist across recompositions
    var pendingLoadMorePage by remember { mutableIntStateOf(-1) } // Track which page is currently being loaded
    var externalLoadMoreRequest by remember { mutableStateOf(false) } // Track external loadmore requests
    var spinnerStartTime by remember { mutableLongStateOf(0L) } // Track when spinner started for minimum display time
    var wasAtLastTweet by remember { mutableStateOf(false) } // Track if user was previously at last tweet

    // Remember scroll position across recompositions and configuration changes
    val savedScrollPosition = rememberSaveable { mutableStateOf(Pair(0, 0)) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (restoreScrollPosition) savedScrollPosition.value.first else 0,
        initialFirstVisibleItemScrollOffset = if (restoreScrollPosition) savedScrollPosition.value.second else 0
    )
    val coroutineScope = rememberCoroutineScope()

    // Detect user changes and initialize data
    LaunchedEffect(currentUserId) {
        if (currentUserId != lastUserId) {
            Timber.tag("TweetListView")
                .d("User changed from $lastUserId to $currentUserId, tweets.size=${tweets.size}, initializing data")
            lastUserId = currentUserId
            lastLoadedPage = -1 // Reset last loaded page
            serverDepleted = false // Reset server depleted flag for new user

            // If tweets are already loaded (by initLoad), infer server depletion and skip initialization
            if (tweets.isNotEmpty()) {
                Timber.tag("TweetListView")
                    .d("Tweets already loaded (${tweets.size} tweets), inferring server depletion")
                
                // If we have fewer than PAGE_SIZE tweets, server is depleted
                if (tweets.size < TW_CONST.PAGE_SIZE) {
                    serverDepleted = true
                    lastLoadedPage = 0
                    Timber.tag("TweetListView")
                        .d("Inferred server depletion: ${tweets.size} < ${TW_CONST.PAGE_SIZE} (PAGE_SIZE)")
                } else {
                    lastLoadedPage = 0
                    Timber.tag("TweetListView")
                        .d("Server may have more data: ${tweets.size} >= ${TW_CONST.PAGE_SIZE} (PAGE_SIZE)")
                }
                return@LaunchedEffect
            }

            // Initialize with 2 pages only (matching server initial load behavior)
            // This prevents rendering all cached tweets at once and ensures proper ordering
            var localServerDepleted = false
            var pageToLoad = 0
            val maxInitialPages = 2 // Only load 2 pages initially
            val startTime = System.currentTimeMillis()
            val maxInitializationTime = 10000L // 10 seconds timeout

            while (!localServerDepleted &&
                pageToLoad < maxInitialPages &&
                (System.currentTimeMillis() - startTime) < maxInitializationTime
            ) {

                try {
                    val tweetsWithNulls = fetchTweets(pageToLoad)

                    // Only increment lastLoadedPage if we got a full page of results
                    if (tweetsWithNulls.size >= TW_CONST.PAGE_SIZE) {
                        lastLoadedPage = pageToLoad
                    } else {
                        // Server is depleted (returned fewer tweets than expected)
                        localServerDepleted = true
                        serverDepleted = true // Update the shared flag
                        lastLoadedPage = pageToLoad // This is the last page we can load
                        Timber.tag("TweetListView")
                            .d("Server depleted at page $pageToLoad, returned ${tweetsWithNulls.size} tweets")
                    }

                    // Check if we have enough visible tweets (for logging purposes)
                    tweets.filter { tweet ->
                        if (showPrivateTweets) {
                            // Profile screen: show private tweets only if it's the app user's own profile
                            !tweet.isPrivate || tweet.authorId == currentUserId
                        } else {
                            // Tweet feed: only show public tweets
                            !tweet.isPrivate
                        }
                    }

                    // Move to next page for next iteration
                    pageToLoad++

                    Timber.tag("TweetListView")
                        .d("Loaded page ${pageToLoad - 1}: fetched ${tweetsWithNulls.size} tweets, total tweets now: ${tweets.size}, lastLoadedPage: $lastLoadedPage")

                    // Add small delay to prevent overwhelming the main thread
                    if (pageToLoad > 0 && pageToLoad < maxInitialPages) {
                        delay(100)
                    }

                } catch (e: Exception) {
                    Timber.tag("TweetListView")
                        .e(e, "Error during initialization at page $pageToLoad")
                    localServerDepleted = true
                    serverDepleted = true
                    break
                }
            }

            if ((System.currentTimeMillis() - startTime) >= maxInitializationTime) {
                Timber.tag("TweetListView")
                    .w("Initialization timed out after ${maxInitializationTime}ms")
            }

            Timber.tag("TweetListView")
                .d("Initialization completed: total tweets: ${tweets.size}, server depleted: $localServerDepleted, lastLoadedPage: $lastLoadedPage")
        }
    }

    // Consolidated LaunchedEffect for videolist and loading state management
    LaunchedEffect(tweets, lastLoadedPage, currentUserId, isInitialLoading) {
        // Initialize lastLoadedPage if it's still -1 and we have tweets
        if (lastLoadedPage == -1 && tweets.isNotEmpty()) {
            Timber.tag("TweetListView")
                .d("Initializing lastLoadedPage from -1 to 0 since we have ${tweets.size} tweets")
            lastLoadedPage = 0
        }
        
        // Track when initial loading is complete
        if (isInitialLoading) {
            // External loading is still in progress (ProfileScreen case)
            isInitialLoadingComplete = false
            Timber.tag("TweetListView").d("External loading in progress, delaying videolist creation")
        } else if (lastLoadedPage >= 0 && currentUserId == lastUserId) {
            // Initial loading is complete - now we can create videolist in background
            isInitialLoadingComplete = true
            Timber.tag("TweetListView").d("Initial loading complete, will create videolist in background")
        }
        
        // Create videolist in background task after initial loading is complete
        if (isInitialLoadingComplete && tweets.isNotEmpty()) {
            // Run videolist creation in background to avoid blocking UI
            withContext(Dispatchers.IO) {
                Timber.tag("TweetListView").d("Creating videolist in background for ${tweets.size} tweets")
                val newVideoIndexedList = createVideoIndexedList(tweets)
                
                // Update on main thread
                withContext(Dispatchers.Main) {
                    videoIndexedList = newVideoIndexedList
                    Timber.tag("TweetListView").d("Background videolist creation completed: ${newVideoIndexedList.size} videos")
                }
            }
        }
    }
    
    // Notify when video-indexed list changes
    LaunchedEffect(videoIndexedList) {
        // Pass video list to our TweetListViewModel
        tweetListViewModel.setVideoIndexedList(videoIndexedList)
        
        // Also call the callback if provided
        onVideoIndexedListChange?.invoke(videoIndexedList)
        Timber.tag("TweetListView").d("Updated video-indexed list with ${videoIndexedList.size} videos from ${tweets.size} tweets")
    }

    // Track scroll state and notify parent
    LaunchedEffect(listState) {
        var previousFirstVisibleItem = listState.firstVisibleItemIndex
        var previousScrollOffset = listState.firstVisibleItemScrollOffset

        snapshotFlow {
            Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
            .collect { (firstVisibleItem, scrollOffset) ->
                val isScrolling = listState.isScrollInProgress
                
                // Determine scroll direction with higher thresholds to filter out small gestures
                val SCROLL_OFFSET_THRESHOLD = 30 // Reduced from 50 to 30 for more sensitivity
                val ITEM_INDEX_THRESHOLD = 1     // Reduced from 2 to 1 for more sensitivity
                
                val indexDelta = firstVisibleItem - previousFirstVisibleItem
                val offsetDelta = scrollOffset - previousScrollOffset

                val direction = when {
                    !isScrolling -> ScrollDirection.NONE
                    indexDelta < -ITEM_INDEX_THRESHOLD ||
                            (indexDelta == 0 && offsetDelta < -SCROLL_OFFSET_THRESHOLD) -> ScrollDirection.UP
                    indexDelta > ITEM_INDEX_THRESHOLD ||
                            (indexDelta == 0 && offsetDelta > SCROLL_OFFSET_THRESHOLD) -> ScrollDirection.DOWN
                    else -> ScrollDirection.NONE
                }

                // Update previous values for next comparison
                previousFirstVisibleItem = firstVisibleItem
                previousScrollOffset = scrollOffset

                onScrollStateChange?.invoke(ScrollState(isScrolling, direction))
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

    // Check if we're at the last tweet for gesture detection
    val isAtLastTweet by remember(listState, tweets) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            val totalItems = layoutInfo.totalItemsCount
            val result = lastVisibleItem != null && lastVisibleItem.index == totalItems - 1

            // Debug logging for isAtLastTweet
            if (result || (lastVisibleItem != null && lastVisibleItem.index >= totalItems - 3)) {
                Timber.tag("TweetListView")
                    .d("isAtLastTweet debug: result=$result, lastVisibleIndex=${lastVisibleItem.index}, totalItems=$totalItems, serverDepleted=$serverDepleted")
            }

            result
        }
    }

    // Notify caller when isAtLastTweet changes
    LaunchedEffect(isAtLastTweet) {
        onIsAtLastTweetChange?.invoke(isAtLastTweet)

        // Track when user was at last tweet
        if (wasAtLastTweet) {
            // User was at last tweet but now scrolled away - reset serverDepleted
            if (serverDepleted) {
                Timber.tag("TweetListView")
                    .d("User scrolled away from last tweet, resetting serverDepleted to false to allow loadmore attempts")
                serverDepleted = false
            }
        }
    }

    // Handle external loadmore triggers
    LaunchedEffect(Unit) {
        onTriggerLoadMore?.let { _ ->
            // This will be called when the caller wants to trigger loadmore
            // We'll use a shared flow or state to communicate this
            externalLoadMoreRequest = true
        }
    }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshingAtTop,
        onRefresh = {
            coroutineScope.launch {
                isRefreshingAtTop = true
                try {
                    withContext(Dispatchers.IO) {
                        serverDepleted = false // User-initiated: allow loading again
                        lastLoadedPage = -1 // Reset last loaded page for fresh start
                        val result = fetchTweets(0) // Await the suspend function
                        Timber.tag("TweetListView")
                            .d("Pull refresh: fetchTweets completed, returned ${result.size} tweets")
                    }
                } catch (e: Exception) {
                    Timber.tag("TweetListView").e(e, "Error during pull refresh")
                } finally {
                    isRefreshingAtTop = false
                    Timber.tag("TweetListView")
                        .d("Pull refresh completed, isRefreshingAtTop set to false")
                }
            }
        }
    )

    // Safety timeout to reset top loading state if it gets stuck
    LaunchedEffect(isRefreshingAtTop) {
        if (isRefreshingAtTop) {
            delay(10000) // 10 second timeout
            if (isRefreshingAtTop) {
                Timber.tag("TweetListView")
                    .w("Top loading state stuck for 10 seconds, forcing reset")
                isRefreshingAtTop = false
            }
        }
    }

    // Safety timeout to reset bottom loading state if it gets stuck
    LaunchedEffect(isRefreshingAtBottom) {
        if (isRefreshingAtBottom) {
            delay(10000) // 10 second timeout
            if (isRefreshingAtBottom) {
                Timber.tag("TweetListView")
                    .w("Bottom loading state stuck for 10 seconds, forcing reset")
                isRefreshingAtBottom = false
            }
        }
    }

    // Preload next page when user is approaching the bottom
    val isNearBottom by remember(tweets) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            val totalItems = layoutInfo.totalItemsCount

            // Check if we're near the bottom (within 5 items of the end) but not at the last tweet
            val isNearBottom = lastVisibleItem != null &&
                    lastVisibleItem.index >= totalItems - 5 &&
                    lastVisibleItem.index < totalItems - 1

            isNearBottom
        }
    }

    // Preload next page when approaching bottom
    LaunchedEffect(isNearBottom, serverDepleted) {
        if (isNearBottom && !serverDepleted && tweets.size >= 4) {
            val nextPage = lastLoadedPage + 1

            // Check if we're already loading this page
            if (pendingLoadMorePage == nextPage) {
                Timber.tag("TweetListView")
                    .d("Preload skipped: page $nextPage is already being loaded")
                return@LaunchedEffect
            }

            Timber.tag("TweetListView").d("Near bottom detected, preloading page $nextPage...")

            coroutineScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val nextPage = lastLoadedPage + 1
                        Timber.tag("TweetListView").d("Preloading page: $nextPage")

                        // Preload the next page and cache it
                        val preloadedTweets = fetchTweets(nextPage)
                        val validTweetsCount = preloadedTweets.count { it != null }

                        if (validTweetsCount > 0) {
                            Timber.tag("TweetListView")
                                .d("Preloaded $validTweetsCount valid tweets from page $nextPage")
                        } else if (preloadedTweets.size < TW_CONST.PAGE_SIZE) {
                            // Server is depleted, but only set it if we're still near bottom
                            // This prevents setting serverDepleted when user has scrolled away
                            serverDepleted = true
                            Timber.tag("TweetListView")
                                .d("Server depleted during preload at page $nextPage")
                        } else {
                            Timber.tag("TweetListView")
                                .d("Preloaded page $nextPage but no valid tweets found")
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag("TweetListView").e(e, "Error during preload")
                }
            }
        }
    }

    // Track scroll position changes and save them
    LaunchedEffect(listState) {
        snapshotFlow {
            Pair(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }
            .collect { position ->
                savedScrollPosition.value = position
            }
    }

    // Infinite scroll - Only trigger when last tweet is visible and server not depleted
    LaunchedEffect(isAtLastTweet, isRefreshingAtBottom, serverDepleted, externalLoadMoreRequest) {
        // Allow loading if last tweet is visible, not already refreshing, and no pending load for the same page
        // OR if there's an external loadmore request (even when serverDepleted is true)
        if ((isAtLastTweet && !isRefreshingAtBottom && tweets.isNotEmpty() && !serverDepleted) ||
            (externalLoadMoreRequest && !isRefreshingAtBottom && tweets.isNotEmpty())
        ) {

            val nextPage = lastLoadedPage + 1

            // Check if we're already loading this page
            if (pendingLoadMorePage == nextPage) {
                Timber.tag("TweetListView")
                    .d("Load more skipped: page $nextPage is already being loaded")
                return@LaunchedEffect
            }

            if (externalLoadMoreRequest) {
                Timber.tag("TweetListView")
                    .d("External loadmore request detected, triggering loadmore...")
            }

            Timber.tag("TweetListView").d("Triggering load more for page $nextPage...")
            pendingLoadMorePage = nextPage // Mark this page as being loaded
            isRefreshingAtBottom = true // Set loading state immediately
            spinnerStartTime = System.currentTimeMillis() // Track when spinner started

            coroutineScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        var currentPage =
                            lastLoadedPage + 1 // Start with the next page after the last loaded page
                        var foundValidTweets = false

                        // Keep trying pages until we find valid tweets or server is depleted
                        while (!foundValidTweets && !serverDepleted) {
                            Timber.tag("TweetListView")
                                .d("Loading tweets, page: $currentPage, lastLoadedPage: $lastLoadedPage, current tweets: ${tweets.size}")
                            val tweetsWithNulls = fetchTweets(currentPage)

                            // Count valid (non-null) tweets
                            val validTweetsCount = tweetsWithNulls.count { it != null }
                            Timber.tag("TweetListView")
                                .d("Page $currentPage: returned ${tweetsWithNulls.size} tweets, valid tweets: $validTweetsCount")

                            if (validTweetsCount > 0) {
                                // Found valid tweets, stop searching and reset serverDepleted flag
                                foundValidTweets = true
                                lastLoadedPage = currentPage
                                serverDepleted = false // Reset flag since we found new data
                                Timber.tag("TweetListView")
                                    .d("Found valid tweets on page $currentPage, resetting serverDepleted flag")
                            } else if (tweetsWithNulls.size < TW_CONST.PAGE_SIZE) {
                                // Partial page with no valid tweets - server is depleted
                                serverDepleted = true
                                // Don't increment lastLoadedPage since we didn't get new data
                                Timber.tag("TweetListView")
                                    .d("Server depleted at page $currentPage, returned ${tweetsWithNulls.size} tweets (all null)")
                            } else {
                                // Full page with all null tweets, try next page
                                Timber.tag("TweetListView")
                                    .d("Page $currentPage has all null tweets, trying next page")
                                currentPage++
                            }
                        }

                        if (serverDepleted) {
                            Timber.tag("TweetListView")
                                .d("Server depleted after searching through pages")
                        } else {
                            Timber.tag("TweetListView")
                                .d("Successfully loaded valid tweets from page $currentPage")
                        }
                    }
                } finally {
                    // Ensure spinner shows for at least 0.5 seconds for smoother scrolling experience
                    val elapsedTime = System.currentTimeMillis() - spinnerStartTime
                    val minDisplayTime = 500L // 0.5 second minimum for smoother feel

                    if (elapsedTime < minDisplayTime) {
                        val remainingTime = minDisplayTime - elapsedTime
                        Timber.tag("TweetListView")
                            .d("Spinner shown for ${elapsedTime}ms, waiting ${remainingTime}ms more for minimum display time")
                        delay(remainingTime)
                    }

                    isRefreshingAtBottom = false // Ensure state is reset
                    pendingLoadMorePage = -1 // Clear pending page
                    Timber.tag("TweetListView")
                        .d("Load more completed, isRefreshingAtBottom set to false, pendingLoadMorePage cleared")
                }
            }
        } else if (isAtLastTweet && serverDepleted) {
            Timber.tag("TweetListView")
                .d("Last tweet visible with serverDepleted=true - waiting for manual gesture to trigger loadmore")
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

            // Show empty state if no tweets
            if (tweets.isEmpty() && !isRefreshingAtTop) {
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
                                context = context
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
 * Creates a video-indexed list that maintains feed order and handles retweets properly.
 * Returns a list of pairs: (MimeiId, MediaType) sorted by tweet timestamp (newest first).
 * 
 * For retweets: Uses the retweet's timestamp (when it was shared), not the original tweet's timestamp
 * This ensures videos are ordered by when they appeared in the feed, not when they were originally created
 */
private suspend fun createVideoIndexedList(tweets: List<Tweet>): List<Pair<MimeiId, MediaType>> {
    val videoInfoList = mutableListOf<VideoInfo>()
    
    tweets.forEachIndexed { feedIndex, tweet ->
        // Check if this tweet has videos
        var tweetToCheck = tweet
        var hasVideo = tweet.attachments?.any { attachment ->
            val mediaType = inferMediaTypeFromAttachment(attachment)
            mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO
        } == true
        
        // If this is a retweet without attachments, fetch the original tweet
        Timber.tag("TweetListView").d("Checking retweet at feed index $feedIndex: ${tweet.mid}, hasVideo: $hasVideo, originalTweetId: ${tweet.originalTweetId}, content: '${tweet.content}', attachments: ${tweet.attachments?.size}")
        
        if (!hasVideo && tweet.originalTweetId != null && tweet.originalAuthorId != null && 
            tweet.content.isNullOrEmpty() && tweet.attachments.isNullOrEmpty()) {
            Timber.tag("TweetListView").d("Pure retweet detected at feed index $feedIndex: ${tweet.mid}, fetching original tweet: ${tweet.originalTweetId}")
            
            val originalTweet = HproseInstance.refreshTweet(tweet.originalTweetId!!, tweet.originalAuthorId!!)
            if (originalTweet != null) {
                tweetToCheck = originalTweet
                hasVideo = originalTweet.attachments?.any { attachment ->
                    val mediaType = inferMediaTypeFromAttachment(attachment)
                    mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO
                } == true
                
                Timber.tag("TweetListView").d("Fetched original tweet: ${originalTweet.mid}, attachments: ${originalTweet.attachments?.size}, hasVideo: $hasVideo")
            } else {
                Timber.tag("TweetListView").w("Failed to fetch original tweet: ${tweet.originalTweetId}")
            }
        }
        
        if (hasVideo) {
            // Add each video attachment as a separate entry
            tweetToCheck.attachments?.forEach { attachment ->
                val mediaType = inferMediaTypeFromAttachment(attachment)
                if (mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO) {
                    videoInfoList.add(VideoInfo(
                        mid = attachment.mid,
                        mediaType = mediaType,
                        feedIndex = feedIndex,
                        tweetTimestamp = tweet.timestamp // Use retweet's timestamp, not original tweet's timestamp
                    ))
                    val tweetType = if (tweet.originalTweetId != null) "retweet" else "original"
                    Timber.tag("TweetListView").d("Added video at feed index $feedIndex: ${attachment.mid}, tweet: ${tweetToCheck.mid}, timestamp: ${tweet.timestamp} (${tweetType}), mediaType: $mediaType")
                }
            }
        } else {
            // Log why this tweet was skipped
            val tweetType = if (tweet.originalTweetId != null) "retweet" else "original"
            Timber.tag("TweetListView").d("Skipped $tweetType at feed index $feedIndex: ${tweet.mid}, attachments: ${tweet.attachments?.size}, originalTweetId: ${tweet.originalTweetId}")
        }
    }
    
    // Sort videos by timestamp (newest first) before converting
    val sortedVideoInfoList = videoInfoList.sortedByDescending { it.tweetTimestamp }
    
    // Convert VideoInfo list to the required format (MimeiId, MediaType)
    val result = sortedVideoInfoList.map { videoInfo -> 
        Pair(videoInfo.mid, videoInfo.mediaType) 
    }
    
    Timber.tag("TweetListView").d("Created video list with ${result.size} videos sorted by timestamp (newest first)")
    result.forEachIndexed { index, (videoMid, mediaType) ->
        val videoInfo = sortedVideoInfoList[index]
        val tweet = tweets.getOrNull(videoInfo.feedIndex)
        val tweetType = if (tweet?.originalTweetId != null) "retweet" else "original"
        Timber.tag("TweetListView").d("Video $index: $videoMid, timestamp: ${videoInfo.tweetTimestamp}, type: $tweetType, mediaType: $mediaType")
    }
    
    return result
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
