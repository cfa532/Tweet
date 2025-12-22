package us.fireshare.tweet.profile

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.HproseInstance.fetchUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TweetCacheManager
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
    val activity = LocalActivity.current as ComponentActivity
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>(viewModelStoreOwner = activity)
    val followOperationFailed by appUserViewModel.followOperationFailed.collectAsState()
    
    // Capture string resources at composable level
    val followOperationFailedText = stringResource(R.string.follow_operation_failed)
    val guestReminderText = stringResource(R.string.guest_reminder)

    // Update local state when followings change
    LaunchedEffect(followings) {
        localFollowState = isFollowing
    }
    
    // Show toast when follow operation fails
    LaunchedEffect(followOperationFailed) {
        if (followOperationFailed == userId) {
            Toast.makeText(
                context,
                followOperationFailedText,
                Toast.LENGTH_LONG
            ).show()
            // Reset the failure signal
            appUserViewModel.clearFollowOperationFailed()
        }
    }

    DebouncedButton(
        text = if (localFollowState) stringResource(R.string.unfollow) else stringResource(R.string.follow),
        onClick = {
            if (appUser.isGuest()) {
                appUserViewModel.viewModelScope.launch {
                    guestWarning(context, navController, guestReminderText)
                }
                return@DebouncedButton
            }

            // Optimistically update followingList and enqueue worker
            appUserViewModel.toggleFollowingOptimistic(
                userId,
                appUser.mid,
                context,
                updateTweetFeed = { isFollowingResult ->
                    viewModel.viewModelScope.launch(Dispatchers.IO) {
                        tweetFeedViewModel.updateFollowingsTweets(
                            userId,
                            isFollowingResult
                        )
                        
                        // Remove cache of the followed/unfollowed user to force refresh from server
                        TweetCacheManager.removeCachedUser(userId)
                        
                        // Refresh user data for the followed/unfollowed user
                        try {
                            // Get fresh user data from server and cache it
                            fetchUser(userId)?.let { refreshedUser ->
                                TweetCacheManager.saveUser(refreshedUser)
                                // Refresh the current viewmodel's user data
                                viewModel.refreshUserData()
                                Timber.tag("ToggleFollowingButton").d("Refreshed user data for: $userId")
                            }
                        } catch (e: Exception) {
                            Timber.tag("ToggleFollowingButton").e("Failed to refresh user data for $userId: $e")
                        }
                    }
                },
                rollbackTweetFeed = { attemptedIsFollowing ->
                    viewModel.viewModelScope.launch(Dispatchers.IO) {
                        tweetFeedViewModel.rollbackFollowingsTweets(userId, attemptedIsFollowing)
                    }
                }
            )
        },
        textColor = if (localFollowState) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 20.dp, vertical = 6.dp)
    )
}