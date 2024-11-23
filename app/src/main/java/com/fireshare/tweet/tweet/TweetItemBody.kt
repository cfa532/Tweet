package com.fireshare.tweet.tweet

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.getMediaUrl
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.widget.Gadget.buildAnnotatedText
import com.fireshare.tweet.widget.MediaItem
import com.fireshare.tweet.widget.MediaPreviewGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TweetItemBody(
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
        modifier = Modifier
            .clickable(
                onClick = {
                    navController.navigate(NavTweet.TweetDetail(tweet.mid))
                })
            .padding(top = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(start = 4.dp, end = 4.dp, top = 0.dp, bottom = 0.dp)
        ) {

            // Tweet Header. Icon, name, timestamp, more actions
            TweetItemHeader(viewModel, parentEntry, parentTweet)

            Surface(
                shape = MaterialTheme.shapes.small, // Inner border
                tonalElevation = 0.dp,
                modifier = Modifier
                    .padding(start = 20.dp, top = 2.dp, bottom = 4.dp, end = 8.dp)
            ) {
                Column {
                    // Text content of the tweet
                    if (!tweet.content.isNullOrEmpty()) {
                        val maxLines = if (isExpanded) Int.MAX_VALUE else 9
                        var lineCount by remember { mutableIntStateOf(0) }
                        val annotatedText = buildAnnotatedText(tweet.content!!)
                        var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                        SelectionContainer {
                            BasicText(
                                text = annotatedText,
                                onTextLayout = { textLayoutResult ->
                                    lineCount = textLayoutResult.lineCount
                                    layoutResult = textLayoutResult
                                },
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = maxLines,
                                modifier = Modifier.clickable {
                                    layoutResult?.let { textLayoutResult ->
                                        val position = textLayoutResult.getOffsetForPosition(
                                            Offset(0f, 0f)
                                        )
                                        // Get the annotations at the clicked position
                                        val annotations = annotatedText.getStringAnnotations(
                                            tag = "USERNAME_CLICK", start = position, end = position
                                        )
                                        // If we have an annotation, it means a username was clicked
                                        if (annotations.isNotEmpty()) {
                                            val username = annotations[0].item
                                            viewModel.viewModelScope.launch {
                                                HproseInstance.getUserId(username)?.let {
                                                    navController.navigate(NavTweet.UserProfile(it))
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
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
                    // there are attached media files
                    if (!tweet.attachments.isNullOrEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .wrapContentHeight()
                                .heightIn(min = 100.dp, max = 400.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceTint,
                                    shape = MaterialTheme.shapes.medium
                                )
                        ) {
                            val mediaItems = tweet.attachments?.map {
                                MediaItem(
                                    getMediaUrl(it.mid, tweet.author?.baseUrl.orEmpty()).toString(),
                                    it.type
                                )
                            } ?: emptyList()
                            MediaPreviewGrid(mediaItems, tweet.mid)
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
                            Spacer(modifier = Modifier.width(40.dp))
                            ShareButton(viewModel)
//                            ShareScreenshotButton(viewModel)
                        }
                    }
                }
            }
        }
    }
}
