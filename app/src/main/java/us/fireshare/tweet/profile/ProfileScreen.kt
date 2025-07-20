package us.fireshare.tweet.profile

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.navigation.BottomNavigationBar
import us.fireshare.tweet.tweet.TweetItem
import us.fireshare.tweet.tweet.ScrollState
import us.fireshare.tweet.tweet.ScrollDirection
import us.fireshare.tweet.viewmodel.UserViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun ProfileScreen(
    navController: NavHostController,
    userId: MimeiId,
    parentEntry: NavBackStackEntry,
    appUserViewModel: UserViewModel,
) {
    val context = LocalContext.current
    val viewModel = if (userId == appUser.mid) appUserViewModel
        else hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(
            parentEntry, key = userId
        ) { factory ->
            factory.create(userId)
        }
    val tweets by viewModel.tweets.collectAsState()
    val pinnedTweets by viewModel.topTweets.collectAsState()
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // State to track scroll state for bottom bar opacity
    var scrollState by remember { mutableStateOf(ScrollState(false, ScrollDirection.NONE)) }
    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    
    // LazyListState for scroll tracking
    val listState = rememberLazyListState()
    
    // Calculate the transparency based on scrolling state
    var bottomBarTransparency by remember { mutableStateOf(0.98f) }
    
    // Track scroll state and update bottom bar transparency
    LaunchedEffect(listState) {
        var previousFirstVisibleItem = listState.firstVisibleItemIndex
        var previousScrollOffset = listState.firstVisibleItemScrollOffset

        snapshotFlow { 
            val isScrolling = listState.isScrollInProgress
            val firstVisibleItem = listState.firstVisibleItemIndex
            val scrollOffset = listState.firstVisibleItemScrollOffset
            
            // Determine scroll direction with threshold
            // When scrolling DOWN (content moves UP): firstVisibleItem increases or scrollOffset increases
            // When scrolling UP (content moves DOWN): firstVisibleItem decreases or scrollOffset decreases
            val direction = when {
                !isScrolling -> ScrollDirection.NONE
                firstVisibleItem > previousFirstVisibleItem || 
                (firstVisibleItem == previousFirstVisibleItem && scrollOffset > previousScrollOffset + 30) -> ScrollDirection.DOWN
                firstVisibleItem < previousFirstVisibleItem || 
                (scrollOffset < previousScrollOffset - 30) -> ScrollDirection.UP
                else -> ScrollDirection.NONE
            }
            
            // Update previous values
            previousFirstVisibleItem = firstVisibleItem
            previousScrollOffset = scrollOffset
            
            ScrollState(isScrolling, direction)
        }
        .collect { newScrollState ->
            scrollState = newScrollState
            
            // Update bottom bar transparency based on scroll direction
            when (newScrollState.direction) {
                ScrollDirection.UP -> {
                    // Scroll UP (content moves down): restore header and bottom bar
                    bottomBarTransparency = 0.98f
                }
                ScrollDirection.DOWN -> {
                    // Scroll DOWN (content moves up): collapse header and reduce bottom bar opacity
                    delay(100) // Small delay for smooth transition
                    if (scrollState.direction == ScrollDirection.DOWN) {
                        bottomBarTransparency = 0.2f
                    }
                }
                ScrollDirection.NONE -> {
                    // Idle state: keep current opacity, don't restore automatically
                    // Only restore when user starts scrolling up
                }
            }
        }
    }

    val activity = context as? Activity
    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    LaunchedEffect(Unit) {
        // load tweets only when user profile screen is opened.
        withContext(Dispatchers.IO) {
            viewModel.initLoad()
        }
    }
    
    // Start listening to tweet and comment notifications
    LaunchedEffect(Unit) {
        viewModel.startListeningToNotifications()
    }

    // Pull-to-refresh state
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = {
            coroutineScope.launch {
                isRefreshing = true
                try {
                    withContext(Dispatchers.IO) {
                        viewModel.fetchTweets(0)
                    }
                } finally {
                    isRefreshing = false
                }
            }
        }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = { ProfileTopAppBar(viewModel, navController, scrollBehavior) },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(innerPadding)
                    .pullRefresh(pullRefreshState)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    state = listState
                ) {
                    // Profile details section
                    item {
                        ProfileDetail(viewModel, navController)
                    }
                    
                    // Pinned tweets section
                    if (pinnedTweets.isNotEmpty()) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                tonalElevation = 100.dp,
                            ) {
                                Text(
                                    text = stringResource(R.string.pinToTop),
                                    modifier = Modifier.padding(
                                        start = 16.dp,
                                        top = 0.dp,
                                        bottom = 4.dp
                                    ),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                        items(pinnedTweets, key = { it.timestamp }) { tweet ->
                            if (!tweet.isPrivate || appUser.mid == tweet.authorId) {
                                TweetItem(tweet, parentEntry)
                            }
                        }
                        item {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 2.dp),
                                thickness = 2.dp,
                                color = MaterialTheme.colorScheme.primaryContainer
                            )
                        }
                    }
                    
                    // Regular tweets section
                    items(tweets, key = { it.mid }) { tweet ->
                        if (!tweet.isPrivate || appUser.mid == tweet.authorId) {
                            TweetItem(tweet, parentEntry)
                        }
                    }
                    
                    // Loading indicator at bottom for pagination
                    if (tweets.isNotEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                
                // Pull refresh indicator
                PullRefreshIndicator(
                    refreshing = isRefreshing,
                    state = pullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    backgroundColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                )
            }
        }
        
        // Place the BottomNavigationBar on top with opacity control
        BottomNavigationBar(
            Modifier
                .alpha(bottomBarTransparency)
                .align(Alignment.BottomCenter),
            navController,
            0
        )
    }
}