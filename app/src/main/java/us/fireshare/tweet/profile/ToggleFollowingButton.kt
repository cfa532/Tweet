package us.fireshare.tweet.profile

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.isGuest
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.tweet.guestWarning
import us.fireshare.tweet.viewmodel.TweetFeedViewModel
import us.fireshare.tweet.viewmodel.UserViewModel

@Composable
fun ToggleFollowingButton(
    userId: MimeiId,
    viewModel: UserViewModel,
    appUserViewModel: UserViewModel
) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val followings by appUserViewModel.followings.collectAsState()
    val isFollowing = followings.contains(userId)
    val followState = remember { mutableStateOf(isFollowing) }
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()

    LaunchedEffect(followings) {
        followState.value = isFollowing
    }
    Text(
        text = if (followState.value) stringResource(R.string.unfollow) else stringResource(R.string.follow),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .clickable(onClick = {
                if (appUser.isGuest()) {
                    appUserViewModel.viewModelScope.launch {
                        guestWarning(context, navController)
                    }
                    return@clickable
                }
                Toast.makeText(context, context.getString(R.string.update_following), Toast.LENGTH_SHORT).show()
                appUserViewModel.viewModelScope.launch(Dispatchers.IO) {
                    appUserViewModel.toggleFollowing(userId, appUser.mid) {
                        viewModel.viewModelScope.launch(Dispatchers.IO) {
                            viewModel.toggleFollower(userId, it, appUser.mid)
                            tweetFeedViewModel.updateFollowingsTweets(userId, it)
                        }
                    }
                }
            })
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 20.dp, vertical = 6.dp)
    )
}