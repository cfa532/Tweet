package us.fireshare.tweet.tweet

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.profile.SimpleAvatar
import us.fireshare.tweet.viewmodel.TweetViewModel
import us.fireshare.tweet.widget.MediaPreviewGrid
import us.fireshare.tweet.widget.SelectableText

@Composable
fun CommentItem(
    comment: Tweet,
    parentTweetViewModel: TweetViewModel?,
    parentEntry: NavBackStackEntry
) {
    val navController = LocalNavController.current

    // viewModel of current Comment, which is a Tweet object
    val viewModel = hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
        parentEntry,
        key = comment.mid
    ) { factory ->
        factory.create(comment)
    }
    val author = comment.author ?: User(mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl)

    Column(
        modifier = Modifier
            .clickable { navController.navigate(NavTweet.TweetDetail(comment.authorId, comment.mid)) }
            .padding(horizontal = 4.dp)
            .heightIn(max = 80000.dp) // Limit individual comment height
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.Start
        ) {
            IconButton(onClick = {
                navController.navigate(NavTweet.UserProfile(comment.authorId)) }
            ) {
                SimpleAvatar(user = author, size = 32)
            }
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 0.dp, top = 8.dp, end = 4.dp, bottom = 0.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(end = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.padding(bottom = 0.dp),
                    ) {
                        Text(
                            text = author?.name ?: "No One",
                            modifier = Modifier.padding(horizontal = 0.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = "@${author?.username}",
                            modifier = Modifier.padding(horizontal = 2.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(text = " • ", fontSize = 12.sp)
                        Text(
                            text = localizedTimeDifference(comment.timestamp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    CommentDropdownMenu(comment, parentTweetViewModel)
                }

                if (!comment.content.isNullOrEmpty()) {
                    SelectableText(text = comment.content!!, maxLines = 10) { username ->
                        viewModel.viewModelScope.launch(Dispatchers.IO) {
                            HproseInstance.getUserId(username)?.let {
                                withContext(Dispatchers.Main) {
                                    navController.navigate(NavTweet.UserProfile(it))
                                }
                            }
                        }
                    }
                }
                // attached media files
                comment.attachments?.let {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                            .heightIn(max = 600.dp) // Max height for media grid in comments
                    ) {
                        MediaPreviewGrid(it, viewModel)
                    }
                }
            }
        }
        // Actions Row
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 40.dp, end = 4.dp, bottom = 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            LikeButton(viewModel)
            BookmarkButton(viewModel)
            CommentButton(viewModel)
            RetweetButton(viewModel)
            Spacer(modifier = Modifier.width(40.dp))
            ShareButton(viewModel)
        }
    }
}

@Composable
fun CommentDropdownMenu(comment: Tweet, parentTweetViewModel: TweetViewModel?) {
    // Use comment.mid as key to ensure state is reset when comment changes
    var expanded by remember(comment.mid) { mutableStateOf(false) }
    val parentTweet by parentTweetViewModel?.tweetState?.collectAsState() ?: remember { mutableStateOf(null) }

    // Dismiss popup menu when comment is deleted or becomes unavailable
    LaunchedEffect(comment.mid) {
        // Reset expanded state when comment changes
        expanded = false
    }

    Box {
        // the 3 dots button on the right
        IconButton(
            modifier = Modifier
                .size(24.dp)
                .alpha(0.9f)
                .rotate(-90f),
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
            if (parentTweet?.authorId == appUser.mid || comment.authorId == appUser.mid) {
                DropdownMenuItem( modifier = Modifier.alpha(0.9f),
                    onClick = {
                        // Dismiss popup immediately for better UX
                        expanded = false
                        
                        parentTweetViewModel?.viewModelScope?.launch(Dispatchers.IO) {
                            try {
                                parentTweetViewModel.delComment(comment.mid)
                            } catch (e: Exception) {
                                Timber.tag("CommentDropdownMenu").e(e, "Error deleting comment: ${e.message}")
                            }
                        }
                    },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                modifier = Modifier.padding(start = 8.dp),
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