package us.fireshare.tweet.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
 * Receives user IDs in batches and progressively shows more user items.
 * Each user item is responsible for retrieving its own data using the provided user ID.
 *
 * @param fetchUserIds Function to fetch user IDs for a specific batch number (returns List<MimeiId>)
 * @param scrollBehavior Optional TopAppBar scroll behavior
 * @param contentPadding Padding for the list content
 * @param modifier Modifier for the component
 * @param userItem Composable for rendering each user item (receives user ID)
 * @param onScrollStateChange Optional callback for scroll state changes
 * @param currentUserId Optional current user ID for change detection
 */
@Composable
@OptIn(ExperimentalMaterialApi::class, ExperimentalMaterial3Api::class)
fun UserListView(
    fetchUserIds: suspend (Int) -> List<MimeiId>,
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = 60.dp),
    userItem: @Composable (MimeiId) -> Unit,
    onScrollStateChange: ((UserScrollState) -> Unit)? = null,
    currentUserId: MimeiId? = null,
) {
    // Internal state management
    var isRefreshingAtTop by remember { mutableStateOf(false) }
    var isRefreshingAtBottom by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var lastUserId by remember { mutableStateOf(currentUserId) }
    var serverDepleted by remember { mutableStateOf(false) }
    var allUserIds by remember { mutableStateOf(emptyList<MimeiId>()) }
    var displayedUserCount by remember { mutableIntStateOf(0) }
    
    // Remember scroll position across recompositions and configuration changes
    val savedScrollPosition = rememberSaveable { mutableStateOf(Pair(0, 0)) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedScrollPosition.value.first,
        initialFirstVisibleItemScrollOffset = savedScrollPosition.value.second
    )
    val coroutineScope = rememberCoroutineScope()

    val SCROLL_OFFSET_THRESHOLD = 8
    val ITEM_INDEX_THRESHOLD = 1

    // Detect user changes and initialize data
    LaunchedEffect(currentUserId) {
        Timber.tag("UserListView").d("LaunchedEffect triggered with currentUserId: $currentUserId, lastUserId: $lastUserId")
        if (currentUserId != lastUserId || allUserIds.isEmpty()) {
            Timber.tag("UserListView").d("User changed from $lastUserId to $currentUserId, initializing data")
            lastUserId = currentUserId
            allUserIds = emptyList()
            displayedUserCount = 0
            serverDepleted = false
            
            // Load initial batch
            try {
                Timber.tag("UserListView").d("Calling fetchUserIds(0)")
                val initialUserIds = fetchUserIds(0)
                Timber.tag("UserListView").d("fetchUserIds(0) returned: ${initialUserIds.size} user IDs")
                if (initialUserIds.isNotEmpty()) {
                    // Filter out invalid user IDs (null, empty, or guest IDs)
                    val validUserIds = initialUserIds.filter { userId ->
                        userId.isNotEmpty() && userId != TW_CONST.GUEST_ID
                    }
                    // Ensure no duplicates in initial load
                    val uniqueInitialUserIds = validUserIds.distinct()
                    allUserIds = uniqueInitialUserIds
                    displayedUserCount = minOf(uniqueInitialUserIds.size, TW_CONST.USER_BATCH_SIZE)
                    Timber.tag("UserListView").d("Initial load completed: fetched ${initialUserIds.size} user IDs, filtered to ${validUserIds.size} valid IDs, ${uniqueInitialUserIds.size} unique user IDs, displaying $displayedUserCount")
                } else {
                    serverDepleted = true
                    Timber.tag("UserListView").d("No initial user IDs found, server depleted")
                }
            } catch (e: Exception) {
                Timber.tag("UserListView").e(e, "Error during initial load")
                serverDepleted = true
            }
        } else {
            Timber.tag("UserListView").d("No change detected, skipping initialization")
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
                        // Reset and reload initial batch
                        allUserIds = emptyList()
                        displayedUserCount = 0
                        serverDepleted = false
                        val initialUserIds = fetchUserIds(0)
                        if (initialUserIds.isNotEmpty()) {
                            allUserIds = initialUserIds
                            displayedUserCount = minOf(initialUserIds.size, TW_CONST.USER_BATCH_SIZE)
                        } else {
                            serverDepleted = true
                        }
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

    // Infinite scroll - load more user IDs in batches
    LaunchedEffect(isAtBottom) {
        Timber.tag("UserListView").d("isAtBottom changed: $isAtBottom, isRefreshingAtBottom: $isRefreshingAtBottom, isLoadingMore: $isLoadingMore, displayedUserCount: $displayedUserCount, allUserIds.size: ${allUserIds.size}, serverDepleted: $serverDepleted")
        
        if (isAtBottom && !isRefreshingAtBottom && !isLoadingMore && !serverDepleted) {
            Timber.tag("UserListView").d("Triggering load more...")
            isLoadingMore = true
            coroutineScope.launch {
                isRefreshingAtBottom = true
                try {
                    withContext(Dispatchers.IO) {
                        val nextBatchNumber = (allUserIds.size / TW_CONST.USER_BATCH_SIZE)
                        Timber.tag("UserListView").d("Loading more user IDs, next batch: $nextBatchNumber")
                        val newUserIds = fetchUserIds(nextBatchNumber)
                        
                        if (newUserIds.isEmpty()) {
                            // No more user IDs available
                            serverDepleted = true
                            Timber.tag("UserListView").d("No more user IDs available, server depleted")
                        } else {
                            // Filter out invalid user IDs (null, empty, or guest IDs)
                            val validNewUserIds = newUserIds.filter { userId ->
                                userId.isNotEmpty() && userId != TW_CONST.GUEST_ID
                            }
                            // Add new user IDs to the list, ensuring no duplicates
                            val uniqueNewUserIds = validNewUserIds.filter { newId -> !allUserIds.contains(newId) }
                            if (uniqueNewUserIds.isNotEmpty()) {
                                allUserIds = allUserIds + uniqueNewUserIds
                                displayedUserCount = minOf(displayedUserCount + TW_CONST.USER_BATCH_SIZE, allUserIds.size)
                                Timber.tag("UserListView").d("Added ${uniqueNewUserIds.size} new unique user IDs (from ${newUserIds.size} fetched, ${validNewUserIds.size} valid), total: ${allUserIds.size}, displayed: $displayedUserCount")
                            } else {
                                // All new IDs were duplicates, mark as depleted
                                serverDepleted = true
                                Timber.tag("UserListView").d("All new user IDs were duplicates, server depleted")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag("UserListView").e(e, "Error loading more user IDs")
                    serverDepleted = true
                } finally {
                    isRefreshingAtBottom = false
                    isLoadingMore = false
                    Timber.tag("UserListView").d("Load more completed, isRefreshingAtBottom set to false, isLoadingMore set to false")
                }
            }
        }
    }

    // Get the subset of user IDs to display, ensuring uniqueness
    val displayedUserIds = allUserIds.take(displayedUserCount).distinct()

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
                items = displayedUserIds,
                key = { userId -> userId },  // userId is already unique - no need for indexOf
                contentType = { "user" }  // Help Compose reuse compositions efficiently
            ) { userId ->
                userItem(userId)
            }
            
            // Show loading indicator if there are more user IDs to load
            // Use fixed-height container to prevent layout shifts
            if (isRefreshingAtBottom || (!serverDepleted && displayedUserCount < allUserIds.size)) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
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
