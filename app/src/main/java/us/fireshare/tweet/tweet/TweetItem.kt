package us.fireshare.tweet.tweet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.viewmodel.TweetViewModel
import us.fireshare.tweet.widget.Gadget.isElementVisible
import us.fireshare.tweet.widget.MediaPreviewGrid
import us.fireshare.tweet.widget.SelectableText

@Composable
fun TweetItem(
    tweet: Tweet,
    parentEntry: NavBackStackEntry, // navGraph scoped
    onDeleteClick: () -> Unit
) {
    val viewModel = hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
        parentEntry, key = tweet.mid
    ) { factory ->
        factory.create(tweet)
    }
    var isVisible by remember { mutableStateOf(false) }
    TweetRefreshHandler(isVisible, viewModel)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 8000.dp)
            .onGloballyPositioned { layoutCoordinates ->
                isVisible = isElementVisible(layoutCoordinates, 30)
            }
            .padding(bottom = 1.dp),
        tonalElevation = 0.dp
    ) {
        if (tweet.originalTweet != null) {
            if (tweet.content.isNullOrEmpty() && tweet.attachments.isNullOrEmpty())
            {
                // this is a retweet of another tweet.
                Surface(
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    // The tweet area
                    val originalTweetViewModel =
                        hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
                            parentEntry, key = tweet.originalTweetId
                        ) { factory -> factory.create(tweet.originalTweet!!) }

                    TweetItemBody(originalTweetViewModel, parentEntry,
                        parentTweet = tweet, onDeleteClick = onDeleteClick)

                    // Label: Forward by user, on top of original tweet
                    Box {
                        val forwardBy = if (tweet.authorId == appUser.mid)
                            stringResource(R.string.forward_by)
                        else "@${tweet.author?.username} " + stringResource(R.string.forwarded)
                        Text(
                            text = forwardBy,
                            fontSize = MaterialTheme.typography.labelSmall.fontSize,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .padding(start = 60.dp)
                                .offset(
                                    y = (-0).dp,
                                    x = (-8).dp
                                ) // Adjust the offset value as needed
                                .zIndex(1f) // Ensure it appears above the tweet area
                        )
                    }
                }
            } else {
                // retweet with comments. Eiter text or media files.
                val navController = LocalNavController.current
                Surface(
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clickable(onClick = {
                                navController.navigate(
                                    NavTweet.TweetDetail(
                                        tweet.authorId,
                                        tweet.mid
                                    )
                                )
                            })
                    ) {
                        // Tweet header: Icon, name, timestamp, more actions
                        TweetItemHeader(viewModel, onDeleteClick = onDeleteClick)

                        tweet.content?.let {
                            SelectableText(it,
                                maxLines = 10,
                                modifier = Modifier
                                    .padding(start = 16.dp)
                            ) { username ->
                                viewModel.viewModelScope.launch(Dispatchers.IO) {
                                    HproseInstance.getUserId(username)?.let {
                                        withContext(Dispatchers.Main) {
                                            navController.navigate(NavTweet.UserProfile(it))
                                        }
                                    }
                                }
                            }
                        }
                        if (! tweet.attachments.isNullOrEmpty()) {
                            Surface (
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 8.dp, top = 4.dp)
                                    .heightIn(min = 32.dp, max = 400.dp), // Set a specific height for the grid
                                tonalElevation = 4.dp
                            ) {
                                MediaPreviewGrid(tweet.attachments!!, viewModel)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            tonalElevation = 8.dp,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 0.dp)
                        ) {
                            // quoted tweet
                            TweetItemBody(
                                hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
                                    parentEntry, key = tweet.originalTweetId
                                ) { factory ->
                                    factory.create(tweet.originalTweet!!)
                                },
                                parentEntry,
                                isQuoted = true,
                                onDeleteClick = onDeleteClick
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            // State hoist
                            LikeButton(viewModel)
                            BookmarkButton(viewModel)
                            CommentButton(viewModel)
                            RetweetButton(viewModel)
                            Spacer(modifier = Modifier.width(40.dp))
                            ShareButton(viewModel)
                        }
                    }
                }
            }
        } else {
            // original tweet by user.
            TweetItemBody(viewModel, parentEntry, onDeleteClick = onDeleteClick)
        }
    }
}

/**
 * This function handles the logic for refreshing tweets based on visibility and time intervals.
 *
 * @param isVisible Indicates whether the composable is currently visible.
 * @param viewModel The ViewModel responsible for refreshing tweets.
 */
@Composable
fun TweetRefreshHandler(isVisible: Boolean, viewModel: TweetViewModel) {
    val refreshIntervalMillis = 3000L // 2 seconds

    // Use rememberCoroutineScope to get a scope tied to the composable's lifecycle.
    val scope = rememberCoroutineScope()

    LaunchedEffect(isVisible) {
        if (isVisible) {
            // Launch a coroutine to handle the refresh after the delay
            scope.launch(Dispatchers.IO) {
                delay(refreshIntervalMillis)
                viewModel.refreshTweet()
            }
        }
    }
}