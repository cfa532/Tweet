package us.fireshare.tweet.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
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
    Column(modifier = Modifier.fillMaxWidth()) {
        // Top row (visual): profile text, darker background
        val hasProfile = !profile.isNullOrBlank()
        if (hasProfile) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)
            ) {
                Text(
                    text = profile ?: "",
                    fontSize = 15.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Bottom row (visual): iOS-style single stat row
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 100.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 24.dp,
                        end = 20.dp,
                        top = if (hasProfile) 8.dp else 2.dp,
                        bottom = 8.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProfileTextStatItem(
                    label = stringResource(R.string.fans),
                    count = followersCount.toString(),
                    modifier = Modifier.clickable {
                        navController.navigate(NavTweet.Follower(displayUser.mid))
                    }
                )
                Spacer(modifier = Modifier.weight(1f))
                ProfileTextStatItem(
                    label = stringResource(R.string.followings),
                    count = followingsCount.toString(),
                    modifier = Modifier.clickable {
                        navController.navigate(NavTweet.Following(displayUser.mid))
                    }
                )
                Spacer(modifier = Modifier.weight(1f))
                ProfileTextStatItem(
                    label = stringResource(R.string.posts),
                    count = tweetCount.toString()
                )
                if (displayUser.mid == appUser.mid) {
                    Spacer(modifier = Modifier.weight(1f))
                    ProfileIconStatItem(
                        icon = Icons.Default.BookmarkBorder,
                        contentDescription = stringResource(R.string.user_bookmarks),
                        count = bookmarksCount.toString(),
                        modifier = Modifier.clickable {
                            navController.navigate(NavTweet.Bookmarks(displayUser.mid))
                        }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    ProfileIconStatItem(
                        icon = Icons.Default.FavoriteBorder,
                        contentDescription = stringResource(R.string.your_favorites),
                        count = favoritesCount.toString(),
                        modifier = Modifier.clickable {
                            navController.navigate(NavTweet.Favorites(displayUser.mid))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileTextStatItem(
    label: String,
    count: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.height(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = count,
            fontSize = 17.sp
        )
    }
}

@Composable
private fun ProfileIconStatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    count: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.height(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = count,
            fontSize = 17.sp
        )
    }
}