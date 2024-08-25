package com.fireshare.tweet.tweet

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.fireshare.tweet.LocalNavController
import com.fireshare.tweet.UserProfile
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.network.HproseInstance.getMediaUrl
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.widget.MediaItem
import com.fireshare.tweet.widget.MediaPreviewGrid

@Composable
fun TweetBlock(tweet: Tweet, viewModel: TweetViewModel) {
    Surface(
        // Apply border to the entire TweetBlock
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.clickable(onClick = {
                println("show detail")
            })) {
        Column(
            modifier = Modifier
                .padding(8.dp)
        ) {
            // Tweet Header
            TweetHeader(tweet)

            Spacer(modifier = Modifier.padding(4.dp))
            Surface(
                shape = MaterialTheme.shapes.small, // Inner border
                tonalElevation = 0.dp,
                modifier = Modifier
                    .padding(start = 16.dp, top = 0.dp, bottom = 0.dp, end = 16.dp)
                    .clickable(onClick = { /* Handle inner column click */ })
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
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // State hoist
                        LikeButton(tweet, viewModel)
                        Spacer(modifier = Modifier.width(8.dp))
                        BookmarkButton(tweet, viewModel)
                        Spacer(modifier = Modifier.width(8.dp))
                        CommentButton(tweet, viewModel)
                        Spacer(modifier = Modifier.width(8.dp))
                        RetweetButton(tweet, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
fun TweetHeader(tweet: Tweet) {
    // Use a Row to align author name and potential verification badge
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        val navController = LocalNavController.current
        val author = tweet.author
        IconButton(onClick = { navController.navigate(UserProfile(tweet.authorId)) })
        {
            author?.baseUrl?.let { getMediaUrl(author.avatar, it) }?.let {
                Image(
                    painter = rememberAsyncImagePainter(author.baseUrl?.let { getMediaUrl(
                        author.avatar, it) }),
                    contentDescription = "User Avatar",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                )
            }
        }
        Spacer(modifier = Modifier.padding(horizontal = 2.dp))
        Column {
            Text(text = author?.name ?: "No One", style = MaterialTheme.typography.bodyMedium)
            Text(text = "@${author?.username}", style = MaterialTheme.typography.bodySmall)
        }
    }
}