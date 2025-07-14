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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.navigation.ProfileEditor
import us.fireshare.tweet.navigation.SharedViewModel
import us.fireshare.tweet.service.SnackbarAction
import us.fireshare.tweet.service.SnackbarEvent
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
    val buttonText = remember { mutableStateOf("Follow") }
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()

    val context = LocalContext.current
    LaunchedEffect(followings) {
        buttonText.value = when {
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
            text = buttonText.value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier
                .border(
                    width = 1.dp,
                    color = Color.DarkGray,
                    shape = RoundedCornerShape(12.dp)
                )
                .clickable(onClick = {
                    when (buttonText.value) {
                        context.getString(R.string.edit) -> navController.navigate(ProfileEditor)
                        else -> {
                            if (! appUser.isGuest()) {
                                Toast.makeText(context, context.getString(R.string.update_following), Toast.LENGTH_SHORT).show()
                                appUserViewModel.viewModelScope.launch(Dispatchers.IO) {
                                    appUserViewModel.toggleFollowing(user.mid) {
                                        viewModel.viewModelScope.launch(Dispatchers.IO) {
                                            viewModel.toggleFollower(user.mid, it, appUser.mid)
                                            tweetFeedViewModel.updateFollowingsTweets(user.mid, it)
                                        }
                                    }
                                }
                            }
                            else {
                                val event = SnackbarEvent(
                                    message = context.getString(R.string.login_follow),
                                    action = SnackbarAction(
                                        name = context.getString(R.string.go),
                                        action = { navController.navigate(NavTweet.Login) }
                                    )
                                )
                                viewModel.viewModelScope.launch {
                                    viewModel.showSnackbar(event)
                                }
                            }
                        }
                    }
                })
                .padding(horizontal = 20.dp, vertical = 8.dp)
        )
    }
}
