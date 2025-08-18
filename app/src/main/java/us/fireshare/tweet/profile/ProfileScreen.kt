package us.fireshare.tweet.profile

import android.app.Activity
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.navigation.BottomNavigationBar
import us.fireshare.tweet.service.OrientationManager
import us.fireshare.tweet.tweet.ScrollDirection
import us.fireshare.tweet.tweet.ScrollState
import us.fireshare.tweet.tweet.TweetItem
import us.fireshare.tweet.tweet.TweetListView
import us.fireshare.tweet.viewmodel.UserViewModel

@RequiresApi(Build.VERSION_CODES.R)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun ProfileScreen(
    navController: NavHostController,
    userId: MimeiId,
    parentEntry: NavBackStackEntry,
    appUserViewModel: UserViewModel,
) {
    val context = LocalContext.current

    // Create ViewModel - move hiltViewModel outside of remember
    val viewModel = if (userId == appUser.mid) appUserViewModel
    else hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(
        parentEntry, key = userId
    ) { factory ->
        factory.create(userId)
    }

    val initState by viewModel.initState.collectAsState()
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // State to track scroll state for bottom bar opacity
    var scrollState by remember { mutableStateOf(ScrollState(false, ScrollDirection.NONE)) }
    val coroutineScope = rememberCoroutineScope()

    // Calculate the transparency based on scrolling state
    var bottomBarTransparency by remember { mutableStateOf(0.98f) }

    val activity = context as? Activity
    activity?.let { OrientationManager.lockToPortrait(it) }

    LaunchedEffect(Unit) {
        // load tweets only when user profile screen is opened.
        withContext(Dispatchers.IO) {
            viewModel.initLoad()
        }
    }

    // Refresh user data when returning to ProfileScreen (e.g., after editing profile)
    LaunchedEffect(Unit) {
        // Refresh user data to ensure it's up to date
        withContext(Dispatchers.IO) {
            viewModel.refreshUserData()
        }
    }

    // Additional refresh when the screen becomes active again
    LaunchedEffect(navController.currentBackStackEntryAsState().value?.destination?.route) {
        // Refresh user data when navigation changes (e.g., returning from EditProfileScreen)
        withContext(Dispatchers.IO) {
            viewModel.refreshUserData()
        }
    }



    // Start listening to tweet and comment notifications
    LaunchedEffect(Unit) {
        viewModel.startListeningToNotifications()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = { ProfileTopAppBar(viewModel, navController, scrollBehavior) },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(innerPadding)
            ) {
                ProfileContentWithTweetListView(
                    viewModel = viewModel,
                    navController = navController,
                    parentEntry = parentEntry,
                    scrollBehavior = scrollBehavior,
                    initState = initState,
                    userId = userId,
                    onScrollStateChange = { newScrollState ->
                            scrollState = newScrollState

                            // Update bottom bar transparency based on scroll direction
                            when (newScrollState.direction) {
                                ScrollDirection.UP -> {
                                    // Scroll UP (content moves down): restore header and bottom bar
                                    bottomBarTransparency = 0.98f
                                }

                                ScrollDirection.DOWN -> {
                                    // Scroll DOWN (content moves up): collapse header and reduce bottom bar opacity
                                    coroutineScope.launch {
                                        withContext(Dispatchers.Main) {
                                            delay(100) // Small delay for smooth transition
                                            if (scrollState.direction == ScrollDirection.DOWN) {
                                                bottomBarTransparency = 0.2f
                                            }
                                        }
                                    }
                                }

                                ScrollDirection.NONE -> {
                                    // Idle state: keep current opacity, don't restore automatically
                                    // Only restore when user starts scrolling up
                                }
                            }
                        }
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

/**
 * Custom composable that combines profile details, pinned tweets, and regular tweets
 * using the proper TweetListView component for pagination.
 */
@RequiresApi(Build.VERSION_CODES.R)
@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
private fun ProfileContentWithTweetListView(
    viewModel: UserViewModel,
    navController: NavHostController,
    parentEntry: NavBackStackEntry,
    scrollBehavior: TopAppBarScrollBehavior,
    initState: Boolean,
    userId: MimeiId, // Add userId parameter
    onScrollStateChange: (ScrollState) -> Unit,

) {
    val tweets by viewModel.tweets.collectAsState()
    val pinnedTweets by viewModel.pinnedTweets.collectAsState()

    // Create header content with profile details and pinned tweets - make it stable
    // Use remember with no dependencies to create a stable reference that doesn't change
    val headerContent: @Composable () -> Unit = remember {
        {
            Column {
                ProfileDetail(viewModel, navController)

                // Pinned tweets section (if any)
                if (pinnedTweets.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.pinToTop),
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    top = 4.dp,
                                    bottom = 0.dp
                                ),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                                        pinnedTweets.forEach { tweet ->
                    TweetItem(tweet, parentEntry, context = if (userId == appUser.mid) "appUserProfile" else "default")
                }

                        HorizontalDivider(
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .padding(horizontal = 0.dp),
                            thickness = 2.dp,
                            color = MaterialTheme.colorScheme.primaryContainer
                        )
                    }
            }
        }
    }

    // Create pull refresh state for initial loading
    val pullRefreshState = rememberPullRefreshState(
        refreshing = initState,
        onRefresh = { /* No-op for initial load */ }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pullRefresh(pullRefreshState)
    ) {
        // Use TweetListView with header content for unified scrolling
        TweetListView(
                tweets = tweets,
                fetchTweets = { pageNumber ->
                    viewModel.fetchTweets(pageNumber)
                },
                modifier = Modifier.fillMaxSize(),
                scrollBehavior = scrollBehavior,
                contentPadding = PaddingValues(bottom = 60.dp),
                showPrivateTweets = true, // Show private tweets in profile
                parentEntry = parentEntry,
                onScrollStateChange = onScrollStateChange,
                currentUserId = userId, // Use userId directly
                onTweetUnavailable = { tweetId ->
                    viewModel.removeTweetFromAllLists(tweetId)
                },
                headerContent = headerContent,
                restoreScrollPosition = false, // Disable scroll position restoration to prevent jumping back
                context = if (userId == appUser.mid) "appUserProfile" else "default"
            )

        // Show pull-to-refresh style indicator during initial load
        if (initState) {
            PullRefreshIndicator(
                refreshing = true,
                state = pullRefreshState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp),
                backgroundColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            )
        }
    }
}