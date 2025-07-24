package us.fireshare.tweet.profile

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
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

import us.fireshare.tweet.viewmodel.TweetFeedViewModel
import us.fireshare.tweet.viewmodel.UserViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileTopBarButton(
    viewModel: UserViewModel,
    navController: NavHostController,
    scrollBehavior: TopAppBarScrollBehavior?
) {
    val sharedViewModel: SharedViewModel = hiltViewModel()
    val appUserViewModel = sharedViewModel.appUserViewModel
    val followings by appUserViewModel.followings.collectAsState()
    val user by viewModel.user.collectAsState()
    var buttonText by remember { mutableStateOf("Follow") }
    var isUpdating by remember { mutableStateOf(false) }
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()

    val context = LocalContext.current
    LaunchedEffect(followings) {
        buttonText = when {
            user.mid == appUser.mid -> context.getString(R.string.edit)
            followings.contains(user.mid) -> context.getString(R.string.unfollow)
            else -> context.getString(R.string.follow)
        }
    }

    Row(modifier = Modifier) {
        // hide the Follow/Unfollow button when header is collapsed.
        if (scrollBehavior?.state?.collapsedFraction == 1f)
            return

        Text(
            text = buttonText,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier
                .border(
                    width = 1.dp,
                    color = Color.DarkGray,
                    shape = RoundedCornerShape(12.dp)
                )
                .clickable(
                    enabled = !isUpdating,
                    onClick = {
                        when (buttonText) {
                            context.getString(R.string.edit) -> navController.navigate(ProfileEditor)
                            else -> {
                                if (!appUser.isGuest()) {
                                    // Optimistic update - immediately change the button state
                                    val currentIsFollowing = followings.contains(user.mid)
                                    val newButtonText = if (currentIsFollowing) {
                                        context.getString(R.string.follow)
                                    } else {
                                        context.getString(R.string.unfollow)
                                    }
                                    buttonText = newButtonText
                                    isUpdating = true

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
                                                // Operation failed, revert the optimistic update
                                                buttonText = if (currentIsFollowing) {
                                                    context.getString(R.string.unfollow)
                                                } else {
                                                    context.getString(R.string.follow)
                                                }
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.follow_operation_failed),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            } else {
                                                // Operation succeeded, show success message
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.update_following),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        } catch (e: Exception) {
                                            // Exception occurred, revert the optimistic update
                                            buttonText = if (currentIsFollowing) {
                                                context.getString(R.string.unfollow)
                                            } else {
                                                context.getString(R.string.follow)
                                            }
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.follow_operation_failed),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        } finally {
                                            isUpdating = false
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
                    }
                )
                .padding(horizontal = 20.dp, vertical = 8.dp)
        )
    }
}
