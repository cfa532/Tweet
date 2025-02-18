package com.fireshare.tweet.tweet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import com.fireshare.tweet.HproseInstance.preferenceHelper
import com.fireshare.tweet.R
import com.fireshare.tweet.navigation.BottomNavigationBar
import com.fireshare.tweet.viewmodel.TweetFeedViewModel

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
        TabItem(title = context.getString(R.string.your_followings)),
        TabItem(title = context.getString(R.string.recommendation))
    )
    // State to track if scrolling is in progress
    val isScrolling by viewModel.isScrolling.collectAsState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val listState = rememberLazyListState()

    // Check if the top bar is collapsed by checking the offset
    val isTopAppBarCollapsed by remember {
        derivedStateOf { scrollBehavior.state.collapsedFraction > 0.9f }
    }

    // Calculate the transparency based on scrolling state
    val bottomBarTransparency = rememberDelayedBottomBarTransparency(isScrolling)
    var selectedTabIndex by remember { mutableIntStateOf(preferenceHelper.getTweetFeedTabIndex()) }
    val pagerState = rememberPagerState(pageCount = { tabs.size })

    LaunchedEffect(selectedTabIndex) {
        pagerState.animateScrollToPage(selectedTabIndex)
    }
    LaunchedEffect(pagerState.currentPage) {
        selectedTabIndex = pagerState.currentPage
    }

    Box(modifier = Modifier.fillMaxSize()) { // Wrap everything in a Box
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = { MainTopAppBar(navController, listState, scrollBehavior) },
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
                    TabRow(
                        selectedTabIndex = selectedTabIndex,
                    ) {
                        tabs.forEachIndexed { index, item ->
                            Tab(
                                modifier = Modifier.height(36.dp), // Original height
                                selected = index == selectedTabIndex,
                                onClick = { selectedTabIndex = index },
                                text = { Text(
                                    color = Color.Gray,
                                    fontWeight = FontWeight.Light,
                                    text = item.title
                                ) },
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
                            0 -> FollowingsTweet(parentEntry, listState, scrollBehavior)
                            1 -> RecommendedTweetScreen()
                        }
                    }
                }
            }
        }
        // Place the BottomNavigationBar on top of the LazyColumn
        BottomNavigationBar(
            navController,
            selectedBottomBarItemIndex,
            Modifier
                .alpha(bottomBarTransparency.value)
                .align(Alignment.BottomCenter)
        )
    }
}