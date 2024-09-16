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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
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
                .padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 4.dp)
        ) {
            // Tweet Header
            TweetItemHeader(tweet)

            Spacer(modifier = Modifier.padding(2.dp))
            Surface(
                shape = MaterialTheme.shapes.small, // Inner border
                tonalElevation = 0.dp,
                modifier = Modifier
                    .padding(start = 20.dp, top = 4.dp, bottom = 0.dp, end = 4.dp)
            ) {
                Column {

                    // Text content of the tweet
                    if (tweet.content?.isNotEmpty() == true) {
                        tweet.content?.let { txt ->
                            var isExpanded by remember { mutableStateOf(false) }
                            val maxLines = if (isExpanded) Int.MAX_VALUE else 11
                            Text(
                                text = txt,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = maxLines,
//                                overflow = TextOverflow.Ellipsis
                            )

                            if (!isExpanded && txt.lines().size > 10) {
                                Text(
                                    text = "Show more...",
                                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.clickable {
//                                        isExpanded = true
                                        tweet.mid?.let { navController.navigate(NavTweet.TweetDetail(it)) }
                                    }
                                )
                            }
                        }
                    }
                    // attached media files
                    Box(
                        modifier = Modifier.fillMaxWidth()
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
