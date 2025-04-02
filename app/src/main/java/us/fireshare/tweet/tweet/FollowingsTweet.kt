package us.fireshare.tweet.tweet

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.viewmodel.TweetFeedViewModel

/**
 * Tweets of the followings of current user.
 * */
@Composable
@OptIn(ExperimentalMaterialApi::class, ExperimentalMaterial3Api::class)
fun FollowingsTweet(
    parentEntry: NavBackStackEntry,
    listState: LazyListState,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: TweetFeedViewModel
) {
    val refreshingAtTop by viewModel.isRefreshingAtTop.collectAsState()
    val tweets by viewModel.tweets.collectAsState()
    val pullRefreshState = rememberPullRefreshState(refreshingAtTop, {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            viewModel.loadNewerTweets()
        }
    })
    val refreshingAtBottom by viewModel.isRefreshingAtBottom.collectAsState()
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem != null && lastVisibleItem.index == layoutInfo.totalItemsCount - 1
        }
    }
    val context = LocalContext.current
    val activity = context as? Activity
    LaunchedEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    val scrollPosition by viewModel.scrollPosition.collectAsState()
    LaunchedEffect(scrollPosition) {
        if (listState.isScrollInProgress.not()) {
            withContext(Dispatchers.Main) {
                delay(500)
                listState.animateScrollToItem(scrollPosition.first, scrollPosition.second)
            }
        }
    }
    val initState by viewModel.initState.collectAsState()
    LaunchedEffect(listState) {
        snapshotFlow { Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect {
                if (listState.isScrollInProgress) {
                    viewModel.updateScrollPosition(it)
                }
            }
    }
    LaunchedEffect(appUser.mid) {
        if (!initState) {
            withContext(IO) {
//                viewModel.reset()
                viewModel.refresh(0)
            }
        }
    }
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            withContext(IO) {
                viewModel.loadOlderTweets()
            }
        }
    }

    // Update isScrolling state
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect {
                viewModel.setScrollingState(it)
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = Color.LightGray)
            .pullRefresh(pullRefreshState)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(bottom = 60.dp) // Adjust this value
        ) {
            items(tweets, key = { it.mid }) { tweet ->
                if (!tweet.isPrivate) {
                    TweetItem(tweet, parentEntry)
                }
            }
            item {
                if (refreshingAtTop) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 60.dp)
                            .wrapContentWidth(Alignment.CenterHorizontally)
                            .size(80.dp),
                        color = Color.LightGray,
                        strokeWidth = 8.dp
                    )
                }
            }
            item {
                if (refreshingAtBottom) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .wrapContentWidth(Alignment.CenterHorizontally)
                            .align(Alignment.BottomCenter)
                    )
                }
            }
        }
        PullRefreshIndicator(
            refreshingAtTop,
            state = pullRefreshState,
            Modifier.align(Alignment.TopCenter)
        )
    }
}

private data object BottomBarTransparency {
    const val VISIBLE = 0.98f
    const val INVISIBLE = 0.3f
}

@Composable
fun rememberDelayedBottomBarTransparency(isScrolling: Boolean): State<Float> {
    val transparency = remember { mutableFloatStateOf(
        if (isScrolling) BottomBarTransparency.INVISIBLE else BottomBarTransparency.VISIBLE) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Use a LaunchedEffect to manage the coroutine and delay
    LaunchedEffect(isScrolling) {
        if (!isScrolling) {
            // If not scrolling, start the delay and update transparency
            delay(2000) // Wait for 2 seconds
            transparency.floatValue = BottomBarTransparency.VISIBLE
        } else {
            // If scrolling, immediately set transparency to 0.3f
            transparency.floatValue = BottomBarTransparency.INVISIBLE
        }
    }

    // Reset transparency to 0.95f when the composable is first created
    // and when the lifecycle is resumed. This ensures the bottom bar is
    // visible when the app is first launched or when returning from the background.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                transparency.floatValue = BottomBarTransparency.VISIBLE
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return transparency
}