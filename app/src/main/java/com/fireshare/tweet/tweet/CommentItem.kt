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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import com.fireshare.tweet.HproseInstance.getMediaUrl
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.widget.MediaItem
import com.fireshare.tweet.widget.MediaPreviewGrid
import com.fireshare.tweet.navigation.NavigationItem
import com.fireshare.tweet.widget.UserAvatar
import com.fireshare.tweet.navigation.UserProfile

@Composable
fun CommentItem(
    comment: Tweet,
    parentEntry: NavBackStackEntry
) {
    val navController = LocalNavController.current
    val viewModel = hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(parentEntry, key = comment.mid) { factory ->
        factory.create(comment)
    }
    val author = comment.author

    // this viewModel is a comment Item.
    Column(
        modifier = Modifier.clickable(onClick = {
            comment.mid?.let {navController.navigate(NavigationItem.TweetDetail(it))}
        } )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            IconButton(onClick = { navController.navigate(UserProfile(comment.authorId)) })
            {
                UserAvatar(author, 40)
            }
            Spacer(modifier = Modifier.padding(horizontal = 2.dp))
            Text(text = author?.name ?: "No One", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.padding(horizontal = 2.dp))
            Text(text = "@${author?.username}", style = MaterialTheme.typography.bodySmall)
        }

        Column(modifier = Modifier.padding(start = 20.dp, bottom = 0.dp))
        {
            Text(text = comment.content, style = MaterialTheme.typography.bodyMedium)

            // attached media files
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 800.dp) // Set a specific height for the grid
            ) {
                val mediaItems = comment.attachments?.mapNotNull {
                    comment.author?.baseUrl?.let { it1 -> getMediaUrl(it, it1).toString() }
                        ?.let { it2 -> MediaItem(it2) }
                }
                mediaItems?.let { MediaPreviewGrid(it) }
            }

            // Actions Row
            Row(
                modifier = Modifier.fillMaxWidth()
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