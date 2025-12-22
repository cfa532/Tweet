package us.fireshare.tweet.profile

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.HproseInstance.fetchUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.TweetCacheManager
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.navigation.ProfileEditor
import us.fireshare.tweet.navigation.SharedViewModel
import us.fireshare.tweet.ui.theme.DebouncedButton
import us.fireshare.tweet.viewmodel.TweetFeedViewModel
import us.fireshare.tweet.viewmodel.UserViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileTopBarButton(
    viewModel: UserViewModel,
    navController: NavHostController,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior?
) {
    val sharedViewModel: SharedViewModel = hiltViewModel()
    val appUserViewModel = sharedViewModel.appUserViewModel
    val followings by appUserViewModel.followings.collectAsState()
    val user by viewModel.user.collectAsState()
    val activity = LocalActivity.current as ComponentActivity
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>(viewModelStoreOwner = activity)
    val context = LocalContext.current
    val followOperationFailed by appUserViewModel.followOperationFailed.collectAsState()
    
    // Capture string resources at composable level
    val followOperationFailedText = stringResource(R.string.follow_operation_failed)
    val loginFollowText = stringResource(R.string.login_follow)
    val editText = stringResource(R.string.edit)
    val unfollowText = stringResource(R.string.unfollow)

    // Use local boolean state for immediate UI feedback
    var localIsFollowing by remember { mutableStateOf(false) }
    
    // Update local state when followings change
    LaunchedEffect(followings) {
        localIsFollowing = followings.contains(user.mid)
    }
    
    // Show toast when follow operation fails
    LaunchedEffect(followOperationFailed) {
        if (followOperationFailed == user.mid) {
            Toast.makeText(
                context,
                followOperationFailedText,
                Toast.LENGTH_LONG
            ).show()
            // Reset the failure signal
            appUserViewModel.clearFollowOperationFailed()
        }
    }

    // Determine button text based on user relationship
    val buttonText = when {
        user.mid == appUser.mid -> stringResource(R.string.edit)
        localIsFollowing -> stringResource(R.string.unfollow)
        else -> stringResource(R.string.follow)
    }

    // Hide the Follow/Unfollow button when header is collapsed
    if (scrollBehavior?.state?.collapsedFraction == 1f) {
        return
    }

    DebouncedButton(
        text = buttonText,
        onClick = {
            when (buttonText) {
                editText -> navController.navigate(ProfileEditor)
                else -> {
                    if (!appUser.isGuest()) {
                        // Optimistically update followingList and enqueue worker
                        appUserViewModel.toggleFollowingOptimistic(
                            user.mid,
                            appUser.mid,
                            context,
                            updateTweetFeed = { isFollowingResult ->
                                viewModel.viewModelScope.launch(Dispatchers.IO) {
                                    tweetFeedViewModel.updateFollowingsTweets(user.mid, isFollowingResult)
                                    
                                    // Remove cache of the followed/unfollowed user to force refresh from server
                                    TweetCacheManager.removeCachedUser(user.mid)
                                    
                                    // Refresh user data for the followed/unfollowed user
                                    try {
                                        // Get fresh user data from server and cache it
                                        fetchUser(user.mid)?.let { refreshedUser ->
                                            TweetCacheManager.saveUser(refreshedUser)
                                            // Refresh the current viewmodel's user data
                                            viewModel.refreshUserData()
                                            Timber.tag("ProfileTopBarButtons").d("Refreshed user data for: ${user.mid}")
                                        }
                                    } catch (e: Exception) {
                                        Timber.tag("ProfileTopBarButtons").e("Failed to refresh user data for ${user.mid}: $e")
                                    }
                                }
                            },
                            rollbackTweetFeed = { attemptedIsFollowing ->
                                viewModel.viewModelScope.launch(Dispatchers.IO) {
                                    tweetFeedViewModel.rollbackFollowingsTweets(user.mid, attemptedIsFollowing)
                                }
                            }
                        )
                    } else {
                        Toast.makeText(context, loginFollowText, Toast.LENGTH_LONG).show()
                        // Navigate to login after a short delay
                        viewModel.viewModelScope.launch {
                            kotlinx.coroutines.delay(500)
                            navController.navigate(NavTweet.Login)
                        }
                    }
                }
            }
        },
        textColor = if (buttonText == unfollowText) {
            MaterialTheme.colorScheme.onError
        } else {
            MaterialTheme.colorScheme.onPrimary
        },
        textStyle = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .background(
                color = if (buttonText == unfollowText) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}
