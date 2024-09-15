package com.fireshare.tweet.tweet

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.HproseInstance.getMediaUrl
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.widget.MediaItem
import com.fireshare.tweet.widget.MediaPreviewGrid
import com.fireshare.tweet.widget.UserAvatar

@Composable
fun TweetDetailHead(tweet: Tweet, viewModel: TweetViewModel) {
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()
    val navController = LocalNavController.current

    Surface(
        // Apply border to the entire TweetBlock
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp
    ) {
        Column( modifier = Modifier
            .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)
        ) {
            // Tweet detail Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    val author = tweet.author
                    IconButton(onClick = { navController.navigate(NavTweet.UserProfile(tweet.authorId)) })
                    {
                        UserAvatar(author, 40)
                    }
                    Spacer(modifier = Modifier.padding(horizontal = 2.dp))
                    Text(
                        text = author?.name ?: "No One",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(modifier = Modifier.padding(horizontal = 2.dp))
                    Text(text = "@${author?.username}", style = MaterialTheme.typography.bodySmall)
                }
                // the 3 dots at the right end
                TweetDropdownMenu(tweet, tweetFeedViewModel, navController)
            }
            // Tweet detail's content
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
fun TweetDropdownMenu(tweet: Tweet, tweetFeedViewModel: TweetFeedViewModel, navController: NavController) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            modifier = Modifier.width(36.dp).alpha(0.8f).rotate(-90f)
                .padding(end = 12.dp),
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
            if (tweet.author?.mid == appUser.mid) {
                DropdownMenuItem( modifier = Modifier.alpha(0.7f),
                    onClick = {
                        tweet.mid?.let {
                            tweetFeedViewModel.delTweet(it)
                            navController.popBackStack()
                        } },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete",
                                tint = Color.Red
                            )
                            Spacer(modifier = Modifier.width(8.dp)) // Add some space between the icon and the text
                            Text("Delete", color = Color.Red)
                        }
                    }
                )
            }
        }
    }
}