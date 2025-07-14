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
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.MimeiId

/**
 * TweetListView: Android Material3 style tweet list with pull-to-refresh, infinite scroll, and loading indicators.
 *
 * @param tweets List of tweets to display
 * @param listState LazyListState for scroll management
 * @param isRefreshingAtTop Whether top refresh is in progress
 * @param isRefreshingAtBottom Whether bottom refresh is in progress
 * @param onRefreshTop Callback for pull-to-refresh
 * @param onLoadMore Callback for infinite scroll
 * @param onScrollPositionChange Callback for scroll position changes
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
    listState: LazyListState,
    isRefreshingAtTop: Boolean = false,
    isRefreshingAtBottom: Boolean = false,
    onRefreshTop: (() -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    onScrollPositionChange: ((Pair<Int, Int>) -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = 60.dp),
    showPrivateTweets: Boolean = false,
    modifier: Modifier = Modifier,
    parentEntry: NavBackStackEntry? = null,
) {
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshingAtTop,
        onRefresh = { onRefreshTop?.invoke() }
    )

    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem != null && lastVisibleItem.index == layoutInfo.totalItemsCount - 1
        }
    }

    // Track scroll position changes
    LaunchedEffect(listState) {
        snapshotFlow { Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect { position ->
                onScrollPositionChange?.invoke(position)
            }
    }

    // Infinite scroll
    LaunchedEffect(isAtBottom) {
        if (isAtBottom && onLoadMore != null) {
            onLoadMore()
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
                    parentEntry?.let { TweetItem(tweet, it) }
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
 * @param modifier Additional modifier for the component
 */
@Composable
fun SimpleTweetListView(
    tweets: List<Tweet>,
    parentEntry: NavBackStackEntry,
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
                parentEntry.let { TweetItem(tweet, it) }
            }
        }
    }
}

/**
 * A TweetListView specifically designed for displaying user tweets with pinned tweets support.
 * 
 * @param tweets The list of regular tweets
 * @param pinnedTweets The list of pinned tweets to display at the top
 * @param parentEntry Navigation back stack entry for navigation context
 * @param listState The LazyListState for scroll management
 * @param scrollBehavior Optional TopAppBar scroll behavior for nested scrolling
 * @param isRefreshingAtTop Whether the top refresh is in progress
 * @param isRefreshingAtBottom Whether the bottom refresh is in progress
 * @param onRefreshTop Callback for top refresh action
 * @param onLoadMore Callback for loading more tweets
 * @param showPrivateTweets Whether to show private tweets
 * @param modifier Additional modifier for the component
 */
