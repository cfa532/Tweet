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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.tweet.TweetItem
import com.fireshare.tweet.viewmodel.UserViewModel

@Composable
fun UserProfileScreen(
    navController: NavHostController,
    userId: MimeiId,
    parentEntry: NavBackStackEntry,
) {
    val userViewModel = hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(parentEntry, key = userId) {
        factory -> factory.create(userId)
    }
    val user by userViewModel.user.collectAsState()
    val tweets by userViewModel.tweets.collectAsState()
    val fans by userViewModel.fans.collectAsState()
    val followings by userViewModel.followings.collectAsState()

    // current user's detail has been loaded by default. Load other users' data only when opening their profile.
    if (user.mid != appUser.mid)
        userViewModel.getFollows(user)

    Scaffold(
        topBar = { ProfileTopAppBar(userViewModel, navController, user) }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding))
        {
            Spacer(modifier = Modifier.padding(top = 8.dp))
            Column(
                modifier = Modifier
                    .fillMaxSize(),
                horizontalAlignment = Alignment.Start
            ) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 1.dp),
                    thickness = 1.dp,
                    color = Color.Gray
                )
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
                        style = MaterialTheme.typography.titleSmall)
                    Row {
                        Text(text = "${fans.count()} Followers",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.clickable(onClick = {
                                navController.navigate((NavTweet.Follower(userId)))
                            })
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 20.dp))

                        Text(text = "${followings.count()} Following",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.clickable(onClick = {
                                // go to list of followings of the user
                                navController.navigate(NavTweet.Following(userId))
                            }),
                        )
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 1.dp),
                    thickness = 1.dp,
                    color = Color.Gray
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 8.dp, bottom = 8.dp)
                )
                {
                    items(tweets) { tweet ->
                        if (!tweet.isPrivate) TweetItem(tweet, parentEntry)
                    }
                }
            }
        }
    }
}
