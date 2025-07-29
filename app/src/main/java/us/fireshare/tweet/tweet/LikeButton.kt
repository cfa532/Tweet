package us.fireshare.tweet.tweet

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
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
import androidx.hilt.navigation.compose.hiltViewModel
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
import us.fireshare.tweet.viewmodel.TweetFeedViewModel
import us.fireshare.tweet.viewmodel.TweetViewModel

suspend fun guestWarning(context: Context, navController: NavController? = null) {
    Toast.makeText(context, context.getString(R.string.guest_reminder), Toast.LENGTH_LONG).show()
    // Navigate to login after a short delay
    delay(1000)
    navController?.navigate(NavTweet.Login)
}

@Composable
fun CommentButton(
    viewModel: TweetViewModel,
    onExpandReply: (() -> Unit)? = null
) {
    val tweet by viewModel.tweetState.collectAsState()
    val count by remember {
        derivedStateOf { tweet.commentCount }
    }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val sharedViewModel: SharedViewModel = hiltViewModel()

    IconButton(onClick = {
        if (appUser.isGuest()) {
            viewModel.viewModelScope.launch {
                guestWarning(context, navController)
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
        Row(horizontalArrangement = Arrangement.Center) {
            Icon(
                painter = painterResource(id = R.drawable.ic_notice),
                contentDescription = stringResource(R.string.comments),
                modifier = Modifier.size(ButtonDefaults.IconSize),
                tint = if (count>0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "$count",
                style = MaterialTheme.typography.labelSmall,
                color = if (count>0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
fun RetweetButton(viewModel: TweetViewModel) {
    val tweet by viewModel.tweetState.collectAsState()
    val count by remember {
        derivedStateOf { tweet.retweetCount }
    }
    val hasRetweeted = tweet.favorites?.get(UserActions.RETWEET) ?: false
    val navController = LocalNavController.current
    val context = LocalContext.current

    IconButton(onClick = {
        if (appUser.isGuest()) {
            viewModel.viewModelScope.launch {
                guestWarning(context, navController)
            }
        } else {
            viewModel.viewModelScope.launch(Dispatchers.IO) {
                try {
                    // Perform the actual retweet action
                    // The retweet will be added to feed automatically via notification system
                    viewModel.retweetTweet()
                    Timber.tag("RetweetButton").d("Retweet action completed")
                } catch (e: Exception) {
                    Timber.tag("RetweetButton").e(e, "Failed to retweet tweet ${tweet.mid}")
                    // Show error message to user
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.tweet_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }) {
        Row(horizontalArrangement = Arrangement.Center) {
            Icon(
                painter = painterResource(id = if (hasRetweeted) R.drawable.ic_squarepath_prim else R.drawable.ic_squarepath),
                contentDescription = stringResource(R.string.forward),
                modifier = Modifier.size(ButtonDefaults.IconSize),
                tint = if (count>0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall,
                color = if (count>0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
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

    IconButton(onClick = {
        if (appUser.isGuest()) {
            viewModel.viewModelScope.launch {
                guestWarning(context, navController)
            }
        } else
            viewModel.viewModelScope.launch(Dispatchers.IO) {
                viewModel.toggleFavorite { tweet, isFavorite ->
                    appUserViewModel.updateFavorite(tweet, isFavorite)
                }
            }
    } ) {
        Row(horizontalArrangement = Arrangement.Center) {
            Icon(
                painter = painterResource(id = if (isFavorite) R.drawable.ic_heart_fill else R.drawable.ic_heart),
                contentDescription = stringResource(R.string.like),
                modifier = Modifier.size(ButtonDefaults.IconSize),
                tint = if (isFavorite) color ?: MaterialTheme.colorScheme.primary else color ?: MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall,
                color = if (isFavorite) color ?: MaterialTheme.colorScheme.primary else color ?: MaterialTheme.colorScheme.secondary
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

    IconButton(onClick = {
        if (appUser.isGuest()) {
            viewModel.viewModelScope.launch {
                guestWarning(context, navController)
            }
        } else
            viewModel.viewModelScope.launch(Dispatchers.IO) {
                viewModel.toggleBookmark { tweet, isBookmarked ->
                    appUserViewModel.updateBookmark(tweet, isBookmarked)
                }
            }
    } )
    {
        Row(horizontalArrangement = Arrangement.Center) {
            Icon(
                painter = painterResource(id = if (hasBookmarked) R.drawable.ic_bookmark_fill else R.drawable.ic_bookmark),
                contentDescription = stringResource(R.string.like),
                modifier = Modifier.size(ButtonDefaults.IconSize)
                    .padding(bottom = 1.dp),
                tint = if (hasBookmarked) color ?: MaterialTheme.colorScheme.primary else color ?: MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall,
                color = if (hasBookmarked) color ?: MaterialTheme.colorScheme.primary else color ?: MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
fun ShareButton(viewModel: TweetViewModel) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    IconButton(onClick = {
        if (appUser.isGuest()) {
            viewModel.viewModelScope.launch {
                guestWarning(context, navController)
            }
        } else
            scope.launch(Dispatchers.IO) {
                viewModel.shareTweet(context)
            }
    })
    {
        Row(horizontalArrangement = Arrangement.Center) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = stringResource(R.string.share),
                modifier = Modifier.size(ButtonDefaults.IconSize)
                    .padding(1.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}
