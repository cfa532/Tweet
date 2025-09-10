package us.fireshare.tweet.tweet

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.TweetApplication.Companion.applicationScope
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetEvent
import us.fireshare.tweet.datamodel.TweetNotificationCenter
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.SharedViewModel
import us.fireshare.tweet.viewmodel.TweetFeedViewModel

/**
 * Shorten tweet ID by showing first 6 and last 6 characters with ellipsis in the middle
 */
private fun shortenTweetId(tweetId: String): String {
    return if (tweetId.length > 12) {
        "${tweetId.take(6)}...${tweetId.takeLast(6)}"
    } else {
        tweetId
    }
}

@Composable
fun TweetDropdownMenuItems(
    tweet: Tweet,
    parentEntry: NavBackStackEntry,
    onDismissRequest: () -> Unit,
    contextType: String = "default" // Context to determine where this dropdown is shown
) {
    val sharedViewModel: SharedViewModel = hiltViewModel()
    val appUserViewModel = sharedViewModel.appUserViewModel
    val navController = LocalNavController.current
    // Use the singleton TweetFeedViewModel from AppModule
    val tweetFeedViewModel: TweetFeedViewModel = hiltViewModel()
    val context = LocalContext.current

    // Show delete button based on context
    val shouldShowDeleteButton = when (contextType) {
        "followingsTweet" -> true // Show delete for all tweets in main feed
        "appUserProfile" -> tweet.authorId == appUser.mid // Show delete only for app user's tweets in profile
        "tweetDetail" -> tweet.authorId == appUser.mid // Show delete for user's own tweets in detail view
        else -> false // Don't show delete in other contexts
    }
    
    if (shouldShowDeleteButton) {
        DropdownMenuItem(
            modifier = Modifier.alpha(0.8f),
            onClick = {
                // inform user the tweet is being deleted.
                Toast.makeText(
                    context,
                    context.getString(R.string.delete_tweet),
                    Toast.LENGTH_SHORT
                ).show()
                onDismissRequest()

                applicationScope.launch(IO) {
                    try {
                        tweetFeedViewModel.delTweet(navController, tweet.mid, appUserViewModel) {
                            // Deletion completed successfully
                            Timber.tag("TweetDropdownMenuItems").d("Tweet ${tweet.mid} deleted successfully")
                            
                            // Update retweet count of original tweet if this is a retweet
                            if (tweet.originalTweetId != null && tweet.originalAuthorId != null) {
                                applicationScope.launch(IO) {
                                    try {
                                        val originalTweet = HproseInstance.fetchTweet(
                                            tweet.originalTweetId!!,
                                            tweet.originalAuthorId!!,
                                            shouldCache = false
                                        )
                                        originalTweet?.let { original ->
                                            HproseInstance.updateRetweetCount(original, tweet.mid, -1)?.let { updatedTweet ->
                                                // Post notification to update UI
                                                TweetNotificationCenter.post(TweetEvent.TweetUpdated(updatedTweet))
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Timber.tag("TweetDropdownMenuItems").e(e, "Error updating retweet count")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag("TweetDropdownMenuItems")
                            .e(e, "Error deleting tweet: ${e.message}")
                        // Show error toast for any exceptions
                        withContext(Main) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.delete_failed),
                                Toast.LENGTH_LONG
                            ).show()
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
                    Spacer(modifier = Modifier.width(8.dp)) // Add some space between the icon and the text
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        )
    }
    // Only author can pin the current Tweet to top list
    if (tweet.authorId == appUser.mid) {
        DropdownMenuItem(
            modifier = Modifier.alpha(1f),
            onClick = {
                appUserViewModel.viewModelScope.launch(IO) {
                    onDismissRequest()
                    appUserViewModel.pinToTop(tweet)
                }
            },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = stringResource(R.string.pin_to_top),
                        tint = MaterialTheme.colorScheme.surfaceTint
                    )
                    Spacer(modifier = Modifier.width(8.dp)) // Add some space between the icon and the text
                    Text(
                        text = if (appUserViewModel.hasPinned(tweet)) stringResource(R.string.unpin)
                        else stringResource(R.string.pin),
                        color = MaterialTheme.colorScheme.surfaceTint
                    )
                }
            }
        )
    }
    DropdownMenuItem(
        modifier = Modifier.alpha(1f),
        onClick = {
            // Copy tweet ID to clipboard
            val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newPlainText("Tweet ID", tweet.mid)
            clipboardManager.setPrimaryClip(clipData)
            
            // Show confirmation toast
            Toast.makeText(
                context,
                "Tweet ID copied to clipboard",
                Toast.LENGTH_SHORT
            ).show()
            
            onDismissRequest()
        },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy tweet ID",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.width(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = shortenTweetId(tweet.mid),
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    )
}
