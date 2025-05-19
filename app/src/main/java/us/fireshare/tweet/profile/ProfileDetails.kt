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
import androidx.navigation.NavHostController
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.viewmodel.UserViewModel

@Composable
fun ProfileDetail(
    viewModel: UserViewModel,
    navController: NavHostController,
    appUserViewModel: UserViewModel
) {
    val appUserFollowings by appUserViewModel.followings.collectAsState()
    val user by viewModel.user.collectAsState()
    val profile by remember { derivedStateOf { user.profile } }
    val bookmarksCount by remember { derivedStateOf { user.bookmarksCount } }
    val favoritesCount by remember { derivedStateOf { user.favoritesCount } }
    val followingsCount by remember { derivedStateOf { user.followingCount } }
    val followersCount by remember { derivedStateOf { user.followersCount } }
    val tweetCount by remember { derivedStateOf { user.tweetCount } }

    LaunchedEffect(appUserFollowings) {
        viewModel.refreshFollowingsAndFans()
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
                    .padding(start = 0.dp, end = if (user.mid == appUser.mid ) 20.dp else 120.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${stringResource(R.string.fans)} $followersCount",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable(
                        onClick = {
                            navController.navigate((NavTweet.Follower(user.mid)))
                        }
                    ))
                Text(
                    text = "${stringResource(R.string.followings)} $followingsCount",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .clickable(onClick = {
                            navController.navigate(NavTweet.Following(user.mid))
                        }),
                )
                Text(
                    text = "${stringResource(R.string.posts)} $tweetCount",
                    style = MaterialTheme.typography.bodySmall,
                )
                // show the following buttons only on appUser's profile
                if (user.mid == appUser.mid) {
                    bookmarksCount?.let {
                        Row(
                            modifier = Modifier.clickable(
                                onClick = {
                                    navController.navigate((NavTweet.Bookmarks(user.mid)))
                                }
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Bookmarks,
                                contentDescription = stringResource(R.string.user_bookmarks),
                                modifier = Modifier.size(IconSize)
                            )
                            Text(
                                text = "${if (it>0) it else ""}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    favoritesCount?.let {
                        Row(
                            modifier = Modifier.clickable(
                                onClick = {
                                    navController.navigate((NavTweet.Favorites(user.mid)))
                                }
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FavoriteBorder,
                                contentDescription = stringResource(R.string.user_favorites),
                                modifier = Modifier.size(IconSize)
                            )
                            Text(
                                text = "${if (it>0) it else ""}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}