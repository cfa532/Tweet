package us.fireshare.tweet.tweet

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.UserActions
import us.fireshare.tweet.navigation.ComposeComment
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.requireAuthenticatedUser
import us.fireshare.tweet.navigation.SharedViewModel
import us.fireshare.tweet.utils.CountFormatUtils
import us.fireshare.tweet.viewmodel.ShareLinkStyle
import us.fireshare.tweet.viewmodel.TweetViewModel

private val TweetActionIconSize = 21.dp
private val TweetActionButtonWidth = 54.dp
private val TweetActionShareButtonWidth = 48.dp

@Composable
fun CommentButton(
    viewModel: TweetViewModel,
    onExpandReply: (() -> Unit)? = null,
    color: Color? = null
) {
    val tweet by viewModel.tweetState.collectAsState()
    val count by remember {
        derivedStateOf { tweet.commentCount }
    }
    val countText = if (count > 0) CountFormatUtils.formatCount(count) else ""
    val navController = LocalNavController.current
    val context = LocalContext.current
    val sharedViewModel: SharedViewModel = hiltViewModel()
    val guestReminderText = stringResource(R.string.guest_reminder)

    IconButton(
        modifier = Modifier.width(TweetActionButtonWidth),
        onClick = {
            if (!requireAuthenticatedUser(context, navController, guestReminderText)) {
                return@IconButton
            }

            // If onExpandReply callback is provided, use it (for TweetDetailScreen)
            if (onExpandReply != null) {
                onExpandReply()
            } else {
                // Otherwise, navigate to separate compose screen (for other screens)
                sharedViewModel.tweetViewModel = viewModel
                navController.navigate(ComposeComment(tweet.mid))
            }
        }
    ) {
        Row(
            modifier = Modifier.width(TweetActionButtonWidth),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_ms_comment),
                contentDescription = stringResource(R.string.comments),
                modifier = Modifier.size(TweetActionIconSize),
                tint = color ?: MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = countText,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                color = color ?: MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.width(28.dp)
            )
        }
    }
}

@Composable
fun RetweetButton(viewModel: TweetViewModel, color: Color? = null) {
    val tweet by viewModel.tweetState.collectAsState()
    val count by remember {
        derivedStateOf { tweet.retweetCount }
    }
    val countText = if (count > 0) CountFormatUtils.formatCount(count) else ""
    val hasRetweeted = tweet.favorites?.get(UserActions.RETWEET) ?: false
    val navController = LocalNavController.current
    val context = LocalContext.current
    val errorMessage = stringResource(R.string.tweet_failed)
    val guestReminderText = stringResource(R.string.guest_reminder)
    val retweetContentColor = if (hasRetweeted) {
        MaterialTheme.colorScheme.primary
    } else {
        color ?: MaterialTheme.colorScheme.secondary
    }

    IconButton(
        modifier = Modifier.width(TweetActionButtonWidth),
        onClick = {
            if (!requireAuthenticatedUser(context, navController, guestReminderText)) {
                return@IconButton
            }

            viewModel.viewModelScope.launch(Dispatchers.IO) {
                try {
                    // The retweet will be added to feed automatically via notification system
                    viewModel.retweetTweet()
                    Timber.tag("RetweetButton").d("Retweet action completed")
                } catch (e: Exception) {
                    Timber.tag("RetweetButton").e(e, "Failed to retweet tweet ${tweet.mid}")
                    // Show error message to user
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    ) {
        Row(
            modifier = Modifier.width(TweetActionButtonWidth),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_ms_retweet),
                contentDescription = stringResource(R.string.forward),
                modifier = Modifier.size(TweetActionIconSize),
                tint = retweetContentColor
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = countText,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                color = retweetContentColor,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.width(28.dp)
            )
        }
    }
}

@Composable
fun LikeButton(viewModel: TweetViewModel, color: Color? = null) {
    val tweet by viewModel.tweetState.collectAsState()
    val count = tweet.favoriteCount
    val countText = if (count > 0) CountFormatUtils.formatCount(count) else ""
    val isFavorite = tweet.favorites?.get(UserActions.FAVORITE) ?: false
    val navController = LocalNavController.current
    val context = LocalContext.current
    val sharedViewModel = hiltViewModel<SharedViewModel>()
    val appUserViewModel = sharedViewModel.appUserViewModel
    val guestReminderText = stringResource(R.string.guest_reminder)

    IconButton(
        modifier = Modifier.width(TweetActionButtonWidth),
        onClick = {
            if (!requireAuthenticatedUser(context, navController, guestReminderText)) {
                return@IconButton
            }

            viewModel.viewModelScope.launch(Dispatchers.IO) {
                viewModel.toggleFavorite { tweet, isFavorite, phase ->
                    appUserViewModel.updateFavorite(tweet, isFavorite, phase)
                }
            }
        }
    ) {
        Row(
            modifier = Modifier.width(TweetActionButtonWidth),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(
                    id = if (isFavorite) R.drawable.ic_ms_heart_filled else R.drawable.ic_ms_heart
                ),
                contentDescription = stringResource(R.string.like),
                modifier = Modifier.size(TweetActionIconSize),
                tint = if (isFavorite) Color(0xFFBB5555) else color ?: MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = countText,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                color = if (isFavorite) Color(0xFFBB5555) else color
                    ?: MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.width(28.dp)
            )
        }
    }
}

