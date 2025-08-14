package us.fireshare.tweet.tweet

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.Tweet

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
    restoreScrollPosition: Boolean = true // Control whether to restore scroll position
) {
    // Debug logging for TweetListView recreation - only log when essential parameters change
    val previousTweetsSize = remember { mutableIntStateOf(tweets.size) }
    val previousUserId = remember { mutableStateOf(currentUserId) }

    LaunchedEffect(tweets.size, currentUserId) {
        if (tweets.size != previousTweetsSize.intValue || currentUserId != previousUserId.value) {
            Timber.tag("TweetListView")
                .d("TweetListView parameters changed: tweets=${tweets.size}->${previousTweetsSize.value}, userId=$currentUserId->${previousUserId.value}")
            previousTweetsSize.intValue = tweets.size
            previousUserId.value = currentUserId
        }
    }

    // Track tweets list changes - only when size actually changes
    LaunchedEffect(tweets.size) {
        Timber.tag("TweetListView").d("Tweets list size changed: ${tweets.size} tweets")
    }

    // Internal state management
    var isRefreshingAtTop by remember { mutableStateOf(false) }
    var isRefreshingAtBottom by remember { mutableStateOf(false) }
    var lastLoadedPage by rememberSaveable { mutableIntStateOf(-1) } // Use rememberSaveable to persist across recompositions
    var lastUserId by remember { mutableStateOf(currentUserId) }
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
    val MINMIMUM_TWEET_COUNT = 4

    // Detect user changes and initialize data
    LaunchedEffect(currentUserId) {
        if (currentUserId != lastUserId) {
            Timber.tag("TweetListView")
                .d("User changed from $lastUserId to $currentUserId, initializing data")
            lastUserId = currentUserId
            lastLoadedPage = -1 // Reset last loaded page
            serverDepleted = false // Reset server depleted flag for new user

            // Initialize with enough data (at least 4 tweets) - with timeout protection
            var enoughTweets = false
            var localServerDepleted = false
            var pageToLoad = 0
            val startTime = System.currentTimeMillis()
            val maxInitializationTime = 10000L // 10 seconds timeout

            while (!enoughTweets && !localServerDepleted &&
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

                    // Check if we have enough tweets
                    enoughTweets = tweets.size >= MINMIMUM_TWEET_COUNT

                    // Move to next page for next iteration
                    pageToLoad++

                    Timber.tag("TweetListView")
                        .d("Page $pageToLoad: fetched ${tweetsWithNulls.size} tweets, total tweets now: ${tweets.size}, lastLoadedPage: $lastLoadedPage")

                    // Add small delay to prevent overwhelming the main thread
                    if (pageToLoad > 0) {
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

    // Initialize lastLoadedPage if it's still -1 and we have tweets
    LaunchedEffect(tweets, lastLoadedPage) {
        if (lastLoadedPage == -1 && tweets.isNotEmpty()) {
            Timber.tag("TweetListView")
                .d("Initializing lastLoadedPage from -1 to 0 since we have ${tweets.size} tweets")
            lastLoadedPage = 0
        }
    }

    // Track scroll state and notify parent
    LaunchedEffect(listState) {
        var previousFirstVisibleItem = listState.firstVisibleItemIndex
        var previousScrollOffset = listState.firstVisibleItemScrollOffset

        snapshotFlow {
            val isScrolling = listState.isScrollInProgress
            val firstVisibleItem = listState.firstVisibleItemIndex
            val scrollOffset = listState.firstVisibleItemScrollOffset

            // Determine scroll direction with higher thresholds to filter out small gestures
            val SCROLL_OFFSET_THRESHOLD = 30 // Reduced from 50 to 30 for more sensitivity
            val ITEM_INDEX_THRESHOLD = 1     // Reduced from 2 to 1 for more sensitivity

            val direction = when {
                !isScrolling -> ScrollDirection.NONE
                firstVisibleItem < previousFirstVisibleItem - ITEM_INDEX_THRESHOLD ||
                        (firstVisibleItem == previousFirstVisibleItem && scrollOffset < previousScrollOffset - SCROLL_OFFSET_THRESHOLD) -> ScrollDirection.UP

                firstVisibleItem > previousFirstVisibleItem + ITEM_INDEX_THRESHOLD ||
                        (firstVisibleItem == previousFirstVisibleItem && scrollOffset > previousScrollOffset + SCROLL_OFFSET_THRESHOLD) -> ScrollDirection.DOWN

                else -> ScrollDirection.NONE
            }

            // Update previous values
            previousFirstVisibleItem = firstVisibleItem
            previousScrollOffset = scrollOffset

            ScrollState(isScrolling, direction)
        }
            .collect { scrollState ->
                onScrollStateChange?.invoke(scrollState)
            }
    }

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
        if (isAtLastTweet) {
            wasAtLastTweet = true
        } else if (wasAtLastTweet) {
            // User was at last tweet but now scrolled away - reset serverDepleted
            wasAtLastTweet = false
            if (serverDepleted) {
                Timber.tag("TweetListView")
                    .d("User scrolled away from last tweet, resetting serverDepleted to false to allow loadmore attempts")
                serverDepleted = false
            }
        }
    }

    // Handle external loadmore triggers
    LaunchedEffect(Unit) {
        onTriggerLoadMore?.let { trigger ->
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
        Timber.tag("TweetListView")
            .d("isAtLastTweet changed: $isAtLastTweet, isRefreshingAtBottom: $isRefreshingAtBottom, tweets.size: ${tweets.size}, serverDepleted: $serverDepleted, externalLoadMoreRequest: $externalLoadMoreRequest, lastLoadedPage: $lastLoadedPage")

        // Allow loading if last tweet is visible, not already refreshing, and no pending load for the same page
        // OR if there's an external loadmore request (even when serverDepleted is true)
        if ((isAtLastTweet && !isRefreshingAtBottom && tweets.size >= 4 && !serverDepleted) ||
            (externalLoadMoreRequest && !isRefreshingAtBottom && tweets.size >= 4)
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
                externalLoadMoreRequest = false // Reset the request
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
                    // Ensure spinner shows for at least 1 second
                    val elapsedTime = System.currentTimeMillis() - spinnerStartTime
                    val minDisplayTime = 1000L // 1 second minimum

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

            items(
                items = tweets,
                key = { it.mid }
            ) { tweet ->
                if (showPrivateTweets || !tweet.isPrivate) {
                    parentEntry?.let {
                        TweetItem(
                            tweet = tweet,
                            parentEntry = it,
                            onTweetUnavailable = onTweetUnavailable
                        )
                    }

                    // Add divider after each tweet item (except the last one)
                    if (tweets.indexOf(tweet) < tweets.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .padding(horizontal = 1.dp),
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                        )
                    }
                }
            }

            // Loading spinner at bottom - use key to make it stable
            if (isRefreshingAtBottom) {
                item(key = "loading_spinner") {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp, bottom = 16.dp)
                            .wrapContentWidth(Alignment.CenterHorizontally),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 4.dp
                    )
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
