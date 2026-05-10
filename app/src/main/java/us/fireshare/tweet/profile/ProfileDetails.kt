package us.fireshare.tweet.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
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
    

    // Only refresh followings and fans when the user changes, not on every size change
    // Removed automatic refresh to prevent count flicking during navigation
    // LaunchedEffect(displayUser.mid) {
    //     withContext(IO) {
    //         viewModel.refreshFollowingsAndFans()
    //     }
    // }
    
    // No need to sync counts since viewModel == appUserViewModel for current user
    // Count updates are handled directly in toggleFollowingWithResult

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
                fontSize = 15.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            // Stats row: Followers, Followings, Tweets (all users), Bookmarks (appUser only)
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    text = "$followersCount ${stringResource(R.string.fans)}",
                    fontSize = 14.sp,
                    modifier = Modifier.clickable {
                        navController.navigate(NavTweet.Follower(displayUser.mid))
                    }
                )
                Text(
                    text = "$followingsCount ${stringResource(R.string.followings)}",
                    fontSize = 14.sp,
                    modifier = Modifier.clickable {
                        navController.navigate(NavTweet.Following(displayUser.mid))
                    }
                )
                Text(
                    text = "$tweetCount ${stringResource(R.string.posts)}",
                    fontSize = 14.sp
                )
                if (displayUser.mid == appUser.mid) {
                    Text(
                        text = "$bookmarksCount ${stringResource(R.string.user_bookmarks)}",
                        fontSize = 14.sp,
                        modifier = Modifier.clickable {
                            navController.navigate(NavTweet.Bookmarks(displayUser.mid))
                        }
                    )
                }
            }
        }
    }
}