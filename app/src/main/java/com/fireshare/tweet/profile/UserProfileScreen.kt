package com.fireshare.tweet.profile

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.navigation.BottomNavigationBar
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.tweet.TweetItem
import com.fireshare.tweet.viewmodel.UserViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun UserProfileScreen(
    navController: NavHostController,
    userId: MimeiId,
    parentEntry: NavBackStackEntry,
    appUserViewModel: UserViewModel,
) {
    val viewModel = hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(
        parentEntry,
        key = userId
    ) { factory ->
        factory.create(userId)
    }
    val tweets by viewModel.tweets.collectAsState()
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    val refreshingAtTop by viewModel.isRefreshingAtTop.collectAsState()      // data loading indicator
    val pullRefreshState =
        rememberPullRefreshState(refreshingAtTop, { viewModel.loadNewerTweets() })

    // for pulling up at the bottom of the list
    val refreshingAtBottom by viewModel.isRefreshingAtBottom.collectAsState()
    val listState = rememberLazyListState()
    val layoutInfo by remember { derivedStateOf { listState.layoutInfo } }     // critical to not read layoutInfo directly
    val isAtBottom =
        layoutInfo.visibleItemsInfo.lastOrNull()?.index == layoutInfo.totalItemsCount - 1

    LaunchedEffect(isAtBottom) {
        if (isAtBottom && tweets.isNotEmpty()) {
            viewModel.loadOlderTweets()
        }
    }
    LaunchedEffect(Unit) {
        Log.d("UserProfileScreen", "Call getTweets()")
        viewModel.getTweets()
    }

    Scaffold(
        topBar = { ProfileTopAppBar(viewModel, navController, parentEntry, scrollBehavior) },
        bottomBar = { BottomNavigationBar(navController, 0) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .pullRefresh(pullRefreshState)
                .fillMaxSize()
//                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(innerPadding),
        ) {
            LazyColumn(
//                modifier = Modifier.fillMaxSize().padding(innerPadding),
                state = listState
            ) {
                item {
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 8.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.surfaceTint
                    )
                    // Display user name, profile, number of followers....
                    ProfileDetail(viewModel, navController, appUserViewModel)
                }
                items(tweets) { tweet ->
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 1.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.surfaceTint
                    )
                    if (!tweet.isPrivate) TweetItem(tweet, parentEntry)
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
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

@Composable
fun ProfileDetail(
    viewModel: UserViewModel,
    navController: NavHostController,
    appUserViewModel: UserViewModel
) {
    val appUserFollowings by appUserViewModel.followings.collectAsState()
    val user by viewModel.user.collectAsState()
    val fansList by viewModel.fans.collectAsState()
    val followingsList by viewModel.followings.collectAsState()

    LaunchedEffect(appUserFollowings) {
        viewModel.updateFans()
    }

    // go to list of followings of the user
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp)) {
        Text(
            text = user.name ?: "No one",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "@" + (user.username ?: "NoOne"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(start = 0.dp)
        )
        Text(
            text = user.profile ?: "Profile",
            style = MaterialTheme.typography.titleSmall
        )
        Row {
            Text(
                text = "${fansList.count()} ${stringResource(R.string.fans)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable(onClick = {
                    navController.navigate((NavTweet.Follower(user.mid)))
                })
            )
            Text(
                text = "${followingsList.count()} ${stringResource(R.string.follow)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp)
                    .clickable(onClick = {
                    navController.navigate(NavTweet.Following(user.mid))
                }),
            )
            Text(
                text = "${user.tweetCount} ${stringResource(R.string.posts)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}