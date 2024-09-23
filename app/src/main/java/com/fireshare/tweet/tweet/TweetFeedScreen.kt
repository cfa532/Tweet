 package com.fireshare.tweet.tweet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.navigation.BottomNavigationBar
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.viewmodel.UserViewModel
import com.fireshare.tweet.widget.AppIcon
import com.fireshare.tweet.widget.UserAvatar

 @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun TweetFeedScreen(
    navController: NavHostController,
    parentEntry: NavBackStackEntry,
    selectedBottomBarItemIndex: Int,
    viewModel: TweetFeedViewModel,
) {
    val tweets by viewModel.tweets.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

     val refreshingAtTop by viewModel.isRefreshingAtTop.collectAsState()      // data loading indicator
     val pullRefreshState = rememberPullRefreshState(refreshingAtTop, { viewModel.loadNewerTweets() })

     // for pulling up at the bottom of the list
     val initState = remember { mutableStateOf(true) }
     val refreshingAtBottom by viewModel.isRefreshingAtBottom.collectAsState()
     val listState = rememberLazyListState()
     val isAtBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == listState.layoutInfo.totalItemsCount - 1
     LaunchedEffect(isAtBottom) {
         if (isAtBottom) {
             viewModel.loadOlderTweets()
         }
     }

    // Call it earlier to initiate some appUser fields that will be used for UI state.
    val userViewModel = hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(
        parentEntry,
        key = appUser.mid
    ) { factory ->
        factory.create(appUser.mid)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { MainTopAppBar(navController, scrollBehavior) },
        bottomBar = { BottomNavigationBar(navController, selectedBottomBarItemIndex) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .pullRefresh(pullRefreshState)
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState
            ) {
                items(tweets) { tweet ->
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 0.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.surfaceTint
                    )
                    if (!tweet.isPrivate) TweetItem(tweet, parentEntry)
                }
                item {
                    if (refreshingAtBottom) {
                        if (initState.value)
                            initState.value = false
                        else
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                                    .wrapContentWidth(Alignment.CenterHorizontally)
                            )
                    }
                }
            }
            PullRefreshIndicator(refreshingAtTop, state = pullRefreshState, Modifier.align(Alignment.TopCenter))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopAppBar(
    navController: NavHostController,
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
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
                        .clickable(onClick = { navController.navigate(NavTweet.TweetFeed) })
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
                    navController.navigate(NavTweet.UserProfile(appUser.mid)) })
            {
                UserAvatar(appUser,32)
            }
        },
        actions = {
            // add delete action here
        },
        scrollBehavior = scrollBehavior
    )
}
