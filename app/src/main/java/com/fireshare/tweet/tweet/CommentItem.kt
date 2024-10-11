package com.fireshare.tweet.tweet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.HproseInstance.getMediaUrl
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.widget.MediaItem
import com.fireshare.tweet.widget.MediaPreviewGrid
import com.fireshare.tweet.widget.UserAvatar

@Composable
fun CommentItem(
    comment: Tweet,
    parentTweetViewModel: TweetViewModel,
    parentEntry: NavBackStackEntry
) {
    val navController = LocalNavController.current

    // viewModel of current Comment, which is a Tweet object
    val viewModel = hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(parentEntry, key = comment.mid) { factory ->
        factory.create(comment)
    }
    val author = comment.author
    val parentTweet by parentTweetViewModel.tweetState.collectAsState()

    Column(
        modifier = Modifier.clickable(onClick = {
            comment.mid?.let {navController.navigate(NavTweet.TweetDetail(it))}
        } )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(onClick = { navController.navigate(NavTweet.UserProfile(comment.authorId)) })
                {
                    UserAvatar(author, 32)
                }
                Text(text = author?.name ?: "No One",
                    modifier = Modifier.padding(horizontal = 0.dp),
                    style = MaterialTheme.typography.labelMedium
                )
                Text(text = "@${author?.username}",
                    modifier = Modifier.padding(horizontal = 2.dp),
                    style = MaterialTheme.typography.labelSmall
                )
                Text( text = " • ", fontSize = 12.sp)
                Text(text = localizedTimeDifference(comment.timestamp),
                    style = MaterialTheme.typography.labelSmall)
            }
            CommentDropdownMenu(comment, parentTweetViewModel)
        }
        Column(modifier = Modifier.padding(start = 20.dp, bottom = 0.dp))
        {
            comment.content?. let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
            // attached media files
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 4.dp, end = 8.dp)
                    .heightIn(max = 800.dp) // Set a specific height for the grid
            ) {
                val mediaItems = comment.attachments?.mapNotNull { attachment ->
                    comment.author?.baseUrl?.let { baseUrl ->
                        val mediaUrl = getMediaUrl(attachment.mid, baseUrl).toString()
                        MediaItem(mediaUrl, attachment.type)
                    }
                }
                mediaItems?.let { MediaPreviewGrid(it, parentTweet.mid!!) }
            }

            // Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                // State hoist
                LikeButton(viewModel)
                BookmarkButton(viewModel)
                CommentButton(viewModel)
                RetweetButton(viewModel)
                Spacer(modifier = Modifier.width(60.dp))
                ShareButton(viewModel)
            }
        }
    }
}

@Composable
fun CommentDropdownMenu(comment: Tweet, parentTweetViewModel: TweetViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            modifier = Modifier.width(24.dp).alpha(0.8f).rotate(-90f),
            onClick = { expanded = !expanded }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More",
                tint = Color.Gray
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .wrapContentWidth(align = Alignment.End)
                .height(IntrinsicSize.Min)
        ) {
            if (comment.author?.mid == appUser.mid) {
                DropdownMenuItem( modifier = Modifier.alpha(0.7f),
                    onClick = { comment.mid?.let { parentTweetViewModel.delComment(it) } },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp)) // Add some space between the icon and the text
                            Text(
                                text = stringResource(R.string.delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
            }
        }
    }
}