package us.fireshare.tweet.tweet

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavBackStackEntry
import timber.log.Timber
import us.fireshare.tweet.viewmodel.TweetFeedViewModel

/**
 * Tweets of the followings of current user.
 * Uses the self-contained TweetListView for displaying tweets with built-in pagination and refresh.
 * Includes external gesture detection for manual loadmore when at the last tweet.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.R)
fun FollowingsTweet(
    parentEntry: NavBackStackEntry,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: TweetFeedViewModel,
    onScrollStateChange: (ScrollState) -> Unit = {},
    scrollToTopTrigger: Int = 0,
) {
    val tweets by viewModel.tweets.collectAsState()

    // State for full-screen video
    var fullScreenVideoUrl by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        TweetListView(
            tweets = tweets.also {
//                timber.log.Timber.tag("FollowingsTweet").d("Passing tweets to TweetListView: ${it.size}")
            },
            fetchTweets = { pageNumber ->
                // Call the ViewModel's fetchTweets and return the result
                viewModel.fetchTweets(pageNumber)
            },
            scrollBehavior = scrollBehavior,
            contentPadding = PaddingValues(bottom = 64.dp),
            showPrivateTweets = false,
            parentEntry = parentEntry,
            onScrollStateChange = onScrollStateChange,
            // Don't pass currentUserId on main feed - it's only for profile screens
            onTweetUnavailable = { tweetId ->
                // Remove the tweet from the list when it becomes unavailable
                viewModel.removeTweet(tweetId)
            },
            context = "followingsTweet",
            scrollToTopTrigger = scrollToTopTrigger
        )
    }
    
    // Show full-screen video overlay
    if (fullScreenVideoUrl != null) {
        Dialog(
            onDismissRequest = { },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                us.fireshare.tweet.widget.FullScreenVideoPlayer(
                    videoUrl = fullScreenVideoUrl!!,
                    onClose = { fullScreenVideoUrl = null }
                )
            }
        }
    }
}