package us.fireshare.tweet.tweet

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.navigation.BottomNavigationBar
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.viewmodel.TweetViewModel
import us.fireshare.tweet.widget.ImageCacheManager

@RequiresApi(Build.VERSION_CODES.R)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun TweetDetailScreen(
    authorId: String,
    tweetId: String,
    parentEntry: NavBackStackEntry,
    parentTweetId: String? = null,
    parentAuthorId: String? = null
) {
    // Use activity scope to ensure same ViewModel instance is shared with TweetItem
    val activity = LocalActivity.current as ComponentActivity
    val viewModel = hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
        viewModelStoreOwner = activity, key = tweetId
    ) { factory ->
        factory.create(Tweet(mid = tweetId, authorId = authorId))
    }
    val tweet by viewModel.tweetState.collectAsState()
    val comments by viewModel.comments.collectAsState()
    val tweetDeleted by viewModel.tweetDeleted.collectAsState()
    val navController = LocalNavController.current
    val context = LocalContext.current

    // Navigate away if tweet gets deleted
    LaunchedEffect(tweetDeleted) {
        if (tweetDeleted) {
            Timber.tag("TweetDetailScreen").d("Tweet ${tweet.mid} was deleted, navigating back")
            navController.popBackStack()
        }
    }

    // Cancel image loading when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            ImageCacheManager.cancelImageLoadingForContext(context)
        }
    }

    // ReplyEditorBox state management
    var isReplyBoxExpanded by remember { mutableStateOf(false) }

    // Comment pagination and loading states (merged from CommentListView)
    var isRefreshingAtTop by remember { mutableStateOf(false) }
    var isRefreshingAtBottom by remember { mutableStateOf(false) }
    var currentPage by remember { mutableIntStateOf(0) }
    var isInitialLoading by remember { mutableStateOf(true) }
    var lastLoadedPage by remember { mutableIntStateOf(-1) } // Track last successfully loaded page
    
    // Prevent double-exit when back button is tapped multiple times
    var isNavigatingBack by remember { mutableStateOf(false) }

    // Remember scroll position across recompositions and configuration changes
    val savedScrollPosition = rememberSaveable { mutableStateOf(Pair(0, 0)) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedScrollPosition.value.first,
        initialFirstVisibleItemScrollOffset = savedScrollPosition.value.second
    )
    val coroutineScope = rememberCoroutineScope()

    // Scroll-based top app bar visibility
    var previousFirstVisibleItemIndex by remember { mutableStateOf(0) }
    var previousScrollOffset by remember { mutableStateOf(0) }
    var showTopAppBar by remember { mutableStateOf(true) }
    
    // Animate top app bar visibility
    val topAppBarAlpha by animateFloatAsState(
        targetValue = if (showTopAppBar) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "topAppBarAlpha"
    )

    // Track scroll direction with improved logic
    LaunchedEffect(listState) {
        snapshotFlow { 
            Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }.collect { (currentIndex, currentOffset) ->
            val indexDelta = currentIndex - previousFirstVisibleItemIndex
            val offsetDelta = currentOffset - previousScrollOffset
            
            // Update previous values for next comparison
            previousFirstVisibleItemIndex = currentIndex
            previousScrollOffset = currentOffset
            
            // Determine scroll direction based on both index and offset changes
            // Scrolling down: index increases OR (index same and offset increases significantly)
            // Scrolling up: index decreases OR (index same and offset decreases significantly)
            val isScrollingDown = indexDelta > 0 || (indexDelta == 0 && offsetDelta > 10)
            val isScrollingUp = indexDelta < 0 || (indexDelta == 0 && offsetDelta < -10)
            
            // Only change state if there's significant scroll movement
            if (isScrollingDown && abs(offsetDelta) > 5) {
                showTopAppBar = false
            } else if (isScrollingUp && abs(offsetDelta) > 5) {
                showTopAppBar = true
            }
        }
    }

    // Pull-to-refresh state
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshingAtTop,
        onRefresh = {
            coroutineScope.launch {
                isRefreshingAtTop = true
                try {
                    withContext(Dispatchers.IO) {
                        currentPage = 0 // Reset to page 0 for refresh
                        viewModel.loadComments(tweet, 0)
                        lastLoadedPage = 0
                    }
                } finally {
                    isRefreshingAtTop = false
                }
            }
        }
    )

    // Detect when at bottom for infinite scroll
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem != null && lastVisibleItem.index == layoutInfo.totalItemsCount - 1
        }
    }

    // Set context for notifications
    LaunchedEffect(Unit) {
        viewModel.setNotificationContext(context)
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

    // Track if we've loaded page 0 to prevent infinite reloads
    var hasLoadedPage0 by remember { mutableStateOf(false) }
    // Track if we should stop pagination (when empty page is returned)
    var shouldStopPagination by remember { mutableStateOf(false) }

    // Initial comment load when tweet is available - only load once per tweet
    LaunchedEffect(tweet.mid) {
        if (tweet.mid != null && !hasLoadedPage0) {
            hasLoadedPage0 = true
            isInitialLoading = true
            withContext(Dispatchers.IO) {
                val newCommentsCount = viewModel.loadComments(tweet, 0)
                currentPage = 0
                lastLoadedPage = 0
                // If page 0 returned no comments, stop pagination immediately
                if (newCommentsCount == 0) {
                    shouldStopPagination = true
                    Timber.tag("TweetDetailScreen").d("Page 0 returned no comments for tweet ${tweet.mid}, stopping pagination")
                }
            }
            isInitialLoading = false
        }
    }

    // Track last pagination attempt to prevent rapid repeated calls
    var lastPaginationAttempt by remember { mutableStateOf(-1L) }

    // Infinite scroll for comments - only trigger if we have comments and haven't stopped pagination
    LaunchedEffect(isAtBottom, shouldStopPagination, comments.isEmpty()) {
        // CRITICAL: Don't attempt pagination if we've confirmed there are no comments
        if (shouldStopPagination || (comments.isEmpty() && hasLoadedPage0)) {
            return@LaunchedEffect
        }
        
        val now = System.currentTimeMillis()
        // Add throttling: don't attempt pagination more than once per second
        // Only load if we're at bottom, have comments, and haven't stopped pagination
        if (isAtBottom && !isRefreshingAtBottom && !isInitialLoading && hasLoadedPage0 && 
            !shouldStopPagination && comments.isNotEmpty() && 
            (now - lastPaginationAttempt) > 1000L) {
            
            lastPaginationAttempt = now
            coroutineScope.launch {
                isRefreshingAtBottom = true
                try {
                    withContext(Dispatchers.IO) {
                        val nextPage = lastLoadedPage + 1
                        val newCommentsCount = viewModel.loadComments(tweet, nextPage)
                        
                        if (newCommentsCount == 0) {
                            // No new comments from this page, stop pagination
                            shouldStopPagination = true
                            Timber.tag("TweetDetailScreen").d("Page $nextPage returned no comments, stopping pagination")
                        } else {
                            // Got new comments, continue pagination
                            currentPage = nextPage
                            lastLoadedPage = nextPage
                            Timber.tag("TweetDetailScreen").d("Page $nextPage returned $newCommentsCount comments, continuing pagination")
                        }
                    }
                } finally {
                    isRefreshingAtBottom = false
                }
            }
        }
    }

    // Refresh handler: refresh immediately when opened, then every 5 minutes
    LaunchedEffect(Unit) {
        // Refresh immediately when screen is opened
        withContext(Dispatchers.IO) {
            viewModel.refreshTweetAndOriginal()
            Timber.tag("TweetDetailScreen").d("Initial refresh completed on screen open")
        }
        // Then refresh periodically every 5 minutes
        while (true) {
            delay(5 * 60 * 1000)
            withContext(Dispatchers.IO) {
                viewModel.refreshTweetAndOriginal()
                Timber.tag("TweetDetailScreen").d("Periodic refresh completed")
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.startListeningToNotifications()
    }


    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clipToBounds()
                    .background(Color.Transparent)
            ) {
                TopAppBar(
                    modifier = Modifier
                        .offset(y = (-56 * (1 - topAppBarAlpha)).dp)
                        .graphicsLayer(alpha = if (topAppBarAlpha < 0.01f) 0f else topAppBarAlpha),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (topAppBarAlpha < 0.01f) Color.Transparent else MaterialTheme.colorScheme.primaryContainer.copy(alpha = topAppBarAlpha),
                        titleContentColor = MaterialTheme.colorScheme.primary.copy(alpha = topAppBarAlpha),
                    ),
                title = {
                    Text(
                        text = "Tweet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = topAppBarAlpha)
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { 
                            if (!isNavigatingBack) {
                                isNavigatingBack = true
                                navController.popBackStack()
                            }
                        },
                        enabled = topAppBarAlpha > 0.5f && !isNavigatingBack
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = topAppBarAlpha)
                        )
                    }
                },
            )
            }
        },
        bottomBar = {
            Column {
                ReplyEditorBox(
                    isExpanded = isReplyBoxExpanded,
                    onExpandedChange = { isExpanded ->
                        isReplyBoxExpanded = isExpanded
                    },
                    onReplySubmit = { replyText, attachments ->
                        if (replyText.isNotBlank() || attachments.isNotEmpty()) {
                            viewModel.uploadComment(context, replyText, attachments)
                        }
                    }
                )
                BottomNavigationBar(
                    navController = navController,
                    selectedIndex = 0 // Home tab
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(
                    top = if (showTopAppBar) innerPadding.calculateTopPadding() else 0.dp,
                    bottom = innerPadding.calculateBottomPadding(),
                    start = innerPadding.calculateLeftPadding(LocalLayoutDirection.current),
                    end = innerPadding.calculateRightPadding(LocalLayoutDirection.current)
                )
                .pullRefresh(pullRefreshState)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                state = listState,
                contentPadding = PaddingValues(bottom = 60.dp)
            ) {
                // Tweet detail at the top
                item {
                    TweetDetailBody(
                        viewModel = viewModel,
                        parentEntry = parentEntry,
                        parentTweetId = parentTweetId,
                        parentAuthorId = parentAuthorId,
                        onExpandReply = { }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 1.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                // Show initial loading spinner for comments
                // Use fixed-height container to prevent layout shifts
                if (isInitialLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 4.dp
                            )
                        }
                    }
                } else {
                    // Show comments when loading is complete
                    itemsIndexed(
                        items = comments,
                        key = { _, comment -> comment.mid },
                        contentType = { _, _ -> "comment" }  // Help Compose reuse compositions efficiently
                    ) { index, comment ->
                        CommentItem(
                            comment = comment,
                            parentTweetViewModel = viewModel,
                            parentEntry = parentEntry
                        )

                        // Add divider after each comment (except the last one)
                        // Use index instead of indexOf for O(1) performance
                        if (index < comments.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 1.dp),
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                            )
                        }
                    }

                    // Show top refresh spinner
                    // Use fixed-height container to prevent layout shifts
                    if (isRefreshingAtTop) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 60.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 4.dp
                                )
                            }
                        }
                    }

                    // Show bottom pagination spinner
                    // Use fixed-height container to prevent layout shifts
                    if (isRefreshingAtBottom) {
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
            }

            // Pull-to-refresh indicator
            PullRefreshIndicator(
                refreshing = isRefreshingAtTop,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                backgroundColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            )
        }
    }
}