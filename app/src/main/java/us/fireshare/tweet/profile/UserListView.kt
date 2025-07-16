package us.fireshare.tweet.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TW_CONST

enum class UserScrollDirection {
    UP, DOWN, NONE
}

data class UserScrollState(
    val isScrolling: Boolean,
    val direction: UserScrollDirection
)

/**
 * Enhanced UserListView: Material3 style list view for displaying user lists (followers/following).
 * Self-contained with built-in pagination, pull-to-refresh, infinite scroll, and loading indicators.
 * Similar to TweetListView but optimized for user lists.
 *
 * @param users List of user IDs to display
 * @param fetchUsers Function to fetch users for a specific page number (returns List<MimeiId?>)
 * @param scrollBehavior Optional TopAppBar scroll behavior
 * @param contentPadding Padding for the list content
 * @param modifier Modifier for the component
 * @param userItem Composable for rendering each user item
 * @param onScrollStateChange Optional callback for scroll state changes
 * @param currentUserId Optional current user ID for change detection
 */
@Composable
@OptIn(ExperimentalMaterialApi::class, ExperimentalMaterial3Api::class)
fun UserListView(
    users: List<MimeiId>,
    fetchUsers: suspend (Int) -> List<MimeiId?>,
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = 60.dp),
    userItem: @Composable (MimeiId) -> Unit,
    onScrollStateChange: ((UserScrollState) -> Unit)? = null,
    currentUserId: MimeiId? = null,
) {
    // Debug logging
    Timber.tag("UserListView").d("UserListView received users: ${users.size}")
    
    // Internal state management
    var isRefreshingAtTop by remember { mutableStateOf(false) }
    var isRefreshingAtBottom by remember { mutableStateOf(false) }
    var lastLoadedPage by remember { mutableIntStateOf(-1) } // Track the last page that was actually loaded
    var isLoadingMore by remember { mutableStateOf(false) }
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
    val MINIMUM_USER_COUNT = 4

    // Detect user changes and initialize data
    LaunchedEffect(currentUserId) {
        if (currentUserId != lastUserId) {
            Timber.tag("UserListView").d("User changed from $lastUserId to $currentUserId, initializing data")
            lastUserId = currentUserId
            lastLoadedPage = -1 // Reset last loaded page
            serverDepleted = false // Reset server depleted flag for new user
            
            // Load initial data (page 0)
            try {
                val initialUsers = fetchUsers(0)
                if (initialUsers.isNotEmpty()) {
                    lastLoadedPage = 0
                    Timber.tag("UserListView").d("Initial load completed: fetched ${initialUsers.size} users")
                } else {
                    serverDepleted = true
                    Timber.tag("UserListView").d("No initial users found, server depleted")
                }
            } catch (e: Exception) {
                Timber.tag("UserListView").e(e, "Error during initial load")
                serverDepleted = true
            }
        }
    }

    // Track scroll state changes
    LaunchedEffect(listState) {
        snapshotFlow { 
            val firstVisibleItem = listState.firstVisibleItemIndex
            val firstVisibleItemOffset = listState.firstVisibleItemScrollOffset
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            
            UserScrollState(
                isScrolling = firstVisibleItem > 0 || firstVisibleItemOffset > 0,
                direction = when {
                    firstVisibleItem > (lastVisibleItem - ITEM_INDEX_THRESHOLD) -> UserScrollDirection.DOWN
                    firstVisibleItem < (lastVisibleItem - ITEM_INDEX_THRESHOLD) -> UserScrollDirection.UP
                    else -> UserScrollDirection.NONE
                }
            )
        }.collect { scrollState ->
            onScrollStateChange?.invoke(scrollState)
        }
    }

    // Track scroll position changes and save them
    LaunchedEffect(listState) {
        snapshotFlow { Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect { position ->
                savedScrollPosition.value = position
            }
    }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshingAtTop,
        onRefresh = {
            coroutineScope.launch {
                isRefreshingAtTop = true
                try {
                    withContext(Dispatchers.IO) {
                        lastLoadedPage = -1 // Reset to page 0 for refresh
                        serverDepleted = false // Reset server depleted flag
                        fetchUsers(0)
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
            lastVisibleItem != null && lastVisibleItem.index >= layoutInfo.totalItemsCount - SCROLL_OFFSET_THRESHOLD
        }
    }

    // Infinite scroll
    LaunchedEffect(isAtBottom) {
        Timber.tag("UserListView").d("isAtBottom changed: $isAtBottom, isRefreshingAtBottom: $isRefreshingAtBottom, isLoadingMore: $isLoadingMore, users.size: ${users.size}, serverDepleted: $serverDepleted, lastLoadedPage: $lastLoadedPage")
        
        if (isAtBottom && !isRefreshingAtBottom && !isLoadingMore && users.size >= 4 && !serverDepleted && lastLoadedPage >= 0) { // Only trigger if we have enough users, server not depleted, and we've loaded at least one page
            Timber.tag("UserListView").d("Triggering load more...")
            isLoadingMore = true
            coroutineScope.launch {
                isRefreshingAtBottom = true
                try {
                    withContext(Dispatchers.IO) {
                        val nextPage = lastLoadedPage + 1 // Load the next page after the last loaded page
                        Timber.tag("UserListView").d("Loading more users, next page: $nextPage, lastLoadedPage: $lastLoadedPage, current users: ${users.size}")
                        val usersWithNulls = fetchUsers(nextPage)
                        
                        if (usersWithNulls.isEmpty()) {
                            // No more users available
                            serverDepleted = true
                            Timber.tag("UserListView").d("No more users available, server depleted")
                        } else if (usersWithNulls.size == TW_CONST.USER_BATCH_SIZE) {
                            // Full page loaded, continue loading
                            lastLoadedPage = nextPage
                            Timber.tag("UserListView").d("Full page loaded, continuing to page ${nextPage + 1}")
                        } else {
                            // Partial page loaded, server is depleted
                            serverDepleted = true
                            lastLoadedPage = nextPage
                            Timber.tag("UserListView").d("Partial page loaded (${usersWithNulls.size} users), server depleted")
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag("UserListView").e(e, "Error loading more users")
                    serverDepleted = true
                } finally {
                    isRefreshingAtBottom = false // Ensure state is reset
                    isLoadingMore = false
                    Timber.tag("UserListView").d("Load more completed, isRefreshingAtBottom set to false, isLoadingMore set to false")
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

/**
 * A simplified version of UserListView for basic user list display without advanced features.
 * 
 * @param users The list of user IDs to display
 * @param userItem Composable for rendering each user item
 * @param modifier Additional modifier for the component
 */
@Composable
fun SimpleUserListView(
    users: List<MimeiId>,
    userItem: @Composable (MimeiId) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 60.dp)
    ) {
        items(
            items = users,
            key = { it }
        ) { userId ->
            userItem(userId)
        }
    }
}