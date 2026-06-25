package us.fireshare.tweet.tweet

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.delay
import timber.log.Timber
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.profile.UserAvatar
import us.fireshare.tweet.viewmodel.TweetFeedViewModel
import us.fireshare.tweet.widget.LocalVideoCoordinator
import us.fireshare.tweet.widget.VideoPlaybackCoordinator

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
    onShowPendingNewTweets: () -> Unit = {},
) {
    val tweets by viewModel.tweets.collectAsState()
    val pendingNewTweets by viewModel.pendingNewTweets.collectAsState()
    val showNewTweetsBanner by viewModel.showNewTweetsBanner.collectAsState()
    val followingsCoordinator = remember { VideoPlaybackCoordinator() }
    DisposableEffect(followingsCoordinator) {
        onDispose {
            followingsCoordinator.clear()
        }
    }

    // State for full-screen video
    var fullScreenVideoUrl by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        CompositionLocalProvider(LocalVideoCoordinator provides followingsCoordinator) {
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

        NewTweetsBanner(
            pendingTweets = pendingNewTweets,
            visible = showNewTweetsBanner && pendingNewTweets.isNotEmpty(),
            onClick = {
                viewModel.applyPendingNewTweets()
                onShowPendingNewTweets()
            },
            onAutoHide = viewModel::dismissNewTweetsBanner,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
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

@Composable
private fun NewTweetsBanner(
    pendingTweets: List<Tweet>,
    visible: Boolean,
    onClick: () -> Unit,
    onAutoHide: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pendingTweetIds = pendingTweets.joinToString(separator = "|") { it.mid }

    LaunchedEffect(visible, pendingTweetIds) {
        if (visible && pendingTweets.isNotEmpty()) {
            delay(10_000)
            onAutoHide()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
        modifier = modifier
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onClick)
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, top = 6.dp, end = 16.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "\u2191",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal)
                )
                NewTweetsAvatarCluster(pendingTweets)
                Text(
                    text = if (pendingTweets.size == 1) "1 new tweet" else "${pendingTweets.size} new tweets",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal)
                )
            }
        }
    }
}

@Composable
private fun NewTweetsAvatarCluster(pendingTweets: List<Tweet>) {
    val authors = pendingTweets
        .mapNotNull { it.author }
        .distinctBy { it.mid }
        .take(3)
    val avatarCount = authors.size.coerceAtLeast(1)
    val clusterWidth = 32 + (avatarCount - 1) * 18

    Box(
        modifier = Modifier
            .width(clusterWidth.dp)
            .height(32.dp)
    ) {
        if (authors.isEmpty()) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        } else {
            authors.forEachIndexed { index, author ->
                Box(
                    modifier = Modifier
                        .offset(x = (index * 18).dp)
                        .zIndex(index.toFloat())
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp)
                ) {
                    UserAvatar(user = author, size = 28)
                }
            }
        }
    }
}
