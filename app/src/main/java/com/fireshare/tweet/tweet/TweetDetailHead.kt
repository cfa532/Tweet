package com.fireshare.tweet.tweet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fireshare.tweet.HproseInstance.getMediaUrl
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.widget.MediaItem
import com.fireshare.tweet.widget.MediaPreviewGrid
import com.fireshare.tweet.widget.UserAvatar

@Composable
fun TweetDetailHead(tweet: Tweet, viewModel: TweetViewModel) {

    Surface(
        // Apply border to the entire TweetBlock
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp
    ) {
        Column( modifier = Modifier
            .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)
        ) {
            // Tweet Header
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                val author = tweet.author
                val navController = LocalNavController.current
                IconButton(onClick = { navController.navigate(NavTweet.UserProfile(tweet.authorId)) })
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
                modifier = Modifier
                    .padding(start = 16.dp, top = 0.dp, bottom = 0.dp, end = 4.dp)
            ) {
                Column {
                    tweet.content?. let {
                        Text(text = it, style = MaterialTheme.typography.bodyMedium)
                    }
                    Box(
                        // media files
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
                        modifier = Modifier.fillMaxWidth()
                            .padding(top = 0.dp)
                    ) {
                        // State hoist
                        LikeButton( viewModel)
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

@Composable
fun TweetDetailHeader(tweet: Tweet) {
    // Use a Row to align author name and potential verification badge
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        val author = tweet.author
        UserAvatar(author, 40)
        Spacer(modifier = Modifier.padding(horizontal = 2.dp))
        Text(text = author?.name ?: "No One", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.padding(horizontal = 2.dp))
        Text(text = "@${author?.username}", style = MaterialTheme.typography.bodySmall)
    }
}