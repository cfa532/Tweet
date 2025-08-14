package us.fireshare.tweet.profile

import android.widget.Toast
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
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
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()
    val context = LocalContext.current

    // Use local boolean state for immediate UI feedback
    var localIsFollowing by remember { mutableStateOf(false) }
    
    // Update local state when followings change
    LaunchedEffect(followings) {
        localIsFollowing = followings.contains(user.mid)
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
                context.getString(R.string.edit) -> navController.navigate(ProfileEditor)
                else -> {
                    if (!appUser.isGuest()) {
                        // Toggle local state immediately for instant UI feedback
                        localIsFollowing = !localIsFollowing

                        appUserViewModel.viewModelScope.launch(Dispatchers.IO) {
                            try {
                                // Attempt the actual toggle operation
                                val result = appUserViewModel.toggleFollowingWithResult(user.mid) { isFollowingResult ->
                                    viewModel.viewModelScope.launch(Dispatchers.IO) {
                                        viewModel.toggleFollower(user.mid, isFollowingResult, appUser.mid)
                                        tweetFeedViewModel.updateFollowingsTweets(user.mid, isFollowingResult)
                                    }
                                }

                                if (result == null) {
                                    // Operation failed, revert the local state
                                    localIsFollowing = !localIsFollowing
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
                                localIsFollowing = !localIsFollowing
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.follow_operation_failed),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    } else {
                        Toast.makeText(context, context.getString(R.string.login_follow), Toast.LENGTH_LONG).show()
                        // Navigate to login after a short delay
                        viewModel.viewModelScope.launch {
                            kotlinx.coroutines.delay(1000)
                            navController.navigate(NavTweet.Login)
                        }
                    }
                }
            }
        },
        textColor = MaterialTheme.colorScheme.tertiary,
        textStyle = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = Color.DarkGray,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 20.dp, vertical = 8.dp)
    )
}
