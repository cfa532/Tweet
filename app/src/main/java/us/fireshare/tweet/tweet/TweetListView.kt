package us.fireshare.tweet.tweet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import com.google.android.gms.common.internal.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.MimeiId
import timber.log.Timber
import us.fireshare.tweet.datamodel.TW_CONST

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
 */
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
) {
    // Internal state management
    var isRefreshingAtTop by remember { mutableStateOf(false) }
    var isRefreshingAtBottom by remember { mutableStateOf(false) }
    var lastLoadedPage by remember { mutableIntStateOf(-1) } // Track the last page that was actually loaded
    var lastUserId by remember { mutableStateOf(currentUserId) }
    var serverDepleted by remember { mutableStateOf(false) } // Track if server is depleted to prevent infinite loading
    
    // Remember scroll position across recompositions and configuration changes
    val savedScrollPosition = rememberSaveable { mutableStateOf(Pair(0, 0)) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedScrollPosition.value.first,
        initialFirstVisibleItemScrollOffset = savedScrollPosition.value.second
    )
    val coroutineScope = rememberCoroutineScope()

    val SCROLL_OFFSET_THRESHOLD = 8
    val ITEM_INDEX_THRESHOLD = 1
    val MINMIMUM_TWEET_COUNT = 4

    // Detect user changes and initialize data
    LaunchedEffect(currentUserId) {
        if (currentUserId != lastUserId) {
            Timber.tag("TweetListView").d("User changed from $lastUserId to $currentUserId, initializing data")
            lastUserId = currentUserId
            lastLoadedPage = -1 // Reset last loaded page
            serverDepleted = false // Reset server depleted flag for new user
            
            // Initialize with enough data (at least 4 tweets)
            var enoughTweets = false
            var localServerDepleted = false
            var pageToLoad = 0
            
            while (!enoughTweets && !localServerDepleted) {
                val tweetsWithNulls = fetchTweets(pageToLoad)
                
                // Only increment lastLoadedPage if we got a full page of results
                if (tweetsWithNulls.size == TW_CONST.PAGE_SIZE) {
                    lastLoadedPage = pageToLoad
                } else {
                    // Server is depleted (returned fewer tweets than expected)
                    localServerDepleted = true
                    serverDepleted = true // Update the shared flag
                    lastLoadedPage = pageToLoad // This is the last page we can load
                    Timber.tag("TweetListView").d("Server depleted at page $pageToLoad, returned ${tweetsWithNulls.size} tweets")
                }
                
                // Check if we have enough tweets
                enoughTweets = tweets.size >= MINMIMUM_TWEET_COUNT
                
                // Move to next page for next iteration
                pageToLoad++
                
                Timber.tag("TweetListView").d("Page $pageToLoad: fetched ${tweetsWithNulls.size} tweets, total tweets now: ${tweets.size}, lastLoadedPage: $lastLoadedPage")
            }
            
            Timber.tag("TweetListView").d("Initialization completed: total tweets: ${tweets.size}, server depleted: $localServerDepleted, lastLoadedPage: $lastLoadedPage")
        }
    }
    
    // Initialize lastLoadedPage if it's still -1 and we have tweets
    LaunchedEffect(tweets, lastLoadedPage) {
        if (lastLoadedPage == -1 && tweets.isNotEmpty()) {
            Timber.tag("TweetListView").d("Initializing lastLoadedPage from -1 to 0 since we have ${tweets.size} tweets")
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

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshingAtTop,
        onRefresh = {
            coroutineScope.launch {
                isRefreshingAtTop = true
                try {
                    withContext(Dispatchers.IO) {
                        serverDepleted = false // User-initiated: allow loading again
                        lastLoadedPage = -1 // Reset last loaded page for fresh start
                        Timber.tag("TweetListView").d("Pull refresh: Loading page 0, current tweets: ${tweets.size}")
                        fetchTweets(0)
                    }
                } catch (e: Exception) {
                    Timber.tag("TweetListView").e(e, "Error during pull refresh")
                } finally {
                    isRefreshingAtTop = false
                    Timber.tag("TweetListView").d("Pull refresh completed, isRefreshingAtTop set to false")
                }
            }
        }
    )
    
    // Safety timeout to reset top loading state if it gets stuck
    LaunchedEffect(isRefreshingAtTop) {
        if (isRefreshingAtTop) {
            delay(10000) // 10 second timeout
            if (isRefreshingAtTop) {
                Timber.tag("TweetListView").w("Top loading state stuck for 10 seconds, forcing reset")
                isRefreshingAtTop = false
            }
        }
    }

    val isAtBottom by remember(tweets) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            val totalItems = layoutInfo.totalItemsCount
            
            // Check if we're near the bottom (within 2 items of the end)
            val isAtBottom = lastVisibleItem != null && lastVisibleItem.index >= totalItems - 2
            
            isAtBottom
        }
    }

    // Track scroll position changes and save them
    LaunchedEffect(listState) {
        snapshotFlow { Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect { position ->
                savedScrollPosition.value = position
            }
    }

    // Infinite scroll
    LaunchedEffect(isAtBottom, isRefreshingAtBottom, serverDepleted) {
        Timber.tag("TweetListView").d("isAtBottom changed: $isAtBottom, isRefreshingAtBottom: $isRefreshingAtBottom, tweets.size: ${tweets.size}, serverDepleted: $serverDepleted, lastLoadedPage: $lastLoadedPage")
        
        if (isAtBottom && !isRefreshingAtBottom && tweets.size >= 4 && !serverDepleted) { // Only trigger if we have enough tweets and server not depleted
            Timber.tag("TweetListView").d("Triggering load more...")
            isRefreshingAtBottom = true // Set loading state immediately
            
            coroutineScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        var currentPage = lastLoadedPage + 1 // Start with the next page after the last loaded page
                        var foundValidTweets = false
                        var localServerDepleted = false
                        
                        // Keep trying pages until we find valid tweets or server is depleted
                        while (!foundValidTweets && !localServerDepleted) {
                            Timber.tag("TweetListView").d("Loading tweets, page: $currentPage, lastLoadedPage: $lastLoadedPage, current tweets: ${tweets.size}")
                            val tweetsWithNulls = fetchTweets(currentPage)
                            
                            // Count valid (non-null) tweets
                            val validTweetsCount = tweetsWithNulls.count { it != null }
                            Timber.tag("TweetListView").d("Page $currentPage: returned ${tweetsWithNulls.size} tweets, valid tweets: $validTweetsCount")
                            
                            if (validTweetsCount > 0) {
                                // Found valid tweets, stop searching
                                foundValidTweets = true
                                lastLoadedPage = currentPage
                                Timber.tag("TweetListView").d("Found valid tweets on page $currentPage, stopping search")
                            } else if (tweetsWithNulls.size < TW_CONST.PAGE_SIZE) {
                                // Partial page with no valid tweets - server is depleted
                                localServerDepleted = true
                                serverDepleted = true
                                lastLoadedPage = currentPage
                                Timber.tag("TweetListView").d("Server depleted at page $currentPage, returned ${tweetsWithNulls.size} tweets (all null)")
                            } else {
                                // Full page with all null tweets, try next page
                                Timber.tag("TweetListView").d("Page $currentPage has all null tweets, trying next page")
                                currentPage++
                            }
                        }
                        
                        if (localServerDepleted) {
                            Timber.tag("TweetListView").d("Server depleted after searching through pages")
                        } else {
                            Timber.tag("TweetListView").d("Successfully loaded valid tweets from page $currentPage")
                        }
                    }
                } finally {
                    isRefreshingAtBottom = false // Ensure state is reset
                    Timber.tag("TweetListView").d("Load more completed, isRefreshingAtBottom set to false")
                }
            }
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
            contentPadding = contentPadding
        ) {
            items(
                items = tweets,
                key = { it.mid }
            ) { tweet ->
                if (showPrivateTweets || !tweet.isPrivate) {
                    parentEntry?.let { 
                        TweetItem(
                            tweet = tweet, 
                            parentEntry = it, 
                            isFromFeed = true,
                            onTweetUnavailable = onTweetUnavailable
                        ) 
                    }
                }
            }
            if (isRefreshingAtTop) {
                item {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 60.dp)
                            .wrapContentWidth(Alignment.CenterHorizontally)
                            .size(48.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 4.dp
                    )
                }
            }
            if (isRefreshingAtBottom) {
                item {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
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

/**
 * A simplified version of TweetListView for basic tweet list display without advanced features.
 * 
 * @param tweets The list of tweets to display
 * @param parentEntry Navigation back stack entry for navigation context
 * @param onTweetUnavailable Callback when a tweet becomes unavailable (e.g., original tweet deleted)
 * @param modifier Additional modifier for the component
 */
@Composable
fun SimpleTweetListView(
    tweets: List<Tweet>,
    parentEntry: NavBackStackEntry,
    onTweetUnavailable: (Tweet) -> Unit = {},
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 60.dp)
    ) {
        items(
            items = tweets,
            key = { it.mid }
        ) { tweet ->
            if (!tweet.isPrivate) {
                parentEntry.let { 
                    TweetItem(
                        tweet = tweet, 
                        parentEntry = it, 
                        isFromFeed = true,
                        onTweetUnavailable = { tweetId ->
                            // Find the tweet by ID and call the callback with the Tweet object
                            tweets.find { it.mid == tweetId }?.let { foundTweet ->
                                onTweetUnavailable(foundTweet)
                            }
                        }
                    ) 
                }
            }
        }
    }
}

