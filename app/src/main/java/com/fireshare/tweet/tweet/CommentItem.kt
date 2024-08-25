package com.fireshare.tweet.tweet

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.rememberAsyncImagePainter
import com.fireshare.tweet.LocalNavController
import com.fireshare.tweet.UserProfile
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.network.HproseInstance.getMediaUrl
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.widget.MediaItem
import com.fireshare.tweet.widget.MediaPreviewGrid

@Composable
fun CommentItem(tweet: Tweet) {
    val navController = LocalNavController.current
    val author = tweet.author
    val viewModel = hiltViewModel<TweetViewModel>(key = tweet.mid)
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            IconButton(onClick = { navController.navigate(UserProfile(tweet.authorId)) })
            {
                author?.baseUrl?.let { getMediaUrl(author.avatar, it) }?.let {
                    Image(
                        painter = rememberAsyncImagePainter(author.baseUrl?.let {
                            getMediaUrl(
                                author.avatar, it
                            )
                        }),
                        contentDescription = "User Avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                    )
                }
            }
            Spacer(modifier = Modifier.padding(horizontal = 2.dp))
            Text(text = author?.name ?: "No One", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.padding(horizontal = 2.dp))
            Text(text = "@${author?.username}", style = MaterialTheme.typography.bodySmall)
        }
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