package us.fireshare.tweet.tweet

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.Tweet
import androidx.compose.ui.draw.alpha
import us.fireshare.tweet.navigation.BottomBarState
import us.fireshare.tweet.navigation.BottomNavigationBar
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.viewmodel.TweetViewModel
import us.fireshare.tweet.widget.ImageCacheManager
import us.fireshare.tweet.widget.LocalVideoCoordinator
import us.fireshare.tweet.widget.VideoPlaybackCoordinator
import kotlin.math.abs

/**
 * Hard cap on how long either comment spinner — the pull-to-refresh indicator or
 * the initial page-0 load — blocks the detail view. Both wait on blocking socket
 * calls with a 30s client timeout each: `refreshCommentsPaginated` issues one per
 * comment page plus one per uncached comment author, and `fetchComments` resolves
 * the tweet author first (up to three `fetchUser` attempts) before `get_comments`.
 * Without this cap a slow node keeps a spinner up for minutes.
 */
private const val MAX_SPINNER_MS = 6_000L

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
    // Activity-scoped and keyed by tweet id, so re-opening the same tweet reuses this
    // instance. It is NOT shared with the feed row that led here: TweetItem gives each row
    // its own ViewModelStore. Every first open therefore starts from the stub built below
    // and depends on TweetViewModel.init to fill it in.
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
    var isInitialLoading by remember { mutableStateOf(true) }
    var lastLoadedPage by remember { mutableIntStateOf(-1) } // Track last successfully loaded page
    // Set once page 0 has been kicked off. Gates the load-more pagination below so it
    // cannot run before the first page exists.
    var hasLoadedPage0 by remember { mutableStateOf(false) }
    // Track if we should stop pagination (when empty page is returned)
    var shouldStopPagination by remember { mutableStateOf(false) }
    
    // Prevent double-exit when back button is tapped multiple times
    var isNavigatingBack by remember { mutableStateOf(false) }

    fun navigateBackWithHomeFallback() {
        if (isNavigatingBack) return

        isNavigatingBack = true
        val didPop = navController.popBackStack()
        if (!didPop) {
            navController.navigate(NavTweet.TweetFeed) {
                popUpTo(navController.graph.startDestinationId) {
                    inclusive = true
                }
                launchSingleTop = true
            }
        }
    }

    BackHandler {
        navigateBackWithHomeFallback()
    }

    // Remember scroll position across recompositions and configuration changes
    val savedScrollPosition = rememberSaveable { mutableStateOf(Pair(0, 0)) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedScrollPosition.value.first,
        initialFirstVisibleItemScrollOffset = savedScrollPosition.value.second
    )
    val coroutineScope = rememberCoroutineScope()

    // Per-instance coordinator for comment video playback (iOS CommentsVideoPlaybackCoordinator pattern)
    // This ensures comment videos don't interfere with the feed's coordinator state.
    val commentsCoordinator = remember { VideoPlaybackCoordinator() }

    // Scroll-based top app bar visibility
    var previousFirstVisibleItemIndex by remember { mutableIntStateOf(0) }
    var previousScrollOffset by remember { mutableIntStateOf(0) }
    var showTopAppBar by remember { mutableStateOf(true) }
    
    // Animate top app bar visibility
    val topAppBarAlpha by animateFloatAsState(
        targetValue = if (showTopAppBar) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "topAppBarAlpha"
    )

    // Animate bottom navigation bar height (hide when scrolling down)
    val bottomNavHeight by animateDpAsState(
        targetValue = if (showTopAppBar) 72.dp else 0.dp,
        animationSpec = tween(durationMillis = 300),
        label = "bottomNavHeight"
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

            // Update comments coordinator scroll direction for video autoplay
            if (abs(indexDelta) >= 2 || abs(offsetDelta) > 200) {
                val approximateOffset = currentIndex * 1000f + currentOffset
                commentsCoordinator.updateScrollDirection(approximateOffset)
            }
        }
    }

    // Pull-to-refresh syncs the latest tweet state, then reloads comments.
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshingAtTop,
        onRefresh = {
            coroutineScope.launch {
                isRefreshingAtTop = true
                val started = System.currentTimeMillis()
                try {
                    // The fetch runs as its own job rather than inline: the Hprose
                    // calls underneath are blocking socket reads, so cancelling the
                    // coroutine cannot cut them short and a plain withTimeout would
                    // still sit here until they returned. Stop *waiting* at the cap
                    // instead — the job finishes in the background and its comments
                    // still land in the list when they arrive.
                    val refreshJob = coroutineScope.launch(Dispatchers.IO) {
                        viewModel.doResyncTweet()
                        viewModel.refreshCommentsPaginated()
                        lastLoadedPage = 0
                        shouldStopPagination = false
                    }
                    withTimeoutOrNull(MAX_SPINNER_MS) { refreshJob.join() }
                    val elapsed = System.currentTimeMillis() - started
                    if (elapsed < 500) delay(500 - elapsed)
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

    // Screen-open sequence. These steps are ordered, not independent, which is why they
    // share one effect: loadCachedCommentsForDetailOpen replaces the comment list
    // wholesale, so it has to finish before anything that merges into that list — both
    // the notification listener and the page-0 load do. Split across two effects they
    // raced, and a slow disk read landing last would drop freshly merged comments.
    LaunchedEffect(Unit) {
        // The comment list below belongs to THIS screen's tweet, so its cache bucket is
        // always tweetId — including when the tweet is itself a comment. `parentTweetId` is
        // the nav arg naming the tweet this comment hangs off; using it as the bucket made a
        // comment's detail view hydrate with (and write its own replies back into) the parent
        // tweet's comment list.
        viewModel.setCommentsCacheParentTweetId(tweetId)
        withContext(Dispatchers.IO) {
            viewModel.loadCachedCommentsForDetailOpen()
        }
        viewModel.setNotificationContext(context)
        viewModel.startListeningToNotifications()

        hasLoadedPage0 = true
        try {
            // Same shape as pull-to-refresh: the fetch gets its own job so the spinner
            // can be dropped at the cap without waiting on a blocking socket read. The
            // flag must be cleared from a finally — this effect runs once, so a throw or
            // a cancellation in between would otherwise leave the spinner up with
            // nothing left to take it down.
            val load = coroutineScope.launch(Dispatchers.IO) {
                val newCommentsCount = viewModel.loadComments(tweet, 0)
                lastLoadedPage = 0
                // If page 0 returned no comments, stop pagination immediately
                if (newCommentsCount == 0) {
                    shouldStopPagination = true
                    Timber.tag("TweetDetailScreen").d("Page 0 returned no comments for tweet ${tweet.mid}, stopping pagination")
                }
            }
            withTimeoutOrNull(MAX_SPINNER_MS) { load.join() }
        } finally {
            isInitialLoading = false
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

    // Track last pagination attempt to prevent rapid repeated calls
    var lastPaginationAttempt by remember { mutableLongStateOf(-1L) }

    // Brief "No more comments" label. Driven from inside the load-more
    // pagination effect below — only flashes after a user-driven fetch
    // returned zero new comments. The hasUserScrolled gate keeps the label
    // from appearing on open when the auto-pagination probe (triggered
    // because the entire short comment list is already visible) comes back
    // empty.
    var showNoMoreComments by remember { mutableStateOf(false) }
    var hasUserScrolledForPagination by remember { mutableStateOf(false) }
    var paginationScrollArmed by remember { mutableStateOf(false) }

    // Arm pagination only after a meaningful downward scroll gesture.
    // This avoids accidental auto-loadmore when the last comment is already near the viewport bottom.
    LaunchedEffect(listState, shouldStopPagination) {
        var prevIndex = listState.firstVisibleItemIndex
        var prevOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress
            )
        }.collect { (currentIndex, currentOffset, isScrollInProgress) ->
            val indexDelta = currentIndex - prevIndex
            val offsetDelta = currentOffset - prevOffset
            val isScrollingDown = indexDelta > 0 || (indexDelta == 0 && offsetDelta > 12)
            val isScrollingUp = indexDelta < 0 || (indexDelta == 0 && offsetDelta < -12)
            val movedEnough = abs(indexDelta) > 0 || abs(offsetDelta) > 24

            // Ignore layout-driven shifts (e.g., label insertion/removal). Only arm on real user drag.
            if (isScrollInProgress && isScrollingDown && movedEnough) {
                paginationScrollArmed = true
                hasUserScrolledForPagination = true
            }

            // Re-enable pagination only after user intentionally scrolls up again.
            if (isScrollInProgress && isScrollingUp && movedEnough && shouldStopPagination) {
                shouldStopPagination = false
                paginationScrollArmed = false
            }

            prevIndex = currentIndex
            prevOffset = currentOffset
        }
    }

    LaunchedEffect(showNoMoreComments) {
        if (showNoMoreComments) {
            delay(2000)
            showNoMoreComments = false
        }
    }

    // Infinite scroll for comments. Gated on `hasUserScrolled` so the auto
    // load-more probe never fires on detail-view open — otherwise a short
    // comment list (where the bottom is already in view) would silently
    // exhaust pagination before the user ever interacts, leaving subsequent
    // real scrolls with no spinner / no "no more" feedback.
    LaunchedEffect(isAtBottom, shouldStopPagination, comments.isEmpty(), hasUserScrolledForPagination) {
        if (shouldStopPagination || (comments.isEmpty() && hasLoadedPage0)) {
            return@LaunchedEffect
        }

        val now = System.currentTimeMillis()
        if (isAtBottom && hasUserScrolledForPagination && paginationScrollArmed && !isRefreshingAtBottom &&
            !isRefreshingAtTop && !isInitialLoading &&
            hasLoadedPage0 && !shouldStopPagination && comments.isNotEmpty() &&
            (now - lastPaginationAttempt) > 1000L) {

            lastPaginationAttempt = now
            paginationScrollArmed = false
            coroutineScope.launch {
                isRefreshingAtBottom = true
                val started = System.currentTimeMillis()
                val newCommentsCount = try {
                    // Yield one frame so the spinner item is composed into the
                    // LazyColumn before we scroll to it — otherwise the layout
                    // hasn't seen the new item yet.
                    withFrameNanos { }
                    val targetIndex = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                    runCatching { listState.animateScrollToItem(targetIndex) }

                    withContext(Dispatchers.IO) {
                        val nextPage = lastLoadedPage + 1
                        val count = viewModel.loadComments(tweet, nextPage)
                        if (count > 0) {
                            lastLoadedPage = nextPage
                            Timber.tag("TweetDetailScreen").d("Page $nextPage returned $count comments, continuing pagination")
                        } else {
                            Timber.tag("TweetDetailScreen").d("Page $nextPage returned no comments, stopping pagination")
                        }
                        count
                    }
                } finally {
                    // Keep the spinner on screen for at least 500ms so a fast
                    // network doesn't render it as a one-frame flash. Then drop
                    // it before deciding whether to surface the "no more" label.
                    val elapsed = System.currentTimeMillis() - started
                    if (elapsed < 500) delay(500 - elapsed)
                    isRefreshingAtBottom = false
                }
                if (newCommentsCount == 0) {
                    shouldStopPagination = true
                    if (hasUserScrolledForPagination) {
                        showNoMoreComments = true
                    }
                }
            }
        }
    }

    // On open and every five minutes, reload from the current provider without
    // triggering a cross-node refresh_tweet sync.
    // Comments are loaded once by the LaunchedEffect(tweet.mid) block above —
    // no duplicate page-0 fetch. The server (`get_comments`) now handles
    // cleaning up genuinely-stale comment IDs itself, so the client no longer
    // needs to sync individual comments.
    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) {
                viewModel.doReadTweet(allowRecoveryOnMissingPayload = true)
                Timber.tag("TweetDetailScreen").d("Initial READ completed on screen open")
            }
            while (isActive) {
                delay(5 * 60 * 1000)
                if (!isActive) break
                withContext(Dispatchers.IO) {
                    viewModel.doReadTweet()
                    Timber.tag("TweetDetailScreen").d("Periodic READ completed")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            Timber.tag("TweetDetailScreen").d("Periodic refresh cancelled (screen disposed)")
            throw e
        }
    }

    // Build video list from comments on the per-instance coordinator.
    // This doesn't touch the shared feed coordinator, so navigating back preserves the feed's state.
    LaunchedEffect(comments) {
        if (comments.isNotEmpty()) {
            commentsCoordinator.buildVideoList(comments)
            Timber.tag("TweetDetailScreen").d("Built comment video list with ${comments.size} comments")
        }
    }

    // Clear the per-instance coordinator when leaving.
    DisposableEffect(Unit) {
        onDispose {
            commentsCoordinator.clear()
            Timber.tag("TweetDetailScreen").d("Cleared comments VideoPlaybackCoordinator")
        }
    }

    // Provide the per-instance coordinator to all child composables (TweetItem, MediaGrid, VideoPreview)
    // so comment videos use the comments coordinator instead of the shared feed coordinator.
    androidx.compose.runtime.CompositionLocalProvider(LocalVideoCoordinator provides commentsCoordinator) {
    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = Color.Transparent,
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .clipToBounds()
                    .background(Color.Transparent)
            ) {
                // Animated TopAppBar – title + background only; insets handled by Box above
                TopAppBar(
                    windowInsets = WindowInsets(0),
                    modifier = Modifier
                        .offset(y = (-56 * (1 - topAppBarAlpha)).dp)
                        .graphicsLayer(alpha = if (topAppBarAlpha < 0.01f) 0f else topAppBarAlpha),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (topAppBarAlpha < 0.01f) Color.Transparent
                                         else MaterialTheme.colorScheme.primaryContainer.copy(alpha = topAppBarAlpha),
                        titleContentColor = MaterialTheme.colorScheme.primary.copy(alpha = topAppBarAlpha),
                    ),
                    title = {},
                    // Placeholder so the title stays centred (same width as the real back button)
                    navigationIcon = { Box(Modifier.size(48.dp)) },
                )
                // Always-visible back button – independent of scroll-driven alpha
                IconButton(
                    onClick = {
                        navigateBackWithHomeFallback()
                    },
                    enabled = !isNavigatingBack,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(bottomNavHeight)
                        .clipToBounds()
                ) {
                    BottomNavigationBar(
                        modifier = Modifier.alpha(BottomBarState.opacity),
                        navController = navController,
                        selectedIndex = 0 // Home tab
                    )
                }
            }
        },
    ) { innerPadding ->
        // Animate top padding so content doesn't jump when app bar hides/shows (prevents scroll feedback shake)
        val animatedTopPadding by animateDpAsState(
            targetValue = if (showTopAppBar) innerPadding.calculateTopPadding() else 0.dp,
            animationSpec = tween(durationMillis = 300),
            label = "topPadding"
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(
                    top = animatedTopPadding,
                    bottom = innerPadding.calculateBottomPadding(),
                    start = innerPadding.calculateLeftPadding(LocalLayoutDirection.current),
                    end = innerPadding.calculateRightPadding(LocalLayoutDirection.current)
                )
                .pullRefresh(pullRefreshState)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .onSizeChanged { size ->
                        // Update comments coordinator with viewport size
                        commentsCoordinator.updateViewportSize(
                            size.width.toFloat(),
                            size.height.toFloat()
                        )
                    },
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
                        onExpandReply = { isReplyBoxExpanded = true },
                        onVideoVisibilityChanged = { visible ->
                            commentsCoordinator.isPaused = visible
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 1.dp, bottom = 8.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                // Comment area, in priority order:
                //   comments in hand  -> show them (cache first, then the merged page 0)
                //   commentCount == 0 -> nothing to fetch, so the label shows at once
                //   load in flight    -> spinner, capped at MAX_SPINNER_MS
                //   otherwise         -> label
                // Use fixed-height containers to prevent layout shifts.
                if (comments.isNotEmpty()) {
                    itemsIndexed(
                        items = comments,
                        key = { _, comment -> comment.mid },
                        contentType = { _, _ -> "comment" }  // Help Compose reuse compositions efficiently
                    ) { index, comment ->
                        val isLast = index == comments.size - 1
                        CommentItem(
                            comment = comment,
                            parentTweetViewModel = viewModel,
                            parentEntry = parentEntry,
                            isLast = isLast
                        )

                        // Add divider after each comment (except the last one)
                        // Use index instead of indexOf for O(1) performance
                        if (!isLast) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 1.dp),
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                            )
                        }
                    }
                } else if (isInitialLoading && tweet.commentCount > 0) {
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
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ChatBubbleOutline,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                            )
                            Text(
                                text = stringResource(R.string.no_comments_yet),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }

                // Show bottom pagination spinner. Suppressed while pull-to-refresh
                // is running so its indicator is the only spinner on screen.
                if (isRefreshingAtBottom && !isRefreshingAtTop) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.5.dp
                            )
                        }
                    }
                }

                // "No more comments" flash — shown briefly when at the bottom with
                // nothing more to load (mirrors iOS CommentListView.showNoMoreComments).
                if (showNoMoreComments && !isRefreshingAtBottom) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.no_more_comments),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
    } // CompositionLocalProvider
}