/**
 * CommentListView: Specialized list view for displaying tweet comments with Material3 styling.
 * Self-contained with built-in pagination and refresh functionality.
 *
 * @param comments List of comment tweets to display
 * @param getComments Function to load comments for a specific page number
 * @param scrollBehavior Optional TopAppBar scroll behavior
 * @param contentPadding Padding for the list content
 * @param modifier Modifier for the component
 * @param parentEntry Optional NavBackStackEntry for navigation context
 */
@Composable
@OptIn(ExperimentalMaterialApi::class, ExperimentalMaterial3Api::class)
fun CommentListView(
    comments: List<Tweet>,
    getComments: (Int) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = 60.dp),
    modifier: Modifier = Modifier,
    parentEntry: NavBackStackEntry? = null,
) {
    // Internal state management
    var isRefreshingAtTop by remember { mutableStateOf(false) }
    var isRefreshingAtBottom by remember { mutableStateOf(false) }
    var currentPage by remember { mutableIntStateOf(0) }
    
    // Remember scroll position across recompositions and configuration changes
    val savedScrollPosition = rememberSaveable { mutableStateOf(Pair(0, 0)) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedScrollPosition.value.first,
        initialFirstVisibleItemScrollOffset = savedScrollPosition.value.second
    )
    val coroutineScope = rememberCoroutineScope()

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshingAtTop,
        onRefresh = {
            coroutineScope.launch {
                isRefreshingAtTop = true
                try {
                    withContext(Dispatchers.IO) {
                        currentPage = 0 // Reset to page 0 for refresh
                        getComments(0)
                    }
                } finally {
                    isRefreshingAtTop = false
                }
            }
        }
    )

    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem != null && lastVisibleItem.index == layoutInfo.totalItemsCount - 1
        }
    }

    // Track scroll position changes and save them
    LaunchedEffect(listState) {
        snapshotFlow { Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect { position ->
                savedScrollPosition.value = position
            }
    }

    // Infinite scroll
    LaunchedEffect(isAtBottom) {
        if (isAtBottom && !isRefreshingAtBottom) {
            coroutineScope.launch {
                isRefreshingAtBottom = true
                try {
                    withContext(Dispatchers.IO) {
                        currentPage += 1 // Increment page for load more
                        getComments(currentPage)
                    }
                } finally {
                    isRefreshingAtBottom = false
                }
            }
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
            contentPadding = contentPadding
        ) {
            items(
                items = comments,
                key = { it.mid }
            ) { comment ->
                parentEntry?.let { CommentItem(comment, null, it) }
            }
            if (isRefreshingAtTop) {
                item {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 60.dp)
                            .wrapContentWidth(Alignment.CenterHorizontally)
                            .size(48.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 4.dp
                    )
                }
            }
            if (isRefreshingAtBottom) {
                item {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
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