@Composable
fun BookmarkButton(viewModel: TweetViewModel, color: Color? = null) {
    val tweet by viewModel.tweetState.collectAsState()
    val count by remember { derivedStateOf { tweet.bookmarkCount } }
    val countText = if (count > 0) CountFormatUtils.formatCount(count) else ""
    val hasBookmarked = tweet.favorites?.get(UserActions.BOOKMARK) ?: false
    val navController = LocalNavController.current
    val context = LocalContext.current
    val sharedViewModel = hiltViewModel<SharedViewModel>()
    val appUserViewModel = sharedViewModel.appUserViewModel
    val guestReminderText = stringResource(R.string.guest_reminder)

    IconButton(
        modifier = Modifier.width(TweetActionButtonWidth),
        onClick = {
            if (!requireAuthenticatedUser(context, navController, guestReminderText)) {
                return@IconButton
            }

            viewModel.viewModelScope.launch(Dispatchers.IO) {
                viewModel.toggleBookmark { tweet, isBookmarked, phase ->
                    appUserViewModel.updateBookmark(tweet, isBookmarked, phase)
                }
            }
        }
    ) {
        Row(
            modifier = Modifier.width(TweetActionButtonWidth),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(
                    id = if (hasBookmarked) R.drawable.ic_ms_bookmark_filled else R.drawable.ic_ms_bookmark
                ),
                contentDescription = stringResource(R.string.like),
                modifier = Modifier.size(TweetActionIconSize)
                    .padding(bottom = 1.dp),
                tint = if (hasBookmarked) color ?: Color(0xFF4477BB) else color ?: MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = countText,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                color = if (hasBookmarked) color ?: Color(0xFF4477BB) else color
                    ?: MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.width(28.dp)
            )
        }
    }
}

@Composable
fun ShareButton(
    viewModel: TweetViewModel,
    color: Color? = null,
    parentTweetId: String? = null,
    parentAuthorId: String? = null,
    linkStyle: ShareLinkStyle = ShareLinkStyle.WEB_DOMAIN
) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isSharing by viewModel.isSharing.collectAsState()
    val guestReminderText = stringResource(R.string.guest_reminder)

    IconButton(
        modifier = Modifier.width(TweetActionShareButtonWidth),
        onClick = {
            if (!requireAuthenticatedUser(context, navController, guestReminderText)) {
                return@IconButton
            }

            scope.launch(Dispatchers.IO) {
                viewModel.shareTweet(context, parentTweetId, parentAuthorId, linkStyle)
            }
        },
        enabled = !isSharing
    ) {
        Box(modifier = Modifier.size(TweetActionIconSize), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(id = R.drawable.ic_ms_share),
                contentDescription = stringResource(R.string.share),
                modifier = Modifier.size(TweetActionIconSize)
                    .padding(1.dp),
                tint = if (isSharing) {
                    (color ?: MaterialTheme.colorScheme.secondary).copy(alpha = 0.5f)
                } else {
                    color ?: MaterialTheme.colorScheme.secondary
                }
            )
            // Show spinner overlay when sharing
            if (isSharing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(TweetActionIconSize * 2)
                        .padding(0.dp),
                    strokeWidth = 3.dp
                )
            }
        }
    }
}
