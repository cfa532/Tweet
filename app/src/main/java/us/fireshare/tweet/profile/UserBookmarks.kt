package us.fireshare.tweet.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.navigation.BottomNavigationBar
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.tweet.TweetItem
import us.fireshare.tweet.viewmodel.UserViewModel
import us.fireshare.tweet.widget.UserAvatar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun UserBookmarks(
    viewModel: UserViewModel,    // appUserViewModel
    parentEntry: NavBackStackEntry
) {
    val navController = LocalNavController.current
    val start = remember { mutableIntStateOf(0) }
    val bookmarks by viewModel.bookmarks.collectAsState()
    val user = appUser

    LaunchedEffect(Unit) {
        // load bookmarked tweets
        withContext(Dispatchers.IO) {
            viewModel.getBookmarks(start.intValue)
        }
    }
    val refreshingAtTop by viewModel.isRefreshingAtTop.collectAsState()      // data loading indicator
    val pullRefreshState = rememberPullRefreshState(refreshingAtTop, {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            start.intValue = 0
            viewModel.getBookmarks(start.intValue)
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
    LaunchedEffect(isAtBottom) {
        if (isAtBottom && bookmarks.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                start.intValue += 10
                viewModel.getBookmarks(start.intValue)
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Column {
                        UserAvatar(user, 36)
                        Text(
                            text = stringResource(R.string.user_bookmarks),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 2.dp, bottom = 0.dp)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() })
                    {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        bottomBar = { BottomNavigationBar(navController, 0) }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()
            .pullRefresh(pullRefreshState)
            .background(color = Color.LightGray)
            .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                state = listState
            ) {
                items(bookmarks, key = { it.mid }) { tweet ->
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
