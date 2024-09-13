package com.fireshare.tweet.tweet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fireshare.tweet.HproseInstance.getMediaUrl
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.widget.MediaItem
import com.fireshare.tweet.widget.MediaPreviewGrid

@Composable
fun TweetBlock(
    viewModel: TweetViewModel,
    isQuoted: Boolean = false     // the block is a quoted tweet or not
) {
    val navController = LocalNavController.current
    val tweet by viewModel.tweetState.collectAsState()

    Surface(
        // Apply border to the entire TweetBlock
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.clickable( onClick = {
            tweet.mid?.let { navController.navigate(NavTweet.TweetDetail(it)) }
        })
    ) {
        Column(
            modifier = Modifier
                .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)
        ) {
            // Tweet Header
            TweetItemHeader(tweet)

            Spacer(modifier = Modifier.padding(2.dp))
            Surface(
                shape = MaterialTheme.shapes.small, // Inner border
                tonalElevation = 0.dp,
                modifier = Modifier
                    .padding(start = 16.dp, top = 0.dp, bottom = 0.dp, end = 16.dp)
            ) {
                Column {
                    tweet.content?. let {
                        Text(text = it, style = MaterialTheme.typography.bodyMedium)
                    }
                    // attached media files
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 800.dp) // Set a specific height for the grid
                    ) {
                        val mediaItems = tweet.attachments?.mapNotNull {
                            tweet.author?.baseUrl?.let { it1 -> getMediaUrl(it, it1).toString() }
                                ?.let { it2 -> MediaItem(it2) }
                        }
                        mediaItems?.let { MediaPreviewGrid(it) }
                    }

                    // Actions Row
                    if (!isQuoted) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 0.dp)
                        ) {
                            // State hoist
                            LikeButton(viewModel)
                            Spacer(modifier = Modifier.width(8.dp))
                            BookmarkButton(viewModel)
                            Spacer(modifier = Modifier.width(8.dp))
                            CommentButton(viewModel)
                            Spacer(modifier = Modifier.width(8.dp))
                            RetweetButton(viewModel)
                        }
                    }
                }
            }
        }
    }
}