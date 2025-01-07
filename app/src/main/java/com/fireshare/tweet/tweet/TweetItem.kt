package com.fireshare.tweet.tweet

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
import androidx.compose.runtime.mutableLongStateOf
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
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.HproseInstance.getMediaUrl
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.widget.MediaItem
import com.fireshare.tweet.widget.MediaPreviewGrid
import com.fireshare.tweet.widget.MediaType
import com.fireshare.tweet.widget.isElementVisible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TweetItem(
    tweet: Tweet,
    parentEntry: NavBackStackEntry, // navGraph scoped
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
                isVisible = isElementVisible(layoutCoordinates, 20)
            }
            .padding(bottom = 1.dp),
        tonalElevation = 0.dp
    ) {
        // Content body
        if (tweet.originalTweet != null) {
            if ((tweet.content == null || tweet.content == "")
                && (tweet.attachments == null || tweet.attachments!!.isEmpty()))
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

                    TweetItemBody(originalTweetViewModel, parentEntry, parentTweet = tweet)

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
                        TweetItemHeader(viewModel, parentEntry)

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
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp, top = 4.dp)
                                .heightIn(max = 800.dp) // Set a specific height for the grid
                        ) {
                            val mediaItems = tweet.attachments?.mapNotNull {
                                tweet.author?.baseUrl?.let { it1 ->
                                    getMediaUrl(it.mid, it1).toString()
                                }?.let { it2 -> MediaItem(it2, it.type?: MediaType.Unknown) }
                            }
                            if (mediaItems != null) {
                                MediaPreviewGrid(mediaItems, viewModel)
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            tonalElevation = 3.dp,
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
                                isQuoted = true
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
            TweetItemBody(viewModel, parentEntry)
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
    // Use remember to persist the state across recompositions.
    var lastRefreshTime by remember { mutableLongStateOf(0L) }
    var visibilityStartTime by remember { mutableLongStateOf(0L) }
    val refreshIntervalMillis = 5 * 60 * 1000L // 5 minutes
    val initialDelayMillis = 1000L
    val minTimeSinceLastRefreshMillis = 3 * 60 * 1000L // 3 minutes
    val minVisibilityDurationMillis = 950L

    // Use rememberCoroutineScope to get a scope tied to the composable's lifecycle.
    val scope = rememberCoroutineScope()
    // Use remember to persist the job across recompositions.
    var refreshJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            visibilityStartTime = System.currentTimeMillis()
            delay(initialDelayMillis)

            val currentTime = System.currentTimeMillis()
            val timeSinceVisibilityStart = currentTime - visibilityStartTime
            val timeSinceLastRefresh = currentTime - lastRefreshTime

            if (timeSinceVisibilityStart >= minVisibilityDurationMillis && timeSinceLastRefresh >= minTimeSinceLastRefreshMillis) {
                withContext(Dispatchers.IO) {
                    viewModel.refreshTweet()
                    lastRefreshTime = currentTime
                }
            }

            // Start periodic refresh after initial refresh
            refreshJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(refreshIntervalMillis)
                    viewModel.refreshTweet()
                    lastRefreshTime = System.currentTimeMillis()
                }
            }
        } else {
            // Cancel the periodic refresh when the composable becomes invisible
            refreshJob?.cancel()
            refreshJob = null
        }
    }
}