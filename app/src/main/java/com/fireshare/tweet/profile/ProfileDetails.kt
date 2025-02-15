package com.fireshare.tweet.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ButtonDefaults.IconSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.fireshare.tweet.R
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.viewmodel.UserViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ProfileDetail(
    viewModel: UserViewModel,
    navController: NavHostController,
    appUserViewModel: UserViewModel
) {
    val appUserFollowings by appUserViewModel.followings.collectAsState()
    val user by viewModel.user.collectAsState()
    val profile by remember { derivedStateOf { user.profile } }
    val tweetCount = viewModel.tweets.collectAsState().value.size
    val fansList by viewModel.fans.collectAsState()
    val followingsList by viewModel.followings.collectAsState()

    LaunchedEffect(appUserFollowings) {
        withContext(Dispatchers.IO) {
            viewModel.refreshFollowingsAndFans()
        }
    }

    // go to list of followings of the user
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 100.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Text(
                text = profile ?: "Profile",
                style = MaterialTheme.typography.titleSmall
            )
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 0.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${fansList.count()} ${stringResource(R.string.fans)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable(
                        onClick = {
                            navController.navigate((NavTweet.Follower(user.mid)))
                        }
                    ))
                Text(
                    text = "${followingsList.count()} ${stringResource(R.string.followings)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .clickable(onClick = {
                            navController.navigate(NavTweet.Following(user.mid))
                        }),
                )
                Text(
                    text = "$tweetCount ${stringResource(R.string.posts)}",
                    style = MaterialTheme.typography.bodySmall,
                )

                Row(
                    modifier = Modifier.clickable(
                        onClick = {
                            navController.navigate((NavTweet.Bookmarks(user.mid)))
                        }
                    )
                ) {
                    Text(
                        text = "${user.bookmarkedTweets?.size ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Icon(
                        imageVector = Icons.Outlined.Bookmarks,
                        contentDescription = stringResource(R.string.user_bookmarks),
                        modifier = Modifier.size(IconSize)
                    )
                }

                Row(
                    modifier = Modifier.clickable(
                        onClick = {
                            navController.navigate((NavTweet.Favorites(user.mid)))
                        }
                    )
                ) {
                    Text(
                        text = "${user.likedTweets?.size ?: "" } ",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Icon(
                        imageVector = Icons.Outlined.FavoriteBorder,
                        contentDescription = stringResource(R.string.user_favorites),
                        modifier = Modifier.size(IconSize)
                    )
                }
            }
        }
    }
}