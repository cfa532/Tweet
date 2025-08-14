package us.fireshare.tweet.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import us.fireshare.tweet.R
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.navigation.SharedViewModel
import us.fireshare.tweet.viewmodel.UserViewModel

@Composable
fun ProfileDetail(
    viewModel: UserViewModel,
    navController: NavHostController,
) {
    val sharedViewModel = hiltViewModel<SharedViewModel>()
    val appUserViewModel = sharedViewModel.appUserViewModel
    val appUserFollowings by appUserViewModel.followings.collectAsState()
    val user by viewModel.user.collectAsState()
    val appUser by appUserViewModel.user.collectAsState()

    // Use the ViewModel's user data directly for display, as it's more reliable
    // The ViewModel's user state is updated when profile changes
    val displayUser = user
    val profile by remember { derivedStateOf { displayUser.profile } }

    // Use ViewModel's public count variables - collect them efficiently
    val bookmarksCount by viewModel.bookmarksCount.collectAsState()
    val favoritesCount by viewModel.favoritesCount.collectAsState()
    val followingsCount by viewModel.followingsCount.collectAsState()
    val followersCount by viewModel.followersCount.collectAsState()
    val tweetCount by viewModel.tweetCount.collectAsState()

    // Stabilize the LaunchedEffect to prevent unnecessary calls
    LaunchedEffect(appUserFollowings.size) {
        withContext(IO) {
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
                    .padding(
                        start = 0.dp,
                        end = if (displayUser.mid == appUser.mid) 20.dp else 120.dp
                    ),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${stringResource(R.string.fans)} $followersCount",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable(
                        onClick = {
                            navController.navigate((NavTweet.Follower(displayUser.mid)))
                        }
                    ))
                Text(
                    text = "${stringResource(R.string.followings)} $followingsCount",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .clickable(onClick = {
                            navController.navigate(NavTweet.Following(displayUser.mid))
                        }),
                )
                Text(
                    text = "${stringResource(R.string.posts)} $tweetCount",
                    style = MaterialTheme.typography.bodySmall,
                )
                // show the following buttons only on appUser's profile
                if (displayUser.mid == appUser.mid) {
                    Row(
                        modifier = Modifier.clickable(
                            onClick = {
                                navController.navigate((NavTweet.Bookmarks(displayUser.mid)))
                            }
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Bookmarks,
                            contentDescription = stringResource(R.string.user_bookmarks),
                            modifier = Modifier.size(IconSize)
                        )
                        Text(
                            text = "${if (bookmarksCount > 0) bookmarksCount else ""}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(
                        modifier = Modifier.clickable(
                            onClick = {
                                navController.navigate((NavTweet.Favorites(displayUser.mid)))
                            }
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FavoriteBorder,
                            contentDescription = stringResource(R.string.user_favorites),
                            modifier = Modifier.size(IconSize)
                        )
                        Text(
                            text = "${if (favoritesCount > 0) favoritesCount else ""}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}