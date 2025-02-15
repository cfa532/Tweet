package com.fireshare.tweet.tweet

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.navigation.BottomNavigationBar
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.widget.AppIcon
import com.fireshare.tweet.widget.UserAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class, FlowPreview::class)
@Composable
fun TweetFeedScreen(
    navController: NavHostController,
    parentEntry: NavBackStackEntry,
    selectedBottomBarItemIndex: Int,
    viewModel: TweetFeedViewModel
) {
    val tweets by viewModel.tweets.collectAsState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val refreshingAtTop by viewModel.isRefreshingAtTop.collectAsState()
    val pullRefreshState = rememberPullRefreshState(refreshingAtTop, {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            viewModel.loadNewerTweets()
        }
    })
    val refreshingAtBottom by viewModel.isRefreshingAtBottom.collectAsState()
    val listState = rememberLazyListState()
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
    LaunchedEffect(key1 = scrollPosition) {
        if (listState.isScrollInProgress.not()) {
            withContext(Dispatchers.Main) {
                delay(500)
                listState.animateScrollToItem(scrollPosition.first, scrollPosition.second)
            }
        }
    }
    LaunchedEffect(key1 = listState) {
        snapshotFlow { Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect {
                if (listState.isScrollInProgress) {
                    viewModel.updateScrollPosition(it)
                }
            }
    }

    val initState by viewModel.initState.collectAsState()
    LaunchedEffect(appUser.mid) {
        if (!initState)
            viewModel.refresh()
    }
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            viewModel.loadOlderTweets()
        }
    }

    // State to track if scrolling is in progress
    val isScrolling = remember { mutableStateOf(false) }

    // Update isScrolling state
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect {
                isScrolling.value = it
            }
    }

    // Calculate the transparency based on scrolling state
    val bottomBarTransparency = rememberDelayedBottomBarTransparency(isScrolling)

    Box(modifier = Modifier.fillMaxSize()) { // Wrap everything in a Box
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = { MainTopAppBar(navController, listState, scrollBehavior) },
            bottomBar = {} // Remove bottomBar from Scaffold
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = Color.LightGray)
                    .pullRefresh(pullRefreshState)
                    .padding(innerPadding),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 60.dp) // Adjust this value
                ) {
                    items(tweets, key = { it.mid }) { tweet ->
                        if (!tweet.isPrivate)
                            TweetItem(
                                tweet = tweet,
                                parentEntry = parentEntry
                            )
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

 @OptIn(ExperimentalMaterial3Api::class)
 @Composable
 fun MainTopAppBar(
     navController: NavHostController,
     listState: LazyListState,
     scrollBehavior: TopAppBarScrollBehavior? = null
 ) {
     val scope = rememberCoroutineScope()
     CenterAlignedTopAppBar(
         colors = TopAppBarDefaults.topAppBarColors(
             containerColor = MaterialTheme.colorScheme.primaryContainer,
             titleContentColor = MaterialTheme.colorScheme.primary,
         ),
         title = {
             Row(
                 modifier = Modifier.fillMaxWidth(),
                 horizontalArrangement = Arrangement.Center
             ) {
                 Box(
                     modifier = Modifier
                         .clip(CircleShape)
                         .clickable(onClick = {
                             scope.launch {
                                 listState.animateScrollToItem(0)
                             }
                         })
                 ) {
                     AppIcon()
                 }
             }
         },
         navigationIcon = {
             IconButton(onClick = {
                 if (appUser.mid == TW_CONST.GUEST_ID)
                     navController.navigate(NavTweet.Login)
                 else
                     navController.navigate(NavTweet.UserProfile(appUser.mid))
             }) {
                 UserAvatar(appUser, 32)
             }
         },
         actions = {
             IconButton(onClick = {
                 navController.navigate(NavTweet.Settings)
             }) {
                 Icon(
                     imageVector = Icons.Default.Settings,
                     contentDescription = "Settings",
                     tint = MaterialTheme.colorScheme.surfaceTint
                 )
             }
         },
         scrollBehavior = scrollBehavior
     )
 }

 private data object BottomBarTransparency {
     const val VISIBLE = 0.98f
     const val INVISIBLE = 0.3f
 }

 @Composable
 fun rememberDelayedBottomBarTransparency(isScrolling: MutableState<Boolean>): State<Float> {
     val transparency = remember { mutableFloatStateOf(
         if (isScrolling.value) BottomBarTransparency.INVISIBLE else BottomBarTransparency.VISIBLE) }
     val lifecycleOwner = LocalLifecycleOwner.current

     // Use a LaunchedEffect to manage the coroutine and delay
     LaunchedEffect(isScrolling.value) {
         if (!isScrolling.value) {
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