@Composable
@OptIn(ExperimentalMaterialApi::class, ExperimentalMaterial3Api::class)
fun UserTweetListView(
    tweets: List<Tweet>,
    pinnedTweets: List<Tweet> = emptyList(),
    parentEntry: NavBackStackEntry,
    listState: LazyListState,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    isRefreshingAtTop: Boolean = false,
    isRefreshingAtBottom: Boolean = false,
    onRefreshTop: (() -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    showPrivateTweets: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Pull-to-refresh state
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshingAtTop,
        onRefresh = {
            onRefreshTop?.let { refresh ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    refresh()
                }
            }
        }
    )

    // Check if we're at the bottom for infinite scrolling
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem != null && lastVisibleItem.index == layoutInfo.totalItemsCount - 1
        }
    }

    // Handle infinite scrolling
    LaunchedEffect(isAtBottom) {
        if (isAtBottom && onLoadMore != null) {
            withContext(Dispatchers.IO) {
                onLoadMore()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(color = Color.LightGray)
            .pullRefresh(pullRefreshState)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .let { 
                    if (scrollBehavior != null) {
                        it.nestedScroll(scrollBehavior.nestedScrollConnection)
                    } else {
                        it
                    }
                },
            state = listState,
            contentPadding = PaddingValues(bottom = 60.dp)
        ) {
            // Display pinned tweets section if available
            if (pinnedTweets.isNotEmpty()) {
                // Note: You would need to add the pinned tweets header and divider here
                // This is a simplified version - you can extend it as needed
                items(pinnedTweets, key = { it.mid }) { tweet ->
                    if (showPrivateTweets || !tweet.isPrivate) {
                        parentEntry.let { TweetItem(tweet, it) }
                    }
                }
            }
            
            // Display regular tweets
            items(tweets, key = { it.mid }) { tweet: Tweet ->
                if (showPrivateTweets || !tweet.isPrivate) {
                    parentEntry.let { TweetItem(tweet, it) }
                }
            }

            // Top loading indicator
            if (isRefreshingAtTop) {
                item {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 60.dp)
                            .wrapContentWidth(Alignment.CenterHorizontally)
                            .size(80.dp),
                        color = Color.LightGray,
                        strokeWidth = 8.dp
                    )
                }
            }

            // Bottom loading indicator
            if (isRefreshingAtBottom) {
                item {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .wrapContentWidth(Alignment.CenterHorizontally)
                            .align(Alignment.BottomCenter)
                    )
                }
            }
        }

        // Pull-to-refresh indicator
        PullRefreshIndicator(
            refreshing = isRefreshingAtTop,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

/**
 * CommentListView: Specialized list view for displaying tweet comments with Material3 styling.
 *
 * @param comments List of comment tweets to display
 * @param listState LazyListState for scroll management
 * @param isRefreshingAtTop Whether top refresh is in progress
 * @param isRefreshingAtBottom Whether bottom refresh is in progress
 * @param onRefreshTop Callback for pull-to-refresh
 * @param onLoadMore Callback for infinite scroll
 * @param onScrollPositionChange Callback for scroll position changes
 * @param scrollBehavior Optional TopAppBar scroll behavior
 * @param contentPadding Padding for the list content
 * @param modifier Modifier for the component
 * @param parentEntry Optional NavBackStackEntry for navigation context
 */
@Composable
@OptIn(ExperimentalMaterialApi::class, ExperimentalMaterial3Api::class)
fun CommentListView(
    comments: List<Tweet>,
    listState: LazyListState,
    isRefreshingAtTop: Boolean = false,
    isRefreshingAtBottom: Boolean = false,
    onRefreshTop: (() -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    onScrollPositionChange: ((Pair<Int, Int>) -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = 60.dp),
    modifier: Modifier = Modifier,
    parentEntry: NavBackStackEntry? = null,
) {
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshingAtTop,
        onRefresh = { onRefreshTop?.invoke() }
    )

    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem != null && lastVisibleItem.index == layoutInfo.totalItemsCount - 1
        }
    }

    // Track scroll position changes
    LaunchedEffect(listState) {
        snapshotFlow { Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect { position ->
                onScrollPositionChange?.invoke(position)
            }
    }

    // Infinite scroll
    LaunchedEffect(isAtBottom) {
        if (isAtBottom && onLoadMore != null) {
            onLoadMore()
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

/**
 * UserListView: Material3 style list view for displaying user lists (followers/following).
 *
 * @param users List of users to display
 * @param listState LazyListState for scroll management
 * @param isRefreshingAtTop Whether top refresh is in progress
 * @param isRefreshingAtBottom Whether bottom refresh is in progress
 * @param onRefreshTop Callback for pull-to-refresh
 * @param onLoadMore Callback for infinite scroll
 * @param onScrollPositionChange Callback for scroll position changes
 * @param scrollBehavior Optional TopAppBar scroll behavior
 * @param contentPadding Padding for the list content
 * @param modifier Modifier for the component
 * @param userItem Composable for rendering each user item
 */
@Composable
@OptIn(ExperimentalMaterialApi::class, ExperimentalMaterial3Api::class)
fun UserListView(
    users: List<MimeiId>,
    listState: LazyListState,
    isRefreshingAtTop: Boolean = false,
    isRefreshingAtBottom: Boolean = false,
    onRefreshTop: (() -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    onScrollPositionChange: ((Pair<Int, Int>) -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = 60.dp),
    modifier: Modifier = Modifier,
    userItem: @Composable (MimeiId) -> Unit,
) {
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshingAtTop,
        onRefresh = { onRefreshTop?.invoke() }
    )

    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem != null && lastVisibleItem.index == layoutInfo.totalItemsCount - 1
        }
    }

    // Track scroll position changes
    LaunchedEffect(listState) {
        snapshotFlow { Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect { position ->
                onScrollPositionChange?.invoke(position)
            }
    }

    // Infinite scroll
    LaunchedEffect(isAtBottom) {
        if (isAtBottom && onLoadMore != null) {
            onLoadMore()
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
                items = users,
                key = { it }
            ) { userId ->
                userItem(userId)
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