package com.fireshare.tweet.tweet

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import coil.compose.rememberAsyncImagePainter
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.HproseInstance.getMediaUrl
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.viewmodel.UserViewModel
import com.fireshare.tweet.widget.AppIcon
import com.fireshare.tweet.navigation.BottomNavigationBar
import com.fireshare.tweet.navigation.NavTweet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TweetFeedScreen(
    navController: NavHostController,
    parentEntry: NavBackStackEntry,
    selectedBottomBarItemIndex: Int,
    viewModel: TweetFeedViewModel,
) {
    val tweets by viewModel.tweets.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // Call it earlier to initiate some appUser fields that will be used for UI state.
    val userViewModel = hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(parentEntry, key = appUser.mid) {
        factory -> factory.create(appUser.mid)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { MainTopAppBar(navController, scrollBehavior) },
        bottomBar = { BottomNavigationBar(navController, selectedBottomBarItemIndex) }
    ) { innerPadding ->

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
        {
            items(tweets) { tweet ->
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 1.dp),
                    thickness = 0.5.dp,
                    color = Color.LightGray
                )
                if (!tweet.isPrivate) TweetItem(tweet, parentEntry)
            }
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
            IconButton(onClick = { navController.navigate(NavTweet.UserProfile(appUser.mid!!)) })
            {
                appUser.baseUrl?.let { getMediaUrl(appUser.avatar, it) }?.let {
                    Image(
                        painter = rememberAsyncImagePainter(appUser.baseUrl?.let {
                            getMediaUrl(
                                appUser.avatar, it
                            )
                        }),
                        contentDescription = "User Avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                    )
                }
            }
        },
        actions = {
            // add delete action here
        },
        scrollBehavior = scrollBehavior
    )
}
