package us.fireshare.tweet.tweet

import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import us.fireshare.tweet.datamodel.TweetCacheManager
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.profile.UserAvatar
import us.fireshare.tweet.viewmodel.TweetViewModel
import us.fireshare.tweet.widget.MediaGrid
import us.fireshare.tweet.widget.SelectableText

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun CommentItem(
    comment: Tweet,
    parentTweetViewModel: TweetViewModel?,
    parentEntry: NavBackStackEntry,
    isLast: Boolean = false
) {
    val navController = LocalNavController.current

    // viewModel of current Comment, which is a Tweet object
    val viewModel = hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
        parentEntry,
        key = comment.mid
    ) { factory ->
        factory.create(comment)
    }
    
    // Observe author changes reactively via StateFlow
    // This ensures all comments from the same author update when user data becomes available
    val authorStateFlow = remember(comment.authorId) {
        TweetCacheManager.getUserStateFlow(comment.authorId)
    }
    val author by authorStateFlow.collectAsState(initial = User(mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl))

    Column(
        modifier = Modifier
            .clickable { 
                navController.navigate(
                    NavTweet.TweetDetail(
                        authorId = comment.authorId,
                        tweetId = comment.mid,
                        parentTweetId = parentTweetViewModel?.tweetState?.value?.mid,
                        parentAuthorId = parentTweetViewModel?.tweetState?.value?.authorId
                    )
                )
            }
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
                UserAvatar(user = author ?: User(mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl), size = 32)
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
                        modifier = Modifier.padding(bottom = 0.dp, top = 2.dp),
                    ) {
                        Text(
                            text = author?.name ?: "No One",
                            modifier = Modifier.padding(horizontal = 0.dp),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = " @${author?.username ?: "unknown"}",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = " · ${localizedTimeDifference(comment.timestamp)}",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    CommentDropdownMenu(comment, parentTweetViewModel)
                }

                if (!comment.content.isNullOrEmpty()) {
                    SelectableText(
                        text = comment.content!!,
                        maxLines = 7,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                        onTextClick = {
                            navController.navigate(
                                NavTweet.TweetDetail(
                                    authorId = comment.authorId,
                                    tweetId = comment.mid,
                                    parentTweetId = parentTweetViewModel?.tweetState?.value?.mid,
                                    parentAuthorId = parentTweetViewModel?.tweetState?.value?.authorId
                                )
                            )
                        }
                    ) { username ->
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
                if (!comment.attachments.isNullOrEmpty()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .heightIn(min = 20.dp, max = 600.dp), // Max height for media grid in comments
                        tonalElevation = 4.dp,
                        shape = RoundedCornerShape(size = 8.dp)
                    ) {
                        MediaGrid(
                            comment.attachments!!,
                            viewModel,
                            parentTweetId = comment.mid
                        )
                    }
                }
            }
        }
        // Actions Row
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 40.dp, end = 4.dp, bottom = if (isLast) 24.dp else 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            CommentButton(viewModel)
            RetweetButton(viewModel)
            LikeButton(viewModel)
            BookmarkButton(viewModel)
            Spacer(modifier = Modifier.width(40.dp))
            // Pass parent tweet info to ShareButton for comments
            ShareButton(
                viewModel = viewModel,
                parentTweetId = parentTweetViewModel?.tweetState?.collectAsState()?.value?.mid,
                parentAuthorId = parentTweetViewModel?.tweetState?.collectAsState()?.value?.authorId
            )
        }
    }
}

@Composable
fun CommentDropdownMenu(comment: Tweet, parentTweetViewModel: TweetViewModel?) {
    // Use comment.mid as key to ensure state is reset when comment changes
    var expanded by remember(comment.mid) { mutableStateOf(false) }
    val parentTweet by parentTweetViewModel?.tweetState?.collectAsState()
        ?: remember { mutableStateOf(null) }
    val context = LocalContext.current
    
    // Capture string resource at composable level to avoid context capture warnings
    val deleteFailedMessage = stringResource(R.string.comment_delete_failed)

    // Dismiss popup menu when comment is deleted or becomes unavailable
    LaunchedEffect(comment.mid) {
        // Reset expanded state when comment changes
        expanded = false
    }

    Box (
        modifier = Modifier.padding(end = 0.dp)
    ) {
        // the 3 dots button on the right
        IconButton(
            modifier = Modifier
                .size(16.dp)
                .alpha(0.7f),
            onClick = { expanded = !expanded }) {
            Icon(
                painter = painterResource(R.drawable.ellipsis),
                contentDescription = stringResource(R.string.more_options),
                tint = MaterialTheme.colorScheme.primary
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
                DropdownMenuItem(
                    modifier = Modifier.alpha(0.9f),
                    onClick = {
                        // Dismiss popup immediately for better UX
                        expanded = false

                        // Delete comment with optimistic updates
                        parentTweetViewModel?.let { viewModel ->
                            viewModel.viewModelScope.launch(Dispatchers.IO) {
                                try {
                                    viewModel.deleteComment(comment.mid, comment)
                                } catch (e: Exception) {
                                    Timber.tag("CommentDropdownMenu")
                                        .e(e, "Error deleting comment: ${e.message}")
                                    // Show toast message on main thread
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            deleteFailedMessage,
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }
                    },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.delete),
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