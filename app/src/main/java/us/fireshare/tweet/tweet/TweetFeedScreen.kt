package us.fireshare.tweet.tweet

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.fireshare.tweet.HproseInstance.preferenceHelper
import us.fireshare.tweet.R
import us.fireshare.tweet.navigation.BottomBarState
import us.fireshare.tweet.navigation.BottomNavigationBar
import us.fireshare.tweet.viewmodel.TweetFeedViewModel
import androidx.compose.ui.res.stringResource

data class TabItem(
    val title: String = "Followings",
    val unselectedIcon :ImageVector? = null,
    val selectedIcon :ImageVector? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TweetFeedScreen(
    navController: NavHostController,
    parentEntry: NavBackStackEntry,
    selectedBottomBarItemIndex: Int,
    viewModel: TweetFeedViewModel
) {
    val context = LocalContext.current
    val tabs = listOf(
        TabItem(title = stringResource(R.string.your_followings)),
        TabItem(title = stringResource(R.string.recommendation))
    )

    // Set context reference for the ViewModel (for string resources)
    LaunchedEffect(context) {
        viewModel.setContext(context)
    }
    
    // Initialize the ViewModel when the screen is first displayed
    // This ensures HproseInstance is ready before tweet loading begins
    LaunchedEffect(Unit) {
        viewModel.initialize()
    }

    // Collect initialization state to show loading indicator
    val initState by viewModel.initState.collectAsState()
    
    // Collect retry message
    val retryMessage by viewModel.retryMessage.collectAsState()

    // State to track scroll state for bottom bar opacity
    var scrollState by remember { mutableStateOf(ScrollState(false, ScrollDirection.NONE)) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // Check if the top bar is collapsed by checking the offset
    val isTopAppBarCollapsed by remember {
        derivedStateOf { scrollBehavior.state.collapsedFraction > 0.9f }
    }

    val coroutineScope = rememberCoroutineScope()

    // Track current direction for transparency
    var currentScrollDirection by remember { mutableStateOf(ScrollDirection.NONE) }

    var selectedTabIndex by remember { mutableIntStateOf(preferenceHelper.getTweetFeedTabIndex()) }
    var scrollToTopTrigger by remember { mutableIntStateOf(0) }
    // Timestamp when scroll-to-top was triggered; suppresses scroll-direction fade for 500ms
    var scrollToTopTime by remember { mutableStateOf(0L) }

    // Reset toolbar and navbar state when scroll-to-top is triggered
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) {
            scrollToTopTime = System.currentTimeMillis()
            // Reset navbar to fully visible
            BottomBarState.opacity = 0.98f
            currentScrollDirection = ScrollDirection.NONE
            // Reset top bar scroll state (expand the toolbar)
            scrollBehavior.state.heightOffset = 0f
        }
    }

    // When home button is tapped from ANY screen (including TweetDetail, Profile, etc.),
    // BottomBarState.homeTapTrigger increments. Reset toolbar + scroll to top here.
    LaunchedEffect(BottomBarState.homeTapTrigger) {
        if (BottomBarState.homeTapTrigger > 0) {
            scrollToTopTrigger++
        }
    }

    val pagerState = rememberPagerState(pageCount = { tabs.size })

    LaunchedEffect(selectedTabIndex) {
        pagerState.animateScrollToPage(selectedTabIndex)
    }
    LaunchedEffect(pagerState.currentPage) {
        selectedTabIndex = pagerState.currentPage
    }

    // Set notification context and start listening to notifications
    LaunchedEffect(Unit) {
        viewModel.setNotificationContext(context)
    }

    Box(modifier = Modifier.fillMaxSize()) { // Wrap everything in a Box
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                MainTopAppBar(
                    navController,
                    onScrollToTop = {
                        scrollToTopTrigger += 1
                    },
                    scrollBehavior = scrollBehavior
                )
            },
            bottomBar = {} // Remove bottomBar from Scaffold
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Use AnimatedVisibility to show/hide the TabRow
                AnimatedVisibility(
                    visible = !isTopAppBarCollapsed,
                    enter = fadeIn(animationSpec = tween(durationMillis = 150)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 150))
                ) {
                    PrimaryTabRow(
                        modifier = Modifier.padding(bottom = 8.dp),
                        selectedTabIndex = selectedTabIndex,
                    ) {
                        tabs.forEachIndexed { index, item ->
                            Tab(
                                modifier = Modifier
                                    .height(36.dp),
                                selected = index == selectedTabIndex,
                                onClick = { selectedTabIndex = index },
                                text = {
                                    Text(
                                        color = Color.Gray,
                                        fontWeight = FontWeight.Light,
                                        text = item.title
                                    )
                                },
                            )
                        }
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) { index ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        when (index) {
                            0 -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    if (initState) {
                                        // Show loading indicator while initializing
                                        Column(
                                            modifier = Modifier.padding(16.dp),
                                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                                        ) {
                                            androidx.compose.material3.CircularProgressIndicator()
                                            // Show retry message if retrying
                                            retryMessage?.let { message ->
                                                androidx.compose.material3.Text(
                                                    text = message,
                                                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                    modifier = Modifier.padding(top = 8.dp)
                                                )
                                            }
                                        }
                                    } else {
                                        FollowingsTweet(
                                            parentEntry,
                                            scrollBehavior,
                                            viewModel,
                                            onScrollStateChange = { newScrollState ->
                                                scrollState = newScrollState

                                                // Ignore NONE - when scroll stops, keep current opacity
                                                if (newScrollState.direction == ScrollDirection.NONE) {
                                                    return@FollowingsTweet
                                                }

                                                // Suppress scroll-direction opacity changes briefly after scroll-to-top
                                                // to prevent the programmatic scroll from overriding the reset
                                                if (System.currentTimeMillis() - scrollToTopTime < 500) {
                                                    return@FollowingsTweet
                                                }

                                                // Only process if direction actually changed
                                                if (newScrollState.direction == currentScrollDirection) {
                                                    return@FollowingsTweet
                                                }

                                                // Update tracked direction
                                                currentScrollDirection = newScrollState.direction

                                                // Apply transparency immediately based on direction
                                                when (newScrollState.direction) {
                                                    ScrollDirection.UP -> {
                                                        // Scroll UP (content moves down): restore navbar
                                                        BottomBarState.opacity = 0.98f
                                                    }

                                                    ScrollDirection.DOWN -> {
                                                        // Scroll DOWN (content moves up): fade navbar
                                                        BottomBarState.opacity = 0.2f
                                                    }

                                                    ScrollDirection.NONE -> {
                                                        // Already filtered out above
                                                    }
                                                }
                                            },
                                            scrollToTopTrigger = scrollToTopTrigger
                                        )
                                    }
                                } else {
                                    // Fallback for API < 30
                                    Text("Followings not available on this Android version")
                                }
                            }

                            1 -> RecommendedTweetScreen()
                        }
                    }
                }
            }
        }
        // Place the BottomNavigationBar on top with opacity control
        BottomNavigationBar(
            Modifier
                .alpha(BottomBarState.opacity)
                .align(Alignment.BottomCenter),
            navController,
            selectedBottomBarItemIndex,
            onScrollToTop = { scrollToTopTrigger += 1 }
        )
    }
}