package us.fireshare.tweet.profile

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.tweet.guestWarning
import us.fireshare.tweet.ui.theme.DebouncedButton
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
    var localFollowState by remember { mutableStateOf(isFollowing) }
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()

    // Update local state when followings change
    LaunchedEffect(followings) {
        localFollowState = isFollowing
    }

    DebouncedButton(
        text = if (localFollowState) stringResource(R.string.unfollow) else stringResource(R.string.follow),
        onClick = {
            if (appUser.isGuest()) {
                appUserViewModel.viewModelScope.launch {
                    guestWarning(context, navController)
                }
                return@DebouncedButton
            }

            // Toggle local state immediately for instant UI feedback
            localFollowState = !localFollowState

            appUserViewModel.viewModelScope.launch(Dispatchers.IO) {
                try {
                    // Attempt the actual toggle operation
                    val result = appUserViewModel.toggleFollowingWithResult(
                        userId,
                        appUser.mid
                    ) { isFollowingResult ->
                        viewModel.viewModelScope.launch(Dispatchers.IO) {
                            viewModel.toggleFollower(userId, isFollowingResult, appUser.mid)
                            tweetFeedViewModel.updateFollowingsTweets(
                                userId,
                                isFollowingResult
                            )
                        }
                    }

                    if (result == null) {
                        // Operation failed, revert the local state
                        localFollowState = !localFollowState
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.follow_operation_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    // Exception occurred, revert the local state
                    localFollowState = !localFollowState
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.follow_operation_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        },
        textColor = MaterialTheme.colorScheme.primary,
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 20.dp, vertical = 6.dp)
    )
}