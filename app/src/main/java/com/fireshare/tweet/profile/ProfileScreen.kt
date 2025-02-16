package com.fireshare.tweet.profile

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.navigation.BottomNavigationBar
import com.fireshare.tweet.tweet.TweetItem
import com.fireshare.tweet.viewmodel.UserViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        context as ComponentActivity, key = userId
    ) { factory ->
        factory.create(userId)
    }
    val tweets by viewModel.tweets.collectAsState()
    val topTweets by viewModel.topTweets.collectAsState()
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    val refreshingAtTop by viewModel.isRefreshingAtTop.collectAsState()      // data loading indicator
    val pullRefreshState = rememberPullRefreshState(refreshingAtTop, {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            viewModel.loadNewerTweets()
        }
    } )
    // for pulling up at the bottom of the list
    val refreshingAtBottom by viewModel.isRefreshingAtBottom.collectAsState()
    val listState = rememberLazyListState()
    val layoutInfo by remember {
        // critical to performance not read layoutInfo directly
        derivedStateOf { listState.layoutInfo }
    }
    val isAtBottom =
        layoutInfo.visibleItemsInfo.lastOrNull()?.index == layoutInfo.totalItemsCount - 1

    val activity = context as? Activity
    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    LaunchedEffect(isAtBottom) {
        if (isAtBottom && tweets.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                viewModel.loadOlderTweets()
            }
        }
    }
    LaunchedEffect(Unit) {
        // load tweets only when user profile screen is opened.
        withContext(Dispatchers.IO) {
            viewModel.getTweets()
        }
    }

    Scaffold(
        topBar = { ProfileTopAppBar(viewModel, navController, scrollBehavior) },
        bottomBar = { BottomNavigationBar(navController, 0) }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()
            .pullRefresh(pullRefreshState)
            .background(color = Color.LightGray)
            .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                state = listState
            ) {

                // the belt where user details and Pin are displayed.
                item {
                    // Display user name, profile, number of followers....
                    ProfileDetail(viewModel, navController, appUserViewModel)
                }
                if (topTweets.isNotEmpty()) {
                    item {
                        Surface( modifier = Modifier.fillMaxWidth(),
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

                    items(topTweets, key = { it.timestamp }) { tweet ->
                        TweetItem(tweet, parentEntry)
                    }
                    item {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 2.dp),
                            thickness = 2.dp,
                            color = MaterialTheme.colorScheme.primaryContainer
                        )
                    }
                }

                items(tweets, key = { it.timestamp }) { tweet ->
                    TweetItem(tweet, parentEntry)
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