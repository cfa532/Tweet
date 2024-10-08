package com.fireshare.tweet.tweet

import android.util.Log
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
    var viewModel = hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
        parentEntry,    // keep the tweetViewModel NavGraph scoped.
        key = tweet.mid
    ) { factory ->
        factory.create(tweet)
    }
    // Log the tweet ID to see if the component is being recomposed
    Log.d("TweetItem", "Displaying tweet with ID: ${tweet.mid}")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 800.dp)
            .padding(0.dp)
    ) {
        // Content body
        if (tweet.originalTweet != null) {
            if (tweet.content == "") {
                // this is a retweet of another tweet.
                Box(modifier = Modifier.padding(top = 8.dp)
                ) {
                    // The tweet area
                    viewModel =
                        hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
                            parentEntry,
                            key = tweet.originalTweetId
                        )
                        { factory -> factory.create(tweet.originalTweet!!) }
                    TweetBlock(viewModel)

                    // Label: Forward by user, on top of original tweet
                    Box {
                        val forwardBy = if (tweet.authorId==appUser.mid)
                            stringResource(R.string.forward_by)
                        else "@${tweet.author?.username}" + stringResource(R.string.forwarded)
                        Text(
                            text = forwardBy,
                            fontSize = MaterialTheme.typography.labelSmall.fontSize,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .padding(start = 60.dp)
                                .offset(
                                    y = (-12).dp,
                                    x = (-8).dp
                                ) // Adjust the offset value as needed
                                .zIndex(1f) // Ensure it appears above the tweet area
                        )
                    }
                }
            } else {
                // retweet with comments
                val navController = LocalNavController.current
                Column(modifier = Modifier
                    .padding(start = 8.dp)
                    .clickable(onClick = {
                        tweet.mid?.let { navController.navigate(NavTweet.TweetDetail(it)) }
                    })
                ) {
                    // Tweet header: Icon, name, timestamp, more actions
                    TweetItemHeader(tweet)

                    tweet.content?. let {
                        Text(
                            modifier = Modifier.padding(start = 16.dp),
                            text = it,
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
                            tweet.author?.baseUrl?.let { it1 -> getMediaUrl(it, it1).toString() }
                                ?.let { it2 -> MediaItem(it2) }
                        }
                        if (tweet.mid != null && mediaItems != null) {
                            MediaPreviewGrid(mediaItems, tweet.mid!!)
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
//                        border = BorderStroke(0.4.dp,
//                            color = MaterialTheme.colorScheme.surfaceTint),
                        tonalElevation = 3.dp,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 0.dp)
                    ) {
                        // quoted tweet
                        TweetBlock(
                            hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
                                parentEntry, key = tweet.originalTweetId
                            ) { factory ->
                                factory.create(tweet.originalTweet!!)
                            }, isQuoted = true
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
        } else {
            // original tweet by current user.
            TweetBlock(viewModel)
        }
    }
}
