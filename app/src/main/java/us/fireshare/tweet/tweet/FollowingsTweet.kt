package us.fireshare.tweet.tweet

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.viewmodel.TweetFeedViewModel

/**
 * Tweets of the followings of current user.
 * Uses the self-contained TweetListView for displaying tweets with built-in pagination and refresh.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FollowingsTweet(
    parentEntry: NavBackStackEntry,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: TweetFeedViewModel
) {
    val tweets by viewModel.tweets.collectAsState()
    val initState by viewModel.initState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Refresh tweets when user changes (login/logout)
    LaunchedEffect(appUser.mid) {
        if (!initState) {
            withContext(IO) {
                viewModel.refresh(0)
            }
        }
    }

    TweetListView(
        tweets = tweets,
        getTweets = { pageNumber ->
            coroutineScope.launch(IO) {
                if (pageNumber == 0) {
                    viewModel.loadNewerTweets()
                } else {
                    viewModel.loadOlderTweets()
                }
            }
        },
        onScrollPositionChange = { viewModel.updateScrollPosition(it) },
        scrollBehavior = scrollBehavior,
        contentPadding = PaddingValues(bottom = 60.dp),
        showPrivateTweets = false,
        parentEntry = parentEntry
    )
}