package us.fireshare.tweet.profile

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
import us.fireshare.tweet.tweet.TweetListView
import us.fireshare.tweet.viewmodel.UserViewModel

@OptIn(ExperimentalMaterial3Api::class)
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
    val pinnedTweets by viewModel.pinnedTweets.collectAsState()
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // State to track scroll state for bottom bar opacity
    var scrollState by remember { mutableStateOf(ScrollState(false, ScrollDirection.NONE)) }
    val coroutineScope = rememberCoroutineScope()
    
    // Calculate the transparency based on scrolling state
    var bottomBarTransparency by remember { mutableStateOf(0.98f) }

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
                // Profile content with TweetListView
                ProfileContentWithTweetListView(
                    viewModel = viewModel,
                    navController = navController,
                    parentEntry = parentEntry,
                    scrollBehavior = scrollBehavior,
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
 * Custom composable that combines profile details, pinned tweets, and TweetListView
 * for a unified profile experience with proper pagination and refresh functionality.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ProfileContentWithTweetListView(
    viewModel: UserViewModel,
    navController: NavHostController,
    parentEntry: NavBackStackEntry,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    onScrollStateChange: (ScrollState) -> Unit
) {
    val tweets by viewModel.tweets.collectAsState()
    val pinnedTweets by viewModel.pinnedTweets.collectAsState()
    val user by viewModel.user.collectAsState()
    
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(bottom = 60.dp)
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
        
        // TweetListView for regular tweets
        item {
            TweetListView(
                tweets = tweets,
                fetchTweets = { pageNumber ->
                    viewModel.fetchTweets(pageNumber)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 2000.dp), // Set maximum height to prevent infinite constraints
                scrollBehavior = null, // We handle scroll behavior at the LazyColumn level
                contentPadding = PaddingValues(0.dp), // No padding since we're inside a LazyColumn item
                showPrivateTweets = true, // Show private tweets for profile view
                parentEntry = parentEntry,
                onScrollStateChange = onScrollStateChange,
                currentUserId = user.mid,
                onTweetUnavailable = { tweetId ->
                    // Handle when a tweet becomes unavailable
                    viewModel.removeTweetFromAllLists(tweetId)
                }
            )
        }
    }
}