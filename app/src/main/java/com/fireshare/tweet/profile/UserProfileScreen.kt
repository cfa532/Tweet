package com.fireshare.tweet.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.navigation.BottomNavigationBar
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.tweet.TweetItem
import com.fireshare.tweet.viewmodel.UserViewModel

@Composable
fun UserProfileScreen(
    navController: NavHostController,
    userId: MimeiId,
    parentEntry: NavBackStackEntry,
    appUserViewModel: UserViewModel,
) {
    val userViewModel = hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(
        parentEntry,
        key = userId
    ) { factory ->
        factory.create(userId)
    }
    val tweets by userViewModel.tweets.collectAsState()

    Scaffold(
        topBar = { ProfileTopAppBar(userViewModel, navController, parentEntry) },
        bottomBar = { BottomNavigationBar(navController, 1) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
        ) {
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 8.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.surfaceTint
                )

                ProfileDetail(userViewModel, navController, appUserViewModel)
            }
            items(tweets) { tweet ->
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 1.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.surfaceTint
                )
                if (!tweet.isPrivate) TweetItem(tweet, parentEntry)
            }
        }
    }
}

@Composable
fun ProfileDetail(viewModel: UserViewModel, navController: NavHostController, appUserViewModel: UserViewModel) {
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
            style = MaterialTheme.typography.titleLarge
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
                text = "${fansList.count()} Followers",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.clickable(onClick = {
                    navController.navigate((NavTweet.Follower(user.mid)))
                })
            )
            Spacer(modifier = Modifier.padding(horizontal = 20.dp))

            Text(
                text = "${followingsList.count()} Following",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.clickable(onClick = {
                    navController.navigate(NavTweet.Following(user.mid))
                }),
            )
        }
    }
}