 package com.fireshare.tweet.tweet

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.navigation.BottomNavigationBar
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.navigation.SharedViewModel
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.widget.AppIcon
import com.fireshare.tweet.widget.UserAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun TweetFeedScreen(
    navController: NavHostController,
    parentEntry: NavBackStackEntry,
    selectedBottomBarItemIndex: Int,
) {
    val sharedViewModel: SharedViewModel = hiltViewModel(LocalContext.current as ComponentActivity)
    val viewModel = sharedViewModel.tweetFeedViewModel
    val tweets by viewModel.tweets.collectAsState()
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    val refreshingAtTop by viewModel.isRefreshingAtTop.collectAsState()      // data loading indicator
    val pullRefreshState =
        rememberPullRefreshState(refreshingAtTop, {
            viewModel.viewModelScope.launch(Dispatchers.IO) {
                viewModel.loadNewerTweets()
            }
        })
    // for pulling up at the bottom of the list
    val refreshingAtBottom by viewModel.isRefreshingAtBottom.collectAsState()
    val listState = rememberLazyListState()
    val layoutInfo by remember { derivedStateOf { listState.layoutInfo } }     // critical to not read layoutInfo directly
    val isAtBottom =
        layoutInfo.visibleItemsInfo.lastOrNull()?.index == layoutInfo.totalItemsCount - 1

    val context = LocalContext.current
    val activity = context as? Activity
    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    LaunchedEffect(appUser.mid) {
        // reload page when user login or out
        if (!viewModel.initState.value)
            withContext(Dispatchers.IO) {
                viewModel.refresh()
            }
    }
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            viewModel.loadOlderTweets()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { MainTopAppBar(navController, scrollBehavior) },
        bottomBar = { BottomNavigationBar(navController, selectedBottomBarItemIndex) }
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
                state = listState
            ) {
                items(tweets, key = { it.mid }) { tweet ->
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
                Modifier.align(Alignment.TopCenter)
            )
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
                        .clickable(onClick = {
                            navController.navigate(NavTweet.TweetFeed)
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
            } ) {
                UserAvatar(appUser,32)
            }
        },
        actions = {
            var showDialog by remember { mutableStateOf(false) }
            IconButton(onClick = {
                showDialog = true
            }) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "privacy policy",
                    tint = MaterialTheme.colorScheme.surfaceTint
                )
            }
            if (showDialog) {
                BasicAlertDialog(
                    onDismissRequest = { showDialog = false }
                ) {
                    ConstraintLayout(
                        modifier = Modifier
                            .width(400.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White),
                    ) {
                        val (content, button) = createRefs()
                        LazyColumn(
                            modifier = Modifier
                                .padding(8.dp)
                                .height(800.dp)
                        ) {
                            item {
                                Text(
                                    "Privacy Policy\n" +
                                            "\n" +
                                            "We operate the Tweet mobile application (the \"App\"). This page informs you of our policies regarding the collection, use, and disclosure of Personal Information when you use our App.\n" +
                                            "\n" +
                                            "Information Collection and Use\n" +
                                            "\n" +
                                            "We collect several types of information for various purposes to provide and improve our App for you.\n" +
                                            "\n" +
                                            "Types of Data Collected\n" +
                                            "\n" +
                                            "Personal Data: While using our App, we may ask you to provide us with certain personally identifiable information, such as your name, email address.\n" +
                                            "\n" +
                                            "Usage Data: We may collect information on how the App is accessed and used, such as your device's Internet Protocol address (e.g., IP address), browser type, browser version, the pages of our App that you visit, the time and date of your visit, and other diagnostic data.\n" +
                                            "\n" +
                                            "Cookies and Tracking Technologies: We use cookies and similar tracking technologies to track the activity on our App and hold certain information.\n" +
                                            "\n" +
                                            "Use of Data\n" +
                                            "\n" +
                                            "We use the collected data for various purposes:\n" +
                                            "\n" +
                                            "To provide and maintain our App\n" +
                                            "To notify you about changes to our App\n" +
                                            "To allow you to participate in interactive features of our App when you choose to do so\n" +
                                            "To provide customer support\n" +
                                            "To gather analysis or valuable information so that we can improve our App\n" +
                                            "To monitor the usage of our App\n" +
                                            "To detect, prevent, and address technical issues\n" +
                                            "Data Security\n" +
                                            "\n" +
                                            "The security of your data is important to us, but remember that no method of transmission over the Internet is 100% secure. While we try our best to protect you data, there is always potential leakholes. Do not disclose sensitive personal information on this App.",
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                TextButton(
                                    onClick = { showDialog = false },
                                    modifier = Modifier.constrainAs(button) {
                                        bottom.linkTo(parent.bottom)
                                        centerHorizontallyTo(parent)
                                    },
                                ) {
                                    Text("Confirm")
                                }
                            }
                        }
                    }
                }
            }
        },
        scrollBehavior = scrollBehavior

    )
}
