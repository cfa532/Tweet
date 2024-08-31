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
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fireshare.tweet.LocalNavController
import com.fireshare.tweet.LocalViewModelProvider
import com.fireshare.tweet.SharedTweetViewModel
import com.fireshare.tweet.TweetDetail
import com.fireshare.tweet.UserProfile
import com.fireshare.tweet.network.HproseInstance.getMediaUrl
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.widget.MediaItem
import com.fireshare.tweet.widget.MediaPreviewGrid
import com.fireshare.tweet.widget.UserAvatar

@Composable
fun TweetBlock(viewModel: TweetViewModel) {
    val navController = LocalNavController.current
    val tweet by viewModel.tweetState.collectAsState()

    Surface(
        // Apply border to the entire TweetBlock
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.clickable(onClick = {
            tweet.mid?.let { navController.navigate(TweetDetail(it)) }
        })
    ) {
        Column(
            modifier = Modifier
                .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)
        ) {
            // Tweet Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                val author = tweet.author
                IconButton(onClick = { navController.navigate(UserProfile(tweet.authorId)) })
                {
                    UserAvatar(author, 40)
                }
                Spacer(modifier = Modifier.padding(horizontal = 2.dp))
                Text(text = author?.name ?: "No One", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.padding(horizontal = 2.dp))
                Text(text = "@${author?.username}", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.padding(2.dp))
            Surface(
                shape = MaterialTheme.shapes.small, // Inner border
                tonalElevation = 0.dp,
                modifier = Modifier
                    .padding(start = 16.dp, top = 0.dp, bottom = 0.dp, end = 16.dp)
            ) {
                Column {
                    Text(text = tweet.content, style = MaterialTheme.typography.bodyMedium)

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
                        Spacer(modifier = Modifier.width(8.dp))
                        UpdateTweetButton(viewModel)
                    }
                }
            }
        }
    }
}

@Composable
fun UpdateTweetButton(viewModel: TweetViewModel) {
    Button(onClick = {
        val updatedTweet =
            viewModel.tweetState.value.copy(commentCount = viewModel.tweetState.value.commentCount + 1)
        viewModel.updateTweet(updatedTweet)
    }) {
        Text("comment")
    }
}
