package com.fireshare.tweet.tweet

import androidx.compose.foundation.Image
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter

import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.network.HproseInstance.getMediaUrl
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.widget.MediaItem
import com.fireshare.tweet.widget.MediaPreviewGrid

@Composable
fun TweetBody(tweet: Tweet, viewModel: TweetViewModel) {
    // Tweet Header
    TweetHeader(tweet)
    Spacer(modifier = Modifier.padding(8.dp))
    Column(
        modifier = Modifier.padding(start = 12.dp)
    ) {
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

        // Use a Row to display likes and bookmarks horizontally
        tweet.let {
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                // state hoist
                LikeButton(it, viewModel)
                Spacer(modifier = Modifier.width(8.dp)) // Add some space between the two texts
                BookmarkButton(it, viewModel)
                Spacer(modifier = Modifier.width(8.dp))
                CommentButton(it, viewModel)
                Spacer(modifier = Modifier.width(8.dp))
                RetweetButton(it, viewModel)
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
        tweet.author?.baseUrl?.let { getMediaUrl(tweet.author?.avatar, it) }?.let {
            Image(
                painter = rememberAsyncImagePainter(it),
                contentDescription = "User Avatar",
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(modifier = Modifier.padding(horizontal = 6.dp))
        Text(text = tweet.author?.name ?: "No One", style = MaterialTheme.typography.bodyMedium)
    }
}