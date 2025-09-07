package us.fireshare.tweet.tweet

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.appUser
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
) {
    val tweets by viewModel.tweets.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // State for gesture detection
    var isAtLastTweet by remember { mutableStateOf(false) }
    var isRefreshingAtBottom by remember { mutableStateOf(false) }
    
    // State for full-screen video
    var fullScreenVideoUrl by remember { mutableStateOf<String?>(null) }

    // Constants
    val MINMIMUM_TWEET_COUNT = 4

    // Create a callback to trigger external loadmore
    val triggerLoadMore = remember {
        {
            Timber.tag("FollowingsTweet").d("External loadmore trigger called")
            // This will be handled by TweetListView's externalLoadMoreRequest mechanism
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(isAtLastTweet, isRefreshingAtBottom) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val (x, y) = dragAmount

                        // Only detect upward gestures when at last tweet and not already loading
                        if (isAtLastTweet && !isRefreshingAtBottom && tweets.size >= MINMIMUM_TWEET_COUNT) {
                            // Check if it's an upward gesture (negative Y means up)
                            if (y < -50) { // Threshold for upward gesture
                                isRefreshingAtBottom = true

                                // Trigger the loadmore in TweetListView
                                triggerLoadMore()

                                // Reset the loading state after a short delay
                                coroutineScope.launch {
                                    kotlinx.coroutines.delay(100) // Small delay to allow TweetListView to process
                                    isRefreshingAtBottom = false
                                }
                            }
                        }
                    }
                )
            }
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
            contentPadding = PaddingValues(bottom = 40.dp),
            showPrivateTweets = false,
            parentEntry = parentEntry,
            onScrollStateChange = onScrollStateChange,
            currentUserId = appUser.mid, // Pass current user ID for change detection
            onTweetUnavailable = { tweetId ->
                // Remove the tweet from the list when it becomes unavailable
                viewModel.removeTweet(tweetId)
            },
            onIsAtLastTweetChange = { isAtLast ->
                Timber.tag("FollowingsTweet").d("isAtLastTweet changed to: $isAtLast")
                isAtLastTweet = isAtLast
            },
            onTriggerLoadMore = triggerLoadMore,
            context = "followingsTweet"
        )
    }
    
    // Show full-screen video overlay
    if (fullScreenVideoUrl != null) {
        Dialog(
            onDismissRequest = { fullScreenVideoUrl = null },
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