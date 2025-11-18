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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
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
    parentEntry: NavBackStackEntry
) {
    val viewModel = hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
        parentEntry, key = tweetId
    ) { factory ->
        factory.create(Tweet(mid = tweetId, authorId = authorId))
    }
    val tweet by viewModel.tweetState.collectAsState()
    val comments by viewModel.comments.collectAsState()
    val navController = LocalNavController.current
    val context = LocalContext.current

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
        snapshotFlow { listState.firstVisibleItemScrollOffset }
            .collect { currentOffset ->
                val scrollDelta = currentOffset - previousScrollOffset
                
                // Only change state if there's significant scroll movement
                if (scrollDelta > 5) { // Scrolling down - hide top bar
                    showTopAppBar = false
                } else if (scrollDelta < -5) { // Scrolling up - show top bar
                    showTopAppBar = true
                }
                // If scrollDelta is small (between -5 and 5), maintain current state
                // This prevents the top bar from popping back when scrolling stops
                
                previousScrollOffset = currentOffset
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

    // Track initial loading completion
    LaunchedEffect(comments) {
        if (isInitialLoading) {
            // Set loading to false when we receive any response (empty or not)
            isInitialLoading = false
        }
    }

    // Initial comment load when tweet is available
    LaunchedEffect(tweet.mid) {
        if (tweet.mid != null && isInitialLoading) {
            withContext(Dispatchers.IO) {
                viewModel.loadComments(tweet, 0)
                lastLoadedPage = 0
            }
        }
    }

    // Infinite scroll for comments
    LaunchedEffect(isAtBottom) {
        if (isAtBottom && !isRefreshingAtBottom && !isInitialLoading && currentPage > lastLoadedPage) {
            coroutineScope.launch {
                isRefreshingAtBottom = true
                try {
                    withContext(Dispatchers.IO) {
                        val pageToLoad = currentPage + 1
                        viewModel.loadComments(tweet, pageToLoad)
                        lastLoadedPage = pageToLoad
                        currentPage = pageToLoad
                    }
                } finally {
                    isRefreshingAtBottom = false
                }
            }
        }
    }

    // Refresh handler: initial refresh after 3 seconds, then every 5 minutes
    LaunchedEffect(Unit) {
        delay(3000L)
        withContext(Dispatchers.IO) {
            viewModel.refreshTweetAndOriginal()
            Timber.tag("TweetDetailScreen").d("Initial refresh completed after 3 seconds")
        }
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
                .background(Color.Transparent)
                .padding(innerPadding)
                .pullRefresh(pullRefreshState)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(y = if (showTopAppBar) 0.dp else -innerPadding.calculateTopPadding())
                    .background(MaterialTheme.colorScheme.background),
                state = listState,
                contentPadding = PaddingValues(bottom = 60.dp)
            ) {
                // Tweet detail at the top
                item {
                    TweetDetailBody(
                        viewModel = viewModel,
                        parentEntry = parentEntry,
                        onExpandReply = { isReplyBoxExpanded = true }
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