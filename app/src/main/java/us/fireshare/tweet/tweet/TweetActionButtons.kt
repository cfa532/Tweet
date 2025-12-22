package us.fireshare.tweet.tweet

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.UserActions
import us.fireshare.tweet.navigation.ComposeComment
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.navigation.SharedViewModel
import us.fireshare.tweet.utils.CountFormatUtils
import us.fireshare.tweet.viewmodel.TweetViewModel
import androidx.compose.ui.text.font.FontFamily
suspend fun guestWarning(context: Context, navController: NavController? = null, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    // Navigate to login after a short delay
    delay(1000)
    navController?.navigate(NavTweet.Login)
}

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
    val navController = LocalNavController.current
    val context = LocalContext.current
    val sharedViewModel: SharedViewModel = hiltViewModel()
    val guestReminderText = stringResource(R.string.guest_reminder)

    IconButton(onClick = {
        if (appUser.isGuest()) {
            viewModel.viewModelScope.launch {
                guestWarning(context, navController, guestReminderText)
            }
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
    }) {
        Row(horizontalArrangement = Arrangement.Start) {
            Icon(
                painter = painterResource(id = R.drawable.bubble_right),
                contentDescription = stringResource(R.string.comments),
                modifier = Modifier.size(ButtonDefaults.IconSize),
                tint = color ?: if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = if (count > 0) CountFormatUtils.formatCount(count) else "",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = color ?: MaterialTheme.colorScheme.primary,
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
    val hasRetweeted = tweet.favorites?.get(UserActions.RETWEET) ?: false
    val navController = LocalNavController.current
    val context = LocalContext.current
    val errorMessage = stringResource(R.string.tweet_failed)
    val guestReminderText = stringResource(R.string.guest_reminder)

    IconButton(onClick = {
        if (appUser.isGuest()) {
            viewModel.viewModelScope.launch {
                guestWarning(context, navController, guestReminderText)
            }
        } else {
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
    }) {
        Row(horizontalArrangement = Arrangement.Start) {
            Icon(
                painter = painterResource(id = if (hasRetweeted) R.drawable.ic_squarepath_prim else R.drawable.ic_squarepath),
                contentDescription = stringResource(R.string.forward),
                modifier = Modifier.size(ButtonDefaults.IconSize),
                tint = color ?: if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = if (count > 0) CountFormatUtils.formatCount(count) else "",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = color ?: if (hasRetweeted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.width(28.dp)
            )
        }
    }
}

@Composable
fun LikeButton(viewModel: TweetViewModel, color: Color? = null) {
    val tweet by viewModel.tweetState.collectAsState()
    val count = tweet.favoriteCount
    val isFavorite = tweet.favorites?.get(UserActions.FAVORITE) ?: false
    val navController = LocalNavController.current
    val context = LocalContext.current
    val sharedViewModel = hiltViewModel<SharedViewModel>()
    val appUserViewModel = sharedViewModel.appUserViewModel
    val guestReminderText = stringResource(R.string.guest_reminder)

    IconButton(onClick = {
        if (appUser.isGuest()) {
            viewModel.viewModelScope.launch {
                guestWarning(context, navController, guestReminderText)
            }
        } else
            viewModel.viewModelScope.launch(Dispatchers.IO) {
                viewModel.toggleFavorite { tweet, isFavorite ->
                    appUserViewModel.updateFavorite(tweet, isFavorite)
                }
            }
    } ) {
        Row(horizontalArrangement = Arrangement.Start) {
            Icon(
                painter = painterResource(id = if (isFavorite) R.drawable.ic_heart_fill else R.drawable.ic_heart),
                contentDescription = stringResource(R.string.like),
                modifier = Modifier.size(ButtonDefaults.IconSize),
                tint = if (isFavorite) color ?: MaterialTheme.colorScheme.primary else color ?: MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = if (count > 0) CountFormatUtils.formatCount(count) else "",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (isFavorite) color ?: MaterialTheme.colorScheme.primary else color
                    ?: MaterialTheme.colorScheme.secondary,
                modifier = Modifier.width(28.dp)
            )
        }
    }
}

@Composable
fun BookmarkButton(viewModel: TweetViewModel, color: Color? = null) {
    val tweet by viewModel.tweetState.collectAsState()
    val count by remember { derivedStateOf { tweet.bookmarkCount } }
    val hasBookmarked = tweet.favorites?.get(UserActions.BOOKMARK) ?: false
    val navController = LocalNavController.current
    val context = LocalContext.current
    val sharedViewModel = hiltViewModel<SharedViewModel>()
    val appUserViewModel = sharedViewModel.appUserViewModel
    val guestReminderText = stringResource(R.string.guest_reminder)

    IconButton(onClick = {
        if (appUser.isGuest()) {
            viewModel.viewModelScope.launch {
                guestWarning(context, navController, guestReminderText)
            }
        } else
            viewModel.viewModelScope.launch(Dispatchers.IO) {
                viewModel.toggleBookmark { tweet, isBookmarked ->
                    appUserViewModel.updateBookmark(tweet, isBookmarked)
                }
            }
    } )
    {
        Row(horizontalArrangement = Arrangement.Start) {
            Icon(
                painter = painterResource(id = if (hasBookmarked) R.drawable.ic_bookmark_fill else R.drawable.ic_bookmark),
                contentDescription = stringResource(R.string.like),
                modifier = Modifier.size(ButtonDefaults.IconSize)
                    .padding(bottom = 1.dp),
                tint = if (hasBookmarked) color ?: MaterialTheme.colorScheme.primary else color ?: MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = if (count > 0) CountFormatUtils.formatCount(count) else "",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (hasBookmarked) color ?: MaterialTheme.colorScheme.primary else color
                    ?: MaterialTheme.colorScheme.secondary,
                modifier = Modifier.width(28.dp)
            )
        }
    }
}

@Composable
fun ShareButton(viewModel: TweetViewModel, color: Color? = null) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isSharing by viewModel.isSharing.collectAsState()
    val guestReminderText = stringResource(R.string.guest_reminder)

    IconButton(
        onClick = {
            if (appUser.isGuest()) {
                viewModel.viewModelScope.launch {
                    guestWarning(context, navController, guestReminderText)
                }
            } else
                scope.launch(Dispatchers.IO) {
                    viewModel.shareTweet(context)
                }
        },
        enabled = !isSharing
    ) {
        Box(modifier = Modifier.size(ButtonDefaults.IconSize)) {
            Row(horizontalArrangement = Arrangement.Center) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = stringResource(R.string.share),
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                        .padding(1.dp),
                    tint = if (isSharing) {
                        (color ?: MaterialTheme.colorScheme.outline).copy(alpha = 0.5f)
                    } else {
                        color ?: MaterialTheme.colorScheme.outline
                    }
                )
            }
            // Show spinner overlay when sharing
            if (isSharing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(ButtonDefaults.IconSize * 2)
                        .padding(0.dp),
                    strokeWidth = 3.dp
                )
            }
        }
    }
}

