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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.HproseInstance.getMediaUrl
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.widget.MediaItem
import com.fireshare.tweet.widget.MediaPreviewGrid

@Composable
fun TweetItem(
    tweet: Tweet,
    parentEntry: NavBackStackEntry,      // navGraph scoped
) {
    val viewModel = hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
        parentEntry, key = tweet.mid
    ) { factory ->
        factory.create(tweet)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 800.dp)
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
                            parentEntry,
                            key = tweet.originalTweetId
                        )
                        { factory -> factory.create(tweet.originalTweet!!) }
                    TweetBlock(originalTweetViewModel, parentEntry, parentTweet = tweet)

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
                                tweet.mid?.let { navController.navigate(NavTweet.TweetDetail(it)) }
                            })
                    ) {
                        // Tweet header: Icon, name, timestamp, more actions
                        TweetItemHeader(tweet, parentEntry)
                        if (tweet.content != null && tweet.content!!.isNotEmpty()) {
                            Text(
                                modifier = Modifier.padding(start = 16.dp),
                                text = tweet.content!!,
                                style = MaterialTheme.typography.bodyLarge
                            )
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
                                }?.let { it2 -> MediaItem(it2, it.type) }
                            }
                            if (tweet.mid != null && mediaItems != null) {
                                MediaPreviewGrid(mediaItems, tweet.mid!!)
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            tonalElevation = 3.dp,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 0.dp)
                        ) {
                            // quoted tweet
                            TweetBlock(
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
                            Spacer(modifier = Modifier.width(20.dp))
                            ShareButton(viewModel)
                        }
                    }
                }
            }
        } else {
            // original tweet by current user.
            TweetBlock(viewModel, parentEntry)
        }
    }
}
