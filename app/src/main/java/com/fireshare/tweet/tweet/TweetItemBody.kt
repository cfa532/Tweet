package com.fireshare.tweet.tweet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import com.fireshare.tweet.HproseInstance.getMediaUrl
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.widget.MediaItem
import com.fireshare.tweet.widget.MediaPreviewGrid

@Composable
fun TweetBlock(
    viewModel: TweetViewModel,
    parentEntry: NavBackStackEntry,
    isQuoted: Boolean = false,     // the block is a quoted tweet or not
    parentTweet: Tweet? = null,    // the parent tweet of the quoted original tweet
) {
    val navController = LocalNavController.current
    val tweet by viewModel.tweetState.collectAsState()
    // fold text content up to 9 lines. Open it upon user click.
    var isExpanded by remember { mutableStateOf(false) }

    Surface(
        // Apply border to the entire TweetBlock
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 0.dp,
        modifier = Modifier.clickable(
            onClick = {
                tweet.mid?.let { navController.navigate(NavTweet.TweetDetail(it) ) }
            })
            .padding(top = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(start = 4.dp, end = 4.dp, top = 0.dp, bottom = 0.dp)
        ) {
            // Tweet Header. Icon, name, timestamp, more actions
            TweetItemHeader(tweet, parentEntry, parentTweet)

            Surface(
                shape = MaterialTheme.shapes.small, // Inner border
                tonalElevation = 0.dp,
                modifier = Modifier
                    .padding(start = 20.dp, top = 2.dp, bottom = 4.dp, end = 4.dp)
            ) {
                Column {
                    // Text content of the tweet
                    if (tweet.content != null && tweet.content!!.isNotEmpty()) {
                        val maxLines = if (isExpanded) Int.MAX_VALUE else 9
                        var lineCount by remember { mutableIntStateOf(0) }
                        Text(
                            text = tweet.content!!,
                            onTextLayout = { textLayoutResult ->
                                lineCount = textLayoutResult.lineCount
                            },
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = maxLines,
                        )
                        if (!isExpanded && lineCount > 8) {
                            Text(
                                text = stringResource(R.string.show_more),
                                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.clickable {
                                    isExpanded = true
                                }
                            )
                        }
                    }
                    // attached media files
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .padding(top = 8.dp)
                            .wrapContentHeight()
//                            .heightIn(max = 800.dp)
                    ) {
                        val mediaItems = tweet.attachments?.map {
                            MediaItem(getMediaUrl(it.mid, tweet.author?.baseUrl.orEmpty()).toString(),
                                it.type)
                        } ?: emptyList()

                        tweet.mid?.let {
                            MediaPreviewGrid(mediaItems, it)
                        }
                    }

                    /**
                     * If the tweet being displayed is quoted by other tweet, do not show buttons
                     * */
                    if (!isQuoted) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            LikeButton(viewModel)
                            BookmarkButton(viewModel)
                            CommentButton(viewModel)
                            RetweetButton(viewModel)
                            Spacer(modifier = Modifier.width(20.dp))
                            ShareButton(viewModel)
//                            ShareScreenshotButton(viewModel)
                        }
                    }
                }
            }
        }
    }
}
