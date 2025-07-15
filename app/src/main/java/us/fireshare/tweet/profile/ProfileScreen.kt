package us.fireshare.tweet.profile

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MimeiId
import androidx.compose.foundation.layout.PaddingValues
import us.fireshare.tweet.navigation.BottomNavigationBar
import us.fireshare.tweet.tweet.TweetItem
import us.fireshare.tweet.tweet.TweetListView
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

    Scaffold(
        topBar = { ProfileTopAppBar(viewModel, navController, scrollBehavior) },
        bottomBar = { BottomNavigationBar(navController = navController, selectedIndex = 0) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
            ) {
                // Profile details section
                item {
                    ProfileDetail(viewModel, navController, appUserViewModel)
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
                
                // Regular tweets using TweetListView
                item {
                    TweetListView(
                        tweets = tweets,
                        getTweets = { pageNumber ->
                            viewModel.viewModelScope.launch(Dispatchers.IO) {
                                if (pageNumber == 0) {
                                    viewModel.loadNewerTweets()
                                } else {
                                    viewModel.loadOlderTweets()
                                }
                            }
                        },
                        scrollBehavior = scrollBehavior,
                        contentPadding = PaddingValues(bottom = 60.dp),
                        showPrivateTweets = appUser.mid == userId,
                        parentEntry = parentEntry
                    )
                }
            }
        }
    }
}