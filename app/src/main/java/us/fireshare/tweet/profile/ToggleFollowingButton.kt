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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.HproseInstance.getUser
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
    var isOperationInProgress by remember { mutableStateOf(false) }
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

            // Prevent multiple simultaneous operations
            if (isOperationInProgress) {
                return@DebouncedButton
            }

            // Store the previous state for potential rollback
            val previousState = localFollowState
            
            // Toggle local state immediately for instant UI feedback
            localFollowState = !localFollowState
            isOperationInProgress = true

            appUserViewModel.viewModelScope.launch(Dispatchers.IO) {
                try {
                    // Attempt the actual toggle operation
                    val result = appUserViewModel.toggleFollowingWithResult(
                        userId,
                        appUser.mid
                    ) { isFollowingResult ->
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
                                getUser(userId)?.let { refreshedUser ->
                                    TweetCacheManager.saveUser(refreshedUser)
                                    // Refresh the current viewmodel's user data
                                    viewModel.refreshUserData()
                                    Timber.tag("ToggleFollowingButton").d("Refreshed user data for: $userId")
                                }
                            } catch (e: Exception) {
                                Timber.tag("ToggleFollowingButton").e("Failed to refresh user data for $userId: $e")
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        isOperationInProgress = false
                        
                        if (result == null) {
                            // Operation failed, revert the local state
                            localFollowState = previousState
                            Toast.makeText(
                                context,
                                context.getString(R.string.follow_operation_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        // If result is not null, the operation succeeded and local state is correct
                    }
                } catch (e: Exception) {
                    // Exception occurred, revert the local state
                    withContext(Dispatchers.Main) {
                        localFollowState = previousState
                        isOperationInProgress = false
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